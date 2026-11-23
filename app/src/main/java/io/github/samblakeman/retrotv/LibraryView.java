package io.github.samblakeman.retrotv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen teletext-style browser for films, shows, local videos and search. */
final class LibraryView extends LinearLayout {
    interface Host {
        Xtream libXtream();
        Prefs libPrefs();
        String libKey();
        List<Xtream.Channel> libChannels();
        Vod.Resume libResume();
        void libWatchLive(Xtream.Channel c);
        void libPlay(Vod.Playable p, long startMs);
        void libClose();
        void libTv();
        void libAskMedia();
        void libAddFolder();
        void libRemoveFolder(String treeUri);
    }

    static final int FILMS = 0, SHOWS = 1, SEARCH = 2, VIDEOS = 3;
    private static final int[] TAB_IDS = {-1, FILMS, SHOWS, VIDEOS, SEARCH};
    private static final int[] CYCLE = {FILMS, SHOWS, VIDEOS, SEARCH};

    private static final int BG = Color.argb(245, 4, 4, 10);
    private static final int BAR = Color.rgb(0, 0, 170);
    private static final int CRUMB_BG = Color.rgb(10, 14, 40);
    private static final int YELLOW = Color.rgb(255, 230, 60);
    private static final int CYAN = Color.rgb(90, 230, 255);
    private static final int GREEN = Color.rgb(90, 255, 120);
    private static final int WHITE = Color.rgb(235, 235, 235);
    private static final int GREY = Color.rgb(130, 130, 150);
    private static final int RED = Color.rgb(255, 90, 90);

    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService catIo = Executors.newSingleThreadExecutor();
    private final ExecutorService infoIo = Executors.newSingleThreadExecutor();
    private final TextView[] tabs = new TextView[5];
    private final TextView crumb;
    private final EditText searchBox;
    private final FrameLayout content;
    private final List<List<Screen>> stacks = new ArrayList<>();
    private final Map<String, JSONObject> info = new HashMap<>();
    private int tab = FILMS;
    private String key = "";

    private Vod.Lib films, shows;
    private boolean filmsLoading, showsLoading;
    private String filmsError, showsError;

    // local videos cache
    private List<Local.MediaFolder> mediaFolders;
    private List<String[]> treeFolders;
    private boolean localFoldersLoading;
    private final Map<String, Local.TreeListing> treeCache = new HashMap<>();
    private final java.util.Set<String> treeLoading = new java.util.HashSet<>();
    private List<Local.Entry> localIndex;
    private List<String> localNorm;
    private boolean localIndexing;

