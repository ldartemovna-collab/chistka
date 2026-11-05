package ru.chistka.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.LruCache;
import android.util.Size;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String HOST = "app.local";

    private WebView web;
    private File root;
    private Trash trash;
    private volatile Scanner scanner;
    private volatile String lastResults;
    private volatile boolean scanning;
    private long lastProgress;

    private final LruCache<String, byte[]> thumbs = new LruCache<String, byte[]>(32 * 1024 * 1024) {
        @Override protected int sizeOf(String k, byte[] v) { return v.length; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = Environment.getExternalStorageDirectory();
        trash = new Trash(this, root);
        if (hasAccess()) new Thread(() -> trash.autoPurge()).start();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        web.addJavascriptInterface(new Api(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                return serve(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return !HOST.equals(req.getUrl().getHost());
            }
        });
        setContentView(web);
        web.loadUrl("https://" + HOST + "/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        js("window.onResumeApp && onResumeApp()");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.goBack ? String(window.goBack()) : 'false'", v -> {
            if (v == null || !v.contains("true")) MainActivity.super.onBackPressed();
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        js("window.onResumeApp && onResumeApp()");
    }

    // ---------------- Отдача страниц и превью ----------------

    private WebResourceResponse serve(Uri u) {
        String path = u.getPath() == null ? "/" : u.getPath();
        try {
            if (path.equals("/") || path.equals("/index.html")) {
                InputStream in = getAssets().open("index.html");
                return new WebResourceResponse("text/html", "utf-8", in);
            }
            byte[] data = null;
            if (path.equals("/thumb")) {
                String id = u.getQueryParameter("id");
                Scanner.Item it = item(id);
                if (it != null) data = thumb("t" + it.path, it.path, it.kind, 300, 70);
            } else if (path.equals("/full")) {
                Scanner.Item it = item(u.getQueryParameter("id"));
                if (it != null) data = it.kind.equals("img")
                        ? ImageAnalyzer.thumbJpeg(it.path, 1600, 85)
                        : thumb("f" + it.path, it.path, it.kind, 900, 80);
            } else if (path.equals("/tthumb")) {
                String tid = u.getQueryParameter("tid");
                String p = tid == null ? null : trash.storedPath(tid);
                if (p != null) {
                    String k = kindOf(u.getQueryParameter("name"));
                    data = thumb("x" + p, p, k, 300, 70);
                }
            }
            if (data == null) return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, new ByteArrayInputStream(new byte[0]));
            return new WebResourceResponse("image/jpeg", null, new ByteArrayInputStream(data));
        } catch (Throwable t) {
            return new WebResourceResponse("text/plain", "utf-8", 500, "Error", null, new ByteArrayInputStream(new byte[0]));
        }
    }

    private static String kindOf(String name) {
        if (name == null) return "other";
        String e = Scanner.ext(name);
        if (Scanner.IMG.contains(e)) return "img";
        if (Scanner.VID.contains(e)) return "vid";
        return "other";
    }

    private byte[] thumb(String key, String path, String kind, int max, int q) {
        byte[] c = thumbs.get(key);
        if (c != null) return c;
        byte[] d = null;
        try {
            if (kind.equals("img")) {
                d = ImageAnalyzer.thumbJpeg(path, max, q);
            } else if (kind.equals("vid")) {
                Bitmap b;
                if (Build.VERSION.SDK_INT >= 29) b = ThumbnailUtils.createVideoThumbnail(new File(path), new Size(max, max), null);
                else b = ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND);
                if (b != null) d = ImageAnalyzer.toJpeg(ImageAnalyzer.scaleMax(b, max), q);
            }
        } catch (Throwable ignored) {}
        if (d != null) thumbs.put(key, d);
        return d;
    }

    private Scanner.Item item(String id) {
        Scanner s = scanner;
        if (s == null || id == null) return null;
        try {
            int i = Integer.parseInt(id);
            return i >= 0 && i < s.items.size() ? s.items.get(i) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------- Доступ к файлам ----------------

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    // ---------------- Связь со страницей ----------------

    private void js(String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s);
    }

    private void progress(String stage, int done, int total, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastProgress < 150) return;
        lastProgress = now;
        js("window.onProgress && onProgress(" + q(stage) + "," + done + "," + total + ")");
    }

    private List<String> pathsOf(String idsJson) throws Exception {
        JSONArray a = new JSONArray(idsJson);
        List<String> out = new ArrayList<>();
        Scanner s = scanner;
        if (s == null) return out;
        for (int i = 0; i < a.length(); i++) {
            int id = a.getInt(i);
            if (id >= 0 && id < s.items.size()) out.add(s.items.get(id).path);
        }
        return out;
    }

    private static List<String> strings(String json) throws Exception {
        JSONArray a = new JSONArray(json);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    final class Api {
        @JavascriptInterface
        public String state() {
            try {
                JSONObject o = new JSONObject();
                o.put("access", hasAccess());
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("scanning", scanning);
                o.put("hasResults", lastResults != null);
                o.put("freed", trash.freed());
                o.put("trashSize", hasAccess() ? trash.totalSize() : 0);
                o.put("trashCount", hasAccess() ? trash.load().length() : 0);
                StatFs st = new StatFs(root.getAbsolutePath());
                o.put("total", st.getTotalBytes());
                o.put("free", st.getAvailableBytes());
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void requestAccess() {
            runOnUiThread(MainActivity.this::requestAccess);
        }

        @JavascriptInterface
        public void startScan() {
            if (scanning || !hasAccess()) return;
            scanning = true;
            new Thread(() -> {
                try {
                    Scanner s = new Scanner(root, new File(getFilesDir(), "photo-cache.tsv"));
                    Scanner.Progress p = (st, d, t) -> progress(st, d, t, false);
                    s.walk(p);
                    progress("photos", 0, 0, true);
                    s.analyzeImages(p);
                    progress("group", 0, 0, true);
                    s.groupPhotos(p);
                    progress("files", 0, 0, true);
                    s.findFileDups(p);
                    progress("done", 1, 1, true);
                    lastResults = s.toJson();
                    scanner = s;
                    thumbs.evictAll();
                    js("window.onScanDone && onScanDone()");
                } catch (Throwable t) {
                    js("window.onScanError && onScanError(" + q(String.valueOf(t)) + ")");
                } finally {
                    scanning = false;
                }
            }).start();
        }

        @JavascriptInterface
        public String results() {
            return lastResults == null ? "null" : lastResults;
        }

        @JavascriptInterface
        public String trash(String idsJson) {
            try {
                return trash.move(pathsOf(idsJson)).toString();
            } catch (Exception e) {
                return "{\"ok\":0,\"fail\":0,\"error\":" + q(e.toString()) + "}";
            }
        }

        @JavascriptInterface
        public String trashList() {
            return trash.load().toString();
        }

        @JavascriptInterface
        public String restore(String tidsJson) {
            try { return trash.restore(strings(tidsJson)).toString(); }
            catch (Exception e) { return "{\"ok\":0}"; }
        }

        @JavascriptInterface
        public String purge(String tidsJson) {
            try {
                List<String> t = tidsJson == null || tidsJson.equals("all") ? null : strings(tidsJson);
                return trash.purge(t, Long.MAX_VALUE).toString();
            } catch (Exception e) {
                return "{\"count\":0}";
            }
        }

        @JavascriptInterface
        public String deleteEmpty(String pathsJson) {
            int n = 0;
            try {
                String r = root.getAbsolutePath() + "/";
                for (String p : strings(pathsJson)) {
                    if (!p.startsWith(r)) continue;
                    File f = new File(p);
                    String[] l = f.list();
                    if (f.isDirectory() && l != null && l.length == 0 && f.delete()) n++;
                }
            } catch (Exception ignored) {}
            return String.valueOf(n);
        }

        @JavascriptInterface
        public void open(int id) {
            Scanner.Item it = item(String.valueOf(id));
            if (it == null) return;
            runOnUiThread(() -> openFile(it.path));
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }

    private void openFile(String path) {
        try {
            Uri files = MediaStore.Files.getContentUri("external");
            Uri uri = null;
            try (Cursor c = getContentResolver().query(files, new String[]{MediaStore.Files.FileColumns._ID},
                    MediaStore.Files.FileColumns.DATA + "=?", new String[]{path}, null)) {
                if (c != null && c.moveToFirst()) uri = ContentUris.withAppendedId(files, c.getLong(0));
            }
            if (uri == null) { Toast.makeText(this, "Не получилось открыть этот файл", Toast.LENGTH_SHORT).show(); return; }
            String ext = Scanner.ext(path);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime == null ? "*/*" : mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Открыть"));
        } catch (Exception e) {
            Toast.makeText(this, "Нет приложения, чтобы открыть этот файл", Toast.LENGTH_SHORT).show();
        }
    }
}
