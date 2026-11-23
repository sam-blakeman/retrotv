package io.github.samblakeman.retrotv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Invisible touch layer over the TV's picture hole. Draws the current teletext page into a small
 * bitmap that the bezel renderer shows on the tube (so it gets scanlines and curvature).
 */
final class TeletextView extends View implements Teletext.Listener {
    interface Host {
        void ttShow(Bitmap frame, boolean mix);   // null = teletext off
        void ttWatch(Xtream.Channel c);
        void ttClose();
    }

    static final int CW = 12, CH = 20;      // cell size in the page bitmap
    private static final int[] PAL = {
            Color.BLACK, Color.rgb(255, 0, 0), Color.rgb(0, 255, 0), Color.rgb(255, 255, 0),
            Color.rgb(0, 0, 255), Color.rgb(255, 0, 255), Color.rgb(0, 255, 255), Color.WHITE};

    private final Teletext tt;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Bitmap bmp = Bitmap.createBitmap(Teletext.COLS * CW, Teletext.ROWS * CH, Bitmap.Config.ARGB_8888);
    private final Canvas cv = new Canvas(bmp);
    private final Paint txt = new Paint(), fill = new Paint();
    private final GestureDetector gd;
    private LinearLayout keypad;

    private int want = 100;               // page asked for
    private Teletext.Page shown;          // page on screen (stays while searching)
    private int subIdx;
    private String entry = "";            // digits typed so far
    private boolean mix, hold, open;
    private int roll = 100;               // rolling header counter while searching
    private long subSince;

    TeletextView(Context c, Teletext tt, Host host) {
        super(c);
        this.tt = tt;
        this.host = host;
        txt.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        txt.setAntiAlias(false);
        txt.setTextSize(19);
        txt.setTextScaleX(1f);
        float w = txt.measureText("W");
        txt.setTextScaleX(11.2f / w);
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public void onLongPress(MotionEvent e) { toggleKeypad(); }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                tap(e.getX(), e.getY());
                return true;
            }

