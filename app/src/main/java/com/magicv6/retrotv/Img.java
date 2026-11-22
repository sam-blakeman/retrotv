package com.magicv6.retrotv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny poster loader: two threads, in-memory cache, downsampled to the view size. */
final class Img {
    private Img() { }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    static void load(ImageView v, String url, int targetW) {
        v.setTag(url);
        if (url == null || url.isEmpty()) { v.setImageDrawable(null); return; }
        Bitmap b = CACHE.get(url);
        if (b != null) { v.setImageBitmap(b); return; }
        v.setImageDrawable(null);
        IO.execute(() -> {
            Bitmap bm = fetch(url, Math.max(targetW, 64));
            if (bm == null) return;
            CACHE.put(url, bm);
            UI.post(() -> { if (url.equals(v.getTag())) v.setImageBitmap(bm); });
        });
    }

    private static Bitmap fetch(String url, int targetW) {
        try {
            byte[] data = null;
            String u = url;
            for (int hop = 0; hop < 4 && data == null; hop++) {   // follows http<->https redirects too
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setRequestProperty("User-Agent", Xtream.UA);
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(false);
                try {
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                        u = new URL(new URL(u), c.getHeaderField("Location")).toString();
                        continue;
                    }
                    if (code >= 400) return null;
                    try (InputStream in = c.getInputStream()) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                        data = bo.toByteArray();
                    }
                } finally {
                    c.disconnect();
                }
            }
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int s = 1;
            while (o.outWidth / (s * 2) >= targetW) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        }
    }
}
