package ru.chistka.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Своя корзина: файлы не удаляются сразу, а переносятся в скрытую папку
 * в памяти телефона. Восстановить можно 30 дней, потом корзина чистится сама.
 */
final class Trash {
    static final String DIR_NAME = ".ChistkaTrash";
    static final long KEEP_MS = 30L * 24 * 3600 * 1000;

    private final Context ctx;
    private final File dir;
    private final File index;
    private final SharedPreferences prefs;

    Trash(Context ctx, File root) {
        this.ctx = ctx;
        this.dir = new File(root, DIR_NAME);
        this.index = new File(dir, "index.json");
        this.prefs = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE);
    }

    private void ensureDir() {
        if (!dir.exists()) dir.mkdirs();
        File nm = new File(dir, ".nomedia");
        if (!nm.exists()) try { nm.createNewFile(); } catch (Exception ignored) {}
    }

    synchronized JSONArray load() {
        try {
            if (!index.exists()) return new JSONArray();
            byte[] b = new byte[(int) index.length()];
            try (InputStream in = new FileInputStream(index)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            return new JSONArray(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private synchronized void save(JSONArray a) {
        ensureDir();
        try (OutputStream out = new FileOutputStream(index)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    /** Перенос в корзину. Возвращает {ok, fail, bytes}. */
    synchronized JSONObject move(List<String> paths) throws Exception {
        ensureDir();
        JSONArray a = load();
        int ok = 0, fail = 0;
        long bytes = 0;
        List<String> scan = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String p : paths) {
            File src = new File(p);
            if (!src.exists()) { fail++; continue; }
            String tid = now + "_" + (ok + fail);
            File dst = new File(dir, tid + "_" + src.getName());
            long size = src.length();
            if (src.renameTo(dst)) {
                JSONObject e = new JSONObject();
                e.put("tid", tid).put("orig", p).put("stored", dst.getAbsolutePath())
                        .put("size", size).put("time", now).put("name", src.getName());
                a.put(e);
                ok++;
                bytes += size;
                scan.add(p);
            } else {
                fail++;
            }
        }
        save(a);
        rescan(scan);
        JSONObject r = new JSONObject();
        r.put("ok", ok).put("fail", fail).put("bytes", bytes);
        return r;
    }

    synchronized JSONObject restore(List<String> tids) throws Exception {
        JSONArray a = load(), keep = new JSONArray();
        int ok = 0, fail = 0;
        List<String> scan = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.getJSONObject(i);
            if (!tids.contains(e.getString("tid"))) { keep.put(e); continue; }
            File src = new File(e.getString("stored"));
            File dst = new File(e.getString("orig"));
            if (dst.exists()) dst = freeName(dst);
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (src.exists() && src.renameTo(dst)) { ok++; scan.add(dst.getAbsolutePath()); }
            else { fail++; keep.put(e); }
        }
        save(keep);
        rescan(scan);
        JSONObject r = new JSONObject();
        r.put("ok", ok).put("fail", fail);
        return r;
    }

    /** Удалить навсегда: выбранные (tids) или всё, если tids == null. */
    synchronized JSONObject purge(List<String> tids, long olderThan) throws Exception {
        JSONArray a = load(), keep = new JSONArray();
        long freed = 0;
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.getJSONObject(i);
            boolean hit = tids == null ? e.optLong("time") <= olderThan : tids.contains(e.getString("tid"));
            if (!hit) { keep.put(e); continue; }
            File f = new File(e.getString("stored"));
            if (!f.exists() || f.delete()) { freed += e.optLong("size"); n++; }
            else keep.put(e);
        }
        save(keep);
        addFreed(freed);
        JSONObject r = new JSONObject();
        r.put("count", n).put("bytes", freed);
        return r;
    }

    void autoPurge() {
        try { purge(null, System.currentTimeMillis() - KEEP_MS); } catch (Exception ignored) {}
    }

    long totalSize() {
        JSONArray a = load();
        long s = 0;
        for (int i = 0; i < a.length(); i++) s += a.optJSONObject(i).optLong("size");
        return s;
    }

    String storedPath(String tid) {
        JSONArray a = load();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e != null && tid.equals(e.optString("tid"))) return e.optString("stored");
        }
        return null;
    }

    void addFreed(long bytes) {
        if (bytes <= 0) return;
        prefs.edit().putLong("freed", prefs.getLong("freed", 0) + bytes).apply();
    }

    long freed() { return prefs.getLong("freed", 0); }

    private void rescan(List<String> paths) {
        if (paths.isEmpty()) return;
        MediaScannerConnection.scanFile(ctx, paths.toArray(new String[0]), null, null);
    }

    private static File freeName(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File c = new File(f.getParentFile(), base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return f;
    }
}