    LibraryView(Context c, Host host) {
        super(c);
        this.host = host;
        setOrientation(VERTICAL);
        setBackgroundColor(BG);
        setClickable(true);                    // don't let touches fall through to the TV
        for (int i = 0; i < 4; i++) stacks.add(new ArrayList<>());

        LinearLayout bar = new LinearLayout(c);
        bar.setBackgroundColor(BAR);
        String[] names = {"TV", "FILMS", "SHOWS", "VIDEOS", "SEARCH"};
        for (int i = 0; i < 5; i++) {
            final int t = TAB_IDS[i];          // -1 = back to TV
            tabs[i] = button(names[i], v -> { if (t < 0) host.libTv(); else selectTab(t); });
            tabs[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            bar.addView(tabs[i], new LayoutParams(0, dp(46), 1));
        }
        addView(bar, new LayoutParams(-1, -2));

        crumb = text(CYAN, 14);
        crumb.setBackgroundColor(CRUMB_BG);
        crumb.setPadding(dp(12), dp(8), dp(12), dp(8));
        crumb.setSingleLine(true);
        crumb.setEllipsize(TextUtils.TruncateAt.START);
        crumb.setOnClickListener(v -> back());
        addView(crumb, new LayoutParams(-1, -2));

        searchBox = new EditText(c);
        searchBox.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        searchBox.setTextColor(YELLOW);
        searchBox.setHintTextColor(GREY);
        searchBox.setHint("Type a channel, film, show or video…");
        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchBox.setBackgroundColor(Color.rgb(20, 20, 30));
        searchBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        searchBox.setVisibility(GONE);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable s) {
                ui.removeCallbacks(searchJob);
                ui.postDelayed(searchJob, 250);
            }
        });
        searchBox.setOnEditorActionListener((v, id, e) -> { hideKeyboard(); return true; });
        addView(searchBox, new LayoutParams(-1, -2));

        content = new FrameLayout(c);
        addView(content, new LayoutParams(-1, 0, 1));
    }

    private final Runnable searchJob = () -> {
        if (tab == SEARCH && stacks.get(SEARCH).size() <= 1) render();
    };

    void shutdown() {
        catIo.shutdownNow();
        infoIo.shutdownNow();
    }

    // ---------------------------------------------------------------- navigation

    /** Called when the login may have changed: drops everything from the old account. */
    void setSource(String k) {
        if (k.equals(key)) return;
        key = k;
        films = shows = null;
        filmsError = showsError = null;
        info.clear();
        for (int i = 0; i < stacks.size(); i++) {
            if (i != VIDEOS) stacks.get(i).clear();
        }
    }

    void open(int t) {
        setVisibility(VISIBLE);
        selectTab(t);
    }

    private void focusSearch() {
        searchBox.requestFocus();
        searchBox.selectAll();
        ui.postDelayed(() -> {
            InputMethodManager im = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    /** Shows the browser again where it was left. */
    void reopen() {
        setVisibility(VISIBLE);
        selectTab(tab);
    }

    /** Re-shows the current screen (e.g. after watching, so resume marks update). */
    void refresh() {
        if (getVisibility() == VISIBLE) render();
    }

    void close() {
        hideKeyboard();
        setVisibility(GONE);
    }

    private void selectTab(int t) {
        saveScroll();
        tab = t;
        for (int i = 1; i < 5; i++) tabs[i].setSelected(TAB_IDS[i] == t);
        if (t != SEARCH) hideKeyboard();
        render();
        if (t == SEARCH && stacks.get(SEARCH).size() <= 1) focusSearch();
    }

    /** Back one level; at the top, back to the TV. */
    boolean back() {
        List<Screen> s = stacks.get(tab);
        if (s.size() > 1) {
            s.remove(s.size() - 1);
            render();
        } else {
            host.libClose();
        }
        return true;
    }

    boolean onKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_L1: selectTab(CYCLE[(cycleIdx() + 3) % 4]); return true;
            case KeyEvent.KEYCODE_BUTTON_R1: selectTab(CYCLE[(cycleIdx() + 1) % 4]); return true;
            case KeyEvent.KEYCODE_BUTTON_B: return back();
        }
        return false;
    }

    private int cycleIdx() {
        for (int i = 0; i < CYCLE.length; i++) if (CYCLE[i] == tab) return i;
        return 0;
    }

    private void push(Screen sc) {
        saveScroll();
        hideKeyboard();
        stacks.get(tab).add(sc);
        render();
    }

    private Screen top() {
        List<Screen> s = stacks.get(tab);
        if (s.isEmpty()) s.add(tab == FILMS ? filmsRoot() : tab == SHOWS ? showsRoot()
                : tab == VIDEOS ? videosRoot() : searchRoot());
        return s.get(s.size() - 1);
    }

    private void saveScroll() {
        List<Screen> s = stacks.get(tab);
        if (s.isEmpty()) return;
        Screen sc = s.get(s.size() - 1);
        if (sc.list != null && sc.list.getChildCount() > 0) {
            sc.pos = sc.list.getFirstVisiblePosition();
            sc.top = sc.list.getChildAt(0).getTop();
        }
    }

    private void render() {
        if (getVisibility() != VISIBLE) return;
        Screen sc = top();
        List<Screen> s = stacks.get(tab);
        StringBuilder path = new StringBuilder(s.size() > 1 ? "◄ " : "");
        for (int i = 0; i < s.size(); i++) path.append(i == 0 ? "" : " › ").append(s.get(i).title);
        crumb.setText(path);
        searchBox.setVisibility(tab == SEARCH && s.size() <= 1 ? VISIBLE : GONE);
        content.removeAllViews();
        sc.list = null;
        View v = sc.build();
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
        if (sc.list != null) {
            if (sc.pos >= 0) sc.list.setSelectionFromTop(sc.pos, sc.top);
            if (tab != SEARCH || s.size() > 1) sc.list.requestFocus();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (ow != 0 && (w != ow || h != oh)) ui.post(() -> { saveScroll(); render(); });
    }

    // ---------------------------------------------------------------- screens

    private abstract static class Screen {
        final String title;
        int pos = -1, top;
        ListView list;
        Screen(String t) { title = t; }
        abstract View build();
    }

    private static final class Row {
        CharSequence text;
        int color = WHITE;
        Vod.Item item;          // when set, the text is worked out as the row is drawn
        boolean heading;
        Runnable tap, hold;
    }

    private Row row(CharSequence text, int color, Runnable tap) {
        Row r = new Row();
        r.text = text;
        r.color = color;
        r.tap = tap;
        return r;
    }

    private Row heading(String text) {
        Row r = row(text, YELLOW, null);
        r.heading = true;
        return r;
    }

    private Row itemRow(Vod.Item it) {
        Row r = new Row();
        r.item = it;
        r.tap = () -> push(it instanceof Vod.Show ? showDetail((Vod.Show) it) : filmDetail((Vod.Movie) it));
        return r;
    }

    private interface Rows { List<Row> get(); }

    private Screen listScreen(String title, Rows rows) {
        return new Screen(title) {
            @Override View build() {
                ListView l = listView(rows.get());
                list = l;
                return l;
            }
        };
    }

    private Screen filmsRoot() {
        return listScreen("FILMS", () -> {
            List<Row> r = new ArrayList<>();
            if (!libRows(r, false)) return r;
            List<Vod.Playable> rec = host.libResume().recent("film");
            if (!rec.isEmpty()) r.add(row("► Continue watching (" + rec.size() + ")", GREEN,
                    () -> push(resumeScreen("film"))));
            r.add(row("✚ Recently added", CYAN, () -> push(items("Recently added", newest(films.items, 300)))));
            r.add(row("☰ All films (" + films.items.size() + ")", CYAN, () -> push(items("All films", films.items))));
            for (Vod.Cat c : films.cats) r.add(row(c.name + "  (" + c.items.size() + ")", WHITE,
                    () -> push(items(c.name, c.items))));
            return r;
        });
    }

    private Screen showsRoot() {
        return listScreen("SHOWS", () -> {
            List<Row> r = new ArrayList<>();
            if (!libRows(r, true)) return r;
            List<Vod.Playable> rec = host.libResume().recent("ep");
            if (!rec.isEmpty()) r.add(row("► Continue watching (" + rec.size() + ")", GREEN,
                    () -> push(resumeScreen("ep"))));
            r.add(row("✚ Recently updated", CYAN, () -> push(items("Recently updated", newest(shows.items, 300)))));
            r.add(row("☰ All shows (" + shows.items.size() + ")", CYAN, () -> push(items("All shows", shows.items))));
            for (Vod.Cat c : shows.cats) r.add(row(c.name + "  (" + c.items.size() + ")", WHITE,
                    () -> push(items(c.name, c.items))));
            return r;
        });
    }

    /** Adds a loading / error / no-login row and returns false while the catalogue isn't there yet. */
    private boolean libRows(List<Row> r, boolean isShows) {
        if (host.libXtream() == null) {
            r.add(row("No IPTV account configured.", YELLOW, null));
            r.add(row("Add your Xtream Codes login in Settings (⚙) for Films & Shows, or switch to VIDEOS for local files.", GREY, null));
            return false;
        }
        Vod.Lib lib = isShows ? shows : films;
        if (lib != null) return true;
        ensure(isShows);
        String err = isShows ? showsError : filmsError;
        if (err != null) {
            r.add(row("Couldn't load " + (isShows ? "shows" : "films") + " – tap to retry", RED, () -> {
                if (isShows) showsError = null; else filmsError = null;
                render();
            }));
            r.add(row(err, GREY, null));
        } else {
            boolean cached = Vod.cachedFresh(getContext(), isShows, key);
            r.add(row(cached ? "LOADING…" : "DOWNLOADING " + (isShows ? "SHOWS" : "FILMS") + " LIST…", YELLOW, null));
        }
        return false;
    }

    private void ensure(boolean isShows) {
        if (host.libXtream() == null) return;
        if (isShows ? (shows != null || showsLoading || showsError != null)
                : (films != null || filmsLoading || filmsError != null)) return;
        if (isShows) showsLoading = true; else filmsLoading = true;
        final String k = key;
        final Context ctx = getContext().getApplicationContext();
        final Xtream x = host.libXtream();
        catIo.execute(() -> {
            Vod.Lib lib = null;
            String err = null;
            try {
                lib = Vod.load(ctx, x, isShows, k);
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            }
            final Vod.Lib l = lib;
            final String er = err;
            ui.post(() -> {
                if (!k.equals(key)) return;
                if (isShows) { shows = l; showsLoading = false; showsError = er; }
                else { films = l; filmsLoading = false; filmsError = er; }
                List<Screen> s = stacks.get(tab);
                if (getVisibility() == VISIBLE && (s.size() <= 1)) render();
            });
        });
    }

    private static List<Vod.Item> newest(List<Vod.Item> all, int n) {
        List<Vod.Item> l = new ArrayList<>(all);
        l.sort((a, b) -> Long.compare(b.added, a.added));
        return l.subList(0, Math.min(n, l.size()));
    }

    private Screen items(String title, List<Vod.Item> list) {
        return listScreen(title + " (" + list.size() + ")", () -> {
            List<Row> r = new ArrayList<>(list.size());
            for (Vod.Item it : list) r.add(itemRow(it));
            return r;
        });
    }

    private Screen resumeScreen(String kind) {
        return listScreen("Continue watching", () -> {
            List<Row> r = new ArrayList<>();
            Vod.Resume rs = host.libResume();
            for (Vod.Playable rp : rs.recent(kind)) {
                long pos = rs.pos(rp.key()), dur = rs.dur(rp.key());
                SpannableStringBuilder sb = new SpannableStringBuilder();
                append(sb, rp.title, WHITE);
                if (!rp.sub.isEmpty()) append(sb, "  " + rp.sub, CYAN);
                append(sb, "  ◔ " + Vod.clock(pos) + (dur > 0 ? " / " + Vod.clock(dur) : ""), GREEN);
                Row row = row(sb, WHITE, () -> {
                    if (kind.equals("local")) playLocalLinked(rp, pos);
                    else host.libPlay(rp, pos);
                });
                row.hold = () -> {
                    rs.forget(rp);
                    Toast.makeText(getContext(), "Removed from Continue watching", Toast.LENGTH_SHORT).show();
                    render();
                };
                r.add(row);
            }
            if (r.isEmpty()) r.add(row("Nothing in progress", GREY, null));
            else r.add(row("(long-press to remove one)", GREY, null));
            return r;
        });
    }

    private void playLocalLinked(Vod.Playable p, long pos) {
        final Context ctx = getContext().getApplicationContext();
        final Set<String> trees = host.libPrefs().treeUris();
        infoIo.execute(() -> {
            Vod.Playable linked = Local.withNext(ctx, p, trees);
            ui.post(() -> host.libPlay(linked, pos));
        });
    }

    // ---------------------------------------------------------------- my videos (MediaStore + SAF)

    private void ensureLocalFolders() {
        if (mediaFolders != null || localFoldersLoading) return;
        localFoldersLoading = true;
        final Context ctx = getContext().getApplicationContext();
        final Set<String> trees = host.libPrefs().treeUris();
        catIo.execute(() -> {
            List<Local.MediaFolder> mf = Local.mediaFolders(ctx);
            List<String[]> tf = new ArrayList<>();
            for (String ts : trees) {
                Uri u = Uri.parse(ts);
                tf.add(new String[]{ts, Local.treeLabel(ctx, u), Local.treeSubpath(u)});
            }
            tf.sort((a, b) -> Local.natural(a[1], b[1]));
            ui.post(() -> {
                mediaFolders = mf;
                treeFolders = tf;
                localFoldersLoading = false;
                if (getVisibility() == VISIBLE && tab == VIDEOS && stacks.get(VIDEOS).size() <= 1) render();
            });
        });
    }

    private Screen videosRoot() {
        return listScreen("MY VIDEOS", () -> {
            List<Row> r = new ArrayList<>();
            List<Vod.Playable> rec = host.libResume().recent("local");
            if (!rec.isEmpty()) r.add(row("\u25ba Continue watching (" + rec.size() + ")", GREEN,
                    () -> push(resumeScreen("local"))));

            if (mediaFolders == null) {
                ensureLocalFolders();
                r.add(row("SCANNING VIDEO FOLDERS…", YELLOW, null));
            } else {
                // 1. User-picked SAF folders (works with .nomedia / ROMs / SD card)
                if (treeFolders != null && !treeFolders.isEmpty()) {
                    r.add(heading("ADDED FOLDERS"));
                    for (String[] tf : treeFolders) {
                        SpannableStringBuilder sb = new SpannableStringBuilder();
                        append(sb, "\u25b8 " + tf[1], WHITE);
                        if (!tf[2].isEmpty()) append(sb, "   " + tf[2], GREY);
                        Row fr = row(sb, WHITE, () -> {
                            Uri u = Uri.parse(tf[0]);
                            push(treeFolderScreen(u, DocumentsContract.getTreeDocumentId(u), tf[1]));
                        });
                        fr.hold = () -> {
                            host.libRemoveFolder(tf[0]);
                            Toast.makeText(getContext(), "Removed folder: " + tf[1], Toast.LENGTH_SHORT).show();
                        };
                        r.add(fr);
                    }
                    r.add(row("(long-press an added folder to remove it)", GREY, null));
                }

                // 2. Device video folders via MediaStore
                r.add(heading("DEVICE VIDEOS"));
                if (!Local.hasMediaAccess(getContext())) {
                    r.add(row("+ Allow access to device videos", YELLOW, host::libAskMedia));
                    r.add(row("Lets Retro TV browse Movies, Downloads and Camera folders automatically.", GREY, null));
                } else if (mediaFolders.isEmpty()) {
                    r.add(row("No device video folders found", GREY, null));
                } else {
                    for (Local.MediaFolder f : mediaFolders) {
                        SpannableStringBuilder sb = new SpannableStringBuilder();
                        append(sb, "\u25b8 " + f.name, WHITE);
                        append(sb, "  (" + f.videos.size() + ")", GREY);
                        r.add(row(sb, WHITE, () -> push(mediaFolderScreen(f))));
                    }
                }
            }

            r.add(heading("CUSTOM FOLDERS"));
            r.add(row("+ Add a folder (SD card, .nomedia, custom…)", CYAN, host::libAddFolder));
            r.add(row("Use Android's folder picker to add any folder, including folders with .nomedia files or on SD cards.", GREY, null));
            return r;
        });
    }

    private Screen mediaFolderScreen(Local.MediaFolder f) {
        return listScreen(f.name, () -> {
            List<Row> r = new ArrayList<>();
            Map<String, Vod.Playable> chain = Local.chain(f.videos);
            for (Local.Entry e : f.videos) r.add(localRow(chain.get(e.uri), null));
            if (r.isEmpty()) r.add(row("No videos in this folder", GREY, null));
            return r;
        });
    }

    private Screen treeFolderScreen(Uri treeUri, String docId, String title) {
        final String ck = treeUri.toString() + "|" + docId;
        return listScreen(title, () -> {
            List<Row> r = new ArrayList<>();
            Local.TreeListing listing = treeCache.get(ck);
            if (listing == null) {
                if (treeLoading.add(ck)) {
                    final Context ctx = getContext().getApplicationContext();
                    catIo.execute(() -> {
                        Local.TreeListing l = Local.listTree(ctx, treeUri, docId, title);
                        ui.post(() -> {
                            treeLoading.remove(ck);
                            treeCache.put(ck, l);
                            if (getVisibility() == VISIBLE && tab == VIDEOS) render();
                        });
                    });
                }
                r.add(row("LOADING FOLDER…", YELLOW, null));
                return r;
            }
            for (Local.TreeDir d : listing.dirs) {
                r.add(row("\u25b8 " + d.name + "/", CYAN, () -> push(treeFolderScreen(treeUri, d.docId, d.name))));
            }
            Map<String, Vod.Playable> chain = Local.chain(listing.videos);
            for (Local.Entry e : listing.videos) r.add(localRow(chain.get(e.uri), null));
            if (r.isEmpty()) r.add(row("No videos in this folder", GREY, null));
            return r;
        });
    }

    /** A video file row: tap resumes (or plays), long-press plays from the start. */
    private Row localRow(Vod.Playable p, String folder) {
        Vod.Resume rs = host.libResume();
        long pos = rs.pos(p.key());
        boolean seen = rs.watched(p.key());
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, seen ? "\u2713 " : pos > 0 ? "\u25d4 " : "  ", seen ? GREY : GREEN);
        append(sb, p.title, seen ? GREY : WHITE);
        if (pos > 0) append(sb, "  " + Vod.clock(pos), GREEN);
        if (folder != null) append(sb, "   " + folder, GREY);
        Row row = row(sb, WHITE, () -> host.libPlay(p, pos));
        row.hold = () -> host.libPlay(p, 0);
        return row;
    }

    private void ensureLocalIndex() {
        if (localIndex != null || localIndexing) return;
        Set<String> trees = host.libPrefs().treeUris();
        if (!Local.hasMediaAccess(getContext()) && trees.isEmpty()) return;
        localIndexing = true;
        final Context ctx = getContext().getApplicationContext();
        catIo.execute(() -> {
            List<Local.Entry> l = Local.index(ctx, trees);
            List<String> norms = new ArrayList<>(l.size());
            for (Local.Entry e : l) norms.add(Vod.norm(Local.title(e.name)));
            ui.post(() -> {
                localIndex = l;
                localNorm = norms;
                localIndexing = false;
                if (tab == SEARCH && stacks.get(SEARCH).size() <= 1) render();
            });
        });
    }

    private void searchLocal(List<Row> r, String[] w) {
        Set<String> trees = host.libPrefs().treeUris();
        if (!Local.hasMediaAccess(getContext()) && trees.isEmpty()) return;
        if (localIndex == null) {
            r.add(heading("MY VIDEOS"));
            r.add(row("  still looking…", GREY, null));
            return;
        }
        List<Row> hits = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < localIndex.size(); i++) {
            if (!Vod.matches(localNorm.get(i), w)) continue;
            n++;
            if (hits.size() >= 100) continue;
            Local.Entry e = localIndex.get(i);
            Vod.Playable p = Local.playable(e);
            Row row = localRow(p, e.folder);
            long pos = host.libResume().pos(p.key());
            row.tap = () -> playLocalLinked(p, pos);
            row.hold = () -> playLocalLinked(p, 0);
            hits.add(row);
        }
        r.add(heading("MY VIDEOS  (" + n + ")"));
        if (hits.isEmpty()) r.add(row("  none", GREY, null));
        r.addAll(hits);
    }

    /** The phone's files or permissions may have changed: look again next time. */
    void forgetLocal() {
        mediaFolders = null;
        treeFolders = null;
        treeCache.clear();
        localIndex = null;
        localNorm = null;
    }

    // ---------------------------------------------------------------- search

    private List<Xtream.Channel> normFor;
    private String[] chNorm = new String[0];

    private Screen searchRoot() {
        return listScreen("SEARCH", () -> {
            boolean hasIptv = host.libXtream() != null;
            if (hasIptv) {
                ensure(false);
                ensure(true);
            }
            ensureLocalIndex();
            List<Row> r = new ArrayList<>();
            String q = searchBox.getText().toString();
            String[] w = Vod.words(q);
            if (w.length == 0) {
                r.add(row(hasIptv ? "Search TV channels, films, shows and videos." : "Search your local videos.", GREY, null));
                r.add(row("Type part of a name to filter.", GREY, null));
                return r;
            }
            String first = " " + w[0];

            if (hasIptv) {
                List<Xtream.Channel> ch = host.libChannels();
                if (ch != normFor) {
                    normFor = ch;
                    chNorm = new String[ch.size()];
                    for (int i = 0; i < ch.size(); i++) chNorm[i] = Vod.norm(ch.get(i).name);
                }
                List<Row> hits = new ArrayList<>();
                int n = 0;
                for (int i = 0; i < ch.size(); i++) {
                    if (!Vod.matches(chNorm[i], w)) continue;
                    n++;
                    if (hits.size() >= 60) continue;
                    Xtream.Channel c = ch.get(i);
                    SpannableStringBuilder sb = new SpannableStringBuilder();
                    append(sb, "TV  ", GREEN);
                    append(sb, c.num + "  ", GREY);
                    append(sb, c.name, WHITE);
                    hits.add(row(sb, WHITE, () -> { hideKeyboard(); host.libWatchLive(c); }));
                }
                r.add(heading("TV CHANNELS  (" + n + (n > hits.size() ? ", first " + hits.size() : "") + ")"));
                if (hits.isEmpty()) r.add(row("  none", GREY, null));
                r.addAll(hits);

                searchLib(r, w, first, false);
                searchLib(r, w, first, true);
            }
            searchLocal(r, w);
            return r;
        });
    }

    private void searchLib(List<Row> r, String[] w, String first, boolean isShows) {
        Vod.Lib lib = isShows ? shows : films;
        String label = isShows ? "SHOWS" : "FILMS";
        if (lib == null) {
            String err = isShows ? showsError : filmsError;
            r.add(heading(label));
            r.add(row(err != null ? "  couldn't load – " + err : "  still loading…", GREY, null));
            return;
        }
        List<Vod.Item> hits = new ArrayList<>();
        for (Vod.Item it : lib.items) if (Vod.matches(it.norm, w)) hits.add(it);
        // names starting with the first word go first, newest first within each group
        hits.sort((a, b) -> {
            int pa = a.norm.startsWith(first) ? 0 : 1, pb = b.norm.startsWith(first) ? 0 : 1;
            return pa != pb ? Integer.compare(pa, pb) : Long.compare(b.added, a.added);
        });
        int shown = Math.min(hits.size(), 150);
        r.add(heading(label + "  (" + hits.size() + (hits.size() > shown ? ", first " + shown : "") + ")"));
        if (hits.isEmpty()) r.add(row("  none", GREY, null));
        for (int i = 0; i < shown; i++) r.add(itemRow(hits.get(i)));
    }

    // ---------------------------------------------------------------- film details

    private Screen filmDetail(Vod.Movie m) {
        return new Screen(m.name) {
            @Override View build() {
                Detail d = new Detail(m.icon);
                d.title.setText(m.name);
                d.meta.setText(stars(m.rating));
                LinearLayout buttons = new LinearLayout(getContext());
                buttons.setOrientation(HORIZONTAL);
                d.info.addView(buttons, d.info.indexOfChild(d.plot), spaced(-1, dp(48)));

                final String[] ext = {m.ext};
                final String[] img = {m.icon};
                Runnable fillButtons = () -> {
                    buttons.removeAllViews();
                    Vod.Playable p = new Vod.Playable(false, m.id, ext[0], m.name, "", "", img[0]);
                    long pos = host.libResume().pos(p.key());
                    if (pos > 0) {
                        buttons.addView(button("► RESUME " + Vod.clock(pos), v -> host.libPlay(p, pos)), bl());
                        buttons.addView(button("⟲ FROM START", v -> host.libPlay(p, 0)), bl());
                    } else {
                        buttons.addView(button(host.libResume().watched(p.key()) ? "► PLAY AGAIN" : "► PLAY",
                                v -> host.libPlay(p, 0)), bl());
                    }
                    buttons.getChildAt(0).requestFocus();
                };
                fillButtons.run();

                withInfo("vod:" + m.id, () -> host.libXtream().vodInfo(m.id), o -> {
                    JSONObject in = o.optJSONObject("info");
                    JSONObject md = o.optJSONObject("movie_data");
                    if (md != null && !md.optString("container_extension").isEmpty()) ext[0] = md.optString("container_extension");
                    if (in == null) return;
                    String cover = first(in.optString("cover_big"), in.optString("movie_image"));
                    if (!cover.isEmpty()) { img[0] = cover; Img.load(d.poster, cover, d.posterW); }
                    List<String> meta = new ArrayList<>();
                    String rel = in.optString("releasedate");
                    if (rel.length() >= 4) meta.add(rel.substring(0, 4));
                    if (!in.optString("genre").isEmpty()) meta.add(in.optString("genre"));
                    String dur = runtime(in.optString("duration"), in.optInt("duration_secs", 0));
                    if (!dur.isEmpty()) meta.add(dur);
                    String st = stars(first(in.optString("rating"), m.rating));
                    if (!st.isEmpty()) meta.add(st);
                    d.meta.setText(TextUtils.join("  ·  ", meta));
                    d.plot.setText(first(in.optString("plot"), in.optString("description")));
                    StringBuilder cr = new StringBuilder();
                    if (!in.optString("director").isEmpty()) cr.append("Director: ").append(in.optString("director"));
                    String cast = first(in.optString("cast"), in.optString("actors"));
                    if (!cast.isEmpty()) cr.append(cr.length() > 0 ? "\n" : "").append("Cast: ").append(cast);
                    d.credits.setText(cr);
                    fillButtons.run();
                });
                return d.root;
            }
        };
    }

    // ---------------------------------------------------------------- show details

    private Screen showDetail(Vod.Show s) {
        return new Screen(s.name) {
            LinkedHashMap<Integer, List<Vod.Episode>> seasons;
            Integer season;

            @Override View build() {
                Detail d = new Detail(s.icon);
                d.title.setText(s.name);
                d.meta.setText(TextUtils.join("  ·  ", nonEmpty(s.year, s.genre, stars(s.rating))));
                d.plot.setText(s.plot);
                d.plot.setMaxLines(wide() ? 6 : 4);
                d.plot.setEllipsize(TextUtils.TruncateAt.END);

                LinearLayout sbar = new LinearLayout(getContext());
                sbar.setGravity(Gravity.CENTER_VERTICAL);
                TextView prev = button("◄", null), next = button("►", null);
                TextView label = text(YELLOW, 16);
                label.setGravity(Gravity.CENTER);
                label.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                label.setText("loading episodes…");
                sbar.addView(prev, new LayoutParams(dp(56), dp(44)));
                sbar.addView(label, new LayoutParams(0, dp(44), 1));
                sbar.addView(next, new LayoutParams(dp(56), dp(44)));
                d.info.addView(sbar, d.info.indexOfChild(d.plot), spaced(-1, dp(44)));

                List<Row> rows = new ArrayList<>();
                RowAdapter ad = new RowAdapter(rows);
                ListView l = d.makeList(ad);
                list = l;

                Runnable show = () -> {
                    rows.clear();
                    if (seasons == null || seasons.isEmpty()) {
                        label.setText(seasons == null ? "loading episodes…" : "no episodes");
                        ad.notifyDataSetChanged();
                        return;
                    }
                    List<Integer> keys = new ArrayList<>(seasons.keySet());
                    if (season == null || !seasons.containsKey(season)) season = keys.get(0);
                    List<Vod.Episode> eps = seasons.get(season);
                    label.setText("SEASON " + season + "  (" + eps.size() + ")"
                            + (keys.size() > 1 ? "  " + (keys.indexOf(season) + 1) + "/" + keys.size() : ""));
                    Map<String, Vod.Playable> chain = chain(s, seasons);
                    Vod.Resume rs = host.libResume();
                    for (Vod.Episode ep : eps) {
                        Vod.Playable p = chain.get(ep.id);
                        long pos = rs.pos(p.key());
                        boolean seen = rs.watched(p.key());
                        SpannableStringBuilder sb = new SpannableStringBuilder();
                        append(sb, seen ? "✓ " : pos > 0 ? "◔ " : "  ", seen ? GREY : GREEN);
                        append(sb, String.format(Locale.ROOT, "E%02d  ", ep.num), CYAN);
                        append(sb, ep.title.isEmpty() ? "Episode " + ep.num : ep.title, seen ? GREY : WHITE);
                        if (pos > 0) append(sb, "  " + Vod.clock(pos), GREEN);
                        else if (ep.durSecs > 0) append(sb, "  " + (ep.durSecs + 30) / 60 + "m", GREY);
                        Row r = row(sb, WHITE, () -> {
                            if (pos > 0) Toast.makeText(getContext(), "Resuming at " + Vod.clock(pos)
                                    + " – long-press an episode to start over", Toast.LENGTH_SHORT).show();
                            host.libPlay(p, pos);
                        });
                        r.hold = () -> host.libPlay(p, 0);
                        rows.add(r);
                    }
                    ad.notifyDataSetChanged();
                };
                prev.setOnClickListener(v -> step(-1, show));
                next.setOnClickListener(v -> step(1, show));

                withInfo("series:" + s.id, () -> host.libXtream().seriesInfo(s.id), o -> {
                    seasons = Vod.episodes(o);
                    JSONObject in = o.optJSONObject("info");
                    if (in != null) {
                        String plot = in.optString("plot").trim();
                        if (!plot.isEmpty()) d.plot.setText(plot);
                        String cover = in.optString("cover");
                        if (s.icon.isEmpty() && !cover.isEmpty()) Img.load(d.poster, cover, d.posterW);
                        if (!in.optString("cast").isEmpty()) d.credits.setText("Cast: " + in.optString("cast"));
                    }
                    if (season == null) season = resumeSeason(s, seasons);
                    show.run();
                    if (pos >= 0) l.setSelectionFromTop(pos, top);
                });
                show.run();
                return d.root;
            }

            private void step(int dir, Runnable show) {
                if (seasons == null || seasons.size() < 2) return;
                List<Integer> keys = new ArrayList<>(seasons.keySet());
                int i = (keys.indexOf(season) + dir + keys.size()) % keys.size();
                season = keys.get(i);
                show.run();
            }
        };
    }

    /** Season of the episode watched most recently, else the first season. */
    private Integer resumeSeason(Vod.Show s, LinkedHashMap<Integer, List<Vod.Episode>> seasons) {
        if (seasons.isEmpty()) return null;
        for (Vod.Playable p : host.libResume().recent("ep")) {
            if (!p.showId.equals(s.id)) continue;
            for (Map.Entry<Integer, List<Vod.Episode>> e : seasons.entrySet())
                for (Vod.Episode ep : e.getValue()) if (ep.id.equals(p.id)) return e.getKey();
        }
        for (Map.Entry<Integer, List<Vod.Episode>> e : seasons.entrySet())
            for (Vod.Episode ep : e.getValue())
                if (!host.libResume().watched("ep:" + ep.id)) return e.getKey();
        return seasons.keySet().iterator().next();
    }

    /** Every episode as a Playable, each linked to the next one for auto-play. */
    private static Map<String, Vod.Playable> chain(Vod.Show s, LinkedHashMap<Integer, List<Vod.Episode>> seasons) {
        Map<String, Vod.Playable> m = new LinkedHashMap<>();
        Vod.Playable prev = null;
        for (List<Vod.Episode> eps : seasons.values()) {
            for (Vod.Episode ep : eps) {
                String sub = String.format(Locale.ROOT, "S%02dE%02d", ep.season, ep.num)
                        + (ep.title.isEmpty() ? "" : " · " + ep.title);
                Vod.Playable p = new Vod.Playable(true, ep.id, ep.ext, s.name, sub, s.id,
                        ep.image.isEmpty() ? s.icon : ep.image);
                if (prev != null) prev.next = p;
                prev = p;
                m.put(ep.id, p);
            }
        }
        return m;
    }

    // ---------------------------------------------------------------- detail layout

    private interface Fetch { JSONObject get() throws Exception; }
    private interface Got { void got(JSONObject o); }

    /** Runs got() with cached details, or fetches them once in the background. */
    private void withInfo(String k, Fetch f, Got got) {
        JSONObject o = info.get(k);
        if (o != null) { got.got(o); return; }
        final String acct = key;
        infoIo.execute(() -> {
            JSONObject r;
            try { r = f.get(); } catch (Exception e) { r = null; }
            final JSONObject res = r;
            ui.post(() -> {
                if (res == null || !acct.equals(key)) {
                    if (res == null) Toast.makeText(getContext(), "Couldn't load details", Toast.LENGTH_SHORT).show();
                    return;
                }
                info.put(k, res);
                if (getVisibility() == VISIBLE) got.got(res);
            });
        });
    }

    /** Poster beside (landscape) or above (portrait) a column of title / meta / buttons / plot. */
    private final class Detail {
        final LinearLayout root, info;
        final ImageView poster;
        final TextView title, meta, plot, credits;
        final int posterW;
        private final boolean wide = wide();

        Detail(String posterUrl) {
            Context c = getContext();
            root = new LinearLayout(c);
            root.setOrientation(wide ? HORIZONTAL : VERTICAL);
            root.setPadding(dp(16), dp(12), dp(16), 0);

            int w = Math.max(getWidth(), 1);
            posterW = wide ? Math.min((int) (w * 0.24f), dp(260)) : Math.min((int) (w * 0.3f), dp(150));
            poster = new ImageView(c);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackgroundColor(Color.rgb(16, 26, 78));
            Img.load(poster, posterUrl, posterW);

            info = new LinearLayout(c);
            info.setOrientation(VERTICAL);
            title = text(YELLOW, 22);
            title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            meta = text(CYAN, 14);
            plot = text(WHITE, 15);
            plot.setTypeface(Typeface.SANS_SERIF);
            plot.setLineSpacing(0, 1.15f);
            credits = text(GREY, 13);
            credits.setTypeface(Typeface.SANS_SERIF);
            info.addView(title);
            info.addView(meta);
            info.addView(plot);
            info.addView(credits);
            for (int i = 0; i < info.getChildCount(); i++)
                ((LayoutParams) info.getChildAt(i).getLayoutParams()).bottomMargin = dp(8);

            if (wide) {
                root.addView(poster, new LayoutParams(posterW, (int) (posterW * 1.5f)));
                ScrollView sv = new ScrollView(c);
                sv.addView(info);
                LayoutParams lp = new LayoutParams(0, -1, 1);
                lp.leftMargin = dp(18);
                root.addView(sv, lp);
            } else {
                LinearLayout topRow = new LinearLayout(c);
                topRow.addView(poster, new LayoutParams(posterW, (int) (posterW * 1.5f)));
                LinearLayout right = new LinearLayout(c);
                right.setOrientation(VERTICAL);
                LayoutParams lp = new LayoutParams(0, -2, 1);
                lp.leftMargin = dp(14);
                topRow.addView(right, lp);
                info.removeView(title);
                info.removeView(meta);
                right.addView(title);
                right.addView(meta);
                root.addView(topRow);
                ScrollView sv = new ScrollView(c);
                sv.addView(info);
                LayoutParams sl = new LayoutParams(-1, 0, 1);
                sl.topMargin = dp(10);
                root.addView(sv, sl);
            }
        }

        ListView makeList(RowAdapter ad) {
            ScrollView sv = (ScrollView) info.getParent();
            ViewGroup parent = (ViewGroup) sv.getParent();
            int idx = parent.indexOfChild(sv);
            LayoutParams lp = (LayoutParams) sv.getLayoutParams();
            parent.removeView(sv);
            sv.removeView(info);
            ListView l = listViewFor(ad);
            l.setItemsCanFocus(true);
            View header = info;
            if (!wide) {
                View topRow = root.getChildAt(0);
                root.removeView(topRow);
                LinearLayout box = new LinearLayout(getContext());
                box.setOrientation(VERTICAL);
                box.addView(topRow);
                LayoutParams il = new LayoutParams(-1, -2);
                il.topMargin = dp(10);
                box.addView(info, il);
                header = box;
                idx = 0;
            }
            header.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
            l.addHeaderView(header, null, false);
            parent.addView(l, idx, lp);
            return l;
        }
    }

    private boolean wide() {
        return getWidth() > getHeight() * 1.1f;
    }

    // ---------------------------------------------------------------- list + widgets

    private ListView listView(List<Row> rows) {
        return listViewFor(new RowAdapter(rows));
    }

    private ListView listViewFor(RowAdapter ad) {
        ListView l = new ListView(getContext());
        l.setDividerHeight(0);
        l.setSelector(android.R.drawable.list_selector_background);
        l.setFastScrollEnabled(ad.getCount() > 60);
        l.setAdapter(ad);
        l.setOnItemClickListener((p, v, pos, id) -> {
            Object o = p.getItemAtPosition(pos);
            if (o instanceof Row && ((Row) o).tap != null) ((Row) o).tap.run();
        });
        l.setOnItemLongClickListener((p, v, pos, id) -> {
            Object o = p.getItemAtPosition(pos);
            if (!(o instanceof Row) || ((Row) o).hold == null) return false;
            ((Row) o).hold.run();
            return true;
        });
        l.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) {
                if (s != SCROLL_STATE_IDLE) hideKeyboard();
            }
            @Override public void onScroll(AbsListView v, int a, int b, int c) { }
        });
        return l;
    }

    private final class RowAdapter extends BaseAdapter {
        final List<Row> rows;
        RowAdapter(List<Row> rows) { this.rows = rows; }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean isEnabled(int i) { return rows.get(i).tap != null || rows.get(i).hold != null; }
        @Override public boolean areAllItemsEnabled() { return false; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            TextView t = (TextView) v;
            if (t == null) {
                t = new TextView(getContext());
                t.setTypeface(Typeface.MONOSPACE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(dp(12), dp(11), dp(12), dp(11));
            }
            Row r = rows.get(i);
            boolean info = r.tap == null && r.hold == null && !r.heading;
            t.setSingleLine(!info);
            t.setMaxLines(info ? 8 : 1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setText(r.item != null ? itemText(r.item) : r.text);
            t.setTextColor(r.color);
            t.setTypeface(Typeface.MONOSPACE, r.heading ? Typeface.BOLD : Typeface.NORMAL);
            t.setBackgroundColor(r.heading ? CRUMB_BG : Color.TRANSPARENT);
            return t;
        }
    }

    private CharSequence itemText(Vod.Item it) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        boolean show = it instanceof Vod.Show;
        if (!show) {
            Vod.Resume rs = host.libResume();
            boolean seen = rs.watched("film:" + it.id);
            boolean part = !seen && rs.pos("film:" + it.id) > 0;
            append(sb, seen ? "✓ " : part ? "◔ " : "  ", seen ? GREY : GREEN);
            append(sb, it.name, seen ? GREY : WHITE);
        } else {
            append(sb, "  ", WHITE);
            append(sb, it.name, WHITE);
        }
        String st = stars(it.rating);
        if (!st.isEmpty()) append(sb, "  " + st, GREY);
        return sb;
    }

    private static void append(SpannableStringBuilder sb, String s, int color) {
        int a = sb.length();
        sb.append(s);
        sb.setSpan(new ForegroundColorSpan(color), a, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String stars(String rating) {
        try {
            double d = Double.parseDouble(rating.trim());
            return d > 0 ? String.format(Locale.ROOT, "★%.1f", d) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** "01:58:12" or seconds -> "1h 58m". */
    private static String runtime(String hms, int secs) {
        if (secs <= 0 && hms != null && hms.matches("\\d+:\\d{2}:\\d{2}")) {
            String[] p = hms.split(":");
            secs = Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
        }
        if (secs <= 0) return "";
        int m = (secs + 30) / 60;
        return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m";
    }

    private static String first(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a.trim() : b == null ? "" : b.trim();
    }

    private static List<String> nonEmpty(String... s) {
        List<String> l = new ArrayList<>();
        for (String x : s) if (x != null && !x.isEmpty()) l.add(x);
        return l;
    }

    private LayoutParams spaced(int w, int h) {
        LayoutParams lp = new LayoutParams(w, h);
        lp.bottomMargin = dp(10);
        return lp;
    }

    private LayoutParams bl() {
        LayoutParams lp = new LayoutParams(-2, dp(48));
        lp.rightMargin = dp(10);
        return lp;
    }

    private TextView text(int color, int sp) {
        TextView t = new TextView(getContext());
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    /** Teletext button: white on blue, black on yellow when focused, pressed or selected. */
    private TextView button(String label, View.OnClickListener l) {
        TextView t = text(WHITE, 16);
        t.setText(label);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(14), 0, dp(14), 0);
        StateListDrawable bg = new StateListDrawable();
        int[][] on = {{android.R.attr.state_pressed}, {android.R.attr.state_focused}, {android.R.attr.state_selected}};
        for (int[] s : on) bg.addState(s, new ColorDrawable(YELLOW));
        bg.addState(new int[0], new ColorDrawable(BAR));
        t.setBackground(bg);
        t.setTextColor(new ColorStateList(new int[][]{on[0], on[1], on[2], {}},
                new int[]{Color.BLACK, Color.BLACK, Color.BLACK, WHITE}));
        t.setFocusable(true);
        t.setClickable(true);
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(getWindowToken(), 0);
        searchBox.clearFocus();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
