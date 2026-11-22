package com.magicv6.retrotv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Everything the app remembers. Credentials stay on the device only. */
final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("retrotv", Context.MODE_PRIVATE);
    }

    String server()  { return sp.getString("server", ""); }
    String user()    { return sp.getString("user", ""); }
    String pass()    { return sp.getString("pass", ""); }
    boolean hls()    { return sp.getBoolean("hls", false); }
    boolean hasLogin() { return !server().isEmpty() && !user().isEmpty(); }
    long loginStamp()  { return sp.getLong("stamp", 0); }

    void saveLogin(String server, String user, String pass, boolean hls) {
        sp.edit().putString("server", server).putString("user", user).putString("pass", pass)
                .putBoolean("hls", hls).putLong("stamp", System.currentTimeMillis()).apply();
    }

    String lastStream() { return sp.getString("lastStream", ""); }
    void lastStream(String id) { sp.edit().putString("lastStream", id).apply(); }

    String lastCat() { return sp.getString("lastCat", ""); }
    void lastCat(String id) { sp.edit().putString("lastCat", id).apply(); }

    String bezelSet() { return sp.getString("set", "tvwide"); }
    void bezelSet(String s) { sp.edit().putString("set", s).apply(); }

    Set<String> favs() { return new HashSet<>(sp.getStringSet("favs", new HashSet<>())); }
    void favs(Set<String> f) { sp.edit().putStringSet("favs", new HashSet<>(f)).apply(); }
}
