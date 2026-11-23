package io.github.samblakeman.retrotv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.LinkedHashSet;
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
                .putBoolean("hls", hls).putBoolean("skipped", false)
                .putLong("stamp", System.currentTimeMillis()).apply();
    }

    void clearLogin() {
        sp.edit().remove("server").remove("user").remove("pass")
                .remove("lastStream").remove("lastCat")
                .putBoolean("skipped", true)
                .putLong("stamp", System.currentTimeMillis()).apply();
    }

    boolean skippedLogin() { return sp.getBoolean("skipped", false); }
    void skippedLogin(boolean v) { sp.edit().putBoolean("skipped", v).apply(); }

    /** Software H.264 decoder for live TV (fixes interlaced DVB artefacts on some Qualcomm GPUs). */
    boolean softAvcLive() { return sp.getBoolean("softAvcLive", false); }
    void softAvcLive(boolean v) { sp.edit().putBoolean("softAvcLive", v).apply(); }

    String lastStream() { return sp.getString("lastStream", ""); }
    void lastStream(String id) { sp.edit().putString("lastStream", id).apply(); }

    String lastCat() { return sp.getString("lastCat", ""); }
    void lastCat(String id) { sp.edit().putString("lastCat", id).apply(); }

    String bezelSet() { return sp.getString("set", "tvwide"); }
    void bezelSet(String s) { sp.edit().putString("set", s).apply(); }

    /** Town for the teletext weather pages ("" = London). */
    String weatherTown() { return sp.getString("town", ""); }
    void weatherTown(String t) { sp.edit().putString("town", t.trim()).apply(); }

    Set<String> favs() { return new HashSet<>(sp.getStringSet("favs", new HashSet<>())); }
    void favs(Set<String> f) { sp.edit().putStringSet("favs", new HashSet<>(f)).apply(); }

    /** Persisted SAF folder tree URIs added by the user for local video playback. */
    Set<String> treeUris() { return new LinkedHashSet<>(sp.getStringSet("trees", new HashSet<>())); }

    void addTreeUri(String uri) {
        Set<String> s = treeUris();
        s.add(uri);
        sp.edit().putStringSet("trees", s).apply();
    }

    void removeTreeUri(String uri) {
        Set<String> s = treeUris();
        s.remove(uri);
        sp.edit().putStringSet("trees", s).apply();
    }
}
