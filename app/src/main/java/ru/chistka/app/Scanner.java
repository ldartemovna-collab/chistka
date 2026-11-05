package ru.chistka.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Обход памяти телефона и поиск всего, что можно почистить. */
final class Scanner {

    interface Progress { void update(String stage, int done, int total); }

    static final class Item {
        int id;
        String path, name, folder;
        long size, mtime;
        String kind;     // img, vid, aud, doc, apk, arc, other
        String app = ""; // telegram / whatsapp / viber / vk
        boolean screenshot, download;
        int w, h;
        long hash;
        boolean hashOk;
        float sharp = -1, bright = -1;
    }

    static final Set<String> IMG = new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif"));
    static final Set<String> VID = new HashSet<>(Arrays.asList("mp4", "mkv", "mov", "3gp", "webm", "avi", "m4v"));
    static final Set<String> AUD = new HashSet<>(Arrays.asList("mp3", "m4a", "ogg", "opus", "aac", "wav", "flac", "amr", "oga"));
    static final Set<String> DOC = new HashSet<>(Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "ods", "epub", "fb2", "djvu"));
    static final Set<String> APK = new HashSet<>(Arrays.asList("apk", "apks", "xapk", "apkm"));
    static final Set<String> ARC = new HashSet<>(Arrays.asList("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz"));
    static final Set<String> STD_TOP = new HashSet<>(Arrays.asList(
            "android", "dcim", "download", "downloads", "pictures", "music", "movies", "documents", "alarms",
            "notifications", "ringtones", "podcasts", "audiobooks", "recordings"));

    final File root;
    final File cacheFile;
    final List<Item> items = new ArrayList<>();
    final List<String> emptyDirs = new ArrayList<>();
    volatile boolean cancelled;

    Scanner(File root, File cacheFile) {
        this.root = root;
        this.cacheFile = cacheFile;
    }

    // ---------------- Обход ----------------

    void walk(Progress p) {
        walkDir(root, 0, p);
    }

    private boolean walkDir(File dir, int depth, Progress p) {
        if (cancelled || depth > 25) return false;
        String rel = rel(dir);
        String relLow = rel.toLowerCase(Locale.ROOT);
        if (relLow.equals("android/data") || relLow.equals("android/obb") || relLow.startsWith(Trash.DIR_NAME.toLowerCase(Locale.ROOT)))
            return false;
        File[] list = dir.listFiles();
        if (list == null) return false;
        if (list.length == 0) {
            if (depth > 0 && !(depth == 1 && STD_TOP.contains(dir.getName().toLowerCase(Locale.ROOT))) && !relLow.startsWith("android"))
                emptyDirs.add(dir.getAbsolutePath());
            return false;
        }
        boolean any = false;
        for (File f : list) {
            String n = f.getName();
            if (f.isDirectory()) {
                if (n.startsWith(".") && !n.equals(".Statuses")) continue;
                walkDir(f, depth + 1, p);
                any = true;
            } else {
                if (n.startsWith(".") || n.equals(".nomedia")) continue;
                add(f, rel);
                any = true;
            }
        }
        p.update("walk", items.size(), 0);
        return any;
    }

    private void add(File f, String relDir) {
        Item it = new Item();
        it.id = items.size();
        it.path = f.getAbsolutePath();
        it.name = f.getName();
        it.folder = relDir;
        it.size = f.length();
        it.mtime = f.lastModified();
        String ext = ext(it.name);
        if (IMG.contains(ext)) it.kind = "img";
        else if (VID.contains(ext)) it.kind = "vid";
        else if (AUD.contains(ext)) it.kind = "aud";
        else if (DOC.contains(ext)) it.kind = "doc";
        else if (APK.contains(ext)) it.kind = "apk";
        else if (ARC.contains(ext)) it.kind = "arc";
        else it.kind = "other";
        String low = relDir.toLowerCase(Locale.ROOT);
        String nameLow = it.name.toLowerCase(Locale.ROOT);
        it.screenshot = it.kind.equals("img") && (low.contains("screenshot") || nameLow.startsWith("screenshot") || nameLow.startsWith("скриншот"));
        it.download = low.equals("download") || low.startsWith("download/") || low.equals("downloads") || low.startsWith("downloads/");
        if (low.contains("telegram")) it.app = "telegram";
        else if (low.contains("whatsapp")) it.app = "whatsapp";
        else if (low.contains("viber")) it.app = "viber";
        else if (low.contains("com.vkontakte") || low.startsWith("vk/") || low.equals("vk")) it.app = "vk";
        items.add(it);
    }

    // ---------------- Анализ фото ----------------

    void analyzeImages(Progress p) {
        Map<String, String> cache = loadCache();
        Map<String, String> fresh = new ConcurrentHashMap<>();
        List<Item> imgs = new ArrayList<>();
        for (Item it : items) if (it.kind.equals("img") && !it.name.toLowerCase(Locale.ROOT).endsWith(".gif")) imgs.add(it);
        final int total = imgs.size();
        AtomicInteger done = new AtomicInteger();
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        for (Item it : imgs) {
            ex.execute(() -> {
                if (cancelled) return;
                String key = it.path + "|" + it.size + "|" + it.mtime;
                String c = cache.get(key);
                boolean ok = false;
                if (c != null) ok = fromCache(it, c);
                if (!ok) {
                    try {
                        ImageAnalyzer.Result r = ImageAnalyzer.analyze(it.path);
                        if (r != null) {
                            it.hash = r.hash; it.hashOk = r.hashOk; it.sharp = r.sharp; it.bright = r.bright;
                            it.w = r.w; it.h = r.h;
                            ok = true;
                        }
                    } catch (Throwable ignored) {}
                }
                if (ok) fresh.put(key, toCache(it));
                int d = done.incrementAndGet();
                if (d % 10 == 0 || d == total) p.update("photos", d, total);
            });
        }
        ex.shutdown();
        try { ex.awaitTermination(2, TimeUnit.HOURS); } catch (InterruptedException ignored) {}
        saveCache(fresh);
    }

    private static boolean fromCache(Item it, String c) {
        try {
            String[] a = c.split(",");
            it.hash = Long.parseLong(a[0]);
            it.hashOk = a[1].equals("1");
            it.sharp = Float.parseFloat(a[2]);
            it.bright = Float.parseFloat(a[3]);
            it.w = Integer.parseInt(a[4]);
            it.h = Integer.parseInt(a[5]);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String toCache(Item it) {
        return it.hash + "," + (it.hashOk ? 1 : 0) + "," + it.sharp + "," + it.bright + "," + it.w + "," + it.h;
    }

    private Map<String, String> loadCache() {
        Map<String, String> m = new HashMap<>();
        if (!cacheFile.exists()) return m;
        try (BufferedReader r = new BufferedReader(new FileReader(cacheFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.lastIndexOf('\t');
                if (t > 0) m.put(line.substring(0, t), line.substring(t + 1));
            }
        } catch (Throwable ignored) {}
        return m;
    }

    private void saveCache(Map<String, String> m) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(cacheFile), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : m.entrySet()) w.write(e.getKey() + "\t" + e.getValue() + "\n");
        } catch (Throwable ignored) {}
    }

    // ---------------- Группы похожих фото ----------------

    static final int DUP_DIST = 6;      // копии, пересланные, с фильтрами
    static final int SERIES_DIST = 14;  // похожие кадры одной серии
    static final long SERIES_TIME = 30 * 60 * 1000L;

    List<List<Integer>> dupGroups = new ArrayList<>();
    List<List<Integer>> seriesGroups = new ArrayList<>();

    void groupPhotos(Progress p) {
        List<Item> c = new ArrayList<>();
        for (Item it : items)
            if (it.kind.equals("img") && it.hashOk && !it.screenshot && Math.min(it.w, it.h) >= 300) c.add(it);
        int n = c.size();
        int[] dup = new int[n], ser = new int[n];
        for (int i = 0; i < n; i++) { dup[i] = i; ser[i] = i; }
        long[] h = new long[n];
        long[] t = new long[n];
        for (int i = 0; i < n; i++) { h[i] = c.get(i).hash; t[i] = c.get(i).mtime; }
        for (int i = 0; i < n; i++) {
            if (cancelled) return;
            long hi = h[i];
            for (int j = i + 1; j < n; j++) {
                int d = Long.bitCount(hi ^ h[j]);
                if (d <= DUP_DIST) { union(dup, i, j); union(ser, i, j); }
                else if (d <= SERIES_DIST && Math.abs(t[i] - t[j]) <= SERIES_TIME) union(ser, i, j);
            }
            if (i % 300 == 0) p.update("group", i, n);
        }
        dupGroups = collect(dup, c);
        List<List<Integer>> series = collect(ser, c);
        // Серии, которые полностью совпадают с одной группой копий, не дублируем
        Map<Integer, Integer> dupOf = new HashMap<>();
        for (int g = 0; g < dupGroups.size(); g++) for (int id : dupGroups.get(g)) dupOf.put(id, g);
        for (List<Integer> s : series) {
            Set<Integer> gs = new HashSet<>();
            boolean allInDup = true;
            for (int id : s) {
                Integer g = dupOf.get(id);
                if (g == null) { allInDup = false; break; }
                gs.add(g);
            }
            if (allInDup && gs.size() == 1) continue;
            seriesGroups.add(s);
        }
        for (List<Integer> g : dupGroups) sortBest(g);
        for (List<Integer> g : seriesGroups) sortBest(g);
        Comparator2.bySaving(dupGroups, items);
        Comparator2.bySaving(seriesGroups, items);
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; }
        return x;
    }

    private static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[ra] = rb;
    }

    private static List<List<Integer>> collect(int[] p, List<Item> c) {
        Map<Integer, List<Integer>> m = new HashMap<>();
        for (int i = 0; i < p.length; i++) {
            int r = find(p, i);
            List<Integer> l = m.get(r);
            if (l == null) { l = new ArrayList<>(); m.put(r, l); }
            l.add(c.get(i).id);
        }
        List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> l : m.values()) if (l.size() > 1) out.add(l);
        return out;
    }

    /** Лучшее фото — первым: больше разрешение, резче, оригинал из камеры, а не копия из мессенджера. */
    private void sortBest(List<Integer> g) {
        Collections.sort(g, (a, b) -> Double.compare(score(items.get(b)), score(items.get(a))));
    }

    private static double score(Item it) {
        double s = Math.log(Math.max(1.0, (double) it.w * it.h)) * 2;
        s += Math.log(1 + Math.max(0, it.sharp));
        if (!it.app.isEmpty()) s -= 1.5;
        if (it.folder.toLowerCase(Locale.ROOT).startsWith("dcim")) s += 0.5;
        return s;
    }

    static final class Comparator2 {
        static void bySaving(List<List<Integer>> groups, List<Item> items) {
            Collections.sort(groups, (a, b) -> Long.compare(saving(b, items), saving(a, items)));
        }
        static long saving(List<Integer> g, List<Item> items) {
            long s = 0;
            for (int i = 1; i < g.size(); i++) s += items.get(g.get(i)).size;
            return s;
        }
    }

    // ---------------- Дубликаты файлов ----------------

    List<List<Integer>> fileDupGroups = new ArrayList<>();

    void findFileDups(Progress p) {
        Map<Long, List<Item>> bySize = new HashMap<>();
        for (Item it : items) {
            if (it.size < 50 * 1024) continue;
            List<Item> l = bySize.get(it.size);
            if (l == null) { l = new ArrayList<>(); bySize.put(it.size, l); }
            l.add(it);
        }
        List<List<Item>> cand = new ArrayList<>();
        for (List<Item> l : bySize.values()) if (l.size() > 1) cand.add(l);
        int total = cand.size(), done = 0;
        for (List<Item> l : cand) {
            if (cancelled) return;
            Map<String, List<Integer>> byHash = new HashMap<>();
            for (Item it : l) {
                String key = partialHash(it);
                if (key == null) continue;
                if (it.size <= 300L * 1024 * 1024) {
                    String full = fullHash(it);
                    if (full == null) continue;
                    key = full;
                }
                List<Integer> g = byHash.get(key);
                if (g == null) { g = new ArrayList<>(); byHash.put(key, g); }
                g.add(it.id);
            }
            for (List<Integer> g : byHash.values()) if (g.size() > 1) {
                // Оставляем файл вне мессенджеров и загрузок, самый старый
                Collections.sort(g, (a, b) -> {
                    Item x = items.get(a), y = items.get(b);
                    int px = (x.app.isEmpty() ? 0 : 2) + (x.download ? 1 : 0);
                    int py = (y.app.isEmpty() ? 0 : 2) + (y.download ? 1 : 0);
                    if (px != py) return Integer.compare(px, py);
                    return Long.compare(x.mtime, y.mtime);
                });
                fileDupGroups.add(g);
            }
            if (++done % 20 == 0) p.update("files", done, total);
        }
        Comparator2.bySaving(fileDupGroups, items);
    }

    private static String partialHash(Item it) {
        try (RandomAccessFile f = new RandomAccessFile(it.path, "r")) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[64 * 1024];
            int n = f.read(buf);
            if (n > 0) md.update(buf, 0, n);
            if (it.size > buf.length * 2L) {
                f.seek(it.size - buf.length);
                n = f.read(buf);
                if (n > 0) md.update(buf, 0, n);
            }
            return "p" + hex(md.digest());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String fullHash(Item it) {
        try (InputStream in = new FileInputStream(it.path)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return "f" + hex(md.digest());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---------------- Итог ----------------

    static final float BLUR_LIMIT = 45f;
    static final float DARK_LIMIT = 28f;

    String toJson() throws Exception {
        JSONObject o = new JSONObject();
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            JSONArray a = new JSONArray();
            a.put(it.id).put(it.name).put(it.folder).put(it.size).put(it.mtime).put(it.kind).put(it.app)
                    .put(it.w).put(it.h).put(it.screenshot ? 1 : 0).put(it.download ? 1 : 0);
            arr.put(a);
        }
        o.put("items", arr);
        o.put("dup", groups(dupGroups));
        o.put("series", groups(seriesGroups));
        o.put("fdup", groups(fileDupGroups));

        List<Item> blurry = new ArrayList<>(), dark = new ArrayList<>();
        for (Item it : items) {
            if (!it.kind.equals("img") || it.screenshot || it.sharp < 0) continue;
            if (Math.min(it.w, it.h) < 300) continue;
            if (it.bright >= 0 && it.bright < DARK_LIMIT) dark.add(it);
            else if (it.sharp < BLUR_LIMIT) blurry.add(it);
        }
        Collections.sort(blurry, (a, b) -> Float.compare(a.sharp, b.sharp));
        Collections.sort(dark, (a, b) -> Float.compare(a.bright, b.bright));
        o.put("blurry", ids(blurry));
        o.put("dark", ids(dark));

        JSONArray empty = new JSONArray();
        for (String d : emptyDirs) empty.put(d);
        o.put("empty", empty);
        o.put("root", root.getAbsolutePath());
        return o.toString();
    }

    private static JSONArray groups(List<List<Integer>> gs) {
        JSONArray a = new JSONArray();
        for (List<Integer> g : gs) {
            JSONArray x = new JSONArray();
            for (int id : g) x.put(id);
            a.put(x);
        }
        return a;
    }

    private static JSONArray ids(List<Item> l) {
        JSONArray a = new JSONArray();
        for (Item it : l) a.put(it.id);
        return a;
    }

    String rel(File f) {
        String r = root.getAbsolutePath();
        String p = f.getAbsolutePath();
        if (p.equals(r)) return "";
        return p.startsWith(r + "/") ? p.substring(r.length() + 1) : p;
    }

    static String ext(String name) {
        int d = name.lastIndexOf('.');
        return d < 0 ? "" : name.substring(d + 1).toLowerCase(Locale.ROOT);
    }
}
