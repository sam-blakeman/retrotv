package com.magicv6.retrotv;

import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RETROTEXT: a teletext service built from live data. Pages are 40x25 character grids with the
 * eight teletext colours, double height text and 2x3 block mosaics. News from the BBC's public
 * RSS feeds, weather from Open-Meteo, TV listings from the provider's EPG.
 */
final class Teletext {
    interface Listener { void onPagesChanged(); }

    /** Channels + guide for the 6xx pages. */
    interface TvSource {
        List<Xtream.Channel> ttChannels();
        List<Xtream.Programme> ttEpg(String streamId);
        void ttWantEpg(List<Xtream.Channel> chans);
    }

    // ------------------------------------------------------------------ page model

    static final int ROWS = 25, COLS = 40;
    static final int K = 0, R = 1, G = 2, Y = 3, B = 4, M = 5, C = 6, W = 7;
    static final byte MOS = 1, DBL = 2, LOWER = 4;

    static final class Page {
        final int num;
        int sub = 1, subs = 1;
        final char[][] ch = new char[ROWS][COLS];
        final byte[][] fg = new byte[ROWS][COLS], bg = new byte[ROWS][COLS], fl = new byte[ROWS][COLS];
        final String[] fastLabel = new String[4];
        final int[] fastPage = new int[4];
        Xtream.Channel watch;            // red button tunes to this channel (TV pages)

        Page(int num) {
            this.num = num;
            for (int r = 0; r < ROWS; r++)
                for (int c = 0; c < COLS; c++) { ch[r][c] = ' '; fg[r][c] = W; }
        }

        Page text(int r, int c, String s, int f) { return text(r, c, s, f, -1); }

        Page text(int r, int c, String s, int f, int b) {
            if (r < 0 || r >= ROWS || s == null) return this;
            for (int i = 0; i < s.length(); i++) {
                int x = c + i;
                if (x < 0) continue;
                if (x >= COLS) break;
                ch[r][x] = s.charAt(i);
                fg[r][x] = (byte) f;
                fl[r][x] &= ~MOS;
                if (b >= 0) bg[r][x] = (byte) b;
            }
            return this;
        }

        Page centre(int r, String s, int f) { return text(r, Math.max(0, (COLS - s.length()) / 2), s, f); }

        Page band(int r, int b) {
            for (int c = 0; c < COLS; c++) bg[r][c] = (byte) b;
            return this;
        }

        /** Double height text on rows r and r+1. */
        Page dbl(int r, int c, String s, int f) {
            text(r, c, s, f);
            for (int x = Math.max(0, c); x < Math.min(COLS, c + s.length()); x++) fl[r][x] |= DBL;
            if (r + 1 < ROWS) for (int x = 0; x < COLS; x++) { fl[r + 1][x] |= LOWER; bg[r + 1][x] = bg[r][x]; }
            return this;
        }

        /** Sets one block-graphics pixel (80 x 75 grid, 2 x 3 per cell). */
        void plot(int px, int py, int f) {
            int c = px >> 1, r = py / 3;
            if (c < 0 || c >= COLS || r < 0 || r >= ROWS) return;
            if ((fl[r][c] & MOS) == 0) { ch[r][c] = 0; fl[r][c] |= MOS; }
            int bit = (py % 3) * 2 + (px & 1);
            ch[r][c] |= (char) (1 << bit);
            fg[r][c] = (byte) f;
        }

        Page fast(String red, int rp, String green, int gp, String yellow, int yp, String blue, int bp) {
            fastLabel[0] = red; fastPage[0] = rp;
            fastLabel[1] = green; fastPage[1] = gp;
            fastLabel[2] = yellow; fastPage[2] = yp;
            fastLabel[3] = blue; fastPage[3] = bp;
            int[] cols = {R, G, Y, C};
            int x = 0;
            for (int i = 0; i < 4; i++) {
                String l = fastLabel[i] == null ? "" : fastLabel[i];
                if (l.length() > 9) l = l.substring(0, 9);
                text(24, x, l, cols[i]);
                x += 10;
            }
            return this;
        }
    }

