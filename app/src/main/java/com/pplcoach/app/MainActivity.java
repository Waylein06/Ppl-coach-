package com.pplcoach.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int PICK_FILE = 7;
    private static final String BACKUP_NAME = "PPL-Coach-backup.json";

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep the screen on while the app is open so the rest timer stays visible.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // localStorage: workout log, settings and rest times live here
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(intent, "Choose backup file"), PICK_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_FILE && filePathCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** Called from the page as window.AndroidBridge.saveBackup(json). Returns "ok" or "error: ...". */
    private class Bridge {
        @JavascriptInterface
        public String saveBackup(String json) {
            try {
                ContentResolver cr = getContentResolver();
                Uri existing = null;
                Cursor c = cr.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Downloads._ID},
                        MediaStore.Downloads.DISPLAY_NAME + "=?",
                        new String[]{BACKUP_NAME},
                        null);
                if (c != null) {
                    if (c.moveToFirst()) {
                        existing = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
                    }
                    c.close();
                }
                if (existing != null) {
                    try {
                        write(cr, existing, json);
                        return "ok";
                    } catch (Exception ignored) {
                        // File belongs to an older install; fall through and create a fresh one.
                    }
                }
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_NAME);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                v.put(MediaStore.Downloads.RELATIVE_PATH, "Download");
                Uri created = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (created == null) {
                    return "error: could not create file";
                }
                write(cr, created, json);
                return "ok";
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        }

        private void write(ContentResolver cr, Uri uri, String json) throws Exception {
            OutputStream out = cr.openOutputStream(uri, "wt");
            if (out == null) {
                throw new Exception("no output stream");
            }
            try {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        }
    }
}
