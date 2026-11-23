package io.github.samblakeman.retrotv;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guide data, fetched lazily per channel for the rows on screen only, by two gentle worker
 * threads (Xtream providers block clients that hammer the API). Results kept for 30 minutes.
 */
final class EpgStore {
    interface Listener { void onEpg(String streamId); }

    private static final long FRESH_MS = 30 * 60_000L;
    private static final long RETRY_MS = 3 * 60_000L;
    private static final int LIMIT = 24;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, List<Xtream.Programme>> data = new HashMap<>();
    private final Map<String, Long> fetchedAt = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private final Set<String> loading = new HashSet<>();
    private final LinkedHashMap<String, Xtream.Channel> pending = new LinkedHashMap<>();
    private final List<Thread> workers = new ArrayList<>();
    private Xtream xt;
    private Listener listener;
    private boolean stopped;

    EpgStore() {
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(this::work, "epg-" + i);
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
    }

    synchronized void setXtream(Xtream x) {
        if (x != xt) {
            xt = x;
            data.clear();
            fetchedAt.clear();
            failed.clear();
            pending.clear();
        }
    }

    void setListener(Listener l) { listener = l; }

    /** null = not loaded yet; empty = provider has no guide for this channel. */
    synchronized List<Xtream.Programme> get(String streamId) { return data.get(streamId); }

    synchronized boolean isLoading(String streamId) {
        return loading.contains(streamId) || pending.containsKey(streamId);
    }

    /** Replaces the work queue with these channels (the visible rows, nearest first). */
    synchronized void want(List<Xtream.Channel> visible) {
        pending.clear();
        long now = System.currentTimeMillis();
        for (Xtream.Channel c : visible) {
            if (loading.contains(c.id)) continue;
            Long t = fetchedAt.get(c.id);
            boolean need = t == null || now - t > (failed.contains(c.id) ? RETRY_MS : FRESH_MS);
            if (need) pending.put(c.id, c);
        }
        if (!pending.isEmpty()) notifyAll();
    }

    synchronized void shutdown() {
        stopped = true;
        notifyAll();
        for (Thread t : workers) t.interrupt();
    }

    private void work() {
        while (true) {
            Xtream.Channel c;
            Xtream x;
            synchronized (this) {
                while (!stopped && (pending.isEmpty() || xt == null)) {
                    try { wait(); } catch (InterruptedException e) { return; }
                }
                if (stopped) return;
                String id = pending.keySet().iterator().next();
                c = pending.remove(id);
                loading.add(id);
                x = xt;
            }
            List<Xtream.Programme> res = null;
            try {
                res = x.epg(c, LIMIT);
            } catch (Exception ignored) {
            }
            final List<Xtream.Programme> r = res;
            synchronized (this) {
                loading.remove(c.id);
                if (x == xt) {
                    fetchedAt.put(c.id, System.currentTimeMillis());
                    if (r == null) {
                        failed.add(c.id);
                        if (!data.containsKey(c.id)) data.put(c.id, new ArrayList<>());
                    } else {
                        failed.remove(c.id);
                        data.put(c.id, r);
                    }
                }
            }
            ui.post(() -> { if (listener != null) listener.onEpg(c.id); });
            try { Thread.sleep(120); } catch (InterruptedException e) { return; }
        }
    }
}
