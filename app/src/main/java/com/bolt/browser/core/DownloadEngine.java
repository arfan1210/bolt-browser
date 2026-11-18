package com.bolt.browser.core;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.URLUtil;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.bolt.browser.BoltApp;
import com.bolt.browser.R;
import com.bolt.browser.ui.DownloadsActivity;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bolt's own download engine — replaces the system DownloadManager entirely
 * (which failed on SAF destinations because the provider process cannot write
 * to content URIs granted to this app).
 *
 * Features: PARALLEL download queue (v1.3.90: up to 3 items run at once;
 * the v1.3.89-era single worker serialized everything — "no parallel
 * downloads"), with pause/resume/retry, cookie + UA + referer aware
 * requests, Range-resume against a .part file, MediaStore / SAF /
 * public-folder destinations, data: URL saving, aggregate progress
 * notification held alive by a dataSync foreground service.
 */
public class DownloadEngine {

    public static final int ST_QUEUED = 0, ST_RUNNING = 1, ST_PAUSED = 2,
            ST_DONE = 3, ST_FAILED = 4;

    public static class Item {
        public long id;
        public String url = "", name = "", mime = "application/octet-stream";
        public String ct = ""; // Content-Type of the final HTTP response (transient)
        public String cdName = ""; // v1.3.86 — filename from the final response's Content-Disposition (transient)
        public String lastUrl = ""; // v1.3.90 — final URL after the redirect chain (transient; the path-extension rescue reads it)
        public String ua = "", referer = "";
        // v1.4.13 — the validator for SAFE resume: the ETag of the response
        // the .part file was downloaded from. Sent back as If-Range on the
        // resume request, so a server-side file change answers 200 (full
        // body — the existing restart path replaces the part) instead of a
        // 206 that would silently APPEND two different files into one
        // corrupt download. Empty when the server sent no ETag — resume
        // then behaves exactly as before (Range only, server's discretion).
        public String etag = "";
        public int status = ST_QUEUED;
        public long done = 0, total = 0;
        public long speed = 0;   // bytes/s while RUNNING (transient, not persisted)
        public String finalUri = "", error = "";
        public long time = System.currentTimeMillis();
        // transient control flags (not persisted)
        public volatile boolean cancelReq = false, pauseReq = false;

        JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", id); o.put("url", url); o.put("name", name); o.put("mime", mime);
                o.put("ua", ua); o.put("ref", referer); o.put("st", status);
                o.put("done", done); o.put("total", total); o.put("uri", finalUri);
                o.put("err", error); o.put("t", time);
                o.put("etag", etag == null ? "" : etag);   // v1.4.13: survives the pause
                return o;
            } catch (Throwable e) { return new JSONObject(); }
        }

        static Item fromJson(JSONObject o) {
            Item it = new Item();
            it.id = o.optLong("id");
            it.url = o.optString("url");
            it.name = o.optString("name");
            it.mime = o.optString("mime", "application/octet-stream");
            it.ua = o.optString("ua");
            it.referer = o.optString("ref");
            it.status = o.optInt("st", ST_FAILED);
            it.done = o.optLong("done");
            it.total = o.optLong("total");
            it.finalUri = o.optString("uri");
            it.etag = o.optString("etag");   // v1.4.13: the If-Range validator rides the pause
            it.error = o.optString("err");
            it.time = o.optLong("t", System.currentTimeMillis());
            return it;
        }
    }

    public interface Listener { void onDownloadsChanged(); }

    /** v1.3.19: fired once per completed download (main thread) — drives the
     *  in-app "Open file?" popup in the browser UI. STATIC registration:
     *  callers must never be forced to construct the engine (the constructor
     *  revives dead downloads and can start the download service — that must
     *  stay off the cold-start path, same rationale as purgeStaleParts). */
    public interface CompletionListener { void onDownloadComplete(Item it); }
    private static final CopyOnWriteArrayList<CompletionListener> completionListeners =
            new CopyOnWriteArrayList<>();

    public static void addCompletionListener(CompletionListener l) {
        if (l != null && !completionListeners.contains(l)) completionListeners.add(l);
    }

    public static void removeCompletionListener(CompletionListener l) { completionListeners.remove(l); }
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private static DownloadEngine instance;
    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();
    private final Object lock = new Object();
    // v1.3.90 — THE PARALLEL POOL: the old single-thread executor ran ONE
    // item at a time (loop() drained the queue serially), so a slow server
    // or a mid-transfer retry-sleep stalled every download behind it — the
    // field report: "no parallel downloads". The pool keeps 3 worker
    // threads; pump() claims QUEUED items (QUEUED->RUNNING, under the same
    // lock pause()/cancel() use) and dispatches one per worker. 3 = "parallel
    // enough" without hammering mobile radios (each worker is its own
    // HttpURLConnection with its own 20s/30s timeouts; more streams just
    // split the same bandwidth and multiply the timeout storms).
    static final int MAX_PARALLEL = 3;
    private final ExecutorService pool = Executors.newFixedThreadPool(MAX_PARALLEL, r -> {
        Thread t = new Thread(r, "bolt-downloads");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final java.util.concurrent.atomic.AtomicInteger running =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private BroadcastReceiver receiver;
    private long lastUiNotify = 0, lastNotifNotify = 0;
    private boolean serviceOn = false;

    private DownloadEngine(Context c) {
        ctx = c.getApplicationContext();
        // CRITICAL: the partial-file directory MUST exist before the first
        // FileOutputStream(part) — on v1.3.7 it was only created by the data:
        // URL path (saveBytes), so EVERY http download died with
        // FileNotFoundException (ENOENT) -> "all downloads fail".
        try {
            File parts = new File(ctx.getCacheDir(), "dlparts");
            if (!parts.exists()) //noinspection ResultOfMethodCallIgnored
                parts.mkdirs();
        } catch (Throwable ignored) {}
        load();
        registerReceiver();
        // a previous process died mid-download: revive those as queued
        boolean revive = false;
        synchronized (lock) {
            for (Item it : items) {
                if (it.status == ST_RUNNING) { it.status = ST_QUEUED; revive = true; }
            }
        }
        if (revive) pump();
    }

    public static synchronized DownloadEngine get(Context c) {
        if (instance == null) instance = new DownloadEngine(c);
        return instance;
    }

    /** v1.3.40 download shelf: TRUE only when the engine is already alive —
     *  never constructs it (a cold-start construction would revive dead
     *  downloads and spin up the foreground service; same rule as
     *  purgeStaleParts above). */
    public static boolean exists() { return instance != null; }

    /** Storage hygiene, called at app start (v1.3.12): delete .part files in
     *  the cache staging dir that no longer belong to any tracked item —
     *  orphans from a killed process or from items aged out of the 200-entry
     *  history — plus any part older than 7 days. Failed-download residue must
     *  never accumulate: everything transient already lives in the CACHE dir
     *  (never app data), so "Clear cache" wipes it AND this purge keeps it
     *  bounded between clears. Static on purpose: must not construct the
     *  engine (that could start foreground services on cold start). */
    public static void purgeStaleParts(Context c) {
        try {
            Context app = c.getApplicationContext();
            java.util.Set<Long> known = new java.util.HashSet<>();
            try {
                JSONArray a = Fx.readJsonArray(new File(app.getFilesDir(), "downloads.json"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) known.add(o.optLong("id"));
                }
            } catch (Throwable ignored) {}
            File dir = new File(app.getCacheDir(), "dlparts");
            File[] fs = dir.listFiles();
            if (fs == null) return;
            long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
            for (File f : fs) {
                try {
                    if (!f.isFile()) continue;
                    String n = f.getName();
                    int dot = n.lastIndexOf('.');
                    if (dot <= 0 || !"part".equals(n.substring(dot + 1))) continue;
                    long id = -1;
                    try { id = Long.parseLong(n.substring(0, dot)); } catch (Throwable ignored) {}
                    if (!known.contains(id) || f.lastModified() < cutoff) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    // ------------------------------------------------------------ persistence

    private File queueFile() { return new File(ctx.getFilesDir(), "downloads.json"); }
    private File partFile(Item it) {
        File dir = new File(ctx.getCacheDir(), "dlparts");
        if (!dir.exists()) { try { //noinspection ResultOfMethodCallIgnored
            dir.mkdirs(); } catch (Throwable ignored) {} }
        return new File(dir, it.id + ".part");
    }

    private void load() {
        try {
            JSONArray a = Fx.readJsonArray(queueFile());
            synchronized (lock) {
                items.clear();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) items.add(Item.fromJson(o));
                }
            }
        } catch (Throwable ignored) {}
    }

    private void persist() {
        try {
            JSONArray a = new JSONArray();
            synchronized (lock) {
                // keep history bounded: done/failed items older than the newest 150
                for (Item it : items) a.put(it.toJson());
            }
            while (a.length() > 200) a.remove(a.length() - 1);
            Fx.writeJsonArray(queueFile(), a);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ public API

    /** Enqueue an http/https download. Returns the item id. */
    public long enqueue(String url, String name, String mime, String ua, String referer) {
        if (url == null) return -1;
        Item it = new Item();
        it.id = System.currentTimeMillis() * 10 + (long) (Math.random() * 9);
        it.url = url;
        it.name = safeName(name == null || name.isEmpty() ? guessName(url, mime) : name);
        it.mime = mime == null || mime.isEmpty() || mime.contains("*") ? "application/octet-stream" : mime;
        it.ua = ua == null ? "" : ua;
        it.referer = referer == null ? "" : referer;
        if (it.mime.equalsIgnoreCase("image/jpg")) it.mime = "image/jpeg";
        // .bin was the #1 download complaint (Google Images etc.): a URL like
        // encrypted-tbn0.gstatic.com/images?q=... guesses the name "images"
        // with NO extension, and with a generic mime the SAF provider appends
        // ".bin" at createDocument time. Give the name a provisional extension
        // from the mime when we have one; the real Content-Type (and magic-byte
        // sniffing) refines it again before the file is written.
        it.name = ensureExt(it.name, it.mime);
        synchronized (lock) { items.add(0, it); }
        persist();
        notifyUi(true);
        pump();
        return it.id;
    }

    /** Save a data: URL (or a blob fetched JS-side into a data URL). */
    public long saveDataUrl(String dataUrl, String name, String mimeHint) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return -1;
        Item it = new Item();
        it.id = System.currentTimeMillis() * 10 + (long) (Math.random() * 9);
        it.url = dataUrl;
        String[] meta = dataUrl.substring(5).split(",", 2);
        if (meta.length < 2) return -1;
        String mt = meta[0].toLowerCase();
        if (mt.contains(";")) mt = mt.substring(0, mt.indexOf(';'));
        it.mime = mt.isEmpty() ? (mimeHint == null ? "application/octet-stream" : mimeHint) : mt;
        if (it.mime.contains("*")) it.mime = "application/octet-stream";
        it.name = safeName(name == null || name.isEmpty() ? "file" : name);
        // data: URLs carry their real mime in the header — use it to pick the
        // extension instead of the old ".bin" fallback.
        it.name = ensureExt(it.name, it.mime);
        synchronized (lock) { items.add(0, it); }
        persist();
        notifyUi(true);
        pump();
        return it.id;
    }

    public void pause(long id) {
        Item it = byId(id);
        if (it != null && (it.status == ST_RUNNING || it.status == ST_QUEUED)) {
            it.pauseReq = true;
            if (it.status == ST_QUEUED) { it.status = ST_PAUSED; notifyUi(true); }
        }
    }

    public void resume(long id) {
        Item it = byId(id);
        if (it != null && (it.status == ST_PAUSED || it.status == ST_FAILED)) {
            it.pauseReq = false; it.cancelReq = false; it.error = "";
            it.status = ST_QUEUED;
            persist(); notifyUi(true); pump();
        }
    }

    public void cancel(long id) {
        Item it = byId(id);
        if (it == null) return;
        if (it.status == ST_RUNNING) {
            it.cancelReq = true;
        } else {
            removeItem(it);
        }
    }

    public void retry(long id) { resume(id); }

    public void delete(long id) {
        Item it = byId(id);
        if (it == null) return;
        if (it.status == ST_RUNNING) { it.cancelReq = true; return; }
        removeItem(it);
    }

    /** remove a finished/cancelled item and its partial file */
    public void removeItem(Item it) {
        synchronized (lock) { items.remove(it); }
        try { partFile(it).delete(); } catch (Throwable ignored) {}
        persist(); notifyUi(true);
    }

    public void cancelAllActive() {
        synchronized (lock) {
            for (Item it : items) {
                if (it.status == ST_RUNNING || it.status == ST_QUEUED) cancel(it.id);
            }
        }
    }

    public Item byId(long id) {
        synchronized (lock) {
            for (Item it : items) if (it.id == id) return it;
        }
        return null;
    }

    /** snapshot, newest first */
    public List<Item> list() {
        synchronized (lock) { return new ArrayList<>(items); }
    }

    public boolean anyActive() {
        synchronized (lock) {
            for (Item it : items) if (it.status == ST_RUNNING || it.status == ST_QUEUED) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ worker

    /** v1.3.90 — THE DISPATCHER. Claims the next QUEUED item (under the
     *  same lock pause()/cancel() hold, so a pause racing the claim sees a
     *  consistent state) and hands one item per free worker slot, up to
     *  MAX_PARALLEL. Each worker's finally re-pumps, so a finishing item
     *  immediately admits the next one; enqueue/resume/revive call this
     *  too. Replaces the old single-thread loop() — one slow transfer no
     *  longer starves the queue behind it ("no parallel downloads"). */
    private void pump() {
        try {
            while (running.get() < MAX_PARALLEL) {
                final Item it;
                synchronized (lock) {
                    it = nextQueued();
                    if (it == null) break;
                    it.status = ST_RUNNING;   // claim: nextQueued must skip it
                }
                running.incrementAndGet();
                try {
                    pool.execute(() -> {
                        try {
                            process(it);
                        } finally {
                            running.decrementAndGet();
                            // the slot freed: admit the next queued item
                            pump();
                            ui.post(() -> { if (!anyActive()) stopDlService(); });
                        }
                    });
                } catch (Throwable t) {
                    // pool rejected (shutdown racing app teardown): roll back
                    running.decrementAndGet();
                    synchronized (lock) {
                        if (it.status == ST_RUNNING) it.status = ST_QUEUED;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private Item nextQueued() {
        synchronized (lock) {
            for (int i = items.size() - 1; i >= 0; i--) {
                Item it = items.get(i);
                if (it.status == ST_QUEUED) return it;
            }
        }
        return null;
    }

    private void process(final Item it) {
        // (status is already ST_RUNNING — pump() claimed it under the lock)
        it.speed = 0;
        persist();
        notifyUi(true);
        ui.post(this::startDlService);
        File part = partFile(it); // partFile() guarantees the parent dir exists
        boolean paused = false, cancelled = false;
        try {
            if (it.url.startsWith("data:")) {
                byte[] bytes = decodeDataUrl(it.url);
                it.total = bytes.length;
                it.done = 0;
                refineBytes(it, bytes);
                Uri u = saveBytes(it, bytes);
                it.finalUri = u == null ? "" : u.toString();
                it.status = ST_DONE;
                try { part.delete(); } catch (Throwable ignored) {}
            } else {
                // v1.3.90 — 4 attempts (was 3): the field's "a lot of
                // failed downloads" were dominated by flaky mobile-network
                // moments; with the parallel pool a sleeping retry no longer
                // stalls the whole queue, so one more honest retry is cheap.
                for (int attempt = 0; attempt < 4; attempt++) {
                    if (it.cancelReq) break;
                    try {
                        downloadHttp(it, part);
                        if (it.pauseReq) { paused = true; break; }
                        if (it.cancelReq) { cancelled = true; break; }
                        // v1.3.90 — THE HTML-GARBAGE GUARD: an expired token
                        // or a session-loss redirect lands the ENGINE's
                        // re-request on a 200 HTML page (the WebView saw the
                        // file; our own HttpURLConnection sees the login/
                        // error wall). Saving that page under a .zip/.apk
                        // name is the "messed up files" failure mode — fail
                        // honestly instead. Only when the server NOW says
                        // text/html while the download was NEVER meant to be
                        // a page (enqueue mime a known non-HTML type).
                        if ("text/html".equals(it.ct)
                                && it.mime != null && !it.mime.isEmpty()
                                && !it.mime.contains("*")
                                && !"application/octet-stream".equals(it.mime)
                                && !"binary/octet-stream".equals(it.mime)
                                && !"application/binary".equals(it.mime)
                                && !"text/html".equals(it.mime)
                                && !"application/xhtml+xml".equals(it.mime)
                                && !it.mime.startsWith("text/")) {
                            throw new GarbageException();
                        }
                        // real Content-Type + magic-byte sniffing + extension fix
                        // BEFORE the destination document is created (the name on
                        // the createDocument call decides the final extension)
                        refineFile(it, part);
                        // finished: move part to final destination
                        Uri u = savePart(it, part);
                        it.finalUri = u == null ? "" : u.toString();
                        it.status = ST_DONE;
                        try { part.delete(); } catch (Throwable ignored) {}
                        break;
                    } catch (PauseException pe) {
                        paused = true; break;
                    } catch (CancelException ce) {
                        cancelled = true; break;
                    } catch (GarbageException ge) {
                        // deterministic: the link/session is dead — retrying
                        // just sleeps and re-saves the same login page
                        it.error = "site returned a web page, not the file "
                                + "(session/link expired?)";
                        it.status = ST_FAILED;
                        break;
                    } catch (IOException e) {
                        if (it.cancelReq) { cancelled = true; break; }
                        if (attempt >= 3) {
                            it.error = String.valueOf(e.getMessage() == null ? e : e.getMessage());
                            it.status = ST_FAILED;
                        } else {
                            try { Thread.sleep(1200L * (attempt + 1)); } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt(); return; }
                        }
                    }
                }
            }
            if (paused) { it.status = ST_PAUSED; }
            else if (cancelled) { removeItem(it); return; }
            else if (it.status != ST_DONE && it.status != ST_FAILED) {
                it.status = it.cancelReq ? ST_FAILED : ST_PAUSED;
            }
        } catch (Throwable e) {
            it.error = String.valueOf(e);
            it.status = ST_FAILED;
        }
        persist();
        notifyUi(true);
        it.speed = 0; // stale speeds must never leak into paused/done/failed rows
        if (it.status == ST_DONE) { notifyDone(it); fireComplete(it); }
        else if (it.status == ST_FAILED) notifyDone(it);
    }

    private static class PauseException extends RuntimeException {}
    private static class CancelException extends RuntimeException {}
    /** v1.3.90 — the final response was an HTML page where a binary file
     *  was promised: fail once, no retries (see the HTML-garbage guard). */
    private static class GarbageException extends IOException {}

    private void downloadHttp(Item it, File part) throws IOException, PauseException, CancelException {
        HttpURLConnection conn = null;
        String url = it.url;
        boolean resume = part.exists() && part.length() > 0;
        // manual redirect loop (handles http<->https which auto-follow misses)
        for (int hop = 0; hop < 8; hop++) {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", it.ua == null || it.ua.isEmpty()
                    ? "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
                    : it.ua);
            if (it.referer != null && it.referer.startsWith("http"))
                conn.setRequestProperty("Referer", it.referer);
            try {
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null && !cookies.isEmpty()) conn.setRequestProperty("Cookie", cookies);
            } catch (Throwable ignored) {}
            conn.setRequestProperty("Accept", "*/*");
            // v1.4.13 — THE RESUME CONTRACT: a .part file on disk means the
            // download picks up at that exact byte (Range, below) — but only
            // when the file on the SERVER has not changed since the part was
            // written. If-Range carries the ETag captured from the response
            // the part came from: an unchanged file answers 206 (append), a
            // CHANGED file answers 200 (the existing restart path replaces
            // the part) — without it, a changed file that still honors Range
            // would splice two different bodies into one corrupt download.
            if (resume) {
                conn.setRequestProperty("Range", "bytes=" + part.length() + "-");
                if (it.etag != null && !it.etag.isEmpty())
                    conn.setRequestProperty("If-Range", it.etag);
            }
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();   // v1.3.90: 3xx hops leaked a connection each
                if (loc == null) throw new IOException("redirect without location");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code == 206) {
                // partial: keep appending to part
            } else if (code == 200 || code == 201 || code == 204) {
                if (resume) { // server ignored Range (or If-Range said CHANGED) -> restart
                    part.delete();
                    resume = false;
                    it.etag = "";   // v1.4.13: the part's validator died with the part; the fresh capture below re-arms it
                }
            } else if (code == 416 && resume) {
                // v1.3.90 — RANGE NOT SATISFIABLE: the part already holds
                // everything the server is willing to give for this Range —
                // classically a finished body whose save was interrupted, or
                // a changed file. The old path threw "HTTP 416" and burned
                // all retries against the same wall ("a lot of failed
                // downloads"). Drop the part and take the transfer fresh.
                conn.disconnect();
                part.delete();
                resume = false;
                it.etag = "";   // v1.4.13: same law as the 200 restart — no part, no validator
                it.done = 0;
                continue;   // (no Range sent on the next pass, so 416 cannot repeat; the hop budget bounds it)
            } else {
                conn.disconnect();
                throw new IOException("HTTP " + code);
            }
            long contentLen = conn.getContentLength();
            // capture the REAL content type of the final response — the enqueue
            // mime is a guess (often generic), and a generic mime is exactly
            // what makes SAF/MediaStore name files ".bin"
            try {
                String ct = conn.getContentType();
                if (ct != null) {
                    ct = ct.trim().toLowerCase();
                    int sc = ct.indexOf(';');
                    if (sc > 0) ct = ct.substring(0, sc).trim();
                    if (!ct.isEmpty()) it.ct = ct;
                }
            } catch (Throwable ignored) {}
            // v1.3.86 — the server's own filename: the WebView listener's
            // contentDisposition guess never reaches this engine connection
            // (the dialog confirmed a URL-derived name), so a server that
            // DOES name the file was overridden by a UUID/hash guess. Kept
            // here; refineFile swaps it in when the confirmed name looks
            // machine-generated.
            try {
                String fn = cdFilename(conn.getHeaderField("Content-Disposition"));
                if (!fn.isEmpty()) it.cdName = fn;
            } catch (Throwable ignored) {}
            // v1.4.13 — capture the validator for the NEXT resume: every
            // final response's ETag (206 or 200) is stored on the item and
            // persisted, so a pause/kill followed by resume can prove the
            // part still matches the server's current file. No ETag -> the
            // item keeps its old one only while the part survives untouched
            // by a 200 restart (the restart path below clears it with the part).
            try {
                String et = conn.getHeaderField("ETag");
                if (et != null && !et.trim().isEmpty()) it.etag = et.trim();
            } catch (Throwable ignored) {}
            it.lastUrl = url;   // v1.3.90: the post-redirect URL feeds the path-extension rescue
            it.total = resume ? part.length() + Math.max(0, contentLen) : Math.max(0, contentLen);
            if (it.total <= 0) it.total = 0;
            it.done = resume ? part.length() : 0;
            notifyUi(false);
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) throw new IOException("no response stream (HTTP " + code + ")");
            try (OutputStream out = new FileOutputStream(part, resume)) {
                byte[] buf = new byte[16384];
                int n;
                long lastStamp = System.currentTimeMillis(), lastBytes = it.done, lastNotify = 0;
                while ((n = in.read(buf)) > 0) {
                    if (it.cancelReq) { out.flush(); throw new CancelException(); }
                    if (it.pauseReq) { out.flush(); throw new PauseException(); }
                    out.write(buf, 0, n);
                    it.done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 400) {
                        lastNotify = now;
                        notifyUi(false);
                    }
                    if (now - lastStamp > 1000) {
                        it.speed = Math.max(0, (it.done - lastBytes) * 1000
                                / Math.max(1, now - lastStamp));
                        lastStamp = now; lastBytes = it.done;
                    }
                }
                out.flush();
            }
            conn.disconnect();
            return; // stream fully read
        }
        throw new IOException("too many redirects");
    }

    private static byte[] decodeDataUrl(String dataUrl) throws IOException {
        String[] meta = dataUrl.substring(5).split(",", 2);
        if (meta.length < 2) throw new IOException("bad data url");
        boolean b64 = meta[0].toLowerCase().contains("base64");
        try {
            if (b64) return android.util.Base64.decode(meta[1], android.util.Base64.DEFAULT);
            return meta[1].getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable e) {
            throw new IOException("bad data url payload");
        }
    }

    // ------------------------------------------------------------ destinations

    /** write the .part file into the chosen destination, resolving its final Uri */
    private Uri savePart(Item it, File part) throws IOException {
        it.total = part.length();
        String dd = Prefs.get(ctx).downloadDir();
        if (dd != null && !dd.isEmpty()) {
            try {
                Uri tree = Uri.parse(dd);
                String docId = DocumentsContract.getTreeDocumentId(tree);
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                String mime = it.mime == null ? "application/octet-stream" : it.mime;
                Uri target;
                try {
                    target = DocumentsContract.createDocument(ctx.getContentResolver(), doc, mime, it.name);
                } catch (Throwable dup) {
                    target = DocumentsContract.createDocument(ctx.getContentResolver(), doc, mime,
                            numbered(it.name));
                }
                if (target == null) throw new IOException("SAF create failed");
                // v1.4.16 — THE 0KB CORPSE LAW: createDocument already MADE
                // the destination file; a copy failure used to fall through
                // to the next destination leaving the empty document behind,
                // so every retry hit the duplicate-name wall and the user
                // collected "name", "name (1)", "name (2)" corpses (the
                // "download 0KB + (1) duplicate" report). Delete the created
                // URI before the fall-through — a retry starts clean.
                try (InputStream in = new FileInputStream(partFile(it));
                     OutputStream out = ctx.getContentResolver().openOutputStream(target, "wt")) {
                    if (out == null) throw new IOException("SAF stream closed");
                    copy(in, out);
                } catch (Throwable copyFail) {
                    try { ctx.getContentResolver().delete(target, null, null); }
                    catch (Throwable ignored) {}
                    throw copyFail;
                }
                return target;
            } catch (Throwable e) {
                // fall through to the default destinations
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, it.name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE,
                        it.mime == null ? "application/octet-stream" : it.mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri dir = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                Uri row = ctx.getContentResolver().insert(dir, cv);
                if (row == null) throw new IOException("MediaStore insert failed");
                // v1.4.16 — the 0KB corpse law, MediaStore edition: the
                // insert already created the (IS_PENDING) row; a failed copy
                // must delete it instead of leaving a 0KB pending corpse —
                // same law as the SAF branch above.
                try (InputStream in = new FileInputStream(partFile(it));
                     OutputStream out = ctx.getContentResolver().openOutputStream(row)) {
                    if (out == null) throw new IOException("MediaStore stream closed");
                    copy(in, out);
                } catch (Throwable copyFail) {
                    try { ctx.getContentResolver().delete(row, null, null); }
                    catch (Throwable ignored) {}
                    throw copyFail;
                }
                ContentValues fin = new ContentValues();
                fin.put(MediaStore.MediaColumns.IS_PENDING, 0);
                ctx.getContentResolver().update(row, fin, null, null);
                return row;
            } catch (Throwable e) {
                // fall through to plain files
            }
        }
        // API 24-28 (or fallback): public Downloads folder when writable, else app dir
        File dir = Environment_getPublicDownloads();
        if (dir != null && dir.canWrite()) {
            File out = uniqueFile(dir, it.name);
            // v1.4.16 — the 0KB corpse law, plain-file edition: a failed
            // copy deletes the partial File so uniqueFile cannot hand the
            // NEXT attempt a "name (1)" slot beside a dead "name".
            try (InputStream in = new FileInputStream(part);
                 OutputStream outS = new FileOutputStream(out)) {
                copy(in, outS);
            } catch (Throwable copyFail) {
                try { out.delete(); } catch (Throwable ignored) {}
                throw copyFail;
            }
            scan(out);
            return Uri.fromFile(out);
        }
        File appDir = ctx.getExternalFilesDir("Download");
        if (appDir == null) appDir = new File(ctx.getFilesDir(), "Download");
        //noinspection ResultOfMethodCallIgnored
        appDir.mkdirs();
        File out = uniqueFile(appDir, it.name);
        try (InputStream in = new FileInputStream(part);
             OutputStream outS = new FileOutputStream(out)) {
            copy(in, outS);
        }
        scan(out);
        return Uri.fromFile(out);
    }

    /** write raw bytes (data: URLs) into the chosen destination */
    private Uri saveBytes(Item it, byte[] bytes) throws IOException {
        it.total = bytes.length;
        File tmp = partFile(it);
        try {
            if (tmp.getParentFile() != null) //noinspection ResultOfMethodCallIgnored
                tmp.getParentFile().mkdirs();
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(bytes);
            }
            return savePart(it, tmp);
        } finally {
            try { tmp.delete(); } catch (Throwable ignored) {}
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private File Environment_getPublicDownloads() {
        try {
            return android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        } catch (Throwable e) {
            return null;
        }
    }

    private void scan(File f) {
        try {
            android.media.MediaScannerConnection.scanFile(ctx,
                    new String[]{f.getAbsolutePath()}, null, null);
        } catch (Throwable ignored) {}
    }

    private File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 1; i < 500; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private String numbered(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name + " " + System.currentTimeMillis();
        return name.substring(0, dot) + " " + System.currentTimeMillis() + name.substring(dot);
    }

    private String guessName(String url, String mime) {
        try {
            String g = URLUtil.guessFileName(url, null, mime);
            if (g == null || g.isEmpty()) g = "download";
            return humanName(url, g);
        } catch (Throwable e) {
            return "download";
        }
    }

    /** v1.3.78 — CDN names are machine tokens, and a download list full of
     *  "a7Xk92jZq3mLpQ4tR" is unreadable ("downloads have a random string
     *  of characters for name"). When the guessed base name looks like a
     *  hash token (>=16 chars, letters+digits mixed, almost no vowels —
     *  real words have ~40% vowels, base64 tokens ~10%), swap it for
     *  host-YYYYMMDD-HHMM: sortable, speakable, still unique per minute.
     *  Real names ("installer", "github-desktop") keep their vowels and
     *  survive untouched. v1.3.86: public + split — the dialog path calls
     *  it for its default name (blob UUIDs read as random strings), and
     *  machineNamed() gates the Content-Disposition swap. */
    public static String humanName(String url, String guessed) {
        try {
            if (!machineNamed(guessed)) return guessed;
            int dot = guessed.lastIndexOf('.');
            String ext = dot > 0 ? guessed.substring(dot) : "";
            String host = "";
            try {
                java.net.URL u = new java.net.URL(url);
                host = u.getHost() == null ? "" : u.getHost().toLowerCase();
                if (host.startsWith("www.")) host = host.substring(4);
                int cut = host.indexOf('.');
                if (cut > 0) host = host.substring(0, cut);
            } catch (Throwable ignored) {}
            if (host.isEmpty()) host = "file";
            java.text.SimpleDateFormat df =
                    new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US);
            return host + "-" + df.format(new java.util.Date()) + ext;
        } catch (Throwable e) {
            return guessed;
        }
    }

    /** v1.3.86 — the machine-token detector split out of humanName: >=16
     *  chars of letters/digits/-_ with both cases present (digits+alpha)
     *  and too few vowels to be a real word. Blob UUIDs (36 chars, 4
     *  hyphens, ~1 vowel) and CDN hash tokens qualify; user-typed and
     *  site-provided names do not. */
    public static boolean machineNamed(String guessed) {
        try {
            if (guessed == null) return false;
            int dot = guessed.lastIndexOf('.');
            String base = dot > 0 ? guessed.substring(0, dot) : guessed;
            if (base.length() < 16) return false;
            boolean hasDigit = false, hasAlpha = false;
            int vowels = 0;
            for (int i = 0; i < base.length(); i++) {
                char c = base.charAt(i);
                if (Character.isDigit(c)) hasDigit = true;
                else if (Character.isLetter(c)) {
                    hasAlpha = true;
                    if ("aeiouAEIOU".indexOf(c) >= 0) vowels++;
                } else if (c != '-' && c != '_') {
                    return false;   // spaces/punct = a human named it
                }
            }
            if (!hasDigit || !hasAlpha) return false;
            if (vowels * 4 > base.length()) return false;  // wordy = real name
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------ type refinement
    // (fixes "downloads become .bin files": names without an extension +
    //  generic application/octet-stream mimes make SAF/MediaStore append .bin)

    private static boolean isGenericMime(String m) {
        return m == null || m.isEmpty() || m.contains("*")
                || "application/octet-stream".equals(m)
                || "binary/octet-stream".equals(m)
                || "application/binary".equals(m);
    }

    /** extension for a mime type, with a supplement for types the platform
     *  map doesn't know (or knows differently). */
    private static String extForMime(String mime) {
        if (isGenericMime(mime)) return "";
        String ext = "";
        try {
            ext = android.webkit.MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mime);
        } catch (Throwable ignored) {}
        if (ext == null || ext.isEmpty()) {
            // supplemental map (lower-case mime -> extension, no dot)
            switch (mime) {
                case "image/jpeg": ext = "jpg"; break;
                case "image/webp": ext = "webp"; break;
                case "image/svg+xml": ext = "svg"; break;
                case "image/vnd.microsoft.icon": case "image/x-icon": ext = "ico"; break;
                case "image/heic": case "image/heif": ext = "heic"; break;
                case "image/avif": ext = "avif"; break;
                case "audio/mpeg": ext = "mp3"; break;
                case "audio/mp4": case "audio/m4a": ext = "m4a"; break;
                case "audio/ogg": case "application/ogg": ext = "ogg"; break;
                case "audio/flac": ext = "flac"; break;
                case "audio/wav": case "audio/x-wav": ext = "wav"; break;
                case "video/webm": ext = "webm"; break;
                case "video/x-matroska": ext = "mkv"; break;
                case "application/zip": ext = "zip"; break;
                case "application/gzip": case "application/x-gzip": ext = "gz"; break;
                case "application/x-7z-compressed": ext = "7z"; break;
                case "application/x-rar-compressed": ext = "rar"; break;
                case "application/epub+zip": ext = "epub"; break;
                case "application/x-mht": case "multipart/related": ext = "mht"; break;
                case "text/html": ext = "html"; break;
                case "text/plain": ext = "txt"; break;
                case "text/csv": ext = "csv"; break;
                case "application/json": ext = "json"; break;
                case "font/woff": ext = "woff"; break;
                case "font/woff2": ext = "woff2"; break;
                case "application/x-msdownload": ext = "exe"; break;
                // v1.3.78: the PK-container families the platform map often
                // returns null for — the sniffed mime must carry its own ext
                case "application/vnd.android.package-archive": ext = "apk"; break;
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": ext = "docx"; break;
                case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": ext = "xlsx"; break;
                case "application/vnd.openxmlformats-officedocument.presentationml.presentation": ext = "pptx"; break;
                case "application/pdf": ext = "pdf"; break;
                default: ext = ""; break;
            }
        }
        return ext == null ? "" : ext.toLowerCase();
    }

    /** make sure a filename carries an extension appropriate for the mime:
     *  appended when missing, replaces a meaningless \".bin\" one. */
    private static String ensureExt(String name, String mime) {
        if (name == null || name.isEmpty()) return name;
        String ext = extForMime(mime);
        if (ext.isEmpty()) return name;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return name + "." + ext;
        String cur = name.substring(dot + 1).toLowerCase();
        if ("bin".equals(cur) || "part".equals(cur)) return name.substring(0, dot) + "." + ext;
        return name;
    }

    /** v1.3.90 — the extension a URL's own path segment carries (clean,
     * 2-5 letter, no query). "" when the path cannot vouch for one — a
     *  query-string token is NOT an extension (".../get?file=x.apk" is
     *  handled by guessFileName; this reads the PATH only). */
    static String urlPathExt(String url) {
        try {
            if (url == null) return "";
            java.net.URL u = new java.net.URL(url);
            String p = u.getPath();
            if (p == null) return "";
            int slash = p.lastIndexOf('/');
            String seg = slash >= 0 ? p.substring(slash + 1) : p;
            int dot = seg.lastIndexOf('.');
            if (dot <= 0 || dot == seg.length() - 1) return "";
            String ext = seg.substring(dot + 1).toLowerCase();
            if (ext.length() < 2 || ext.length() > 5) return "";
            for (int i = 0; i < ext.length(); i++) {
                char c = ext.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return "";
            }
            return ext;
        } catch (Throwable e) {
            return "";
        }
    }

    /** v1.3.90 — the current name's extension is UNTRUSTED when it is the
     *  platform's "we had no idea" filler (".bin"), the engine's staging
     *  suffix (".part") or absent entirely. Such a name never came from a
     *  human or a server that knew what it was serving — every better
     *  source may rewrite it. */
    static boolean extUntrusted(String name) {
        try {
            if (name == null || name.isEmpty()) return true;
            int dot = name.lastIndexOf('.');
            if (dot < 0 || dot == name.length() - 1) return true;
            String cur = name.substring(dot + 1).toLowerCase();
            return "bin".equals(cur) || "part".equals(cur);
        } catch (Throwable e) {
            return false;
        }
    }

    /** magic-byte sniffing — the source of truth when even the server sends a
     *  generic Content-Type. Covers the formats people actually download. */
    private static String sniffMime(byte[] b) {
        if (b == null || b.length < 12) return "";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF)
            return "image/jpeg";
        if ((b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47)
            return "image/png";
        if (b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x38) return "image/gif";
        if (b[0] == 0x42 && b[1] == 0x4D) return "image/bmp";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') {
            if (b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
            if (b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') return "audio/wav";
            if (b[8] == 'A' && b[9] == 'V' && b[10] == 'I' && b[11] == ' ') return "video/x-msvideo";
        }
        if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "application/pdf";
        if (b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) {
            if (b.length > 30) {
                // app-specific: find "mimetype" entry of ODF/EPUB containers
                String head = new String(b, 0, Math.min(b.length, 512),
                        java.nio.charset.StandardCharsets.ISO_8859_1);
                int mi = head.indexOf("mimetype");
                if (mi >= 0 && mi + 16 < head.length()) {
                    String t = head.substring(mi + 8, Math.min(head.length(), mi + 40)).trim();
                    if (t.startsWith("application/epub")) return "application/epub+zip";
                    if (t.startsWith("application/vnd.oasis") || t.startsWith("application/vnd.openxmlformats"))
                        return t.split("[\\s\\x00]")[0];
                }
                // v1.3.78 — a PK file is NOT always a zip: APKs (the user's
                // installs read as generic zips and the open path lost the
                // installer) and the OOXML family (docx/xlsx/pptx share the
                // PK container with zip; the v1.3.77 sniff called all of
                // them "application/zip" so a .docx downloaded as
                // name.zip). The first local-file-header name inside the
                // archive tells them apart: APKs open with
                // AndroidManifest.xml / classes.dex / META-INF, OOXML with
                // [Content_Types].xml + word|xl|ppt/.
                if (head.contains("AndroidManifest.xml") || head.contains("classes.dex"))
                    return "application/vnd.android.package-archive";
                if (head.contains("[Content_Types].xml")) {
                    if (head.contains("word/")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    if (head.contains("xl/")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                    if (head.contains("ppt/")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                }
                if (head.contains("Android")) return "application/vnd.android.package-archive";
            }
            return "application/zip";
        }
        if (b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            String brand = new String(b, 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (brand.startsWith("M4A") || brand.startsWith("M4B")) return "audio/mp4";
            if (brand.startsWith("qt")) return "video/quicktime";
            return "video/mp4";
        }
        if ((b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45 && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3)
            return "video/webm"; // EBML: webm or mkv — webm is the safe default
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') return "audio/mpeg";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) return "audio/mpeg"; // raw frame sync
        if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return "audio/ogg";
        if (b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') return "audio/flac";
        if (b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'v') return "video/x-flv";
        if (b[0] == 0x00 && b[1] == 0x00 && b[2] == 0x01 && b[3] == 0x00) return "image/vnd.microsoft.icon";
        if (b[0] == 'G' && b[1] == 'B' && b[2] == 'P' && b[3] == 'S') return "application/gzip";
        return "";
    }

    private static final int SNIFF_LEN = 512;

    /** refine mime + filename from the downloaded part file (http path) */
    private void refineFile(Item it, File part) {
        try {
            // v1.4.16 — THE LEARNED-TYPE FLAG: when refinement actually
            // LEARNS the file's type this call (the real Content-Type of
            // the final response, or magic-byte sniffing), that knowledge is
            // more authoritative than any extension a URL guess slapped on
            // the name — the strict enforcement below uses it to override
            // BOTH the old ".bin" fallback and a plain WRONG extension.
            boolean learned = false;
            // v1.3.90 — THE NAME LAW, ROUND 2 (the field: "download often
            // messes up files names, and file format extension becomes bin
            // a lot of times"). The v1.3.86 swap only fired when the whole
            // name looked machine-generated (>=16 chars, no vowels) — but
            // URLUtil.guessFileName hands the dialog names like "getfile.bin"
            // or "download.bin" (short, wordy, NOT machine-named) whenever a
            // query-string URL meets a generic mime, and a server that DID
            // send Content-Disposition: filename="Setup-2.4.exe" was ignored
            // for exactly those. The server's filename now wins whenever the
            // current name is machine-generated OR its extension is
            // UNTRUSTED (missing/.bin/.part): a name a human typed into the
            // dialog AND that already carries a real extension keeps its base
            // — the extension still gets repaired below.
            if (!it.cdName.isEmpty()
                    && (machineNamed(it.name) || extUntrusted(it.name))) {
                String sv = safeName(it.cdName);
                if (!sv.isEmpty() && !"download".equals(sv)) it.name = sv;
            }
            if (isGenericMime(it.mime) && !isGenericMime(it.ct)) { it.mime = it.ct; learned = true; }
            if (isGenericMime(it.mime) && part.exists() && part.length() > 0) {
                byte[] head = new byte[SNIFF_LEN];
                try (FileInputStream fi = new FileInputStream(part)) {
                    int r = fi.read(head);
                    if (r > 0) {
                        byte[] real = new byte[r];
                        System.arraycopy(head, 0, real, 0, r);
                        String sm = sniffMime(real);
                        if (!sm.isEmpty()) { it.mime = sm; learned = true; }
                    }
                } catch (Throwable ignored) {}
            }
            it.name = ensureExt(it.name, it.mime);
            // v1.4.16 — THE STRICT EXTENSION LAW: ensureExt only repairs a
            // MISSING or ".bin/.part" extension. When the type was LEARNED
            // this call (real Content-Type / magic bytes), the name's
            // extension is forced to agree with it — a query-string URL
            // guessed "photo.zip" while the bytes sniff image/jpeg must land
            // as photo.jpg, and a ".bin" that somehow survived the lenient
            // pass is overridden here too. Only fires on learned types, so a
            // human-typed "Setup-2.4.exe" with an honest mime keeps its name.
            if (learned) {
                String forced = extForMime(it.mime);
                if (!forced.isEmpty()) {
                    int dot = it.name.lastIndexOf('.');
                    String cur = dot > 0 ? it.name.substring(dot + 1).toLowerCase() : "";
                    if (!cur.equals(forced)) {
                        String base = dot > 0 ? it.name.substring(0, dot) : it.name;
                        if (base.isEmpty()) base = "download";
                        it.name = base + "." + forced;
                    }
                }
            }
            // v1.3.90 — THE EXTENSION RESCUE: the name may STILL end in
            // .bin/.part or carry no extension at all (generic mime all the
            // way down). Two remaining witnesses can repair it: the URL's
            // own path segment (…/installer.apk redirects are common) and
            // the Content-Disposition filename's extension. A meaningless
            // .bin must never survive when ANY witness can name the format.
            if (extUntrusted(it.name)) {
                String better = urlPathExt(it.lastUrl.isEmpty() ? it.url : it.lastUrl);
                if (better.isEmpty() && !it.cdName.isEmpty()) {
                    String cd = safeName(it.cdName);
                    int dot = cd.lastIndexOf('.');
                    if (dot > 0 && dot < cd.length() - 1) {
                        String cde = cd.substring(dot + 1).toLowerCase();
                        if (cde.length() >= 2 && cde.length() <= 5) better = cde;
                    }
                }
                if (!better.isEmpty()) {
                    int dot = it.name.lastIndexOf('.');
                    String base = dot > 0 ? it.name.substring(0, dot) : it.name;
                    if (base.isEmpty()) base = "download";
                    it.name = base + "." + better;
                }
            }
            // v1.3.90 — THE LAST-MILE MIME: the destination calls
            // (SAF createDocument / MediaStore insert) append ".bin" when
            // they are handed a generic mime with an extension-less name.
            // If every witness above left the mime generic but the NAME now
            // carries a real extension, derive the mime from the extension —
            // the file the provider writes then agrees with the name.
            if (isGenericMime(it.mime)) {
                int dot = it.name.lastIndexOf('.');
                if (dot > 0 && dot < it.name.length() - 1) {
                    try {
                        String m = android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(
                                        it.name.substring(dot + 1).toLowerCase());
                        if (m != null && !m.isEmpty() && !m.contains("*")) it.mime = m;
                    } catch (Throwable ignored) {}
                }
            }
            if (it.mime.equalsIgnoreCase("image/jpg")) it.mime = "image/jpeg";
        } catch (Throwable ignored) {}
    }

    /** refine mime + filename from in-memory bytes (data: URL path) */
    private void refineBytes(Item it, byte[] bytes) {
        try {
            if (isGenericMime(it.mime) && bytes != null && bytes.length > 0) {
                byte[] head = bytes.length <= SNIFF_LEN ? bytes
                        : java.util.Arrays.copyOf(bytes, SNIFF_LEN);
                String sm = sniffMime(head);
                if (!sm.isEmpty()) it.mime = sm;
            }
            it.name = ensureExt(it.name, it.mime);
            if (it.mime.equalsIgnoreCase("image/jpg")) it.mime = "image/jpeg";
        } catch (Throwable ignored) {}
    }

    private String safeName(String n) {
        if (n == null) return "download";
        n = n.replace('/', '_').replace('\\', '_').replace('\0', '_').trim();
        if (n.isEmpty()) return "download";
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n;
    }

    /** v1.3.86 — parse a Content-Disposition header's filename: RFC 5987
     *  filename*=UTF-8''… first (percent-encoded, the modern form), then
     *  the quoted filename="…" form (with \. unescaping), then a bare
     *  filename= token. Empty when the header is absent or unusable. */
    static String cdFilename(String cd) {
        try {
            if (cd == null || cd.isEmpty()) return "";
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("filename\\*=\\s*([^']*)'([^']*)'([^;]+)",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(cd);
            if (m.find()) {
                String raw = m.group(3).trim();
                if (raw.isEmpty()) return "";
                try {
                    // RFC 5987 is percent-encoding only — a literal '+' must
                    // not become a space the way query decoding does
                    return java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
                } catch (Throwable e) {
                    return raw;
                }
            }
            m = java.util.regex.Pattern
                    .compile("filename=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(cd);
            if (m.find()) {
                return m.group(1)
                        .replace("\\\"", "\"").replace("\\\\", "\\").trim();
            }
            m = java.util.regex.Pattern
                    .compile("filename=\\s*([^;]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(cd);
            if (m.find()) {
                String v = m.group(1).trim().replace("\"", "");
                return v;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    // ------------------------------------------------------------ notifications

    private void registerReceiver() {
        try {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    long id = intent.getLongExtra("id", -1);
                    String act = intent.getAction();
                    if (act == null) return;
                    if (act.endsWith("PAUSE")) pause(id);
                    else if (act.endsWith("RESUME")) resume(id);
                    else if (act.endsWith("CANCEL")) cancel(id);
                    else if (act.endsWith("CANCELALL")) cancelAllActive();
                }
            };
            IntentFilter f = new IntentFilter();
            String pkg = ctx.getPackageName();
            f.addAction(pkg + ".DL_PAUSE");
            f.addAction(pkg + ".DL_RESUME");
            f.addAction(pkg + ".DL_CANCEL");
            f.addAction(pkg + ".DL_CANCELALL");
            ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable ignored) {}
    }

    /** broadcast to our dynamic receiver (implicit + package-scoped: safe on all APIs) */
    private PendingIntent actionPi(String action, long id) {
        Intent i = new Intent(ctx.getPackageName() + ".DL_" + action);
        i.setPackage(ctx.getPackageName());
        i.putExtra("id", id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(ctx, (int) ((id + action.hashCode()) % 999983),
                i, flags);
    }

    private void startDlService() {
        if (serviceOn || TabManager.INCOGNITO) return;
        try {
            Intent i = new Intent(ctx, DownloadService.class);
            ContextCompat.startForegroundService(ctx, i);
            serviceOn = true;
        } catch (Throwable ignored) {}
    }

    private void stopDlService() {
        if (!serviceOn) return;
        serviceOn = false;
        try {
            NotificationManagerCompat.from(ctx).cancel(1001);
            ctx.stopService(new Intent(ctx, DownloadService.class));
        } catch (Throwable ignored) {}
    }

    /** service summary notification: aggregate progress of the queue */
    public Notification buildSummary(Context c) {
        List<Item> all = list();
        int active = 0;
        long doneBytes = 0, totalBytes = 0;
        Item first = null;
        int doneCount = 0;
        for (Item it : all) {
            if (it.status == ST_RUNNING || it.status == ST_QUEUED) {
                active++;
                if (first == null) first = it;
                doneBytes += it.done;
                totalBytes += it.total;
            } else if (it.status == ST_DONE) doneCount++;
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, BoltApp.CH_DL)
                .setSmallIcon(R.drawable.ic_notify_download)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (active == 0) {
            b.setContentTitle(c.getString(R.string.downloads))
                    .setContentText(c.getString(R.string.download_complete))
                    .setProgress(0, 0, false);
        } else if (active == 1 && first != null) {
            String speedTxt = first.speed > 0
                    ? " \u00b7 " + android.text.format.Formatter.formatShortFileSize(c, first.speed) + "/s" : "";
            b.setContentTitle(first.name)
                    .setContentText(first.total > 0
                            ? (100 * first.done / first.total) + "%" + speedTxt
                            : c.getString(R.string.downloading) + speedTxt)
                    .setProgress(first.total > 0 ? 100 : 0,
                            first.total > 0 ? (int) (100 * first.done / first.total) : 0,
                            first.total <= 0)
                    .setContentIntent(openDownloadsPi(c))
                    .addAction(R.drawable.ic_ntf_pause, c.getString(R.string.pause_dl),
                            actionPi("PAUSE", first.id))
                    .addAction(R.drawable.ic_ntf_close, c.getString(R.string.cancel),
                            actionPi("CANCEL", first.id));
        } else {
            b.setContentTitle(c.getString(R.string.downloading) + " · " + active)
                    .setContentText(doneCount > 0 ? doneCount + " ✓" : "")
                    .setProgress(totalBytes > 0 ? 100 : 0,
                            totalBytes > 0 ? (int) (100 * doneBytes / totalBytes) : 0,
                            totalBytes <= 0)
                    .setContentIntent(openDownloadsPi(c))
                    .addAction(R.drawable.ic_ntf_close, c.getString(R.string.cancel_all), actionPi("CANCELALL", 0));
        }
        return b.build();
    }

    private PendingIntent openDownloadsPi(Context c) {
        Intent i = new Intent(c, DownloadsActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(c, 31, i, flags);
    }

    /** per-item completion/failure notification */
    private void notifyDone(Item it) {
        try {
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, BoltApp.CH_DL)
                    .setSmallIcon(it.status == ST_DONE ? R.drawable.ic_notify_download : R.drawable.ic_notify_close)
                    .setContentTitle(it.name)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true);
            if (it.status == ST_DONE) {
                b.setContentText(ctx.getString(R.string.download_complete));
                Intent open = openFileIntent(it);
                if (open != null) {
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
                    b.setContentIntent(PendingIntent.getActivity(ctx, (int) (it.id % 100000), open, flags));
                }
            } else {
                b.setContentText(ctx.getString(R.string.download_failed) + ": " + it.error);
                b.addAction(R.drawable.ic_reload, ctx.getString(R.string.retry), actionPi("RESUME", it.id));
            }
            NotificationManagerCompat.from(ctx).notify((int) (it.id % 100000) + 500000, b.build());
        } catch (Throwable ignored) {}
    }

    /** v1.3.19: completion fan-out for the in-app "Open file?" popup. Runs
     *  on the main thread (dialog-safe); every listener is isolated so one
     *  dead activity can never break the fan-out for the others. */
    private void fireComplete(Item it) {
        ui.post(() -> {
            for (CompletionListener l : completionListeners) {
                try { l.onDownloadComplete(it); } catch (Throwable ignored) {}
            }
        });
    }

    /** build an ACTION_VIEW intent able to open the finished file.
     *  v1.3.78: [isApk] asks for the installer mime explicitly — an APK
     *  whose sniffed/recorded mime went generic opened as a zip (or not at
     *  all); the INSTALL permission itself is handled by the caller. */
    public Intent openFileIntent(Item it) { return openFileIntent(it, false); }

    /** @param forceGenericMime true = the typed intent found no handler, so
     *        retry with slash-star so any viewer can take the file. */
    public Intent openFileIntent(Item it, boolean forceGenericMime) {
        try {
            if (it.finalUri == null || it.finalUri.isEmpty()) return null;
            Uri u = Uri.parse(it.finalUri);
            Intent i = new Intent(Intent.ACTION_VIEW);
            String name = it.name == null ? "" : it.name.toLowerCase();
            boolean apkish = name.endsWith(".apk") || name.endsWith(".xapk")
                    || (it.mime != null && it.mime.contains("android.package-archive"));
            String mime;
            if (forceGenericMime) mime = "*/*";
            else if (apkish) mime = "application/vnd.android.package-archive";
            else mime = it.mime == null || it.mime.isEmpty() ? "*/*" : it.mime;
            if ("content".equals(u.getScheme())) {
                i.setDataAndType(u, mime);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                File f = new File(u.getPath());
                android.net.Uri shared = androidx.core.content.FileProvider.getUriForFile(
                        ctx, "com.bolt.browser.fileprovider", f);
                i.setDataAndType(shared, mime);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            return i;
        } catch (Throwable e) {
            return null;
        }
    }

    /** v1.3.78 — true when the app holds the "install unknown apps" grant
     *  (API 26+). The manifest carries REQUEST_INSTALL_PACKAGES now; the
     *  user still has to flip the per-app switch once, and the deep link
     *  below is the shortest honest path there. */
    public boolean canInstallApks() {
        if (android.os.Build.VERSION.SDK_INT < 26) return true;
        try {
            return ctx.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable e) {
            return false;
        }
    }

    /** the settings deep link that flips the "install unknown apps" switch
     *  for THIS app (API 26+). */
    public Intent installPermissionIntent() {
        try {
            return new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
        } catch (Throwable e) {
            return null;
        }
    }

    // ------------------------------------------------------------ change fanout

    private void notifyUi(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastUiNotify < 300) return;
        lastUiNotify = now;
        if (force) persist();
        ui.post(() -> {
            for (Listener l : listeners) {
                try { l.onDownloadsChanged(); } catch (Throwable ignored) {}
            }
        });
        ui.post(() -> {
            long n2 = System.currentTimeMillis();
            if (n2 - lastNotifNotify < 800 && !force) return;
            lastNotifNotify = n2;
            try {
                if (anyActive()) {
                    NotificationManagerCompat.from(ctx).notify(1001, buildSummary(ctx));
                } else {
                    NotificationManagerCompat.from(ctx).cancel(1001);
                }
            } catch (Throwable ignored) {}
        });
    }

    public boolean canNotify() {
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled();
    }
}
