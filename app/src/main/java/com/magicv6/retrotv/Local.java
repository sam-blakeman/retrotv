package com.magicv6.retrotv;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Video files on the phone: the folders worth showing, listing, play order and search index. */
final class Local {
    private Local() { }

    private static final List<String> EXT = Arrays.asList(
            "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mts", "mpg", "mpeg", "3gp", "flv", "ogv", "wmv", "vob", "divx");

    /** Folders shown at the top of MY VIDEOS (only the ones that exist). */
    private static final String[][] ROOTS = {
            {"ROMs/tv", "Retro TV (4:3)"},
            {"ROMs/video", "Videos (Widescreen)"},
            {"Movies", "Movies"},
            {"Download", "Downloads"},
            {"DCIM/Camera", "Camera"},
    };

    static File storage() { return Environment.getExternalStorageDirectory(); }

    /** ROMs folders sit behind ES-DE's .nomedia, so the normal video permission can't see them. */
    static boolean hasAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    static List<String[]> roots() {
        List<String[]> out = new ArrayList<>();
        for (String[] r : ROOTS) if (new File(storage(), r[0]).isDirectory()) out.add(r);
        return out;
    }

    static boolean isVideo(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 && !name.startsWith(".") && EXT.contains(name.substring(i + 1).toLowerCase(Locale.ROOT));
    }

    static List<File> dirs(File d) {
        List<File> out = new ArrayList<>();
        File[] a = d.listFiles();
        if (a != null) for (File f : a) if (f.isDirectory() && !f.getName().startsWith(".")) out.add(f);
        out.sort((x, y) -> natural(x.getName(), y.getName()));
        return out;
    }

    static List<File> videos(File d) {
        List<File> out = new ArrayList<>();
        File[] a = d.listFiles();
        if (a != null) for (File f : a) if (f.isFile() && isVideo(f.getName())) out.add(f);
        out.sort((x, y) -> natural(x.getName(), y.getName()));
        return out;
    }

    /** "Test.Bars.4x3.mp4" -> "Test Bars 4x3". */
    static String title(String fileName) {
        int i = fileName.lastIndexOf('.');
        String s = i > 0 ? fileName.substring(0, i) : fileName;
        if (!s.contains(" ")) s = s.replace('.', ' ').replace('_', ' ');
        return s.trim();
    }

    static String key(String name, long size) { return name + "|" + size; }

    static String label(File dir) {
        String rel = rel(dir);
        for (String[] r : ROOTS) if (r[0].equals(rel)) return r[1];
        return dir.getName();
    }

    static String rel(File f) {
        String base = storage().getAbsolutePath() + "/";
        String p = f.getAbsolutePath();
        return p.startsWith(base) ? p.substring(base.length()) : p;
    }

    static Vod.Playable playable(File f) {
        Vod.Playable p = Vod.Playable.local(Uri.fromFile(f).toString(), title(f.getName()),
                label(f.getParentFile()), key(f.getName(), f.length()));
        p.fileUri = p.localUri;
        return p;
    }

    /** Every video in the list as a Playable, each linked to the next for auto-play. */
    static Map<String, Vod.Playable> chain(List<File> files) {
        Map<String, Vod.Playable> m = new LinkedHashMap<>();
        Vod.Playable prev = null;
        for (File f : files) {
            Vod.Playable p = playable(f);
            if (prev != null) prev.next = p;
            prev = p;
            m.put(f.getAbsolutePath(), p);
        }
        return m;
    }

    /**
     * Finds the real file behind a content:// URI handed over by ES-DE (its provider paths end in the
     * storage path), so the next file in the folder can play afterwards. Null if it can't be found.
     */
    static File fileFor(Uri u, String name, long size) {
        String path = u.getPath();
        if (path == null) return null;
        String sd = storage().getAbsolutePath();                 // /storage/emulated/0
        int i = path.indexOf(sd);
        File f = null;
        if (i >= 0) f = new File(path.substring(i));
        else {
            int r = path.indexOf("/ROMs/");
            if (r >= 0) f = new File(sd + path.substring(r));
        }
        if (f == null) return null;
        if (name != null && !f.getName().equals(name)) return null;
        if (f.exists() && (size <= 0 || f.length() == size)) return f;
        return null;
    }

    /** All videos under the roots (for search), capped so a huge Download folder can't stall it. */
    static List<File> index() {
        List<File> out = new ArrayList<>();
        for (String[] r : roots()) walk(new File(storage(), r[0]), 0, out);
        return out;
    }

    private static void walk(File d, int depth, List<File> out) {
        if (depth > 5 || out.size() > 5000) return;
        out.addAll(videos(d));
        for (File s : dirs(d)) walk(s, depth + 1, out);
    }

    /** Case-insensitive order where "Ep 2" comes before "Ep 10". */
    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", ""), nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
