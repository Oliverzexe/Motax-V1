package org.motax.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;
import java.util.Base64;

public final class MainActivity extends Activity {
    private static final int REQUEST_FILE = 700;
    private static final int REQUEST_EXPORT = 701;
    private static final int REQUEST_LOCATION = 702;
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private byte[] pendingExport;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff100909);
        getWindow().setNavigationBarColor(0xff100909);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        webView.addJavascriptInterface(new AndroidExports(), "MotaxAndroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("https://appassets.androidplatform.net/")) return false;
                // Navigation/services open outside the app. Supabase API requests use fetch and stay in-app.
                if (request.isForMainFrame()) {
                    openExternal(request.getUrl());
                    return true;
                }
                return false;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (url.startsWith("https://appassets.androidplatform.net/")) injectDownloadHandler();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, REQUEST_FILE);
                } catch (Exception ex) {
                    fileCallback.onReceiveValue(null); fileCallback = null;
                    Toast.makeText(MainActivity.this, "Não foi possível abrir arquivos", Toast.LENGTH_LONG).show();
                }
                return true;
            }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    geoCallback = callback;
                    geoOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
                }
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                try {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype));
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype));
                    ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
                } catch (Exception ex) { Toast.makeText(this, "Falha ao baixar arquivo", Toast.LENGTH_LONG).show(); }
            }
        });
        webView.loadUrl(HOME);
    }

    private void openExternal(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (ActivityNotFoundException ex) { Toast.makeText(this, "Não foi encontrado aplicativo para este link", Toast.LENGTH_LONG).show(); }
    }

    private void injectDownloadHandler() {
        // Preserve the existing HTML's JSON/CSV Blob export buttons on Android WebView.
        // Only user-clicked downloads use this bridge; normal navigation is unaffected.
        String script = "(function(){if(window.__motaxExportInstalled)return;window.__motaxExportInstalled=true;"
          + "document.addEventListener('click',function(ev){"
          + "var a=ev.target.closest&&ev.target.closest('a[download]');"
          + "if(!a||!a.href||!a.href.startsWith('blob:'))return;"
          + "ev.preventDefault();ev.stopPropagation();"
          + "fetch(a.href).then(function(r){return r.blob()}).then(function(blob){"
          + "var fr=new FileReader();fr.onload=function(){window.MotaxAndroid.saveBase64(a.download||'motax-backup.json',fr.result)};"
          + "fr.readAsDataURL(blob)}).catch(function(){alert('Não foi possível salvar o arquivo')});"
          + "},true)})();";
        webView.evaluateJavascript(script, null);
    }

    public class AndroidExports {
        @JavascriptInterface public void saveBase64(String filename, String dataUrl) {
            if (dataUrl == null || !dataUrl.contains(",") || dataUrl.length() > 18_000_000) return;
            try {
                final byte[] bytes = android.util.Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), android.util.Base64.DEFAULT);
                if (bytes.length > 12_000_000) return;
                final String safeName = filename == null ? "motax-backup.json" : filename.replaceAll("[^a-zA-Z0-9._-]", "_");
                runOnUiThread(() -> {
                    pendingExport = bytes;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(safeName.endsWith(".csv") ? "text/csv" : "application/json");
                    intent.putExtra(Intent.EXTRA_TITLE, safeName);
                    try { startActivityForResult(intent, REQUEST_EXPORT); }
                    catch (Exception e) { pendingExport = null; Toast.makeText(MainActivity.this, "Falha ao abrir salvamento", Toast.LENGTH_LONG).show(); }
                });
            } catch (Exception ignored) { }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
        } else if (requestCode == REQUEST_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingExport != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out != null) {
                        out.write(pendingExport);
                        Toast.makeText(this, "Backup salvo", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception ex) { Toast.makeText(this, "Não foi possível salvar o backup", Toast.LENGTH_LONG).show(); }
            }
            pendingExport = null;
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(req, permissions, results);
        if (req == REQUEST_LOCATION && geoCallback != null) {
            boolean granted = false;
            for (int r : results) if (r == PackageManager.PERMISSION_GRANTED) granted = true;
            geoCallback.invoke(geoOrigin, granted, false);
            geoCallback = null;
        }
    }
    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
    @Override public void onDestroy() {
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
