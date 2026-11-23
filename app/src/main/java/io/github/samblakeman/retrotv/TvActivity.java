package io.github.samblakeman.retrotv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TvActivity extends Activity implements BezelRenderer.Host, GuideView.Host, LibraryView.Host,
        TeletextView.Host, Teletext.TvSource {
    private static final int GREEN = Color.rgb(90, 255, 120);
    private static final String FAV = "__fav", ALL = "__all";
    private static final int REQ_MEDIA = 101, REQ_TREE = 102;
    private static final String[] MENU_NO_IPTV = {
            "▸  My Videos (local files)",
            "▸  RETROTEXT (News · Weather)",
            "▸  IPTV Login & Settings",
            "▸  Switch TV Bezel (CRT / Widescreen)",
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Prefs prefs;
    private boolean debuggable;
    private Set<String> favSet = new HashSet<>();
    private Map<String, BezelSet> sets;
    private BezelSet set;

    private FrameLayout root, holeBox;
    private GLSurfaceView glView;
    private BezelRenderer renderer;
    private TextView osdCh, osdInfo;
    private LinearLayout panel;
    private TextView catTitle, catPrev, catNext;
    private ListView list;
    private ChannelAdapter adapter;
    private GestureDetector gestures;
    private final EpgStore epg = new EpgStore();
    private GuideView guide;
    private Teletext teletext;
    private TeletextView ttView;
    private View ttKeys;
    private LibraryView lib;
    private Vod.Resume resume;

    // films / episodes / local videos
    private Vod.Playable vod;               // null = watching live TV or standby menu
    private boolean liveOn;                 // a live channel is loaded in the player
    private boolean vodFailed;
    private LinearLayout transport;
    private TextView tTitle, tSub, tPlay, tTime, tDur, tAudio, tSubs;
    private SeekBar seek;
    private boolean seeking;
    private TextView subs;
    private int ticks;

    private ExoPlayer player;
    private Surface videoSurface;

    private Xtream xt;
    private long loadedStamp = -1;
    private final List<Xtream.Category> cats = new ArrayList<>();   // incl. Favourites + All
    private final Map<String, List<Xtream.Channel>> byCat = new LinkedHashMap<>();
    private final List<Xtream.Channel> all = new ArrayList<>();
    private List<Xtream.Channel> curList = new ArrayList<>();
    private int catIdx;
    private Xtream.Channel current;
    private boolean triedAlt;
    private String testUrl;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        prefs = new Prefs(this);
        favSet = prefs.favs();
        sets = BezelSet.loadAll(this);
        set = sets.containsKey(prefs.bezelSet()) ? sets.get(prefs.bezelSet()) : sets.get("tvwide");
        testUrl = getIntent().getStringExtra("url");
        resume = new Vod.Resume(this);
        if (isView(getIntent())) pendingView = getIntent();
        buildUi();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (isView(i)) {
            setIntent(i);
            pendingView = i;
            if (player != null) handleView();
            return;
        }
        String u = i.getStringExtra("url");
        if (u != null) {
            testUrl = u;
            playUrl(u);
        }
    }

    private static boolean isView(Intent i) {
        return i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        standby = false;
        glView.onResume();
        createPlayer();
        if (pendingView != null) {
            handleView();
        } else if (fromExternal && vod != null) {
            playVod(vod, lastPos, false);
        } else if (fromExternal) {
            // browsing MY VIDEOS after an external file: nothing to restart
        } else if (testUrl != null) {
            playUrl(testUrl);
        } else if (!prefs.hasLogin()) {
            if (loadedStamp != prefs.loginStamp()) {
                clearIptv();
                loadedStamp = prefs.loginStamp();
            }
            if (!prefs.skippedLogin()) {
                startActivity(new Intent(this, SetupActivity.class));
            } else if (vod != null) {
                playVod(vod, lastPos, false);
            } else if (lib.getVisibility() != View.VISIBLE) {
                showWelcome();
            }
        } else if (loadedStamp != prefs.loginStamp()) {
            vod = null;
            lib.close();
            hideTransport();
            loadChannels();
        } else if (vod != null) {
            playVod(vod, lastPos, false);
        } else if (lib.getVisibility() == View.VISIBLE && !liveOn) {
            // came back to the browser after a film: stay quiet until something is picked
        } else if (current != null) {
            play(current);
        }
    }

    @Override
    protected void onStop() {
        saveVodPos();
        ui.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        glView.onPause();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        epg.shutdown();
        if (teletext != null) teletext.shutdown();
        if (lib != null) lib.shutdown();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    private void clearIptv() {
        xt = null;
        epg.setXtream(null);
        cats.clear();
        byCat.clear();
        all.clear();
        curList = new ArrayList<>();
        current = null;
        liveOn = false;
        loadFailed = false;
        lib.setSource(libKey());
    }

    private void showWelcome() {
        renderer.setHasVideo(false);
        renderer.setStatic(0.45f);
        osd("RETRO TV", "TAP FOR MENU · VIDEOS · TELETEXT · SETTINGS", true);
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        renderer = new BezelRenderer(this, glView, this, set);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(glView, new FrameLayout.LayoutParams(-1, -1));

        holeBox = new FrameLayout(this);
        osdCh = osdText(Gravity.TOP | Gravity.START);
        osdInfo = osdText(Gravity.BOTTOM | Gravity.START);
        root.addView(holeBox, new FrameLayout.LayoutParams(0, 0));

        buildPanel();

        guide = new GuideView(this, this, epg);
        guide.setVisibility(View.GONE);
        epg.setListener(id -> {
            guide.onEpg(id);
            if (teletext != null) teletext.onEpg();
        });
        root.addView(guide, new FrameLayout.LayoutParams(-1, -1));

        buildTransport();
        teletext = new Teletext(prefs, this);
        ttView = new TeletextView(this, teletext, this);
        ttView.setVisibility(View.GONE);
        root.addView(ttView, new FrameLayout.LayoutParams(0, 0));
        ttKeys = ttView.buildKeypad(this);
        root.addView(ttKeys, new FrameLayout.LayoutParams(dp(300), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        lib = new LibraryView(this, this);
        lib.setVisibility(View.GONE);
        root.addView(lib, new FrameLayout.LayoutParams(-1, -1));
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> layoutHole());

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (vod != null) {
                    if (vodFailed) playVod(vod, lastPos, false);
                    else toggleTransport();
                    return true;
                }
                showPanel();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (vod != null) {                   // left third back 10 s, right third forward, middle pause
                    float x = e.getX() / Math.max(1, glView.getWidth());
                    if (x < 0.33f) seekBy(-10_000);
                    else if (x > 0.67f) seekBy(10_000);
                    else playPause();
                    return true;
                }
                if (cats.isEmpty() && testUrl == null) {
                    startActivity(new Intent(TvActivity.this, SetupActivity.class));
                    return true;
                }
                showOsd(true);
                return true;
            }

            @Override public void onLongPress(MotionEvent e) { toggleBezel(); }

            @Override public boolean onFling(MotionEvent a, MotionEvent e, float vx, float vy) {
                if (vod != null) {                   // films: swipe sideways = skip 1 minute
                    if (Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 600) {
                        seekBy(vx > 0 ? 60_000 : -60_000);
                        return true;
                    }
                    return false;
                }
                if (Math.abs(vy) > Math.abs(vx) && Math.abs(vy) > 600) {
                    zap(vy < 0 ? 1 : -1);   // swipe up = channel up
                    return true;
                }
                if (Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 600) {
                    openGuide();            // swipe sideways = TV guide
                    return true;
                }
                return false;
            }
        });
        glView.setOnTouchListener((v, e) -> {
            if (panel.getVisibility() == View.VISIBLE) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) hidePanel();
                return true;
            }
            if (wheelTouch(e)) return true;
            return gestures.onTouchEvent(e);
        });

        setContentView(root);
    }

    private TextView osdText(int gravity) {
        TextView t = new TextView(this);
        t.setTextColor(GREEN);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setShadowLayer(6, 0, 0, Color.argb(200, 0, 80, 0));
        t.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, gravity);
        holeBox.addView(t, lp);
        return t;
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.argb(235, 12, 12, 12));
        panel.setVisibility(View.GONE);
        int pad = dp(10);
        panel.setPadding(pad, pad, pad, pad);

        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER_VERTICAL);
        modes.setBackgroundColor(Color.rgb(0, 0, 170));
        TextView tv = headButton("TV", null);
        tv.setTextColor(Color.BLACK);
        tv.setBackgroundColor(Color.rgb(255, 230, 60));
        TextView films = headButton("FILMS", v -> openLibrary(LibraryView.FILMS));
        TextView shows = headButton("SHOWS", v -> openLibrary(LibraryView.SHOWS));
        TextView find = headButton("FIND", v -> openLibrary(LibraryView.SEARCH));
        TextView mine = headButton("VIDEOS", v -> openLibrary(LibraryView.VIDEOS));
        TextView gear = headButton("⚙", v -> {
            hidePanel();
            startActivity(new Intent(this, SetupActivity.class));
        });
        TextView reload = headButton("↻", v -> {
            hidePanel();
            if (!prefs.hasLogin()) {
                startActivity(new Intent(this, SetupActivity.class));
                return;
            }
            Toast.makeText(this, "Refreshing channel list…", Toast.LENGTH_SHORT).show();
            loadChannels();
        });
        for (TextView t : new TextView[]{tv, films, shows, mine, find, reload, gear}) {
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            if (t != tv) t.setTextColor(Color.WHITE);
        }
        modes.addView(tv, new LinearLayout.LayoutParams(0, dp(44), 0.7f));
        modes.addView(films, new LinearLayout.LayoutParams(0, dp(44), 1.2f));
        modes.addView(shows, new LinearLayout.LayoutParams(0, dp(44), 1.2f));
        modes.addView(mine, new LinearLayout.LayoutParams(0, dp(44), 1.35f));
        modes.addView(find, new LinearLayout.LayoutParams(0, dp(44), 1.0f));
        modes.addView(gear, new LinearLayout.LayoutParams(0, dp(44), 0.6f));
        panel.addView(modes);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        catPrev = headButton("◄", v -> switchCat(-1));
        catTitle = headButton("", null);
        catTitle.setGravity(Gravity.CENTER);
        catNext = headButton("►", v -> switchCat(1));
        head.addView(catPrev, new LinearLayout.LayoutParams(dp(48), dp(48)));
        head.addView(catTitle, new LinearLayout.LayoutParams(0, dp(48), 1));
        head.addView(catNext, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView guideBtn = headButton("▦", v -> openGuide());
        head.addView(guideBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView textBtn = headButton("TXT", v -> openTeletext());
        textBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        textBtn.setTextColor(Color.rgb(255, 230, 60));
        head.addView(textBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        head.addView(reload, new LinearLayout.LayoutParams(dp(48), dp(48)));
        reload.setTextColor(GREEN);
        reload.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        panel.addView(head);

        list = new ListView(this);
        list.setDividerHeight(0);
        list.setSelector(android.R.drawable.list_selector_background);
        adapter = new ChannelAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (cats.isEmpty() && !prefs.hasLogin()) {
                handleNoIptvMenu(pos);
                return;
            }
            if (pos >= 0 && pos < curList.size()) {
                play(curList.get(pos));
                hidePanel();
            }
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (cats.isEmpty() || pos < 0 || pos >= curList.size()) return false;
            toggleFav(curList.get(pos));
            return true;
        });
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        root.addView(panel, new FrameLayout.LayoutParams(-1, -1, Gravity.START));
    }

    private void handleNoIptvMenu(int pos) {
        switch (pos) {
            case 0: openLibrary(LibraryView.VIDEOS); break;
            case 1: openTeletext(); break;
            case 2: hidePanel(); startActivity(new Intent(this, SetupActivity.class)); break;
            case 3: hidePanel(); toggleBezel(); break;
        }
    }

    private TextView headButton(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(GREEN);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        if (l != null) {
            t.setOnClickListener(l);
            t.setFocusable(true);
            t.setBackgroundResource(android.R.drawable.list_selector_background);
        }
        return t;
    }

    /** Positions the OSD box over the bezel's picture hole and sizes the channel panel. */
    private void layoutHole() {
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        RectF r = set.layoutFor(w, h).hole(w, h);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) holeBox.getLayoutParams();
        lp.width = (int) r.width();
        lp.height = (int) r.height();
        holeBox.setLayoutParams(lp);
        holeBox.setX(r.left);
        holeBox.setY(r.top);
        FrameLayout.LayoutParams tl = (FrameLayout.LayoutParams) ttView.getLayoutParams();
        tl.width = (int) r.width();
        tl.height = (int) r.height();
        ttView.setLayoutParams(tl);
        ttView.setX(r.left);
        ttView.setY(r.top);
        if (Build.VERSION.SDK_INT >= 29) {        // keep Android's edge back-swipe off the wheels
            List<android.graphics.Rect> ex = new ArrayList<>();
            BezelSet.Layout L = set.layoutFor(w, h);
            for (String name : L.controls.keySet()) {
                if (!isWheel(name)) continue;
                RectF c = L.control(name, w, h);
                if (c != null) ex.add(new android.graphics.Rect((int) c.left, (int) c.top, (int) c.right, (int) c.bottom));
            }
            glView.setSystemGestureExclusionRects(ex);
        }
        FrameLayout.LayoutParams kl = (FrameLayout.LayoutParams) ttKeys.getLayoutParams();
        kl.bottomMargin = h > w ? dp(24) : dp(8);
        ttKeys.setLayoutParams(kl);
        int m = (int) (r.height() * 0.06f);
        holeBox.setPadding(m * 3 / 2, m, m * 3 / 2, m);
        osdCh.setTextSize(TypedValue.COMPLEX_UNIT_PX, r.height() * 0.085f);
        osdInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(r.height() * 0.045f, dp(13)));
        float small = Math.max(r.height() * 0.042f, dp(12));
        tTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(r.height() * 0.06f, dp(15)));
        for (TextView t : new TextView[]{tSub, tTime, tDur}) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, small);
        for (TextView t : new TextView[]{tPlay, tAudio, tSubs}) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, small * 1.25f);
        subs.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(r.height() * 0.05f, dp(13)));

        ViewGroup.LayoutParams pl = panel.getLayoutParams();
        pl.width = (int) Math.min(w * (w > h ? 0.5f : 0.85f), dp(600));
        panel.setLayoutParams(pl);
    }

    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private final Runnable hideOsd = () -> {
        osdCh.setVisibility(View.GONE);
        osdInfo.setVisibility(View.GONE);
    };

    private void osd(String ch, String info, boolean sticky) {
        osdCh.setText(ch);
        osdCh.setVisibility(ch == null ? View.GONE : View.VISIBLE);
        osdInfo.setText(info);
        osdInfo.setVisibility(info == null || info.isEmpty() ? View.GONE : View.VISIBLE);
        ui.removeCallbacks(hideOsd);
        if (!sticky) ui.postDelayed(hideOsd, 5000);
    }

    private void showOsd(boolean refreshEpg) {
        if (current == null) return;
        osd(chLabel(current), current.name + (tech.isEmpty() ? "" : "\n" + tech), false);
        if (refreshEpg) fetchEpg(current);
    }

    private static String chLabel(Xtream.Channel c) {
        String n = c.num;
        try { n = String.format("%03d", Integer.parseInt(c.num)); } catch (Exception ignored) { }
        return "CH " + n;
    }

    private void showPanel() {
        if (ttView.isOpen()) ttView.close();
        if (cats.isEmpty()) {
            if (testUrl != null) return;
            if (loadFailed) {
                loadChannels();
                return;
            }
            catPrev.setVisibility(View.INVISIBLE);
            catNext.setVisibility(View.INVISIBLE);
            catTitle.setText(prefs.hasLogin() ? "LOADING CHANNELS…" : "RETRO TV MENU");
        } else {
            catPrev.setVisibility(View.VISIBLE);
            catNext.setVisibility(View.VISIBLE);
        }
        panel.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged();
        int pos = current == null ? -1 : curList.indexOf(current);
        if (pos >= 0) list.setSelection(Math.max(0, pos - 3));
        list.requestFocus();
        if (pos >= 0) list.setSelectionFromTop(pos, dp(120));
    }

    private void hidePanel() {
        panel.setVisibility(View.GONE);
        immersive();
    }

    private void toggleBezel() {
        set = set.name.equals("tvwide") ? sets.get("tvcrt") : sets.get("tvwide");
        prefs.bezelSet(set.name);
        bezelAuto = false;
        renderer.setBezelSet(set);
        layoutHole();
        Toast.makeText(this, set.name.equals("tvcrt") ? "Classic CRT" : "Widescreen TV", Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- channels

    private boolean loadFailed;

    private void loadChannels() {
        xt = new Xtream(prefs.server(), prefs.user(), prefs.pass());
        epg.setXtream(xt);
        loadedStamp = prefs.loginStamp();
        loadFailed = false;
        final Xtream x = xt;
        final String key = prefs.server() + "|" + prefs.user();
        lib.setSource(key);
        renderer.setStatic(0.9f);
        osd(null, "LOADING CHANNELS…", true);
        io.execute(() -> {
            // 1. saved list first, so the TV comes on even if the server is slow
            boolean shown = false;
            try {
                String[] c = readCache(key);
                if (c != null) {
                    List<Xtream.Category> cc = Xtream.parseCategories(c[0]);
                    List<Xtream.Channel> ch = Xtream.parseChannels(c[1]);
                    ui.post(() -> onChannels(cc, ch, false));
                    shown = true;
                }
            } catch (Exception ignored) {
            }
            // 2. fresh list from the server
            try {
                String rc = x.rawCategories();
                String rs = x.rawChannels();
                List<Xtream.Category> cc = Xtream.parseCategories(rc);
                List<Xtream.Channel> ch = Xtream.parseChannels(rs);
                writeCache(key, rc, rs);
                final boolean refresh = shown;
                ui.post(() -> onChannels(cc, ch, refresh));
            } catch (Exception e) {
                final boolean haveList = shown;
                ui.post(() -> {
                    if (haveList) {
                        Toast.makeText(this, "Server not responding – using saved channel list", Toast.LENGTH_LONG).show();
                    } else {
                        loadedStamp = -1;
                        loadFailed = true;
                        osd(null, "NO CONNECTION\n" + e.getMessage()
                                + "\n\nTAP TO RETRY · DOUBLE-TAP FOR SETTINGS", true);
                    }
                });
            }
        });
    }

    private String[] readCache(String key) throws Exception {
        java.io.File d = getFilesDir();
        java.io.File k = new java.io.File(d, "live_key.txt");
        if (!k.exists() || !readFile(k).equals(key)) return null;
        return new String[]{readFile(new java.io.File(d, "live_cats.json")),
                readFile(new java.io.File(d, "live_streams.json"))};
    }

    private void writeCache(String key, String cats, String streams) {
        try {
            java.io.File d = getFilesDir();
            writeFile(new java.io.File(d, "live_cats.json"), cats);
            writeFile(new java.io.File(d, "live_streams.json"), streams);
            writeFile(new java.io.File(d, "live_key.txt"), key);
        } catch (Exception ignored) {
        }
    }

    private static String readFile(java.io.File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeFile(java.io.File f, String s) throws Exception {
        java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
        java.nio.file.Files.write(tmp.toPath(), s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename");
    }

    /** refresh = a newer list arrived while already watching: update lists, don't retune. */
    private void onChannels(List<Xtream.Category> provCats, List<Xtream.Channel> chans, boolean refresh) {
        all.clear();
        all.addAll(chans);
        byCat.clear();
        for (Xtream.Channel c : chans) {
            List<Xtream.Channel> l = byCat.get(c.catId);
            if (l == null) byCat.put(c.catId, l = new ArrayList<>());
            l.add(c);
        }
        cats.clear();
        cats.add(new Xtream.Category(FAV, "★ Favourites"));
        cats.add(new Xtream.Category(ALL, "All channels"));
        for (Xtream.Category c : provCats) if (byCat.containsKey(c.id)) cats.add(c);

        catIdx = 1;
        for (int i = 0; i < cats.size(); i++) if (cats.get(i).id.equals(prefs.lastCat())) catIdx = i;
        selectCat(catIdx);

        if (refresh && current != null) {
            for (Xtream.Channel c : all) if (c.id.equals(current.id)) current = c;
            adapter.notifyDataSetChanged();
            return;
        }
        Xtream.Channel start = null;
        for (Xtream.Channel c : all) if (c.id.equals(prefs.lastStream())) start = c;
        if (start == null && !curList.isEmpty()) start = curList.get(0);
        if (start == null && !all.isEmpty()) start = all.get(0);
        if (start != null) play(start);
        else osd(null, "NO CHANNELS ON THIS ACCOUNT", true);
    }

    private void selectCat(int idx) {
        catIdx = (idx + cats.size()) % cats.size();
        Xtream.Category c = cats.get(catIdx);
        if (c.id.equals(ALL)) {
            curList = all;
        } else if (c.id.equals(FAV)) {
            curList = new ArrayList<>();
            for (Xtream.Channel ch : all) if (favSet.contains(ch.id)) curList.add(ch);
        } else {
            curList = byCat.get(c.id);
        }
        catTitle.setText(c.name + "  (" + curList.size() + ")");
        prefs.lastCat(c.id);
        adapter.notifyDataSetChanged();
    }

    private void switchCat(int d) {
        if (cats.isEmpty()) return;
        selectCat(catIdx + d);
        list.setSelection(0);
    }

    private void toggleFav(Xtream.Channel c) {
        boolean added = favSet.add(c.id);
        if (!added) favSet.remove(c.id);
        prefs.favs(favSet);
        Toast.makeText(this, (added ? "Added to" : "Removed from") + " favourites: " + c.name, Toast.LENGTH_SHORT).show();
        if (cats.get(catIdx).id.equals(FAV)) selectCat(catIdx);
        adapter.notifyDataSetChanged();
    }

    /** Channel up/down within the current list (falls back to all channels). */
    private void zap(int d) {
        List<Xtream.Channel> l = curList.contains(current) ? curList : all;
        if (l.isEmpty() || current == null) return;
        int i = (l.indexOf(current) + d + l.size()) % l.size();
        play(l.get(i));
    }

    // ---------------------------------------------------------------- playback

    /** Optional software H.264 decoder for interlaced live broadcasts (controlled by Settings). */
    private static volatile boolean preferSoftwareAvc = false;

    private static final androidx.media3.exoplayer.mediacodec.MediaCodecSelector SOFTWARE_AVC_FIRST =
            (mime, secure, tunneling) -> {
                List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> l = new ArrayList<>(
                        androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                                .getDecoderInfos(mime, secure, tunneling));
                if (MimeTypes.VIDEO_H264.equals(mime) && preferSoftwareAvc) {
                    l.sort((a, b) -> Boolean.compare(!a.softwareOnly, !b.softwareOnly));
                }
                return l;
            };

    private void createPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Xtream.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(20000);
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(SOFTWARE_AVC_FIRST);
        player = new ExoPlayer.Builder(this, rf)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(
                        new androidx.media3.datasource.DefaultDataSource.Factory(this, http)))
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .build();
        player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                renderer.setHasVideo(true);
                renderer.setStatic(0f);
            }

            @Override public void onVideoSizeChanged(VideoSize v) {
                if (v.width > 0 && v.height > 0) {
                    float ar = v.width * v.pixelWidthHeightRatio / v.height;
                    renderer.setSourceAspect(ar);
                    // local videos: 4:3 material gets the classic CRT, widescreen gets the wide TV
                    if (vod != null && vod.isLocal() && bezelAuto) useBezel(ar < 1.55f ? "tvcrt" : "tvwide");
                }
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) renderer.setStatic(0.35f);
                else if (state == Player.STATE_READY) {
                    renderer.setStatic(0f);
                    vodFailed = false;
                } else if (state == Player.STATE_ENDED && vod != null) onVodEnded();
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                boolean paused = player != null && !player.getPlayWhenReady();
                tPlay.setText(paused ? "►" : "❚❚");
                if (vod != null && paused) showTransport(true);
                else if (vod != null && playing) scheduleHideTransport();
            }

            @Override public void onCues(CueGroup cg) {
                StringBuilder sb = new StringBuilder();
                for (Cue c : cg.cues) {
                    if (c.text == null) continue;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(c.text);
                }
                subs.setText(sb);
                subs.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
            }

            @Override public void onPlayerError(PlaybackException e) {
                diag("error " + e.getErrorCodeName());
                onStreamError(e);
            }

            @Override public void onTracksChanged(Tracks tracks) {
                tech = describe(tracks);
                diag("tracks: " + tech);
            }
        });
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(EventTime t, String name, long a, long b) {
                diag("video decoder " + name);
            }
            @Override public void onAudioDecoderInitialized(EventTime t, String name, long a, long b) {
                diag("audio decoder " + name);
            }
            @Override public void onDroppedVideoFrames(EventTime t, int count, long ms) {
                diag("dropped " + count + " frames");
            }
            @Override public void onAudioSinkError(EventTime t, Exception e) {
                diag("audio sink error " + e.getClass().getSimpleName());
            }
            @Override public void onVideoCodecError(EventTime t, Exception e) {
                diag("video codec error " + e.getClass().getSimpleName());
            }
        });
        if (videoSurface != null) player.setVideoSurface(videoSurface);
    }

    @Override
    public void onVideoSurface(Surface s) {
        ui.post(() -> {
            videoSurface = s;
            if (player != null) player.setVideoSurface(s);
        });
    }

    private void play(Xtream.Channel c) {
        if (vod != null) endVod();
        boolean wantSoft = prefs.softAvcLive();
        if (preferSoftwareAvc != wantSoft && player != null) player.stop();
        preferSoftwareAvc = wantSoft;
        liveOn = true;
        current = c;
        triedAlt = false;
        tech = "";
        prefs.lastStream(c.id);
        startStream(xt.streamUrl(c, prefs.hls()));
        showOsd(true);
    }

    private void playUrl(String url) {
        current = new Xtream.Channel("test", "0", "TEST STREAM", "");
        startStream(url);
        osd("CH 000", "TEST STREAM", false);
    }

    private void startStream(String url) {
        if (player == null) return;
        ui.removeCallbacks(retry);
        renderer.setStatic(0.9f);
        MediaItem.Builder mi = new MediaItem.Builder().setUri(url);
        if (url.contains(".m3u8")) mi.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(mi.build());
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private final Runnable retry = () -> {
        if (current == null || vod != null || !liveOn) return;
        if (testUrl != null && xt == null) playUrl(testUrl);
        else if (xt != null) play(current);
    };

    private void onStreamError(PlaybackException e) {
        if (vod != null) {
            if (!triedAlt) {                            // one quiet retry from where it stopped
                triedAlt = true;
                final Vod.Playable p = vod;
                final long at = Math.max(lastPos, resume.pos(p.key()));
                ui.postDelayed(() -> { if (vod == p) playVod(p, at, true); }, 2000);
                return;
            }
            vodFailed = true;
            renderer.setStatic(0.9f);
            osd(null, "PLAYBACK ERROR  " + e.getErrorCodeName() + "\nTAP TO TRY AGAIN · BACK FOR THE LIST", true);
            return;
        }
        if (!liveOn) return;
        if (!triedAlt && xt != null && current != null && testUrl == null) {
            triedAlt = true;                            // try the other container once
            startStream(xt.streamUrl(current, !prefs.hls()));
            return;
        }
        renderer.setStatic(0.9f);
        osd(current == null ? null : chLabel(current), "NO SIGNAL\n" + e.getErrorCodeName(), true);
        ui.postDelayed(retry, 10000);
    }

    private Runnable epgJob;

    /** Waits a moment so channel-hopping doesn't fire a request per channel. */
    private void fetchEpg(Xtream.Channel c) {
        if (xt == null) return;
        if (epgJob != null) ui.removeCallbacks(epgJob);
        final Xtream x = xt;
        epgJob = () -> io.execute(() -> {
            List<String> l = x.nowNext(c);
            if (l.isEmpty()) return;
            ui.post(() -> {
                if (current == null || !c.id.equals(current.id)) return;
                StringBuilder sb = new StringBuilder(c.name);
                for (String s : l) sb.append('\n').append(s);
                osd(chLabel(c), sb.toString(), false);
            });
        });
        ui.postDelayed(epgJob, 1500);
    }

    // ---------------------------------------------------------------- keys / controller

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (ttView.isOpen()) {
            return ttView.onKey(code) || super.onKeyDown(code, e);
        }
        if (code == KeyEvent.KEYCODE_TV_TELETEXT || code == KeyEvent.KEYCODE_T
                || (code == KeyEvent.KEYCODE_BUTTON_START && vod == null)) {
            openTeletext();
            return true;
        }
        if (guide.getVisibility() == View.VISIBLE) {
            return guide.onKey(code) || super.onKeyDown(code, e);
        }
        if (lib.getVisibility() == View.VISIBLE) {
            return lib.onKey(code) || super.onKeyDown(code, e);
        }
        if (vod != null && panel.getVisibility() != View.VISIBLE) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_REWIND:
                    seekBy(-10_000); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    seekBy(10_000); return true;
                case KeyEvent.KEYCODE_BUTTON_L1: seekBy(-60_000); return true;
                case KeyEvent.KEYCODE_BUTTON_R1: seekBy(60_000); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_SPACE:
                    if (vodFailed) playVod(vod, lastPos, false); else playPause();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                    showTransport(false); return true;
                case KeyEvent.KEYCODE_BUTTON_Y: cycleTrack(C.TRACK_TYPE_AUDIO); return true;
                case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_CAPTIONS:
                    cycleTrack(C.TRACK_TYPE_TEXT); return true;
                case KeyEvent.KEYCODE_BUTTON_X: toggleBezel(); return true;
            }
            return super.onKeyDown(code, e);
        }
        if (code == KeyEvent.KEYCODE_SEARCH) {
            openLibrary(LibraryView.SEARCH);
            return true;
        }
        if (code == KeyEvent.KEYCODE_GUIDE || code == KeyEvent.KEYCODE_BUTTON_SELECT) {
            openGuide();
            return true;
        }
        boolean open = panel.getVisibility() == View.VISIBLE;
        if (open) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_BUTTON_L1:
                    switchCat(-1); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_BUTTON_R1:
                    switchCat(1); return true;
                case KeyEvent.KEYCODE_BUTTON_Y:
                    int p = list.getSelectedItemPosition();
                    if (p >= 0 && p < curList.size()) toggleFav(curList.get(p));
                    return true;
            }
            return super.onKeyDown(code, e);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_BUTTON_R1:
                zap(1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_BUTTON_L1:
                zap(-1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_BUTTON_A:
                showPanel(); return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                toggleBezel(); return true;
            case KeyEvent.KEYCODE_INFO: case KeyEvent.KEYCODE_BUTTON_Y:
                showOsd(true); return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (ttView.isOpen()) ttView.back();
        else if (guide.getVisibility() == View.VISIBLE) guideClose();
        else if (lib.getVisibility() == View.VISIBLE) lib.back();
        else if (panel.getVisibility() == View.VISIBLE) hidePanel();
        else if (vod != null) exitVod();
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- teletext

    private void openTeletext() {
        hidePanel();
        if (guide.getVisibility() == View.VISIBLE) guideClose();
        hideOsd.run();
        ttView.open();
    }

    @Override
    public void ttShow(android.graphics.Bitmap frame, boolean mix) { renderer.setText(frame, mix); }

    @Override
    public void ttWatch(Xtream.Channel c) {
        ttClose();
        if (vod != null) exitVod();
        if (current == null || !c.id.equals(current.id)) play(c);
        else showOsd(false);
    }

    @Override
    public void ttClose() {
        ttView.close();
        if (!prefs.hasLogin() && vod == null && !liveOn
                && panel.getVisibility() != View.VISIBLE && lib.getVisibility() != View.VISIBLE) {
            showWelcome();
        }
        immersive();
    }

    @Override
    public List<Xtream.Channel> ttChannels() {
        List<Xtream.Channel> out = new ArrayList<>();
        for (Xtream.Channel ch : all) if (favSet.contains(ch.id)) out.add(ch);
        if (out.isEmpty() && curList != null) out.addAll(curList.subList(0, Math.min(15, curList.size())));
        return out;
    }

    @Override public List<Xtream.Programme> ttEpg(String id) { return epg.get(id); }

    @Override public void ttWantEpg(List<Xtream.Channel> chans) { epg.want(chans); }

    // ---------------------------------------------------------------- TV guide

    private void openGuide() {
        if (!prefs.hasLogin()) {
            Toast.makeText(this, "Add an IPTV account in Settings (⚙) for the TV Guide", Toast.LENGTH_SHORT).show();
            return;
        }
        if (cats.isEmpty() || xt == null) {
            Toast.makeText(this, "Guide needs the channel list – still loading", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ttView.isOpen()) ttView.close();
        hidePanel();
        guide.setVisibility(View.VISIBLE);
        guide.open();
    }

    @Override public List<Xtream.Channel> guideChannels() { return curList; }
    @Override public String guideCategory() { return cats.isEmpty() ? "" : cats.get(catIdx).name; }
    @Override public Xtream.Channel guideCurrent() { return current; }

    @Override
    public void guideWatch(Xtream.Channel c) {
        guideClose();
        if (current == null || !c.id.equals(current.id)) play(c);
        else showOsd(false);
    }

    @Override
    public void guideCategory(int delta) {
        switchCat(delta);
        guide.resetRows();
    }

    @Override
    public void guideClose() {
        guide.setVisibility(View.GONE);
        immersive();
    }

    // ---------------------------------------------------------------- drawn controls (pocket TV art)

    private String wheel;                 // "tune" / "volume" while a wheel or CH / VOL button is held
    private String button;                // any drawn button being held
    private RectF wheelRect;
    private float wheelY, wheelAcc;
    private int wheelSteps;
    private Xtream.Channel pendingCh;     // channel shown while spinning the tuning wheel
    private boolean standby;              // POWER button: screen dark, playback stopped

    private static boolean isWheel(String n) { return n.equals("tune") || n.equals("volume"); }

    /** Wheels: drag to step, tap upper / lower half for one step. Buttons: press (CH / VOL repeat while held). */
    private boolean wheelTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                BezelSet.Layout L = set.layoutFor(glView.getWidth(), glView.getHeight());
                for (String name : L.controls.keySet()) {
                    RectF r = L.control(name, glView.getWidth(), glView.getHeight());
                    if (r == null || !r.contains(x, y)) continue;
                    if (standby && !name.equals("power")) return true;   // only POWER works in standby
                    wheelRect = r;
                    wheelSteps = 0;
                    if (isWheel(name)) {
                        wheel = name;
                        wheelY = y;
                        wheelAcc = 0;
                    } else {
                        button = name;
                        pressButton(name);
                    }
                    return true;
                }
                return standby;                   // swallow other taps while the set is off
            }
            case MotionEvent.ACTION_MOVE:
                if (button != null) return true;
                if (wheel == null) return standby;
                wheelAcc += wheelY - y;          // finger up = turn up
                wheelY = y;
                float step = dp(26);
                while (wheelAcc >= step) { wheelStep(1); wheelAcc -= step; }
                while (wheelAcc <= -step) { wheelStep(-1); wheelAcc += step; }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (button != null) {
                    String b = button;
                    button = null;
                    ui.removeCallbacks(repeat);
                    wheelEnd();
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && wheelRect != null && wheelRect.contains(x, y)) {
                        releaseButton(b);
                    }
                    return true;
                }
                if (wheel == null) return standby;
                if (e.getActionMasked() == MotionEvent.ACTION_UP && wheelSteps == 0 && wheelRect != null)
                    wheelStep(y < wheelRect.centerY() ? 1 : -1);
                wheelEnd();
                return true;
        }
        return wheel != null || button != null;
    }

    private int repeatDir;
    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (wheel == null) return;
            wheelStep(repeatDir);
            ui.postDelayed(this, 170);
        }
    };

    private void pressButton(String name) {
        glView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        switch (name) {
            case "ch_up": case "ch_down": case "vol_up": case "vol_down":
                wheel = name.startsWith("ch") ? "tune" : "volume";
                repeatDir = name.endsWith("up") ? 1 : -1;
                wheelStep(repeatDir);
                ui.postDelayed(repeat, 450);      // hold to keep going
                break;
        }
    }

    private void releaseButton(String name) {
        switch (name) {
            case "tvav":
                if (ttView.isOpen()) ttClose(); else openTeletext();
                break;
            case "menu":
                if (ttView.isOpen()) ttClose();
                showPanel();
                break;
            case "power":
                togglePower();
                break;
        }
    }

    private void wheelStep(int dir) {
        wheelSteps++;
        glView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        if ("volume".equals(wheel)) {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    dir > 0 ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER, 0);
            int v = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int max = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
            int bars = Math.round(v * 16f / max);
            StringBuilder b = new StringBuilder("VOL ");
            for (int i = 0; i < 16; i++) b.append(i < bars ? '█' : '·');
            osd(b.toString(), null, false);
            return;
        }
        // tuning
        if (ttView.isOpen()) { ttView.onKey(dir > 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN); return; }
        if (vod != null) { seekBy(dir * 10_000L); return; }
        List<Xtream.Channel> l = curList.contains(current) ? curList : all;
        if (l.isEmpty() || current == null) return;
        Xtream.Channel from = pendingCh != null ? pendingCh : current;
        pendingCh = l.get((l.indexOf(from) + dir + l.size()) % l.size());
        osd(chLabel(pendingCh), pendingCh.name, false);
    }

    /** Letting go of the tuning wheel / CH button tunes to the channel it stopped on. */
    private void wheelEnd() {
        wheel = null;
        Xtream.Channel c = pendingCh;
        pendingCh = null;
        if (c != null && (current == null || !c.id.equals(current.id))) play(c);
    }

    /** Standby: picture off and playback stopped; POWER again resumes. */
    private void togglePower() {
        if (!standby) {
            standby = true;
            if (ttView.isOpen()) ttClose();
            hidePanel();
            hideOsd.run();
            if (vod != null) {
                hideTransport();
                if (player != null) player.setPlayWhenReady(false);
                saveVodPos();
            } else if (player != null) {
                player.stop();
            }
            renderer.setHasVideo(false);
            renderer.setStatic(0f);
        } else {
            standby = false;
            if (vod != null) {
                if (player != null) player.setPlayWhenReady(true);
            } else if (current != null) {
                play(current);
            } else if (!prefs.hasLogin()) {
                showWelcome();
            }
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- films, shows & local videos: browser hooks

    private long lastPos;

    private void openLibrary(int tab) {
        if (xt == null && tab != LibraryView.VIDEOS && tab != LibraryView.SEARCH) {
            Toast.makeText(this, prefs.hasLogin() ? "Still connecting – try again in a moment"
                    : "Add an IPTV account in Settings (⚙) for Films & Shows", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ttView.isOpen()) ttView.close();
        hidePanel();
        lib.setSource(libKey());
        lib.open(tab);
    }

    @Override public Xtream libXtream() { return xt; }
    @Override public Prefs libPrefs() { return prefs; }
    @Override public String libKey() { return prefs.server() + "|" + prefs.user(); }
    @Override public List<Xtream.Channel> libChannels() { return all; }
    @Override public Vod.Resume libResume() { return resume; }

    @Override
    public void libWatchLive(Xtream.Channel c) {
        lib.close();
        immersive();
        play(c);
    }

    @Override
    public void libPlay(Vod.Playable p, long startMs) {
        lib.close();
        immersive();
        playVod(p, startMs, false);
    }

    /** TV tab: back to live TV (or standby menu if no IPTV login). */
    @Override
    public void libTv() {
        lib.close();
        immersive();
        goLive();
    }

    /** Back out of the browser: to the film if one is playing behind it, else live TV (or launcher). */
    @Override
    public void libClose() {
        lib.close();
        immersive();
        if (vod != null) return;
        if (fromExternal) {
            finish();
            return;
        }
        if (!liveOn) goLive();
    }

    private void goLive() {
        if (vod != null) endVod();
        fromExternal = false;
        if (!prefs.hasLogin()) {
            showWelcome();
        } else if (xt == null || loadedStamp != prefs.loginStamp()) {
            if (player != null) player.stop();
            loadChannels();
        } else if (current != null) {
            play(current);
        }
    }

    @Override
    public void libAskMedia() {
        requestPermissions(new String[]{Local.mediaPermission()}, REQ_MEDIA);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void libAddFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            Toast.makeText(this, "No folder picker available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void libRemoveFolder(String treeUri) {
        try {
            getContentResolver().releasePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        prefs.removeTreeUri(treeUri);
        lib.forgetLocal();
        lib.refresh();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_MEDIA) {
            boolean granted = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted && !shouldShowRequestPermissionRationale(Local.mediaPermission())) {
                Toast.makeText(this, "Enable video access in Android Settings, or use + Add a folder", Toast.LENGTH_LONG).show();
            }
            lib.forgetLocal();
            lib.refresh();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int resultCode, Intent data) {
        super.onActivityResult(req, resultCode, data);
        if (req == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            prefs.addTreeUri(tree.toString());
            lib.forgetLocal();
            lib.refresh();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lib.getVisibility() == View.VISIBLE) {
            lib.forgetLocal();
            lib.refresh();
        }
    }

    // ---------------------------------------------------------------- external video intents

    private Intent pendingView;
    private boolean fromExternal;       // opened by a file manager / launcher: Back returns there
    private boolean bezelAuto = true;

    /** Plays a video file handed over via ACTION_VIEW (content:// or file://). */
    private void handleView() {
        Intent i = pendingView;
        pendingView = null;
        if (i == null || player == null) return;
        Uri u = i.getData();
        String name = null;
        long size = -1;
        try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) {
        }
        if (name == null) name = u.getLastPathSegment() == null ? "Video" : u.getLastPathSegment();
        diag("open " + u.getScheme() + " " + name + " " + size);

        if (ttView.isOpen()) ttView.close();
        guide.setVisibility(View.GONE);
        hidePanel();
        lib.close();
        fromExternal = true;
        liveOn = false;
        ui.removeCallbacks(retry);

        String bezel = i.getStringExtra("bezel");
        bezelAuto = bezel == null;
        if (bezel != null) useBezel(bezel);

        final Vod.Playable p = Vod.Playable.local(u.toString(), Local.title(name), "", Local.key(name, size));
        long at = resume.pos(p.key());
        playVod(p, at, false);
        if (at > 0) osd("RESUMED " + Vod.clock(at), null, false);

        // Link to sibling files in background if this file sits inside MediaStore or a picked folder
        final Set<String> trees = prefs.treeUris();
        io.execute(() -> {
            Vod.Playable linked = Local.withNext(getApplicationContext(), p, trees);
            if (linked != null && linked != p) {
                ui.post(() -> {
                    if (vod == p) {
                        p.next = linked.next;
                        p.fileUri = linked.localUri;
                    }
                });
            }
        });
    }

    /** Switches the bezel for this video only (your saved choice comes back afterwards). */
    private void useBezel(String name) {
        BezelSet b = sets.get(name);
        if (b == null || b == set) return;
        set = b;
        renderer.setBezelSet(set);
        layoutHole();
    }

    private void restoreBezel() {
        bezelAuto = true;
        useBezel(sets.containsKey(prefs.bezelSet()) ? prefs.bezelSet() : "tvwide");
    }

    // ---------------------------------------------------------------- films & shows: playback

    private void playVod(Vod.Playable p, long startMs, boolean again) {
        if (player == null || (xt == null && !p.isLocal())) return;
        if (ttView.isOpen()) ttView.close();
        if (vod != null && vod != p) saveVodPos();
        if (vod != null && vod.isLocal() && !p.isLocal()) restoreBezel();
        boolean wasLive = liveOn;
        liveOn = false;
        vod = p;
        vodFailed = false;
        if (!again) triedAlt = false;
        tech = "";
        lastPos = Math.max(0, startMs);
        ui.removeCallbacks(retry);
        ui.removeCallbacks(nextJob);
        if (epgJob != null) ui.removeCallbacks(epgJob);
        if (preferSoftwareAvc || wasLive) player.stop();
        preferSoftwareAvc = false;
        renderer.setStatic(0.9f);
        player.setMediaItem(new MediaItem.Builder().setUri(p.url(xt)).build(), lastPos);
        player.prepare();
        player.setPlayWhenReady(true);
        subs.setText("");
        subs.setVisibility(View.GONE);
        tTitle.setText(p.title);
        tSub.setText(p.sub);
        tSub.setVisibility(p.sub.isEmpty() ? View.GONE : View.VISIBLE);
        tPlay.setText("❚❚");
        seek.setProgress(0);
        tTime.setText(Vod.clock(lastPos));
        tDur.setText("--:--");
        osd(null, null, false);
        showTransport(false);
        ui.removeCallbacks(tick);
        ui.post(tick);
        diag("vod " + p.key() + " from " + Vod.clock(lastPos));
    }

    private void saveVodPos() {
        if (vod == null || player == null) return;
        if (player.getPlaybackState() != Player.STATE_IDLE) lastPos = player.getCurrentPosition();
        long dur = player.getDuration();
        resume.save(vod, lastPos, dur == C.TIME_UNSET ? 0 : dur);
    }

    /** Leaves film mode (position saved); the caller decides what plays next. */
    private void endVod() {
        saveVodPos();
        if (vod != null && vod.isLocal()) restoreBezel();
        vod = null;
        vodFailed = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(nextJob);
        hideTransport();
        subs.setVisibility(View.GONE);
        if (player != null) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        }
    }

    /** Back during a film: stop it and return to where it was picked in the browser. */
    private void exitVod() {
        boolean wasLocal = vod != null && vod.isLocal();
        endVod();
        if (player != null) player.stop();
        if (fromExternal && lib.getVisibility() != View.VISIBLE && wasLocal) {
            finish();
            return;
        }
        osd(null, null, false);
        renderer.setStatic(0.9f);
        lib.setSource(libKey());
        lib.reopen();
    }

    private Runnable nextJob = () -> { };

    private void onVodEnded() {
        Vod.Playable p = vod;
        resume.finished(p);
        hideTransport();
        if (p.next == null) {
            osd(null, "THE END", false);
            ui.postDelayed(() -> { if (vod == p) exitVod(); }, 2500);
            return;
        }
        Vod.Playable n = p.next;
        osd("NEXT", (n.isLocal() ? n.title : n.sub) + "\nstarting in 5 s · BACK to stop", true);
        nextJob = () -> { if (vod == p) playVod(n, resume.pos(n.key()), false); };
        ui.postDelayed(nextJob, 5000);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (vod == null || player == null) return;
            long pos = player.getCurrentPosition(), dur = player.getDuration();
            if (dur != C.TIME_UNSET && dur > 0) {
                seek.setMax((int) (dur / 1000));
                tDur.setText(Vod.clock(dur));
            }
            if (!seeking) {
                seek.setProgress((int) (pos / 1000));
                tTime.setText(Vod.clock(pos));
            }
            if (player.getPlaybackState() != Player.STATE_IDLE) lastPos = pos;
            if (++ticks % 20 == 0 && player.isPlaying()) saveVodPos();   // every ~10 s
            ui.postDelayed(this, 500);
        }
    };

    private void playPause() {
        if (player == null || vod == null) return;
        boolean play = !player.getPlayWhenReady();
        player.setPlayWhenReady(play);
        tPlay.setText(play ? "❚❚" : "►");
        if (play) showTransport(false);
        else {
            showTransport(true);
            saveVodPos();
        }
    }

    private void seekBy(long d) {
        if (player == null || vod == null) return;
        long dur = player.getDuration();
        long to = Math.max(0, player.getCurrentPosition() + d);
        if (dur != C.TIME_UNSET && dur > 0) to = Math.min(to, dur - 1000);
        player.seekTo(to);
        long s = Math.abs(d) / 1000;
        osd((d > 0 ? "» +" : "« −") + (s >= 60 ? s / 60 + " min" : s + " s"), null, false);
        showTransport(false);
    }

    private void cycleTrack(int type) {
        if (player == null) return;
        boolean text = type == C.TRACK_TYPE_TEXT;
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        int cur = -1;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                if (g.isTrackSelected(i)) cur = groups.size();
                groups.add(g);
                idx.add(i);
            }
        }
        if (groups.isEmpty() || (!text && groups.size() < 2)) {
            osd(text ? "NO SUBTITLES" : "ONE AUDIO TRACK", null, false);
            return;
        }
        int slots = groups.size() + (text ? 1 : 0);     // subtitles get an extra "off" slot
        if (cur < 0) cur = text ? groups.size() : 0;
        int nx = (cur + 1) % slots;
        androidx.media3.common.TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
        String label;
        if (text && nx == groups.size()) {
            b.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
            label = "SUBTITLES OFF";
        } else {
            Tracks.Group g = groups.get(nx);
            int i = idx.get(nx);
            b.setTrackTypeDisabled(type, false).setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), i));
            label = (text ? "SUBS: " : "AUDIO: ") + trackName(g.getTrackFormat(i), nx + 1);
        }
        player.setTrackSelectionParameters(b.build());
        osd(label, null, false);
    }

    private static String trackName(Format f, int n) {
        StringBuilder s = new StringBuilder();
        if (f.language != null && !f.language.equals("und")) {
            s.append(new java.util.Locale(f.language).getDisplayLanguage());
        }
        if (f.label != null && !f.label.isEmpty() && !f.label.equalsIgnoreCase(s.toString())) {
            if (s.length() > 0) s.append(' ');
            s.append(f.label);
        }
        if (f.channelCount > 0) s.append(s.length() > 0 ? " " : "").append(f.channelCount).append("ch");
        return s.length() == 0 ? "track " + n : s.toString();
    }

    // ---------------------------------------------------------------- films & shows: transport bar

    private final Runnable hideTransportR = this::hideTransport;

    private void buildTransport() {
        transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.VERTICAL);
        transport.setBackgroundColor(Color.argb(170, 0, 0, 0));
        transport.setPadding(dp(10), dp(6), dp(10), dp(4));
        transport.setClickable(true);                 // taps on the bar don't reach the TV behind
        transport.setVisibility(View.GONE);

        tTitle = barText(GREEN);
        tTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tSub = barText(Color.rgb(90, 230, 255));
        transport.addView(tTitle);
        transport.addView(tSub);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        tPlay = barButton("❚❚", v -> playPause());
        tTime = barText(Color.rgb(220, 220, 220));
        tDur = barText(Color.rgb(220, 220, 220));
        seek = new SeekBar(this);
        android.content.res.ColorStateList g = android.content.res.ColorStateList.valueOf(GREEN);
        seek.setProgressTintList(g);
        seek.setThumbTintList(g);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) tTime.setText(Vod.clock(p * 1000L));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {
                seeking = true;
                ui.removeCallbacks(hideTransportR);
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (player != null) player.seekTo(s.getProgress() * 1000L);
                scheduleHideTransport();
            }
        });
        tAudio = barButton("♪", v -> { cycleTrack(C.TRACK_TYPE_AUDIO); scheduleHideTransport(); });
        tSubs = barButton("CC", v -> { cycleTrack(C.TRACK_TYPE_TEXT); scheduleHideTransport(); });
        TextView tList = barButton("☰", v -> {
            hideTransport();
            lib.setSource(libKey());
            if (xt == null || (vod != null && vod.isLocal())) lib.open(LibraryView.VIDEOS);
            else lib.reopen();
        });
        row.addView(tPlay, new LinearLayout.LayoutParams(dp(46), dp(42)));
        row.addView(tTime);
        row.addView(seek, new LinearLayout.LayoutParams(0, dp(42), 1));
        row.addView(tDur);
        row.addView(tAudio, new LinearLayout.LayoutParams(dp(44), dp(42)));
        row.addView(tSubs, new LinearLayout.LayoutParams(dp(44), dp(42)));
        row.addView(tList, new LinearLayout.LayoutParams(dp(44), dp(42)));
        transport.addView(row);

        subs = new TextView(this);
        subs.setTextColor(Color.WHITE);
        subs.setGravity(Gravity.CENTER);
        subs.setShadowLayer(4, 0, 0, Color.BLACK);
        subs.setBackgroundColor(Color.argb(110, 0, 0, 0));
        subs.setPadding(dp(6), dp(2), dp(6), dp(2));
        subs.setVisibility(View.GONE);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        holeBox.addView(subs, sl);
        holeBox.addView(transport, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private TextView barText(int color) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(dp(4), 0, dp(4), 0);
        return t;
    }

    private TextView barButton(String label, View.OnClickListener l) {
        TextView t = barText(GREEN);
        t.setText(label);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        t.setBackgroundResource(android.R.drawable.list_selector_background);
        return t;
    }

    private void showTransport(boolean sticky) {
        if (vod == null) return;
        transport.setVisibility(View.VISIBLE);
        transport.post(() -> subs.setTranslationY(-transport.getHeight()));
        ui.removeCallbacks(hideTransportR);
        if (!sticky) scheduleHideTransport();
    }

    private void scheduleHideTransport() {
        ui.removeCallbacks(hideTransportR);
        if (player != null && !player.getPlayWhenReady()) return;   // stays up while paused
        ui.postDelayed(hideTransportR, 4000);
    }

    private void hideTransport() {
        if (seeking) return;
        transport.setVisibility(View.GONE);
        subs.setTranslationY(0);
    }

    private void toggleTransport() {
        if (transport.getVisibility() == View.VISIBLE) hideTransport();
        else showTransport(false);
    }

    // ---------------------------------------------------------------- diagnostics (debug builds only)

    private String tech = "";

    /** e.g. "H.264 720x576 · MP2 audio ✗" (✗ = the phone can't decode it). */
    private static String describe(Tracks tracks) {
        StringBuilder v = new StringBuilder(), a = new StringBuilder();
        for (Tracks.Group g : tracks.getGroups()) {
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                boolean ok = g.isTrackSupported(i);
                String name = codecName(f.sampleMimeType);
                if (g.getType() == C.TRACK_TYPE_VIDEO) {
                    if (v.length() > 0) v.append(", ");
                    v.append(name);
                    if (f.width > 0) v.append(' ').append(f.width).append('x').append(f.height);
                    if (f.frameRate > 0) v.append(' ').append(Math.round(f.frameRate)).append("fps");
                    if (!ok) v.append(" \u2717");
                } else if (g.getType() == C.TRACK_TYPE_AUDIO) {
                    if (a.length() > 0) a.append(", ");
                    a.append(name);
                    if (f.language != null && !f.language.equals("und")) a.append(" [").append(f.language).append(']');
                    if (f.channelCount > 0) a.append(' ').append(f.channelCount).append("ch");
                    if (!ok) a.append(" \u2717");
                    else if (g.isTrackSelected(i)) a.append(" \u266A");
                }
            }
        }
        return (v.length() == 0 ? "no video" : v) + " \u00b7 " + (a.length() == 0 ? "no audio" : "audio: " + a);
    }

    private static String codecName(String mime) {
        if (mime == null) return "?";
        switch (mime) {
            case "video/avc": return "H.264";
            case "video/hevc": return "HEVC";
            case "video/mpeg2": return "MPEG-2";
            case "audio/mp4a-latm": return "AAC";
            case "audio/mpeg-L2": return "MP2";
            case "audio/mpeg": return "MP3";
            case "audio/ac3": return "AC-3";
            case "audio/eac3": return "E-AC-3";
            case "audio/ac4": return "AC-4";
            case "audio/vnd.dts": return "DTS";
            default: return mime.substring(mime.indexOf('/') + 1);
        }
    }

    /** Appends a line to files/diag.log in debug builds only (no URLs or login details). */
    private void diag(String line) {
        if (!debuggable) return;
        final String ch = current == null ? "-" : current.num + " " + current.name;
        final java.io.File f = new java.io.File(getFilesDir(), "diag.log");
        io.execute(() -> {
            try {
                if (f.length() > 200_000) f.delete();
                String s = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date())
                        + " [" + ch + "] " + line + "\n";
                java.nio.file.Files.write(f.toPath(), s.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {
            }
        });
    }

    // ---------------------------------------------------------------- list adapter

    private final class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() {
            if (cats.isEmpty() && !prefs.hasLogin()) return MENU_NO_IPTV.length;
            return curList.size();
        }
        @Override public Object getItem(int i) {
            if (cats.isEmpty() && !prefs.hasLogin()) return MENU_NO_IPTV[i];
            return curList.get(i);
        }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            TextView t = (TextView) v;
            if (t == null) {
                t = new TextView(TvActivity.this);
                t.setTypeface(Typeface.MONOSPACE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                t.setSingleLine(true);
                t.setPadding(dp(8), dp(10), dp(8), dp(10));
            }
            if (cats.isEmpty() && !prefs.hasLogin()) {
                t.setText(MENU_NO_IPTV[i]);
                t.setTextColor(i == 0 ? GREEN : i == 1 ? Color.rgb(255, 230, 60) : Color.rgb(220, 220, 220));
                return t;
            }
            Xtream.Channel c = curList.get(i);
            boolean fav = favSet.contains(c.id);
            t.setText((fav ? "★ " : "  ") + pad(c.num) + "  " + c.name);
            t.setTextColor(c == current ? GREEN : Color.rgb(220, 220, 220));
            return t;
        }

        private String pad(String n) {
            StringBuilder s = new StringBuilder(n);
            while (s.length() < 4) s.insert(0, ' ');
            return s.toString();
        }
    }
}
