package io.github.samblakeman.retrotv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Full-screen teletext-style programme guide: channels down the side, 24 h of time across. */
final class GuideView extends View implements EpgStore.Listener {
    interface Host {
        List<Xtream.Channel> guideChannels();
        String guideCategory();
        Xtream.Channel guideCurrent();
        void guideWatch(Xtream.Channel c);
        void guideCategory(int delta);
        void guideClose();
    }

    private static final long MIN = 60_000L;
    private static final int SPAN_MIN = 24 * 60;

    // teletext palette
    private static final int BG = Color.rgb(4, 4, 10);
    private static final int BAR = Color.rgb(0, 0, 170);
    private static final int CHAN_BG = Color.rgb(10, 14, 40);
    private static final int CHAN_BG_CUR = Color.rgb(20, 60, 30);
    private static final int PROG = Color.rgb(16, 26, 78);
    private static final int PROG_NOW = Color.rgb(18, 70, 40);
    private static final int YELLOW = Color.rgb(255, 230, 60);
    private static final int CYAN = Color.rgb(90, 230, 255);
    private static final int GREEN = Color.rgb(90, 255, 120);
    private static final int WHITE = Color.rgb(235, 235, 235);
    private static final int GREY = Color.rgb(120, 120, 140);
    private static final int RED = Color.rgb(255, 60, 60);

    private final Host host;
    private final EpgStore store;
    private final float dp;
    private final Paint fill = new Paint();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat day = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
    private final SimpleDateFormat dayHm = new SimpleDateFormat("EEE HH:mm", Locale.getDefault());

    // geometry
    private float headerH, rulerH, detailH, rowH, chanW, pxPerMin;
    private float gridTop, gridBottom, gridLeft;
    private boolean compact;

    // state
    private long t0;                // time at scrollMin = 0
    private float scrollMin, scrollY;
    private int selRow = -1;
    private long selTime;
    private Xtream.Programme sel;

