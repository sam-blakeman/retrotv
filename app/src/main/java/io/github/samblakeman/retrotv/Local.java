package io.github.samblakeman.retrotv;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Local video browsing using Play-Store-safe Android APIs:
 * 1) MediaStore (READ_MEDIA_VIDEO on API 33+, READ_EXTERNAL_STORAGE on API 26-32)
 * 2) User-picked folders via Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE), which also
 *    reaches folders containing .nomedia (such as emulator ROMs/video folders) and SD cards.
 */
final class Local {
    private Local() { }

    private static final List<String> EXT = Arrays.asList(
            "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mts", "mpg", "mpeg", "3gp", "flv", "ogv", "wmv", "vob", "divx");

    static final class Entry {
        final String uri, name, folder;
        final long size;
        final String bucketId;       // non-null for MediaStore items
        final String treeUri;        // non-null for SAF items
        final String parentDocId;    // non-null for SAF items

        Entry(String uri, String name, long size, String folder,
              String bucketId, String treeUri, String parentDocId) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.folder = folder;
            this.bucketId = bucketId;
            this.treeUri = treeUri;
            this.parentDocId = parentDocId;
        }
    }

    static final class MediaFolder {
        final String bucketId, name;
        final List<Entry> videos = new ArrayList<>();

        MediaFolder(String bucketId, String name) {
            this.bucketId = bucketId;
            this.name = name;
        }
    }

    static final class TreeDir {
        final String docId, name;

        TreeDir(String docId, String name) {
            this.docId = docId;
            this.name = name;
        }
    }

    static final class TreeListing {
        final List<TreeDir> dirs = new ArrayList<>();
        final List<Entry> videos = new ArrayList<>();
    }

    static String mediaPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    static boolean hasMediaAccess(Context c) {
        return c.checkSelfPermission(mediaPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isVideo(String name) {
        if (name == null) return false;
        int i = name.lastIndexOf('.');
        return i > 0 && !name.startsWith(".") && EXT.contains(name.substring(i + 1).toLowerCase(Locale.ROOT));
    }

    /** "Test.Bars.4x3.mp4" -> "Test Bars 4x3". */
    static String title(String fileName) {
        if (fileName == null) return "Video";
        int i = fileName.lastIndexOf('.');
        String s = i > 0 ? fileName.substring(0, i) : fileName;
        if (!s.contains(" ")) s = s.replace('.', ' ').replace('_', ' ');
        return s.trim();
    }

    static String key(String name, long size) { return name + "|" + size; }

    static Vod.Playable playable(Entry e) {
        Vod.Playable p = Vod.Playable.local(e.uri, title(e.name), e.folder, key(e.name, e.size));
        p.fileUri = e.uri;
        return p;
    }

    /** Every video in the list as a Playable, each linked to the next for auto-play. */
    static Map<String, Vod.Playable> chain(List<Entry> entries) {
        Map<String, Vod.Playable> m = new LinkedHashMap<>();
        Vod.Playable prev = null;
        for (Entry e : entries) {
            Vod.Playable p = playable(e);
            if (prev != null) prev.next = p;
            prev = p;
            m.put(e.uri, p);
        }
        return m;
    }

    // ---------------------------------------------------------------- MediaStore

    static List<MediaFolder> mediaFolders(Context c) {
        List<MediaFolder> out = new ArrayList<>();
        if (!hasMediaAccess(c)) return out;
        Map<String, MediaFolder> byBucket = new LinkedHashMap<>();
        String[] cols = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        };
        try (Cursor cur = c.getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null, null)) {
            if (cur == null) return out;
            int idI = cur.getColumnIndex(MediaStore.Video.Media._ID);
            int nameI = cur.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
            int sizeI = cur.getColumnIndex(MediaStore.Video.Media.SIZE);
            int bidI = cur.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
            int bnameI = cur.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
            while (cur.moveToNext()) {
                long id = cur.getLong(idI);
                String name = nameI >= 0 ? cur.getString(nameI) : null;
                if (name == null || name.isEmpty() || name.startsWith(".")) continue;
                long size = sizeI >= 0 ? cur.getLong(sizeI) : 0;
                String bid = bidI >= 0 ? cur.getString(bidI) : "0";
                if (bid == null) bid = "0";
                String bname = bnameI >= 0 ? cur.getString(bnameI) : null;
                if (bname == null || bname.isEmpty()) bname = "Videos";
                Uri u = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                MediaFolder f = byBucket.get(bid);
                if (f == null) byBucket.put(bid, f = new MediaFolder(bid, bname));
                f.videos.add(new Entry(u.toString(), name, size, bname, bid, null, null));
            }
        } catch (Exception ignored) {
        }
        for (MediaFolder f : byBucket.values()) {
            f.videos.sort((a, b) -> natural(a.name, b.name));
            out.add(f);
        }
        out.sort((a, b) -> natural(a.name, b.name));
        return out;
    }

    // ---------------------------------------------------------------- SAF tree folders

    static String treeLabel(Context c, Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
            try (Cursor cur = c.getContentResolver().query(
                    docUri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    String n = cur.getString(0);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
            int colon = docId.lastIndexOf(':');
            String tail = colon >= 0 ? docId.substring(colon + 1) : docId;
            if (tail.isEmpty()) return "Storage";
            int slash = tail.lastIndexOf('/');
            return slash >= 0 ? tail.substring(slash + 1) : tail;
        } catch (Exception e) {
            return "Folder";
        }
    }

    static String treeSubpath(Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            int colon = docId.indexOf(':');
            return colon >= 0 && colon + 1 < docId.length() ? docId.substring(colon + 1) : docId;
        } catch (Exception e) {
            return "";
        }
    }

    static TreeListing listTree(Context c, Uri treeUri, String parentDocId, String folderLabel) {
        TreeListing out = new TreeListing();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
        };
        try (Cursor cur = c.getContentResolver().query(childrenUri, cols, null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                String docId = cur.getString(0);
                String name = cur.getString(1);
                String mime = cur.getString(2);
                long size = cur.isNull(3) ? 0 : cur.getLong(3);
                if (docId == null || name == null || name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    out.dirs.add(new TreeDir(docId, name));
                } else if (isVideo(name) || (mime != null && mime.startsWith("video/"))) {
                    Uri u = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    out.videos.add(new Entry(u.toString(), name, size, folderLabel, null, treeUri.toString(), parentDocId));
                }
            }
        } catch (Exception ignored) {
        }
        out.dirs.sort((a, b) -> natural(a.name, b.name));
        out.videos.sort((a, b) -> natural(a.name, b.name));
        return out;
    }

    /** Indexes MediaStore videos + all user-picked SAF folders for Search (capped to stay fast). */
    static List<Entry> index(Context c, Set<String> treeUris) {
        List<Entry> out = new ArrayList<>();
        if (hasMediaAccess(c)) {
            for (MediaFolder f : mediaFolders(c)) {
                for (Entry e : f.videos) {
                    out.add(e);
                    if (out.size() >= 5000) return out;
                }
            }
        }
        for (String ts : treeUris) {
            if (out.size() >= 5000) break;
            try {
                Uri tree = Uri.parse(ts);
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                walkTree(c, tree, rootId, treeLabel(c, tree), 0, out);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void walkTree(Context c, Uri tree, String docId, String label, int depth, List<Entry> out) {
        if (depth > 5 || out.size() >= 5000) return;
        TreeListing l = listTree(c, tree, docId, label);
        for (Entry e : l.videos) {
            out.add(e);
            if (out.size() >= 5000) return;
        }
        for (TreeDir d : l.dirs) {
            walkTree(c, tree, d.docId, d.name, depth + 1, out);
            if (out.size() >= 5000) return;
        }
    }

    /**
     * Re-links a playable item (from Continue Watching, Search, or an external VIEW intent) to the
     * rest of its folder so the next episode/file plays automatically afterwards.
     */
    static Vod.Playable withNext(Context c, Vod.Playable p, Set<String> treeUris) {
        if (p == null || p.localUri == null) return p;
        // 1. Check MediaStore bucket if this is a MediaStore video URI
        if (p.localUri.startsWith(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString()) && hasMediaAccess(c)) {
            for (MediaFolder f : mediaFolders(c)) {
                Map<String, Vod.Playable> ch = chain(f.videos);
                Vod.Playable hit = ch.get(p.localUri);
                if (hit != null) return hit;
            }
            return p;
        }
        // 2. Check persisted SAF trees by URI or matching filename+size
        for (String ts : treeUris) {
            try {
                Uri tree = Uri.parse(ts);
                Vod.Playable hit = findInTree(c, tree, DocumentsContract.getTreeDocumentId(tree),
                        treeLabel(c, tree), p.localUri, p.localKey, 0);
                if (hit != null) return hit;
            } catch (Exception ignored) {
            }
        }
        return p;
    }

    private static Vod.Playable findInTree(Context c, Uri tree, String docId, String label,
                                           String wantUri, String wantKey, int depth) {
        if (depth > 5) return null;
        TreeListing l = listTree(c, tree, docId, label);
        for (Entry e : l.videos) {
            if (e.uri.equals(wantUri) || (wantKey != null && key(e.name, e.size).equals(wantKey))) {
                return chain(l.videos).get(e.uri);
            }
        }
        for (TreeDir d : l.dirs) {
            Vod.Playable hit = findInTree(c, tree, d.docId, d.name, wantUri, wantKey, depth + 1);
            if (hit != null) return hit;
        }
        return null;
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
