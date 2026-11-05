package ru.chistka.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Анализ фото: перцептивный отпечаток (pHash), резкость, яркость, превью. */
final class ImageAnalyzer {

    static final class Result {
        long hash;        // 63-битный pHash
        boolean hashOk;   // false для однотонных картинок (их хеш ничего не значит)
        float sharp;      // дисперсия лапласиана — чем меньше, тем более размыто
        float bright;     // средняя яркость 0..255
        int w, h;         // реальный размер с учётом поворота
    }

    private static final double[][] COS = new double[32][32];
    static {
        for (int u = 0; u < 32; u++)
            for (int x = 0; x < 32; x++)
                COS[u][x] = Math.cos((2 * x + 1) * u * Math.PI / 64.0);
    }

    private ImageAnalyzer() {}

    static Result analyze(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int rot = exifRotation(path);
        Result r = new Result();
        boolean swap = rot == 90 || rot == 270;
        r.w = swap ? o.outHeight : o.outWidth;
        r.h = swap ? o.outWidth : o.outHeight;

        Bitmap bmp = decodeSampled(path, o.outWidth, o.outHeight, 400);
        if (bmp == null) return null;
        bmp = rotate(bmp, rot);
        try {
            // Резкость и яркость — на картинке ~400 px по длинной стороне
            Bitmap mid = scaleMax(bmp, 400);
            int mw = mid.getWidth(), mh = mid.getHeight();
            float[] g = gray(mid);
            double sum = 0, sum2 = 0;
            for (float v : g) { sum += v; sum2 += v * v; }
            double mean = sum / g.length;
            double std = Math.sqrt(Math.max(0, sum2 / g.length - mean * mean));
            r.bright = (float) mean;
            double ls = 0, ls2 = 0; int n = 0;
            for (int y = 1; y < mh - 1; y++) {
                for (int x = 1; x < mw - 1; x++) {
                    int i = y * mw + x;
                    double lap = g[i - 1] + g[i + 1] + g[i - mw] + g[i + mw] - 4 * g[i];
                    ls += lap; ls2 += lap * lap; n++;
                }
            }
            r.sharp = n > 0 ? (float) (ls2 / n - (ls / n) * (ls / n)) : 0;
            if (mid != bmp) mid.recycle();

            // pHash: 32x32, DCT, верхний левый угол 8x8
            Bitmap small = Bitmap.createScaledBitmap(bmp, 32, 32, true);
            float[] s = gray(small);
            small.recycle();
            r.hashOk = std > 8;
            r.hash = phash(s);
        } finally {
            bmp.recycle();
        }
        return r;
    }

    private static long phash(float[] px) {
        double[][] tmp = new double[8][32];
        for (int u = 0; u < 8; u++)
            for (int y = 0; y < 32; y++) {
                double acc = 0;
                for (int x = 0; x < 32; x++) acc += px[y * 32 + x] * COS[u][x];
                tmp[u][y] = acc;
            }
        double[] coef = new double[63];
        int k = 0;
        for (int v = 0; v < 8; v++)
            for (int u = 0; u < 8; u++) {
                if (u == 0 && v == 0) continue;
                double acc = 0;
                for (int y = 0; y < 32; y++) acc += tmp[u][y] * COS[v][y];
                coef[k++] = acc;
            }
        double[] sorted = coef.clone();
        Arrays.sort(sorted);
        double med = sorted[31];
        long h = 0;
        for (int i = 0; i < 63; i++) if (coef[i] > med) h |= (1L << i);
        return h;
    }

    private static float[] gray(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] p = new int[w * h];
        b.getPixels(p, 0, w, 0, 0, w, h);
        float[] g = new float[p.length];
        for (int i = 0; i < p.length; i++) {
            int c = p[i];
            g[i] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
        }
        return g;
    }

    static int exifRotation(String path) {
        String low = path.toLowerCase();
        if (!(low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".heic") || low.endsWith(".heif") || low.endsWith(".webp")))
            return 0;
        try {
            ExifInterface e = new ExifInterface(path);
            switch (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    static Bitmap decodeSampled(String path, int w, int h, int target) {
        int sample = 1;
        while (Math.min(w, h) / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return BitmapFactory.decodeFile(path, o);
        } catch (OutOfMemoryError e) {
            o.inSampleSize = sample * 2;
            try { return BitmapFactory.decodeFile(path, o); } catch (Throwable t) { return null; }
        }
    }

    static Bitmap rotate(Bitmap b, int rot) {
        if (rot == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(rot);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    static Bitmap scaleMax(Bitmap b, int max) {
        int w = b.getWidth(), h = b.getHeight();
        int m = Math.max(w, h);
        if (m <= max) return b;
        float k = max / (float) m;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
    }

    /** JPEG-превью для интерфейса. */
    static byte[] thumbJpeg(String path, int max, int quality) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0) return null;
        Bitmap b = decodeSampled(path, o.outWidth, o.outHeight, max);
        if (b == null) return null;
        b = rotate(b, exifRotation(path));
        Bitmap s = scaleMax(b, max);
        if (s != b) b.recycle();
        return toJpeg(s, quality);
    }

    static byte[] toJpeg(Bitmap b, int quality) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        b.recycle();
        return bos.toByteArray();
    }
}