    // ------------------------------------------------------------------ data

    static final class Item {
        String title = "", desc = "";
        long when;
    }

    static final class Section {
        final String title, url;
        final int index, first, colour;
        List<Item> items;
        long at;

        Section(String title, int index, int first, int colour, String url) {
            this.title = title; this.index = index; this.first = first; this.colour = colour; this.url = url;
        }
    }

    private static final String BBC = "https://feeds.bbci.co.uk/";
    private final Section[] sections = {
            new Section("TOP STORIES", 101, 104, B, BBC + "news/rss.xml"),
            new Section("UK NEWS", 102, 120, B, BBC + "news/uk/rss.xml"),
            new Section("WORLD NEWS", 103, 140, B, BBC + "news/world/rss.xml"),
            new Section("TECHNOLOGY", 160, 161, M, BBC + "news/technology/rss.xml"),
            new Section("SCIENCE", 180, 181, M, BBC + "news/science_and_environment/rss.xml"),
            new Section("BUSINESS", 200, 201, C, BBC + "news/business/rss.xml"),
            new Section("SPORT", 300, 301, G, BBC + "sport/rss.xml"),
            new Section("FOOTBALL", 320, 321, G, BBC + "sport/football/rss.xml"),
            new Section("ENTERTAINMENT", 500, 501, M, BBC + "news/entertainment_and_arts/rss.xml"),
    };
    private static final int MAX_STORIES = 15;

    /** UK weather map cities: name, lat, lon. */
    private static final Object[][] CITIES = {
            {"Inverness", 57.48, -4.22}, {"Glasgow", 55.86, -4.25}, {"Edinburgh", 55.95, -3.19},
            {"Belfast", 54.60, -5.93}, {"Newcastle", 54.98, -1.61}, {"Manchester", 53.48, -2.24},
            {"Birmingham", 52.48, -1.90}, {"Norwich", 52.63, 1.30}, {"Cardiff", 51.48, -3.18},
            {"London", 51.51, -0.13}, {"Plymouth", 50.37, -4.14},
    };
    /** Which cities get a number on the map (the rest are too close together). */
    private static final boolean[] ON_MAP = {true, true, false, true, true, true, true, true, true, true, true};

    static final class Wx {
        double now; int nowCode;
        double[] max, min; int[] code; String[] day;
    }

    private Wx[] cityWx;
    private Wx localWx;
    private String localName;
    private long wxAt;
    private volatile String wxTown;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Prefs prefs;
    private final TvSource tv;
    private Listener listener;
    private Map<Integer, List<Page>> pages = new TreeMap<>();
    private boolean refreshing;

    Teletext(Prefs prefs, TvSource tv) {
        this.prefs = prefs;
        this.tv = tv;
        rebuild();
    }

    void setListener(Listener l) { listener = l; }

    List<Page> get(int num) { return pages.get(num); }

    int next(int num, int dir) {
        TreeMap<Integer, List<Page>> m = (TreeMap<Integer, List<Page>>) pages;
        Integer k = dir > 0 ? m.higherKey(num) : m.lowerKey(num);
        if (k == null) k = dir > 0 ? m.firstKey() : m.lastKey();
        return k;
    }

    void shutdown() { io.shutdownNow(); }

    /** Fetches whatever is stale; pages rebuild as each source arrives. */
    void refresh() {
        tv.ttWantEpg(tvChannels());
        if (refreshing) return;
        refreshing = true;
        io.execute(() -> {
            long now = System.currentTimeMillis();
            for (Section s : sections) {
                if (s.items != null && now - s.at < 10 * 60_000L) continue;
                try {
                    List<Item> items = parseRss(Xtream.get(s.url));
                    ui.post(() -> { s.items = items; s.at = System.currentTimeMillis(); rebuild(); });
                } catch (Exception ignored) {
                }
            }
            String town = prefs.weatherTown();
            if (cityWx == null || now - wxAt > 20 * 60_000L || !town.equals(wxTown)) {
                try {
                    Wx[] cw = fetchCities();
                    wxTown = town;
                    Object[] lw = fetchLocal(town.isEmpty() ? "London" : town);
                    ui.post(() -> {
                        cityWx = cw;
                        if (lw != null) { localName = (String) lw[0]; localWx = (Wx) lw[1]; }
                        wxAt = System.currentTimeMillis();
                        rebuild();
                    });
                } catch (Exception ignored) {
                }
            }
            ui.post(() -> refreshing = false);
        });
    }

