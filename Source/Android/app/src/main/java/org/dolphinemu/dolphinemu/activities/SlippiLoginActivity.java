package org.dolphinemu.dolphinemu.activities;

import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.dolphinemu.dolphinemu.R;
import org.dolphinemu.dolphinemu.UserDirectoryBootstrap;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Embedded Slippi.gg login flow.
 *
 * The Slippi team prefers we don't ship their Firebase API key in our
 * binary; instead they point users at
 * https://slippi.gg/online/enable
 * which has a normal web login and serves a user.json download once the
 * user is signed in and has a confirmed connect code.
 *
 * This activity hosts a WebView pointed at that URL. When the user
 * clicks "Download" on the page we intercept via DownloadListener,
 * re-issue the request with the WebView's session cookies, and write
 * the bytes to our app's canonical user.json location. From the user's
 * perspective they tap one button, sign into Slippi.gg as they would
 * in any other browser, and end up logged into the launcher — no
 * separate file picker step.
 */
public class SlippiLoginActivity extends AppCompatActivity {

    public static final String TAG = "SlippiLogin";
    public static final String LOGIN_URL = "https://slippi.gg/online/enable";

    private WebView webview;
    private ProgressBar progress;
    private TextView statusText;
    private volatile boolean savedOnce = false;

    /**
     * JS we re-inject on every page load. Overrides URL.createObjectURL
     * so that whenever the slippi.gg page (or any other) creates a Blob
     * for download — which is how the user.json button works — we read
     * the blob with FileReader, base64-encode the payload, and bounce
     * it back to {@link JsBridge#onBlob} on the Java side. Bypasses the
     * "unknown protocol: blob" failure of HttpURLConnection.
     */
    private static final String JS_INSTALL_BLOB_HOOK =
        "(function(){"
      + " if (window.__slippi_blob_hooked) return;"
      + " window.__slippi_blob_hooked = true;"
      + " var orig = URL.createObjectURL;"
      + " URL.createObjectURL = function(blob){"
      + "   var url = orig.call(URL, blob);"
      + "   try {"
      + "     if (blob && blob.size && blob.size < 1000000) {"
      + "       var r = new FileReader();"
      + "       r.onload = function(){"
      + "         var d = r.result || '';"
      + "         var c = d.indexOf(',');"
      + "         var b64 = c >= 0 ? d.substring(c+1) : '';"
      + "         if (window.SlippiAndroid && SlippiAndroid.onBlob) SlippiAndroid.onBlob(b64);"
      + "       };"
      + "       r.readAsDataURL(blob);"
      + "     }"
      + "   } catch (e) {}"
      + "   return url;"
      + " };"
      + "})();";

    /**
     * Polls the page for a button whose visible text is "Download" and
     * clicks it once found. The page builds asynchronously after auth,
     * so we retry for a few seconds before giving up (the user can
     * always click manually if our auto-click misses).
     */
    private static final String JS_AUTO_DOWNLOAD =
        "(function(){"
      + " if (window.__slippi_autoclicked) return;"
      + " var tries = 0;"
      + " var iv = setInterval(function(){"
      + "   tries++;"
      + "   var nodes = document.querySelectorAll('button, a');"
      + "   for (var i = 0; i < nodes.length; i++) {"
      + "     var n = nodes[i];"
      + "     var t = (n.innerText || n.textContent || '').trim().toLowerCase();"
      + "     if (t === 'download') {"
      + "       window.__slippi_autoclicked = true;"
      + "       n.click();"
      + "       clearInterval(iv);"
      + "       return;"
      + "     }"
      + "   }"
      + "   if (tries > 30) clearInterval(iv);"
      + " }, 200);"
      + "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_slippi_login);

        webview = findViewById(R.id.login_webview);
        progress = findViewById(R.id.login_progress);
        statusText = findViewById(R.id.login_status);
        Button cancel = findViewById(R.id.login_cancel);
        cancel.setOnClickListener(v -> finish());

