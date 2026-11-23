package io.github.samblakeman.retrotv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Settings & optional Xtream Codes IPTV login. Details stay in private app storage only. */
public class SetupActivity extends Activity {
    private EditText server, user, pass, town;
    private CheckBox hls, softAvc;
    private TextView status;
    private Button go, alt;

    @Override
    protected void onPause() {
        super.onPause();
        Prefs p = new Prefs(this);
        p.weatherTown(town.getText().toString());
        p.softAvcLive(softAvc.isChecked());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs p = new Prefs(this);
        int pad = dp(20);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Retro TV — Settings");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        col.addView(title);

        TextView note = new TextView(this);
        note.setText("Retro TV provides no channels or media. Watch your own local video files and "
                + "Teletext right away, or sign in with your Xtream Codes IPTV details (stored only on this device).");
        note.setTextColor(Color.LTGRAY);
        note.setPadding(0, dp(8), 0, dp(16));
        col.addView(note);

        TextView iptvHead = section(col, "IPTV account (optional)", 0);
        server = field(col, "Server (e.g. http://example.com:8080)", p.server(), InputType.TYPE_TEXT_VARIATION_URI);
        user = field(col, "Username", p.user(), InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        pass = field(col, "Password", p.pass(), InputType.TYPE_TEXT_VARIATION_PASSWORD);

        hls = new CheckBox(this);
        hls.setText("Use HLS (.m3u8) streams instead of MPEG-TS");
        hls.setChecked(p.hls());
        col.addView(hls);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(8), 0, 0);

        go = new Button(this);
        go.setText("Connect");
        go.setOnClickListener(v -> connect());
        btnRow.addView(go, new LinearLayout.LayoutParams(0, -2, 1));

        alt = new Button(this);
        if (p.hasLogin()) {
            alt.setText("Log out");
            alt.setOnClickListener(v -> logout());
        } else {
            alt.setText("Skip — Local & Teletext");
            alt.setOnClickListener(v -> {
                new Prefs(this).skippedLogin(true);
                finish();
            });
        }
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, -2, 1);
        alp.leftMargin = dp(8);
        btnRow.addView(alt, alp);
        col.addView(btnRow);

        status = new TextView(this);
        status.setPadding(0, dp(10), 0, 0);
        status.setGravity(Gravity.START);
        col.addView(status);

        section(col, "Playback", dp(20));
        softAvc = new CheckBox(this);
        softAvc.setText("Software H.264 decoder for live TV");
        softAvc.setChecked(p.softAvcLive());
        softAvc.setOnCheckedChangeListener((v, checked) -> new Prefs(this).softAvcLive(checked));
        col.addView(softAvc);

        TextView avcHint = new TextView(this);
        avcHint.setText("Turn on if interlaced live TV channels show green/magenta blocks on your phone.");
        avcHint.setTextColor(Color.GRAY);
        avcHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        avcHint.setPadding(dp(32), 0, 0, dp(4));
        col.addView(avcHint);

        section(col, "Teletext", dp(20));
        town = field(col, "Weather town (e.g. Leeds) — blank for London", p.weatherTown(),
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        sv.addView(col);
        setContentView(sv);
        if (iptvHead != null) iptvHead.setTextColor(Color.WHITE);
    }

    private TextView section(LinearLayout col, String label, int topPad) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        t.setPadding(0, topPad, 0, dp(4));
        col.addView(t);
        return t;
    }

    private EditText field(LinearLayout col, String hint, String value, int variation) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        e.setText(value);
        col.addView(e);
        return e;
    }

    private void logout() {
        new Prefs(this).clearLogin();
        server.setText("");
        user.setText("");
        pass.setText("");
        status.setTextColor(Color.rgb(120, 230, 140));
        status.setText("IPTV account removed. Press Back or Skip to use local videos & Teletext.");
        alt.setText("Skip — Local & Teletext");
        alt.setOnClickListener(v -> finish());
    }

    private void connect() {
        final String s = server.getText().toString().trim();
        final String u = user.getText().toString().trim();
        final String pw = pass.getText().toString();
        final boolean h = hls.isChecked();
        if (s.isEmpty() || u.isEmpty()) {
            status.setTextColor(Color.rgb(255, 120, 120));
            status.setText("Server and username are required.");
            return;
        }
        go.setEnabled(false);
        status.setTextColor(Color.LTGRAY);
        status.setText("Checking login…");
        new Thread(() -> {
            String err = new Xtream(s, u, pw).checkLogin();
            runOnUiThread(() -> {
                go.setEnabled(true);
                if (err == null) {
                    new Prefs(this).saveLogin(Xtream.normalise(s), u, pw, h);
                    finish();
                } else {
                    status.setTextColor(Color.rgb(255, 120, 120));
                    status.setText("Couldn't log in: " + err);
                }
            });
        }).start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
