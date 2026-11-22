package com.magicv6.retrotv;

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

/** Xtream login form. Details are saved only in this app's private storage on the phone. */
public class SetupActivity extends Activity {
    private EditText server, user, pass;
    private CheckBox hls;
    private TextView status;
    private Button go;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Prefs p = new Prefs(this);
        int pad = dp(20);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Retro TV — IPTV login");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        col.addView(title);

        TextView note = new TextView(this);
        note.setText("Your Xtream Codes details from your provider. They are stored only on this phone.");
        note.setPadding(0, dp(8), 0, dp(16));
        col.addView(note);

        server = field(col, "Server (e.g. http://example.com:8080)", p.server(), InputType.TYPE_TEXT_VARIATION_URI);
        user = field(col, "Username", p.user(), InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        pass = field(col, "Password", p.pass(), InputType.TYPE_TEXT_VARIATION_PASSWORD);

        hls = new CheckBox(this);
        hls.setText("Use HLS (.m3u8) streams instead of MPEG-TS");
        hls.setChecked(p.hls());
        col.addView(hls);

        go = new Button(this);
        go.setText("Connect");
        go.setOnClickListener(v -> connect());
        col.addView(go);

        status = new TextView(this);
        status.setPadding(0, dp(12), 0, 0);
        status.setGravity(Gravity.START);
        col.addView(status);

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        sv.addView(col);
        setContentView(sv);
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