        // Accept session cookies — Slippi.gg's authenticated download
        // is bound to the session cookies set by their login flow, and
        // we need them present when we re-fetch the user.json URL with
        // HttpURLConnection in handleDownload().
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webview, true);

        WebSettings s = webview.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);

        // Bridge JS → Java. The blob hook installed on every page calls
        // SlippiAndroid.onBlob(base64) when a Blob is created for
        // download.
        webview.addJavascriptInterface(new JsBridge(), "SlippiAndroid");

        webview.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap fav) {
                progress.setVisibility(View.VISIBLE);
                statusText.setText("Loading…");
                // Install the blob hook as early as possible so any
                // downloads triggered during page load get captured.
                view.evaluateJavascript(JS_INSTALL_BLOB_HOOK, null);
            }
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                view.evaluateJavascript(JS_INSTALL_BLOB_HOOK, null);
                // Once we're on the enable page (which only renders for
                // signed-in users with a confirmed connect code) auto-
                // click the Download button so the user doesn't have to
                // see this page at all.
                if (url.contains("slippi.gg/online/enable")) {
                    statusText.setText("Downloading user.json…");
                    view.evaluateJavascript(JS_AUTO_DOWNLOAD, null);
                } else if (url.contains("slippi.gg/online")) {
                    statusText.setText("Sign in to continue");
                } else {
                    statusText.setText("");
                }
            }
        });
        webview.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
            }
        });

        // Fallback: if a download is triggered via plain HTTP (not a
        // blob) for some reason, fall back to fetching with our cookies.
        webview.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
            Log.i(TAG, "DownloadListener: " + filename + " (" + contentLength + " B) " + url);
            if (url == null || url.startsWith("blob:")) {
                // Blob downloads are handled by the JS hook → JsBridge.
                return;
            }
            handleDownload(url, userAgent);
        });

        webview.loadUrl(LOGIN_URL);
    }

    /**
     * Persist the captured user.json bytes + finish with RESULT_OK.
     * Idempotent — multiple JS firings (which can happen because the
     * page sometimes calls createObjectURL more than once) get
     * collapsed by {@link #savedOnce}.
     */
    private void persistAndFinish(byte[] bytes) {
        if (savedOnce) return;
        savedOnce = true;
        File dst = UserDirectoryBootstrap.slippiUserJson(this);
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            postFail("Couldn't create " + parent);
            savedOnce = false;
            return;
        }
        try (FileOutputStream out = new FileOutputStream(dst)) {
            out.write(bytes);
            Log.i(TAG, "saved " + bytes.length + " B → " + dst);
        } catch (IOException e) {
            Log.w(TAG, "write failed: " + e);
            postFail("Save failed: " + e.getMessage());
            savedOnce = false;
            return;
        }
        postSuccess();
    }

    /** Java side of the JS bridge. Methods run on a WebView worker thread. */
    private class JsBridge {
        @JavascriptInterface
        public void onBlob(String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                if (bytes == null || bytes.length == 0) return;
                // Filter out obvious non-user.json blobs (the page
                // could theoretically createObjectURL for an image
                // somewhere). user.json starts with '{'.
                int firstNonWs = 0;
                while (firstNonWs < bytes.length
                        && Character.isWhitespace((char) bytes[firstNonWs])) firstNonWs++;
                if (firstNonWs >= bytes.length || bytes[firstNonWs] != '{') return;
                runOnUiThread(() -> persistAndFinish(bytes));
            } catch (IllegalArgumentException ignored) {
                // base64 decode failure — not our blob.
            }
        }
    }

    /**
     * Fallback for direct http(s) downloads (not blob URLs). Fetch with
     * the WebView's session cookies and route through persistAndFinish
     * for idempotency vs the JS-bridge path.
     */
    private void handleDownload(String url, String userAgent) {
        statusText.setText("Downloading…");
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(30_000);
                conn.setInstanceFollowRedirects(true);
                if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) conn.setRequestProperty("Cookie", cookies);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    postFail("HTTP " + code + " — sign in and try again");
                    return;
                }
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                }
                byte[] bytes = buf.toByteArray();
                runOnUiThread(() -> persistAndFinish(bytes));
            } catch (IOException e) {
                Log.w(TAG, "http download failed: " + e);
                postFail("Download failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "SlippiUserJsonDL").start();
    }

    private void postSuccess() {
        runOnUiThread(() -> {
            Toast.makeText(this, "Signed in via slippi.gg.", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });
    }

    private void postFail(String msg) {
        runOnUiThread(() -> {
            statusText.setText("Failed");
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onBackPressed() {
        if (webview.canGoBack()) webview.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webview != null) {
            webview.stopLoading();
            webview.destroy();
        }
        super.onDestroy();
    }
}
