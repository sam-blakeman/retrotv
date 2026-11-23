package io.github.samblakeman.retrotv;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Minimal Xtream Codes "player_api" client (live TV only). Call from a background thread. */
final class Xtream {
    static final String UA = "VLC/3.0.20 LibVLC/3.0.20";

    static final class Category {
        final String id, name;
        Category(String id, String name) { this.id = id; this.name = name; }
    }

    static final class Channel {
        final String id, num, name, catId;
        Channel(String id, String num, String name, String catId) {
            this.id = id; this.num = num; this.name = name; this.catId = catId;
        }
    }

    final String base, user, pass;

    Xtream(String server, String user, String pass) {
        this.base = normalise(server);
        this.user = user.trim();
        this.pass = pass;
    }

    static String normalise(String s) {
        s = s.trim();
        if (!s.contains("://")) s = "http://" + s;
        int i = s.indexOf("://");
        s = s.substring(0, i).toLowerCase(Locale.ROOT) + s.substring(i);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith("/player_api.php")) s = s.substring(0, s.length() - "/player_api.php".length());
        return s;
    }

    private String api(String action) throws IOException {
        String u = base + "/player_api.php?username=" + URLEncoder.encode(user, "UTF-8")
                + "&password=" + URLEncoder.encode(pass, "UTF-8");
        if (action != null) u += "&action=" + action;
        return u;
    }

    static String get(String url) throws IOException {
        return get(url, 20000);
    }

    /** GET with one retry; readMs is how long the server may stay silent. */
    static String get(String url, int readMs) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return getOnce(url, readMs);
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(2000); } catch (InterruptedException ie) { throw e; }
            }
        }
        throw last;
    }

    private static String getOnce(String url, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(15000);
        c.setReadTimeout(readMs);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("Server replied HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                return bo.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    /** Returns null when the login works, otherwise a short reason. */
    String checkLogin() {
        try {
            JSONObject o = new JSONObject(get(api(null)));
            JSONObject ui = o.optJSONObject("user_info");
            if (ui == null) return "Unexpected reply (is this an Xtream server?)";
            if (ui.optInt("auth", 0) != 1) return "Login rejected";
            String status = ui.optString("status", "Active");
            if (!status.equalsIgnoreCase("Active")) return "Account status: " + status;
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Raw JSON (cached on disk by the activity). */
    String rawCategories() throws Exception { return get(api("get_live_categories"), 45000); }
    String rawChannels() throws Exception { return get(api("get_live_streams"), 90000); }

    static List<Category> parseCategories(String json) throws Exception {
        JSONArray a = new JSONArray(json);
        List<Category> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            out.add(new Category(o.optString("category_id"), o.optString("category_name").trim()));
        }
        return out;
    }

    static List<Channel> parseChannels(String json) throws Exception {
        JSONArray a = new JSONArray(json);
        List<Channel> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            out.add(new Channel(o.optString("stream_id"), o.optString("num", String.valueOf(i + 1)),
                    o.optString("name").trim(), o.optString("category_id")));
        }
        return out;
    }

    String streamUrl(Channel c, boolean hls) {
        return base + "/live/" + user + "/" + pass + "/" + c.id + (hls ? ".m3u8" : ".ts");
    }

    /** "NOW 20:00 Title" / "NEXT 21:00 Title" lines, or empty list. */
    List<String> nowNext(Channel c) {
        List<String> out = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(getOnce(api("get_short_epg") + "&stream_id=" + c.id + "&limit=2", 8000));
            JSONArray l = o.optJSONArray("epg_listings");
            if (l == null) return out;
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
            for (int i = 0; i < l.length() && i < 2; i++) {
                JSONObject e = l.getJSONObject(i);
                String title = b64(e.optString("title"));
                long ts = e.optLong("start_timestamp", 0);
                String t = ts > 0 ? hm.format(new Date(ts * 1000)) : "";
                out.add((i == 0 ? "NOW  " : "NEXT ") + t + "  " + title);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static final class Programme {
        final long start, end;          // epoch ms
        final String title, desc;
        Programme(long start, long end, String title, String desc) {
            this.start = start; this.end = end; this.title = title; this.desc = desc;
        }
    }

    /** Upcoming programmes (current one first). Throws on network failure so callers can retry later. */
    List<Programme> epg(Channel c, int limit) throws Exception {
        JSONObject o = new JSONObject(getOnce(api("get_short_epg") + "&stream_id=" + c.id + "&limit=" + limit, 10000));
        JSONArray l = o.optJSONArray("epg_listings");
        List<Programme> out = new ArrayList<>();
        if (l == null) return out;
        for (int i = 0; i < l.length(); i++) {
            JSONObject e = l.getJSONObject(i);
            long s = e.optLong("start_timestamp", 0) * 1000;
            long en = e.optLong("stop_timestamp", 0) * 1000;
            if (s <= 0) continue;
            out.add(new Programme(s, en, b64(e.optString("title")), b64(e.optString("description"))));
        }
        out.sort((a, b) -> Long.compare(a.start, b.start));
        // fill missing/odd end times from the next programme's start
        for (int i = 0; i < out.size(); i++) {
            Programme p = out.get(i);
            long next = i + 1 < out.size() ? out.get(i + 1).start : p.start + 3600_000L;
            if (p.end <= p.start) out.set(i, new Programme(p.start, next, p.title, p.desc));
        }
        return out;
    }

    // ---------------------------------------------------------------- films & shows

    String rawVodCategories() throws Exception { return get(api("get_vod_categories"), 45000); }
    String rawSeriesCategories() throws Exception { return get(api("get_series_categories"), 45000); }

    /** Streams a (large) catalogue straight to a file: action = get_vod_streams / get_series. */
    void download(String action, java.io.File dest) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            java.io.File tmp = new java.io.File(dest.getPath() + ".part");
            HttpURLConnection c = (HttpURLConnection) new URL(api(action)).openConnection();
            c.setRequestProperty("User-Agent", UA);
            c.setConnectTimeout(15000);
            c.setReadTimeout(90000);
            try {
                int code = c.getResponseCode();
                if (code >= 400) throw new IOException("Server replied HTTP " + code);
                try (InputStream in = c.getInputStream();
                     java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                if (!tmp.renameTo(dest)) throw new IOException("Could not save catalogue");
                return;
            } catch (IOException e) {
                last = e;
                tmp.delete();
                try { Thread.sleep(2000); } catch (InterruptedException ie) { throw e; }
            } finally {
                c.disconnect();
            }
        }
        throw last;
    }

    JSONObject vodInfo(String id) throws Exception {
        return new JSONObject(get(api("get_vod_info") + "&vod_id=" + id, 20000));
    }

    JSONObject seriesInfo(String id) throws Exception {
        return new JSONObject(get(api("get_series_info") + "&series_id=" + id, 30000));
    }

    String movieUrl(String id, String ext) {
        return base + "/movie/" + user + "/" + pass + "/" + id + "." + (ext == null || ext.isEmpty() ? "mp4" : ext);
    }

    String episodeUrl(String id, String ext) {
        return base + "/series/" + user + "/" + pass + "/" + id + "." + (ext == null || ext.isEmpty() ? "mp4" : ext);
    }

    private static String b64(String s) {
        try {
            return new String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return s;
        }
    }
}
