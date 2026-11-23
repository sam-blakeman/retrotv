package io.github.samblakeman.retrotv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Films & shows: catalogue (cached on disk), playable items, resume points and search. */
final class Vod {
    private Vod() { }

    static final long FRESH_MS = 12 * 3600_000L;

    // ---------------------------------------------------------------- models

    static class Item {
        String id = "", name = "", icon = "", catId = "", rating = "";
        long added;
        String norm = "";
    }

    static final class Movie extends Item {
        String ext = "";
    }

    static final class Show extends Item {
        String plot = "", genre = "", year = "";
    }

    static final class Cat {
        final String id, name;
        final List<Item> items = new ArrayList<>();
        Cat(String id, String name) { this.id = id; this.name = name; }
    }

    static final class Lib {
        final List<Cat> cats = new ArrayList<>();
        final List<Item> items = new ArrayList<>();
    }

    static final class Episode {
        String id = "", title = "", ext = "", plot = "", image = "";
        int season, num, durSecs;
    }

    /** Something the player can play: a film or an episode. */
    static final class Playable {
        final boolean episode;
        final String id, ext, title, sub, showId, image;
        Playable next;   // following episode, for auto-play

        Playable(boolean episode, String id, String ext, String title, String sub, String showId, String image) {
            this.episode = episode; this.id = id; this.ext = ext; this.title = title;
            this.sub = sub == null ? "" : sub; this.showId = showId == null ? "" : showId;
            this.image = image == null ? "" : image;
        }

        // local files (phone storage or a file handed over via ACTION_VIEW)
        String localUri, localKey;
        String fileUri;          // file:// form of a handed-over content:// URI, for "Continue watching"

        static Playable local(String uri, String title, String folder, String key) {
            Playable p = new Playable(false, "", "", title, folder, "", "");
            p.localUri = uri;
            p.localKey = key;
            return p;
        }

        boolean isLocal() { return localUri != null; }

        String kind() { return isLocal() ? "local" : episode ? "ep" : "film"; }

        String key() { return isLocal() ? "local:" + localKey : (episode ? "ep:" : "film:") + id; }

        String url(Xtream x) {
            if (isLocal()) return localUri;
            return episode ? x.episodeUrl(id, ext) : x.movieUrl(id, ext);
        }
    }

    // ---------------------------------------------------------------- catalogue

    /**
     * Loads the films (shows=false) or shows catalogue: disk cache if fresh, otherwise the server
     * (falling back to a stale cache if the server is down).
     */
    static Lib load(Context ctx, Xtream x, boolean shows, String key) throws Exception {
        File d = ctx.getFilesDir();
        String p = shows ? "series" : "vod";
        File cats = new File(d, p + "_cats.json"), items = new File(d, p + "_items.json"), k = new File(d, p + "_key.txt");
        boolean have = cats.exists() && items.exists() && k.exists() && read(k).equals(key);
        boolean fresh = have && System.currentTimeMillis() - items.lastModified() < FRESH_MS;
        if (!fresh) {
            try {
                String rc = shows ? x.rawSeriesCategories() : x.rawVodCategories();
                x.download(shows ? "get_series" : "get_vod_streams", items);
                write(cats, rc);
                write(k, key);
            } catch (Exception e) {
                if (!have) throw e;
            }
        }
        return parse(read(cats), items, shows);
    }

    static boolean cachedFresh(Context ctx, boolean shows, String key) {
        try {
            File d = ctx.getFilesDir();
            String p = shows ? "series" : "vod";
            File items = new File(d, p + "_items.json"), k = new File(d, p + "_key.txt");
            return items.exists() && k.exists() && read(k).equals(key)
                    && System.currentTimeMillis() - items.lastModified() < FRESH_MS;
        } catch (Exception e) {
            return false;
        }
    }