    /** Called when guide data for a channel arrives. */
    void onEpg() {
        ui.removeCallbacks(rebuildJob);
        ui.postDelayed(rebuildJob, 400);
    }

    private final Runnable rebuildJob = this::rebuild;

    private List<Xtream.Channel> tvChannels() {
        List<Xtream.Channel> l = tv.ttChannels();
        return l.size() > 27 ? l.subList(0, 27) : l;
    }

    // ------------------------------------------------------------------ fetching

    static List<Item> parseRss(String xml) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(xml));
        List<Item> out = new ArrayList<>();
        Item cur = null;
        String tag = null;
        SimpleDateFormat df = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);
        for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.START_TAG) {
                tag = p.getName();
                if ("item".equals(tag)) cur = new Item();
            } else if (e == XmlPullParser.TEXT && cur != null && tag != null) {
                String t = p.getText();
                if ("title".equals(tag)) cur.title += t;
                else if ("description".equals(tag)) cur.desc += t;
                else if ("pubDate".equals(tag)) {
                    try { cur.when = df.parse(t.trim()).getTime(); } catch (Exception ignored) { }
                }
            } else if (e == XmlPullParser.END_TAG) {
                if ("item".equals(p.getName()) && cur != null) {
                    cur.title = clean(cur.title);
                    cur.desc = clean(cur.desc);
                    if (!cur.title.isEmpty()) out.add(cur);
                    cur = null;
                }
                tag = null;
            }
        }
        return out;
    }

    private static final String OM = "https://api.open-meteo.com/v1/forecast?daily=weather_code,"
            + "temperature_2m_max,temperature_2m_min&current=temperature_2m,weather_code&timezone=auto";

    private Wx[] fetchCities() throws Exception {
        StringBuilder la = new StringBuilder(), lo = new StringBuilder();
        for (Object[] c : CITIES) {
            if (la.length() > 0) { la.append(','); lo.append(','); }
            la.append(c[1]);
            lo.append(c[2]);
        }
        JSONArray a = new JSONArray(Xtream.get(OM + "&forecast_days=3&latitude=" + la + "&longitude=" + lo));
        Wx[] out = new Wx[CITIES.length];
        for (int i = 0; i < out.length && i < a.length(); i++) out[i] = parseWx(a.getJSONObject(i));
        return out;
    }

    private Object[] fetchLocal(String town) throws Exception {
        JSONObject g = new JSONObject(Xtream.get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name="
                + URLEncoder.encode(town, "UTF-8")));
        JSONArray res = g.optJSONArray("results");
        if (res == null || res.length() == 0) return null;
        JSONObject r = res.getJSONObject(0);
        Wx w = parseWx(new JSONObject(Xtream.get(OM + "&forecast_days=7&latitude=" + r.getDouble("latitude")
                + "&longitude=" + r.getDouble("longitude"))));
        return new Object[]{r.optString("name", town), w};
    }

    private static Wx parseWx(JSONObject o) throws Exception {
        Wx w = new Wx();
        JSONObject cur = o.getJSONObject("current");
        w.now = cur.getDouble("temperature_2m");
        w.nowCode = cur.getInt("weather_code");
        JSONObject d = o.getJSONObject("daily");
        JSONArray mx = d.getJSONArray("temperature_2m_max"), mn = d.getJSONArray("temperature_2m_min"),
                cd = d.getJSONArray("weather_code"), tm = d.getJSONArray("time");
        int n = mx.length();
        w.max = new double[n]; w.min = new double[n]; w.code = new int[n]; w.day = new String[n];
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.UK), out = new SimpleDateFormat("EEE", Locale.UK);
        for (int i = 0; i < n; i++) {
            w.max[i] = mx.optDouble(i, 0);
            w.min[i] = mn.optDouble(i, 0);
            w.code[i] = cd.optInt(i, 0);
            try { w.day[i] = out.format(in.parse(tm.getString(i))).toUpperCase(Locale.UK); } catch (Exception e) { w.day[i] = "?"; }
        }
        return w;
    }

    static String wxText(int code) {
        if (code == 0) return "Sunny";
        if (code <= 2) return "Bright spells";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 85 && code <= 86) return "Snow showers";
        if (code >= 95) return "Thunder";
        return "Mixed";
    }

    static int wxColour(int code) {
        if (code <= 2) return Y;
        if (code == 3 || code == 45 || code == 48) return W;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return C;
        if (code >= 95) return M;
        return C;
    }

    /** Plain ASCII-ish text a teletext font can show. */
    static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.trim().toCharArray()) {
            switch (ch) {
                case '‘': case '’': case '‛': b.append('\''); break;
                case '“': case '”': b.append('"'); break;
                case '–': case '—': b.append('-'); break;
                case '…': b.append("..."); break;
                case '\n': case '\r': case '\t': case ' ': b.append(' '); break;
                default:
                    if (ch < 0x2000) b.append(ch);
            }
        }
        return b.toString().replaceAll(" +", " ");
    }

    static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            while (w.length() > width) {               // very long word
                if (line.length() > 0) { out.add(line.toString()); line.setLength(0); }
                out.add(w.substring(0, width));
                w = w.substring(width);
            }
            if (line.length() + (line.length() > 0 ? 1 : 0) + w.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    // ------------------------------------------------------------------ page building

    private void rebuild() {
        Map<Integer, List<Page>> m = new TreeMap<>();
        put(m, index());
        for (int i = 0; i < sections.length; i++) buildSection(m, sections[i], sections[(i + 1) % sections.length]);
        buildWeather(m);
        buildTv(m);
        pages = m;
        if (listener != null) listener.onPagesChanged();
    }

    private static void put(Map<Integer, List<Page>> m, Page p) {
        List<Page> l = m.get(p.num);
        if (l == null) m.put(p.num, l = new ArrayList<>());
        l.add(p);
        for (Page q : l) { q.subs = l.size(); }
        p.sub = l.size();
    }

    // logo font: 5x6 glyphs drawn with doubled rows (5x12 block pixels)
    private static final Map<Character, String[]> GLYPH = new java.util.HashMap<>();
    static {
        GLYPH.put('R', new String[]{"####.", "#...#", "####.", "#.#..", "#..#.", "#...#"});
        GLYPH.put('E', new String[]{"#####", "#....", "####.", "#....", "#....", "#####"});
        GLYPH.put('T', new String[]{"#####", "..#..", "..#..", "..#..", "..#..", "..#.."});
        GLYPH.put('O', new String[]{".###.", "#...#", "#...#", "#...#", "#...#", ".###."});
        GLYPH.put('X', new String[]{"#...#", ".#.#.", "..#..", "..#..", ".#.#.", "#...#"});
    }

    private static void logo(Page p, int row) {
        String word = "RETROTEXT";
        int[] cols = {R, Y, G, C, M, W, Y, G, C};
        int x0 = 14, y0 = row * 3;
        for (int i = 0; i < word.length(); i++) {
            String[] g = GLYPH.get(word.charAt(i));
            for (int gy = 0; gy < 6; gy++)
                for (int gx = 0; gx < 5; gx++)
                    if (g[gy].charAt(gx) == '#') {
                        p.plot(x0 + i * 6 + gx, y0 + gy * 2, cols[i]);
                        p.plot(x0 + i * 6 + gx, y0 + gy * 2 + 1, cols[i]);
                    }
        }
    }

    private Page index() {
        Page p = new Page(100);
        for (int r = 1; r <= 4; r++) p.band(r, B);
        logo(p, 1);
        p.centre(5, "NEWS  -  SPORT  -  WEATHER  -  TV", C);
        Object[][] idx = {
                {"Top stories", 101}, {"UK news", 102}, {"World news", 103}, {"Technology", 160},
                {"Science", 180}, {"Business", 200}, {"Sport", 300}, {"Football", 320},
                {"Weather", 400}, {"UK weather map", 401}, {"Your forecast", 402},
                {"Entertainment", 500}, {"TV now & next", 600},
        };
        int r = 7;
        for (Object[] e : idx) {
            String label = (String) e[0];
            StringBuilder dots = new StringBuilder(label);
            while (dots.length() < 30) dots.append('.');
            p.text(r, 3, dots.toString(), r % 2 == 0 ? C : Y);
            p.text(r, 34, String.valueOf(e[1]), W);
            r++;
        }
        if (localWx != null) {
            p.text(21, 2, localName + " now " + Math.round(localWx.now) + "C " + wxText(localWx.nowCode),
                    wxColour(localWx.nowCode));
        }
        Section top = sections[0];
        if (top.items != null && !top.items.isEmpty()) {
            p.band(22, R);
            String h = top.items.get(0).title;
            p.text(22, 1, "NEWS", Y);
            p.text(22, 6, h.length() > 33 ? h.substring(0, 32) + "." : h, W);
        }
        return p.fast("News", 101, "Sport", 300, "Weather", 400, "TV", 600);
    }

    private static Page sectionHead(int num, String title, int colour, boolean big) {
        Page p = new Page(num);
        p.band(1, colour);
        if (big) {
            p.dbl(1, 1, title, W);
        } else {
            p.text(1, 1, title, W);
        }
        return p;
    }

    private void buildSection(Map<Integer, List<Page>> m, Section s, Section next) {
        int firstLink = s.first;
        List<Item> items = s.items;
        if (items == null) {
            Page p = sectionHead(s.index, s.title, s.colour, true);
            p.text(5, 2, "Fetching the latest stories...", C);
            put(m, p.fast("Top", 101, next.title, next.index, "Weather", 400, "Index", 100));
            return;
        }
        int n = Math.min(MAX_STORIES, items.size());
        // index page(s)
        Page p = null;
        int row = 0;
        for (int i = 0; i < n; i++) {
            Item it = items.get(i);
            List<String> lines = wrap(it.title, 34);
            if (lines.size() > 2) lines = lines.subList(0, 2);
            if (p == null || row + lines.size() > 22) {
                if (p != null) put(m, p);
                p = sectionHead(s.index, s.title, s.colour, true);
                p.fast("First", firstLink, next.title, next.index, "Weather", 400, "Index", 100);
                row = 4;
            }
            p.text(row, 1, String.valueOf(s.first + i), W);
            for (String l : lines) p.text(row++, 5, l, i % 2 == 0 ? C : Y);
            row++;
        }
        if (p == null) {
            p = sectionHead(s.index, s.title, s.colour, true);
            p.text(5, 2, "No stories right now.", C);
            p.fast("Top", 101, next.title, next.index, "Weather", 400, "Index", 100);
        }
        p.text(23, 1, "Source: BBC News", G);
        put(m, p);
        // stories
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm EEE d MMM", Locale.UK);
        for (int i = 0; i < n; i++) {
            Item it = items.get(i);
            int num = s.first + i;
            Page sp = sectionHead(num, s.title, s.colour, false);
            int r = 3;
            for (String l : wrap(it.title, 38)) {
                if (r > 8) break;
                sp.dbl(r, 1, l, Y);
                r += 2;
            }
            r++;
            for (String l : wrap(it.desc, 38)) {
                if (r > 21) break;
                sp.text(r++, 1, l, W);
            }
            if (it.when > 0) sp.text(Math.min(r + 1, 22), 1, "Updated " + tf.format(new Date(it.when)), C);
            sp.text(23, 1, "Source: BBC News", G);
            int nx = i + 1 < n ? num + 1 : s.index;
            put(m, sp.fast(i + 1 < n ? "Next" : "Back", nx, "Headlines", s.index, "Top", 101, "Index", 100));
        }
    }

    // ---- weather

    private static final double LON0 = -10.7, LON1 = 2.0, LAT0 = 49.8, LAT1 = 58.9;
    private static final int MAP_X = 2, MAP_Y = 6, MAP_H = 63;   // block pixels
    private static final double KX = Math.cos(Math.toRadians(54.3)) / 1.25;   // pixel aspect correction
    private static final int MAP_W = (int) Math.round(MAP_H * (LON1 - LON0) * KX / (LAT1 - LAT0));

    private static int mapPx(double lon) { return MAP_X + (int) Math.round((lon - LON0) / (LON1 - LON0) * MAP_W); }
    private static int mapPy(double lat) { return MAP_Y + (int) Math.round((LAT1 - lat) / (LAT1 - LAT0) * MAP_H); }

    // simplified coastlines (lon, lat)
    private static final double[] GB = {
            -5.7, 50.05, -5.0, 50.0, -4.2, 50.35, -3.5, 50.35, -2.9, 50.7, -1.9, 50.6, -1.1, 50.78, 0.3, 50.75,
            1.0, 50.95, 1.4, 51.2, 0.9, 51.4, 0.6, 51.5, 1.3, 51.8, 1.75, 52.5, 1.7, 52.75, 1.3, 52.95,
            0.4, 52.95, 0.2, 52.8, 0.35, 53.2, 0.15, 53.6, -0.1, 54.1, -0.6, 54.5, -1.2, 54.65, -1.4, 55.0,
            -1.6, 55.6, -2.0, 55.8, -2.5, 56.0, -3.0, 56.0, -2.6, 56.3, -2.4, 56.6, -2.1, 57.1, -1.8, 57.5,
            -2.5, 57.7, -3.5, 57.7, -4.0, 57.6, -3.8, 57.9, -3.1, 58.3, -3.05, 58.6, -4.0, 58.6, -5.0, 58.6,
            -5.3, 58.2, -5.6, 57.9, -5.7, 57.3, -5.8, 56.8, -5.6, 56.4, -5.7, 55.3, -4.9, 55.6, -4.7, 55.4,
            -5.1, 55.0, -4.9, 54.65, -4.4, 54.7, -3.6, 54.9, -3.4, 54.5, -3.2, 54.1, -2.9, 53.8, -3.1, 53.4,
            -3.4, 53.35, -4.1, 53.3, -4.6, 53.3, -4.7, 52.8, -4.1, 52.6, -4.1, 52.3, -4.6, 52.1, -5.3, 51.85,
            -5.0, 51.6, -4.3, 51.65, -4.0, 51.6, -3.2, 51.45, -2.7, 51.55, -3.0, 51.3, -3.6, 51.2, -4.2, 51.2,
            -4.6, 51.0, -5.0, 50.6,
    };
    private static final double[] IE = {
            -6.2, 55.25, -5.5, 54.6, -6.0, 54.0, -6.1, 53.5, -6.0, 53.0, -6.35, 52.2, -7.0, 52.1, -8.3, 51.8,
            -9.5, 51.5, -10.2, 51.8, -9.8, 52.2, -9.5, 52.6, -9.0, 53.2, -10.1, 53.5, -10.0, 54.2, -8.6, 54.3,
            -8.8, 54.7, -8.2, 55.2, -7.3, 55.35,
    };

    private static boolean inside(double[] poly, double x, double y) {
        boolean in = false;
        int n = poly.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = poly[i * 2], yi = poly[i * 2 + 1], xj = poly[j * 2], yj = poly[j * 2 + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    private static Page weatherHead(int num, String title) {
        Page p = sectionHead(num, title, B, true);
        p.text(23, 1, "Data: Open-Meteo.com", G);
        return p;
    }

    private void buildWeather(Map<Integer, List<Page>> m) {
        Page idx = weatherHead(400, "WEATHER");
        idx.text(4, 3, "UK weather map ..................401", C);
        idx.text(5, 3, "Your forecast ...................402", Y);
        idx.text(6, 3, "UK cities .......................403", C);
        if (localWx != null) {
            idx.dbl(9, 2, localName.toUpperCase(Locale.UK), Y);
            idx.dbl(12, 2, Math.round(localWx.now) + "C  " + wxText(localWx.nowCode), wxColour(localWx.nowCode));
            idx.text(15, 2, "Today: high " + Math.round(localWx.max[0]) + "C, low " + Math.round(localWx.min[0]) + "C", W);
            if (localWx.code.length > 1)
                idx.text(16, 2, "Tomorrow: " + wxText(localWx.code[1]) + ", " + Math.round(localWx.max[1]) + "C", W);
        } else {
            idx.text(9, 2, "Fetching the forecast...", C);
        }
        if (prefs.weatherTown().isEmpty()) idx.text(19, 2, "Set your town in the settings (gear)", G);
        put(m, idx.fast("Map", 401, "Forecast", 402, "Cities", 403, "Index", 100));

        String[] when = {"TODAY", "TOMORROW"};
        for (int d = 0; d < 2; d++) {
            Page p = weatherHead(401, "UK WEATHER");
            p.text(1, 22, when[d] + " MAX C", Y, B);
            for (int py = MAP_Y; py < MAP_Y + MAP_H; py++) {
                for (int px = MAP_X; px <= MAP_X + MAP_W; px++) {
                    double lon = LON0 + (px - MAP_X + 0.5) / MAP_W * (LON1 - LON0);
                    double lat = LAT1 - (py - MAP_Y + 0.5) / MAP_H * (LAT1 - LAT0);
                    if (inside(GB, lon, lat) || inside(IE, lon, lat)) p.plot(px, py, G);
                }
            }
            for (int r = 2; r <= 22; r++) for (int c = 0; c <= (MAP_X + MAP_W) / 2 + 1; c++) p.bg[r][c] = B;
            int row = 4;
            for (int i = 0; i < CITIES.length; i++) {
                Object[] cty = CITIES[i];
                Wx w = cityWx == null ? null : cityWx[i];
                String t = w == null ? "--" : String.valueOf(Math.round(w.max[d]));
                if (ON_MAP[i]) {
                    int c = mapPx((Double) cty[2]) / 2, r = mapPy((Double) cty[1]) / 3;
                    p.text(r, Math.max(0, c - t.length() / 2), t, w == null ? W : wxColour(w.code[d]), K);
                }
                String name = (String) cty[0];
                p.text(row, 25, name, W);
                p.text(row, 36, String.format(Locale.UK, "%3s", t), w == null ? W : wxColour(w.code[d]));
                row++;
            }
            p.text(17, 25, "Yellow  sunny", Y);
            p.text(18, 25, "White   cloudy", W);
            p.text(19, 25, "Cyan    wet", C);
            p.text(20, 25, "Magenta storms", M);
            put(m, p.fast("Forecast", 402, "Cities", 403, "Weather", 400, "Index", 100));
        }

        Page f = weatherHead(402, "FORECAST");
        if (localWx != null) {
            f.dbl(4, 1, localName.toUpperCase(Locale.UK), Y);
            f.text(7, 1, "DAY   HIGH  LOW  OUTLOOK", C);
            for (int i = 0; i < localWx.max.length && i < 7; i++) {
                int r = 9 + i * 2;
                f.text(r, 1, localWx.day[i], W);
                f.text(r, 7, String.format(Locale.UK, "%3dC", Math.round(localWx.max[i])), R);
                f.text(r, 13, String.format(Locale.UK, "%3dC", Math.round(localWx.min[i])), C);
                f.text(r, 19, wxText(localWx.code[i]), wxColour(localWx.code[i]));
            }
        } else {
            f.text(5, 2, "Fetching the forecast...", C);
        }
        put(m, f.fast("Map", 401, "Cities", 403, "Weather", 400, "Index", 100));

        Page c = weatherHead(403, "UK CITIES");
        c.text(4, 1, "CITY         NOW  HIGH  LOW", C);
        for (int i = 0; i < CITIES.length; i++) {
            Wx w = cityWx == null ? null : cityWx[i];
            int r = 6 + i + (i / 4);
            c.text(r, 1, (String) CITIES[i][0], W);
            if (w == null) continue;
            c.text(r, 13, String.format(Locale.UK, "%3d", Math.round(w.now)), wxColour(w.nowCode));
            c.text(r, 18, String.format(Locale.UK, "%3d", Math.round(w.max[0])), R);
            c.text(r, 24, String.format(Locale.UK, "%3d", Math.round(w.min[0])), C);
            c.text(r, 29, wxText(w.nowCode), wxColour(w.nowCode));
        }
        put(m, c.fast("Map", 401, "Forecast", 402, "Weather", 400, "Index", 100));
    }

    // ---- TV

    private void buildTv(Map<Integer, List<Page>> m) {
        List<Xtream.Channel> chans = tvChannels();
        long now = System.currentTimeMillis();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.UK);
        Page p = null;
        int row = 0;
        if (chans.isEmpty()) {
            p = sectionHead(600, "TV NOW & NEXT", R, true);
            p.text(5, 2, "Add favourite channels (long-press a", W);
            p.text(6, 2, "channel in the list) to see them here.", W);
            put(m, p.fast("News", 101, "Sport", 300, "Weather", 400, "Index", 100));
            return;
        }
        for (int i = 0; i < chans.size(); i++) {
            Xtream.Channel ch = chans.get(i);
            int num = 601 + i;
            List<Xtream.Programme> g = tv.ttEpg(ch.id);
            Xtream.Programme cur = null, nxt = null;
            if (g != null) for (Xtream.Programme pr : g) {
                if (pr.end <= now) continue;
                if (cur == null && pr.start <= now) cur = pr;
                else if (nxt == null) { nxt = pr; if (cur != null) break; }
            }
            if (p == null || row > 20) {
                if (p != null) put(m, p);
                p = sectionHead(600, "TV NOW & NEXT", R, true);
                p.fast("News", 101, "Sport", 300, "Weather", 400, "Index", 100);
                row = 4;
            }
            String name = ch.name.length() > 34 ? ch.name.substring(0, 34) : ch.name;
            p.text(row, 1, String.valueOf(num), W);
            p.text(row, 5, name, Y);
            String line = g == null ? "..." : cur != null ? "Now: " + cur.title : nxt != null
                    ? hm.format(new Date(nxt.start)) + " " + nxt.title : "No listings";
            p.text(row + 1, 5, line.length() > 35 ? line.substring(0, 35) : line, C);
            row += 3;

            // channel page
            Page cp = sectionHead(num, name.length() > 19 ? name.substring(0, 19) : name, R, true);
            cp.watch = ch;
            int r = 4;
            if (g == null) cp.text(r, 1, "Fetching listings...", C);
            else if (g.isEmpty()) cp.text(r, 1, "No listings for this channel.", C);
            else {
                for (Xtream.Programme pr : g) {
                    if (pr.end <= now) continue;
                    if (r > 21) break;
                    boolean on = pr.start <= now;
                    cp.text(r, 1, hm.format(new Date(pr.start)), on ? Y : C);
                    String t = Teletext.clean(pr.title);
                    cp.text(r, 7, t.length() > 32 ? t.substring(0, 32) : t, on ? Y : W);
                    r++;
                    if (on && pr.desc != null && !pr.desc.isEmpty()) {
                        for (String l : wrap(clean(pr.desc), 32)) {
                            if (r > 12) break;
                            cp.text(r++, 7, l, G);
                        }
                    }
                }
            }
            put(m, cp.fast("Watch", -1, "TV", 600, "Next ch", i + 1 < chans.size() ? num + 1 : 600, "Index", 100));
        }
        if (p != null) put(m, p);
    }

    /** Header date part, e.g. "Sun 22 Nov". */
    static String headerDate(Calendar c) {
        return new SimpleDateFormat("EEE dd MMM", Locale.UK).format(c.getTime());
    }
}