            @Override public boolean onFling(MotionEvent a, MotionEvent e, float vx, float vy) {
                if (Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 500) {
                    go(tt.next(cur(), vx < 0 ? 1 : -1));
                    return true;
                }
                if (Math.abs(vy) > 500) { flipSub(vy < 0 ? 1 : -1); return true; }
                return false;
            }
        });
        tt.setListener(this);
    }

    boolean isOpen() { return open; }

    void open() {
        open = true;
        setVisibility(VISIBLE);
        tt.refresh();
        go(want);
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    void close() {
        open = false;
        setVisibility(GONE);
        if (keypad != null) keypad.setVisibility(GONE);
        ui.removeCallbacks(tick);
        host.ttShow(null, false);
    }

    private int cur() { return shown != null ? shown.num : want; }

    void go(int num) {
        want = num;
        entry = "";
        subIdx = 0;
        subSince = System.currentTimeMillis();
        searchUntil = System.currentTimeMillis() + 450;   // the header counter rolls briefly
        render();
    }

    private long searchUntil;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!open) return;
            long now = System.currentTimeMillis();
            List<Teletext.Page> l = tt.get(want);
            if (l != null && !hold && l.size() > 1 && now - subSince > 12_000) {
                subIdx = (subIdx + 1) % l.size();
                subSince = now;
            }
            render();
            boolean searching = l == null || now < searchUntil;
            ui.postDelayed(this, searching ? 100 : 1000 - (now % 1000));
        }
    };

    @Override public void onPagesChanged() { if (open) render(); }

    // ------------------------------------------------------------------ input

    private void digit(int d) {
        entry += d;
        if (entry.length() == 1 && (d == 0 || d == 9)) entry = "";   // pages are 100-899
        if (entry.length() == 3) go(Integer.parseInt(entry));
        else render();
    }

    private void fast(int i) {
        Teletext.Page p = shown;
        if (p == null) return;
        if (i == 0 && p.watch != null) { host.ttWatch(p.watch); return; }
        if (p.fastPage[i] > 0) go(p.fastPage[i]);
    }

    private void flipSub(int d) {
        List<Teletext.Page> l = tt.get(want);
        if (l == null || l.size() < 2) return;
        subIdx = (subIdx + d + l.size()) % l.size();
        subSince = System.currentTimeMillis();
        render();
    }

    private void tap(float x, float y) {
        float w = getWidth(), h = getHeight();
        float cw = Math.min(w, h * 4f / 3f), x0 = (w - cw) / 2;
        int col = (int) ((x - x0) / cw * Teletext.COLS), row = (int) (y / h * Teletext.ROWS);
        if (row <= 1) { toggleKeypad(); return; }
        if (row >= 24) { fast(Math.max(0, Math.min(3, col / 10))); return; }
        if (shown == null) return;
        // nearest 3-digit page number on that row (or the row below, for wrapped headlines)
        for (int r : new int[]{row, row - 1, row + 1}) {
            if (r < 1 || r > 23) continue;
            String s = new String(shown.ch[r]);
            int best = -1, bestD = 99;
            for (int c = 0; c + 3 <= Teletext.COLS; c++) {
                if ((shown.fl[r][c] & Teletext.MOS) != 0) continue;
                if (!Character.isDigit(s.charAt(c)) || !Character.isDigit(s.charAt(c + 1)) || !Character.isDigit(s.charAt(c + 2)))
                    continue;
                if (c > 0 && Character.isDigit(s.charAt(c - 1))) continue;
                if (c + 3 < Teletext.COLS && Character.isDigit(s.charAt(c + 3))) continue;
                int n = Integer.parseInt(s.substring(c, c + 3));
                if (n < 100 || n > 899 || tt.get(n) == null) continue;
                int d = Math.abs(c + 1 - col);
                if (d < bestD) { bestD = d; best = n; }
            }
            if (best > 0) { go(best); return; }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) { return gd.onTouchEvent(e); }

    boolean onKey(int code) {
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) { digit(code - KeyEvent.KEYCODE_0); return true; }
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) {
            digit(code - KeyEvent.KEYCODE_NUMPAD_0);
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP: go(tt.next(cur(), 1)); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN: go(tt.next(cur(), -1)); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: flipSub(1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: flipSub(-1); return true;
            case KeyEvent.KEYCODE_PROG_RED: case KeyEvent.KEYCODE_BUTTON_L1: fast(0); return true;
            case KeyEvent.KEYCODE_PROG_GREEN: case KeyEvent.KEYCODE_BUTTON_R1: fast(1); return true;
            case KeyEvent.KEYCODE_PROG_YELLOW: case KeyEvent.KEYCODE_BUTTON_L2: fast(2); return true;
            case KeyEvent.KEYCODE_PROG_BLUE: case KeyEvent.KEYCODE_BUTTON_R2: fast(3); return true;
            case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_M: toggleMix(); return true;
            case KeyEvent.KEYCODE_BUTTON_Y: case KeyEvent.KEYCODE_H: toggleHold(); return true;
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                toggleKeypad(); return true;
            case KeyEvent.KEYCODE_I: case KeyEvent.KEYCODE_MOVE_HOME: go(100); return true;
            case KeyEvent.KEYCODE_TV_TELETEXT: case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_T:
                host.ttClose(); return true;
        }
        return false;
    }

    /** Back: hides the keypad first, then closes teletext. */
    void back() {
        if (keypad != null && keypad.getVisibility() == VISIBLE) keypad.setVisibility(GONE);
        else host.ttClose();
    }

    private void toggleMix() { mix = !mix; render(); }

    private void toggleHold() { hold = !hold; subSince = System.currentTimeMillis(); render(); }

    private void toggleKeypad() {
        if (keypad != null) keypad.setVisibility(keypad.getVisibility() == VISIBLE ? GONE : VISIBLE);
    }

    // ------------------------------------------------------------------ remote keypad

    LinearLayout buildKeypad(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout k = new LinearLayout(c);
        k.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable body = new GradientDrawable();
        body.setColor(Color.rgb(28, 28, 30));
        body.setCornerRadius(18 * d);
        body.setStroke((int) (2 * d), Color.rgb(70, 70, 74));
        k.setBackground(body);
        int pad = (int) (10 * d);
        k.setPadding(pad, pad, pad, pad);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"MIX", "0", "HOLD"}};
        for (String[] row : rows) {
            LinearLayout r = new LinearLayout(c);
            for (String s : row) r.addView(key(c, s, Color.rgb(60, 60, 64), Color.WHITE, v -> {
                if (s.equals("MIX")) toggleMix();
                else if (s.equals("HOLD")) toggleHold();
                else digit(Integer.parseInt(s));
            }), new LinearLayout.LayoutParams(0, (int) (46 * d), 1));
            k.addView(r);
        }
        LinearLayout colours = new LinearLayout(c);
        int[] cols = {Color.rgb(220, 30, 30), Color.rgb(30, 180, 40), Color.rgb(230, 210, 20), Color.rgb(30, 160, 220)};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            colours.addView(key(c, "", cols[i], Color.WHITE, v -> fast(idx)),
                    new LinearLayout.LayoutParams(0, (int) (34 * d), 1));
        }
        k.addView(colours);
        LinearLayout last = new LinearLayout(c);
        last.addView(key(c, "INDEX", Color.rgb(60, 60, 64), Color.WHITE, v -> go(100)),
                new LinearLayout.LayoutParams(0, (int) (40 * d), 1));
        last.addView(key(c, "TV", Color.rgb(60, 60, 64), Color.WHITE, v -> host.ttClose()),
                new LinearLayout.LayoutParams(0, (int) (40 * d), 1));
        last.addView(key(c, "HIDE", Color.rgb(60, 60, 64), Color.WHITE, v -> toggleKeypad()),
                new LinearLayout.LayoutParams(0, (int) (40 * d), 1));
        k.addView(last);
        k.setVisibility(GONE);
        keypad = k;
        return k;
    }

    private static TextView key(Context c, String label, int col, int ink, OnClickListener l) {
        float d = c.getResources().getDisplayMetrics().density;
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextColor(ink);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, label.length() > 1 ? 13 : 18);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(col);
        g.setCornerRadius(10 * d);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -1);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        int m = (int) (3 * d);
        t.setPadding(m, m, m, m);
        // spacing between keys via a wrapper margin
        t.post(() -> {
            if (t.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) t.getLayoutParams();
                p.setMargins(m, m, m, m);
                t.setLayoutParams(p);
            }
        });
        return t;
    }

    // ------------------------------------------------------------------ drawing

    private void render() {
        if (!open) return;
        long now = System.currentTimeMillis();
        List<Teletext.Page> l = tt.get(want);
        boolean searching = l == null || now < searchUntil;
        if (!searching) {
            subIdx = Math.min(subIdx, l.size() - 1);
            shown = l.get(subIdx);
            roll = want;
        } else {
            roll = roll >= 899 ? 100 : roll + 1;
        }
        bmp.eraseColor(mix ? Color.TRANSPARENT : Color.BLACK);
        Teletext.Page p = shown;
        if (p != null) {
            for (int r = 1; r < Teletext.ROWS; r++) drawRow(p, r, true);    // backgrounds first,
            for (int r = 1; r < Teletext.ROWS; r++) drawRow(p, r, false);   // so double height text isn't covered
            if (p.subs > 1) {
                String s = " " + p.sub + "/" + p.subs + (hold ? " H" : "");
                drawText(23, Teletext.COLS - s.length(), s, Teletext.W, Teletext.K, false);
            }
        }
        // header: P123  RETROTEXT 100 Sun 22 Nov 14:52/07
        Calendar c = Calendar.getInstance();
        String typed = entry.isEmpty() ? String.valueOf(want) : (entry + "--").substring(0, 3);
        String hdr = String.format(Locale.UK, " P%s  RETROTEXT %3d %s", typed, searching ? roll : want,
                Teletext.headerDate(c));
        drawText(0, 0, hdr, Teletext.W, Teletext.K, false);
        if (searching) drawText(0, 1, "P" + typed, Teletext.G, Teletext.K, false);
        String time = String.format(Locale.UK, "%02d:%02d/%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                c.get(Calendar.SECOND));
        drawText(0, Teletext.COLS - time.length(), time, Teletext.Y, Teletext.K, false);
        if (hold) drawText(0, 1, "HOLD", Teletext.R, Teletext.K, false);
        host.ttShow(bmp.copy(Bitmap.Config.ARGB_8888, false), mix);
    }

    private void drawRow(Teletext.Page p, int r, boolean background) {
        for (int c = 0; c < Teletext.COLS; c++) {
            int b = p.bg[r][c];
            int x = c * CW, y = r * CH;
            if (background) {
                if (b != Teletext.K) {
                    fill.setColor(PAL[b]);
                    cv.drawRect(x, y, x + CW, y + CH, fill);
                }
                continue;
            }
            byte f = p.fl[r][c];
            if ((f & Teletext.LOWER) != 0) continue;
            int col = PAL[p.fg[r][c]];
            if ((f & Teletext.MOS) != 0) {
                int bits = p.ch[r][c];
                fill.setColor(col);
                int[] ys = {0, 7, 14, CH};
                for (int i = 0; i < 6; i++) {
                    if ((bits & (1 << i)) == 0) continue;
                    int bx = x + (i & 1) * (CW / 2), by = y + ys[i >> 1];
                    cv.drawRect(bx, by, bx + CW / 2, y + ys[(i >> 1) + 1], fill);
                }
            } else if (p.ch[r][c] != ' ') {
                txt.setColor(col);
                String s = String.valueOf(p.ch[r][c]);
                if ((f & Teletext.DBL) != 0) {
                    cv.save();
                    cv.scale(1f, 2f, 0, y);
                    cv.drawText(s, x + 0.4f, y + 15, txt);
                    cv.restore();
                } else {
                    cv.drawText(s, x + 0.4f, y + 15, txt);
                }
            }
        }
    }

    private void drawText(int r, int c, String s, int fgc, int bgc, boolean dbl) {
        for (int i = 0; i < s.length() && c + i < Teletext.COLS; i++) {
            int x = (c + i) * CW, y = r * CH;
            if (bgc != Teletext.K || !mix) {
                fill.setColor(PAL[bgc]);
                cv.drawRect(x, y, x + CW, y + CH, fill);
            }
            if (s.charAt(i) != ' ') {
                txt.setColor(PAL[fgc]);
                cv.drawText(String.valueOf(s.charAt(i)), x + 0.4f, y + 15, txt);
            }
        }
    }
}