    private static Lib parse(String catsJson, File items, boolean shows) throws Exception {
        Lib lib = new Lib();
        Map<String, Cat> byId = new LinkedHashMap<>();
        JSONArray a = new JSONArray(catsJson);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Cat c = new Cat(o.optString("category_id"), o.optString("category_name").trim());
            byId.put(c.id, c);
        }
        try (JsonReader r = new JsonReader(new InputStreamReader(new FileInputStream(items), StandardCharsets.UTF_8))) {
            r.setLenient(true);
            if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return lib; }
            r.beginArray();
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue; }
                Item it = shows ? readShow(r) : readMovie(r);
                if (it.id.isEmpty() || it.name.isEmpty()) continue;
                it.norm = norm(it.name);
                lib.items.add(it);
                Cat c = byId.get(it.catId);
                if (c != null) c.items.add(it);
            }
            r.endArray();
        }
        for (Cat c : byId.values()) if (!c.items.isEmpty()) lib.cats.add(c);
        return lib;
    }

    private static Movie readMovie(JsonReader r) throws Exception {
        Movie m = new Movie();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "stream_id": m.id = str(r); break;
                case "name": m.name = str(r).trim(); break;
                case "stream_icon": m.icon = str(r); break;
                case "category_id": m.catId = str(r); break;
                case "rating": m.rating = str(r); break;
                case "added": m.added = num(str(r)); break;
                case "container_extension": m.ext = str(r); break;
                default: r.skipValue();
            }
        }
        r.endObject();
        return m;
    }

    private static Show readShow(JsonReader r) throws Exception {
        Show s = new Show();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "series_id": s.id = str(r); break;
                case "name": s.name = str(r).trim(); break;
                case "cover": s.icon = str(r); break;
                case "category_id": s.catId = str(r); break;
                case "rating": s.rating = str(r); break;
                case "last_modified": s.added = num(str(r)); break;
                case "plot": s.plot = str(r).trim(); break;
                case "genre": s.genre = str(r).trim(); break;
                case "releaseDate": case "release_date": {
                    String v = str(r);
                    if (s.year.isEmpty() && v.length() >= 4) s.year = v.substring(0, 4);
                    break;
                }
                default: r.skipValue();
            }
        }
        r.endObject();
        return s;
    }

    private static String str(JsonReader r) throws Exception {
        switch (r.peek()) {
            case NULL: r.nextNull(); return "";
            case BOOLEAN: return String.valueOf(r.nextBoolean());
            case BEGIN_ARRAY: case BEGIN_OBJECT: r.skipValue(); return "";
            default: return r.nextString();
        }
    }

    private static long num(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    // ---------------------------------------------------------------- series info

    /** Seasons in order, each with its episodes in order. */
    static LinkedHashMap<Integer, List<Episode>> episodes(JSONObject info) {
        Map<Integer, List<Episode>> m = new HashMap<>();
        Object e = info.opt("episodes");
        List<JSONArray> groups = new ArrayList<>();
        List<Integer> keys = new ArrayList<>();
        if (e instanceof JSONObject) {
            JSONObject o = (JSONObject) e;
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONArray arr = o.optJSONArray(k);
                if (arr != null) { groups.add(arr); keys.add((int) num(k)); }
            }
        } else if (e instanceof JSONArray) {
            JSONArray arr = (JSONArray) e;
            for (int i = 0; i < arr.length(); i++) {
                JSONArray g = arr.optJSONArray(i);
                if (g != null) { groups.add(g); keys.add(-1); }
            }
        }
        for (int gi = 0; gi < groups.size(); gi++) {
            JSONArray g = groups.get(gi);
            for (int i = 0; i < g.length(); i++) {
                JSONObject o = g.optJSONObject(i);
                if (o == null) continue;
                Episode ep = new Episode();
                ep.id = o.optString("id");
                ep.ext = o.optString("container_extension");
                ep.num = o.optInt("episode_num", i + 1);
                ep.season = o.optInt("season", keys.get(gi) >= 0 ? keys.get(gi) : gi + 1);
                ep.title = cleanTitle(o.optString("title"));
                JSONObject in = o.optJSONObject("info");
                if (in != null) {
                    ep.plot = in.optString("plot").trim();
                    ep.durSecs = in.optInt("duration_secs", 0);
                    ep.image = in.optString("movie_image");
                }
                if (ep.id.isEmpty()) continue;
                List<Episode> l = m.get(ep.season);
                if (l == null) m.put(ep.season, l = new ArrayList<>());
                l.add(ep);
            }
        }
        List<Integer> seasons = new ArrayList<>(m.keySet());
        java.util.Collections.sort(seasons);
        LinkedHashMap<Integer, List<Episode>> out = new LinkedHashMap<>();
        for (Integer s : seasons) {
            List<Episode> l = m.get(s);
            l.sort((a, b) -> Integer.compare(a.num, b.num));
            out.put(s, l);
        }
        return out;
    }

    /** "Show (2026) - S01E01 - Pilot" -> "Pilot". */
    static String cleanTitle(String t) {
        t = t.trim();
        java.util.regex.Matcher mt = java.util.regex.Pattern
                .compile("(?i)\\bS\\d+\\s*E\\d+\\s*(\\[[^\\]]*\\])?\\s*-\\s*(.+)$").matcher(t);
        if (mt.find()) return mt.group(2).trim() + (mt.group(1) == null ? "" : " " + mt.group(1));
        return t;
    }

    // ---------------------------------------------------------------- search

    /** Lower-case, accents removed, punctuation to spaces. */
    static String norm(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return " " + n + " ";
    }

    /** True when every word of the query starts a word of the name. */
    static boolean matches(String normName, String[] words) {
        for (String w : words) if (!normName.contains(" " + w)) return false;
        return true;
    }

    static String[] words(String query) {
        String q = norm(query).trim();
        return q.isEmpty() ? new String[0] : q.split(" ");
    }

    // ---------------------------------------------------------------- resume points

    static final class Resume {
        private final SharedPreferences sp;

        Resume(Context c) { sp = c.getSharedPreferences("resume", Context.MODE_PRIVATE); }

        /** Saved position in ms, 0 if none. */
        long pos(String key) {
            try {
                String s = sp.getString("r:" + key, null);
                return s == null ? 0 : new JSONObject(s).optLong("pos", 0);
            } catch (Exception e) {
                return 0;
            }
        }

        long dur(String key) {
            try {
                String s = sp.getString("r:" + key, null);
                return s == null ? 0 : new JSONObject(s).optLong("dur", 0);
            } catch (Exception e) {
                return 0;
            }
        }

        boolean watched(String key) { return sp.getBoolean("w:" + key, false); }

        void save(Playable p, long pos, long dur) {
            if (dur > 0 && pos > dur * 0.92) {        // near the end: count it as watched
                sp.edit().remove("r:" + p.key()).putBoolean("w:" + p.key(), true).apply();
                return;
            }
            if (pos < 60_000) return;                  // less than a minute in: nothing to resume
            try {
                JSONObject o = new JSONObject()
                        .put("kind", p.kind()).put("uri", p.fileUri != null ? p.fileUri : p.localUri).put("lkey", p.localKey)
                        .put("ep", p.episode).put("id", p.id).put("ext", p.ext).put("title", p.title)
                        .put("sub", p.sub).put("show", p.showId).put("img", p.image)
                        .put("pos", pos).put("dur", dur).put("t", System.currentTimeMillis());
                sp.edit().putString("r:" + p.key(), o.toString()).apply();
            } catch (Exception ignored) {
            }
        }

        void finished(Playable p) {
            sp.edit().remove("r:" + p.key()).putBoolean("w:" + p.key(), true).apply();
        }

        void forget(Playable p) { sp.edit().remove("r:" + p.key()).apply(); }

        /** Unfinished items of one kind ("film", "ep" or "local"), most recent first. */
        List<Playable> recent(String kind) {
            List<JSONObject> l = new ArrayList<>();
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                if (!e.getKey().startsWith("r:") || !(e.getValue() instanceof String)) continue;
                try {
                    JSONObject o = new JSONObject((String) e.getValue());
                    String k = o.optString("kind", o.optBoolean("ep") ? "ep" : "film");
                    if (k.equals(kind)) l.add(o);
                } catch (Exception ignored) {
                }
            }
            l.sort((a, b) -> Long.compare(b.optLong("t"), a.optLong("t")));
            List<Playable> out = new ArrayList<>();
            for (JSONObject o : l) {
                if (kind.equals("local")) {
                    out.add(Playable.local(o.optString("uri"), o.optString("title"), o.optString("sub"), o.optString("lkey")));
                } else {
                    out.add(new Playable(o.optBoolean("ep"), o.optString("id"), o.optString("ext"),
                            o.optString("title"), o.optString("sub"), o.optString("show"), o.optString("img")));
                }
                if (out.size() >= 40) break;
            }
            return out;
        }
    }

    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%02d:%02d", m, sec);
    }

    static String read(File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static void write(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        java.nio.file.Files.write(tmp.toPath(), s.getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename");
    }
}