    GuideView(Context c, Host host, EpgStore store) {
        super(c);
        this.host = host;
        this.store = store;
        dp = c.getResources().getDisplayMetrics().density;
        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        text.setTypeface(mono);
        stroke.setStyle(Paint.Style.STROKE);
        scroller = new OverScroller(c);
        setFocusable(true);
        setClickable(true);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true);
                return true;
            }

            @Override public boolean onScroll(MotionEvent a, MotionEvent e, float dx, float dy) {
                scrollBy(dx, dy);
                return true;
            }

            @Override public boolean onFling(MotionEvent a, MotionEvent e, float vx, float vy) {
                scroller.fling((int) (scrollMin * pxPerMin), (int) scrollY, (int) -vx, (int) -vy,
                        0, (int) (maxScrollMin() * pxPerMin), 0, (int) maxScrollY());
                postInvalidateOnAnimation();
                return true;
            }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                tap(e.getX(), e.getY());
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ public

    void open() {
        if (getWidth() == 0 || getHeight() == 0) {   // just made visible: wait for layout
            postDelayed(this::open, 16);
            return;
        }
        long now = System.currentTimeMillis();
        long half = 30 * MIN;
        t0 = (now / half) * half - half;          // start 30 min before the current half hour
        scrollMin = 30;
        List<Xtream.Channel> ch = host.guideChannels();
        selRow = Math.max(0, ch.indexOf(host.guideCurrent()));
        selTime = now;
        sel = null;
        layoutDims();
        scrollY = Math.max(0, selRow * rowH - (gridBottom - gridTop) / 3);
        clamp();
        pickAtSelTime();
        invalidate();
        requestFocus();
    }

    /** After the channel list (category) changed. */
    void resetRows() {
        selRow = 0;
        scrollY = 0;
        sel = null;
        selTime = Math.max(selTime, System.currentTimeMillis());
        pickAtSelTime();
        invalidate();
    }

    @Override
    public void onEpg(String streamId) {
        if (getVisibility() != VISIBLE) return;
        if (sel == null) pickAtSelTime();
        invalidate();
    }

    // ------------------------------------------------------------------ geometry

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutDims();
        clamp();
    }

    private void layoutDims() {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        compact = h / dp < 520;                       // folded outer screen in landscape
        headerH = (compact ? 40 : 48) * dp;
        rulerH = (compact ? 26 : 30) * dp;
        detailH = (compact ? 74 : 118) * dp;
        rowH = (compact ? 50 : 56) * dp;
        chanW = Math.min(w * 0.30f, 230 * dp);
        gridTop = headerH + rulerH;
        gridBottom = h - detailH;
        gridLeft = chanW;
        pxPerMin = Math.max((w - chanW) / 150f, 2.2f * dp);   // ~2.5 hours across
    }

    private float x(long t) { return gridLeft + ((t - t0) / (float) MIN - scrollMin) * pxPerMin; }

    private float maxScrollMin() {
        return Math.max(0, SPAN_MIN - (getWidth() - gridLeft) / pxPerMin);
    }

    private float maxScrollY() {
        return Math.max(0, host.guideChannels().size() * rowH - (gridBottom - gridTop));
    }

    private void clamp() {
        if (pxPerMin <= 0) return;
        if (Float.isNaN(scrollMin)) scrollMin = 0;
        if (Float.isNaN(scrollY)) scrollY = 0;
        scrollMin = Math.max(0, Math.min(scrollMin, maxScrollMin()));
        scrollY = Math.max(0, Math.min(scrollY, maxScrollY()));
    }

    private void scrollBy(float dx, float dy) {
        scrollMin += dx / pxPerMin;
        scrollY += dy;
        clamp();
        invalidate();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollMin = scroller.getCurrX() / pxPerMin;
            scrollY = scroller.getCurrY();
            clamp();
            postInvalidateOnAnimation();
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (pxPerMin <= 0) layoutDims();
        long now = System.currentTimeMillis();
        List<Xtream.Channel> ch = host.guideChannels();
        Xtream.Channel cur = host.guideCurrent();
        c.drawColor(BG);

        // rows
        int first = (int) (scrollY / rowH);
        int last = Math.min(ch.size() - 1, (int) ((scrollY + gridBottom - gridTop) / rowH));
        List<Xtream.Channel> visible = new ArrayList<>();
        for (int i = Math.max(0, first); i <= last; i++) visible.add(ch.get(i));
        for (int i = last + 1; i <= Math.min(ch.size() - 1, last + 4); i++) visible.add(ch.get(i));
        store.want(visible);

        c.save();
        c.clipRect(0, gridTop, w, gridBottom);
        for (int i = Math.max(0, first); i <= last; i++) {
            float y = gridTop + i * rowH - scrollY;
            Xtream.Channel chan = ch.get(i);
            drawRow(c, chan, i, y, w, now);
            boolean isCur = cur != null && chan.id.equals(cur.id);
            fill.setColor(isCur ? CHAN_BG_CUR : CHAN_BG);
            c.drawRect(0, y, chanW, y + rowH - 1, fill);
            text.setTextSize(13 * dp);
            text.setColor(YELLOW);
            c.drawText(chan.num, 8 * dp, y + 20 * dp, text);
            text.setTextSize(15 * dp);
            text.setColor(isCur ? GREEN : WHITE);
            c.drawText(ell(chan.name, chanW - 16 * dp), 8 * dp, y + 42 * dp, text);
        }
        // now line
        float nx = x(now);
        if (nx >= gridLeft && nx <= w) {
            fill.setColor(Color.argb(170, 255, 60, 60));
            c.drawRect(nx - 1f * dp, gridTop, nx + 1f * dp, gridBottom, fill);
        }
        c.restore();

        if (ch.isEmpty()) {
            text.setTextSize(18 * dp);
            text.setColor(GREY);
            c.drawText("No channels in this category", gridLeft + 16 * dp, gridTop + 40 * dp, text);
        }

        drawRuler(c, w, now);
        drawHeader(c, w, now);
        drawDetail(c, w, h, ch);
    }

    private void drawRow(Canvas c, Xtream.Channel chan, int row, float y, int w, long now) {
        List<Xtream.Programme> list = store.get(chan.id);
        text.setTextSize(15 * dp);
        if (list == null || list.isEmpty()) {
            fill.setColor(PROG);
            c.drawRect(gridLeft + 1, y + 1, w, y + rowH - 2, fill);
            text.setColor(GREY);
            String s = list == null || store.isLoading(chan.id) ? "· · · loading guide" : "No guide information";
            c.drawText(s, gridLeft + 12 * dp, y + rowH / 2 + 5 * dp, text);
            if (row == selRow) outline(c, gridLeft + 1, y + 1, w - 1, y + rowH - 2);
            return;
        }
        for (Xtream.Programme p : list) {
            float x0 = x(p.start), x1 = x(p.end);
            if (x1 < gridLeft || x0 > w) continue;
            boolean onNow = p.start <= now && now < p.end;
            fill.setColor(onNow ? PROG_NOW : PROG);
            r.set(Math.max(x0, gridLeft) + 1, y + 1, x1 - 1, y + rowH - 2);
            c.drawRect(r, fill);
            float tx = Math.max(x0, gridLeft) + 8 * dp;
            float avail = x1 - tx - 6 * dp;
            if (avail > 12 * dp) {
                text.setTextSize(15 * dp);
                text.setColor(WHITE);
                c.drawText(ell(p.title, avail), tx, y + 23 * dp, text);
                text.setTextSize(12 * dp);
                text.setColor(CYAN);
                c.drawText(ell(hm.format(new Date(p.start)), avail), tx, y + 43 * dp, text);
            }
            if (row == selRow && sel == p) outline(c, r.left, r.top, r.right, r.bottom);
        }
    }

    private void outline(Canvas c, float l, float t, float rr, float b) {
        stroke.setColor(YELLOW);
        stroke.setStrokeWidth(3 * dp);
        c.drawRect(l + 1.5f * dp, t + 1.5f * dp, rr - 1.5f * dp, b - 1.5f * dp, stroke);
    }

    private void drawRuler(Canvas c, int w, long now) {
        fill.setColor(Color.rgb(0, 0, 90));
        c.drawRect(0, headerH, w, gridTop, fill);
        text.setTextSize(13 * dp);
        text.setColor(CYAN);
        c.drawText(day.format(new Date(t0 + (long) (scrollMin * MIN))).toUpperCase(Locale.getDefault()),
                8 * dp, gridTop - 9 * dp, text);
        c.save();
        c.clipRect(gridLeft, headerH, w, gridTop);
        long half = 30 * MIN;
        long tStart = t0 + (long) (scrollMin * MIN);
        for (long t = (tStart / half) * half; x(t) < w; t += half) {
            float xx = x(t);
            fill.setColor(CYAN);
            c.drawRect(xx, gridTop - 8 * dp, xx + 1.5f * dp, gridTop, fill);
            text.setColor(t % (60 * MIN) == 0 ? YELLOW : CYAN);
            c.drawText(hm.format(new Date(t)), xx + 5 * dp, gridTop - 10 * dp, text);
        }
        c.restore();
    }

    private void drawHeader(Canvas c, int w, long now) {
        fill.setColor(BAR);
        c.drawRect(0, 0, w, headerH, fill);
        text.setTextSize(20 * dp);
        text.setColor(YELLOW);
        c.drawText("TV GUIDE", 12 * dp, headerH / 2 + 7 * dp, text);
        String clock = hm.format(new Date(now));
        float cw = text.measureText(clock);
        text.setColor(WHITE);
        c.drawText(clock, w - cw - 56 * dp, headerH / 2 + 7 * dp, text);
        text.setColor(RED);
        c.drawText("✕", w - 36 * dp, headerH / 2 + 7 * dp, text);

        // category with arrows, centred
        text.setTextSize(17 * dp);
        String cat = ell(host.guideCategory(), w * 0.42f);
        float catW = text.measureText(cat);
        float cx = w / 2f;
        text.setColor(CYAN);
        c.drawText("◄", cx - catW / 2 - 34 * dp, headerH / 2 + 6 * dp, text);
        c.drawText("►", cx + catW / 2 + 16 * dp, headerH / 2 + 6 * dp, text);
        text.setColor(WHITE);
        c.drawText(cat, cx - catW / 2, headerH / 2 + 6 * dp, text);
    }

    private void drawDetail(Canvas c, int w, int h, List<Xtream.Channel> ch) {
        fill.setColor(Color.rgb(0, 0, 60));
        c.drawRect(0, gridBottom, w, h, fill);
        fill.setColor(CYAN);
        c.drawRect(0, gridBottom, w, gridBottom + 2 * dp, fill);
        float pad = 14 * dp;
        float y = gridBottom + (compact ? 22 : 26) * dp;
        Xtream.Channel chan = selRow >= 0 && selRow < ch.size() ? ch.get(selRow) : null;
        if (sel == null) {
            text.setTextSize(15 * dp);
            text.setColor(GREY);
            c.drawText(chan == null ? "Tap a programme for details" : chan.num + "  " + chan.name
                    + "  ·  tap the channel name to watch", pad, y, text);
            return;
        }
        text.setTextSize(19 * dp);
        text.setColor(YELLOW);
        c.drawText(ell(sel.title, w - pad * 2 - 150 * dp), pad, y, text);
        text.setTextSize(13 * dp);
        text.setColor(GREEN);
        String hint = "TAP AGAIN TO WATCH";
        c.drawText(hint, w - pad - text.measureText(hint), y, text);
        text.setColor(CYAN);
        String when = dayHm.format(new Date(sel.start)) + "–" + hm.format(new Date(sel.end))
                + (chan == null ? "" : "   " + chan.num + " " + chan.name);
        c.drawText(ell(when, w - pad * 2), pad, y + 22 * dp, text);
        if (sel.desc != null && !sel.desc.isEmpty()) {
            text.setTextSize(13 * dp);
            text.setColor(WHITE);
            StaticLayout sl = StaticLayout.Builder.obtain(sel.desc, 0, sel.desc.length(), text, (int) (w - pad * 2))
                    .setMaxLines(compact ? 1 : 3).setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            c.save();
            c.translate(pad, y + 32 * dp);
            sl.draw(c);
            c.restore();
        }
    }

    private String ell(String s, float width) {
        if (s == null) return "";
        if (width <= 0) return "";
        return TextUtils.ellipsize(s, text, width, TextUtils.TruncateAt.END).toString();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return gestures.onTouchEvent(e) || super.onTouchEvent(e);
    }

    private void tap(float tx, float ty) {
        int w = getWidth();
        List<Xtream.Channel> ch = host.guideChannels();
        if (ty < headerH) {
            if (tx > w - 50 * dp) host.guideClose();
            else if (tx < w * 0.5f && tx > w * 0.2f) host.guideCategory(-1);
            else if (tx >= w * 0.5f && tx < w - 120 * dp) host.guideCategory(1);
            return;
        }
        if (ty >= gridBottom) {
            if (sel != null && selRow >= 0 && selRow < ch.size()) host.guideWatch(ch.get(selRow));
            return;
        }
        if (ty < gridTop) return;
        int row = (int) ((ty - gridTop + scrollY) / rowH);
        if (row < 0 || row >= ch.size()) return;
        if (tx < chanW) {
            host.guideWatch(ch.get(row));
            return;
        }
        long t = t0 + (long) (((tx - gridLeft) / pxPerMin + scrollMin) * MIN);
        Xtream.Programme p = progAt(ch.get(row), t);
        if (p != null && p == sel && row == selRow) {
            host.guideWatch(ch.get(row));
            return;
        }
        selRow = row;
        sel = p;
        selTime = Math.max(t, System.currentTimeMillis());
        invalidate();
    }

    /** D-pad / controller navigation. Returns true if handled. */
    boolean onKey(int code) {
        List<Xtream.Channel> ch = host.guideChannels();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: moveRow(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: moveRow(1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: moveProg(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: moveProg(1); return true;
            case KeyEvent.KEYCODE_PAGE_UP: moveRow(-6); return true;
            case KeyEvent.KEYCODE_PAGE_DOWN: moveRow(6); return true;
            case KeyEvent.KEYCODE_BUTTON_L1: host.guideCategory(-1); return true;
            case KeyEvent.KEYCODE_BUTTON_R1: host.guideCategory(1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_BUTTON_A:
                if (selRow >= 0 && selRow < ch.size()) host.guideWatch(ch.get(selRow));
                return true;
            case KeyEvent.KEYCODE_GUIDE: case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_BUTTON_B:
                host.guideClose();
                return true;
        }
        return false;
    }

    private void moveRow(int d) {
        int n = host.guideChannels().size();
        if (n == 0) return;
        selRow = Math.max(0, Math.min(n - 1, selRow + d));
        pickAtSelTime();
        float y = selRow * rowH, viewH = gridBottom - gridTop;
        if (y < scrollY) scrollY = y;
        else if (y + rowH > scrollY + viewH) scrollY = y + rowH - viewH;
        clamp();
        invalidate();
    }

    private void moveProg(int d) {
        List<Xtream.Channel> ch = host.guideChannels();
        if (selRow < 0 || selRow >= ch.size()) return;
        List<Xtream.Programme> l = store.get(ch.get(selRow).id);
        if (l == null || l.isEmpty() || sel == null) {
            selTime += d * 30 * MIN;
            pickAtSelTime();
        } else {
            int i = l.indexOf(sel) + d;
            if (i >= 0 && i < l.size()) sel = l.get(i);
            selTime = Math.max(sel.start, System.currentTimeMillis());
        }
        selTime = Math.max(t0, Math.min(selTime, t0 + SPAN_MIN * MIN));
        // keep the selection on screen horizontally
        long a = sel != null ? Math.max(sel.start, t0) : selTime;
        float m = (a - t0) / (float) MIN;
        float vis = (getWidth() - gridLeft) / pxPerMin;
        if (m < scrollMin) scrollMin = m - 10;
        else if (m > scrollMin + vis - 40) scrollMin = m - 20;
        clamp();
        invalidate();
    }

    private void pickAtSelTime() {
        List<Xtream.Channel> ch = host.guideChannels();
        sel = selRow >= 0 && selRow < ch.size() ? progAt(ch.get(selRow), selTime) : null;
    }

    private Xtream.Programme progAt(Xtream.Channel c, long t) {
        List<Xtream.Programme> l = store.get(c.id);
        if (l == null) return null;
        for (Xtream.Programme p : l) if (p.start <= t && t < p.end) return p;
        return null;
    }
}
