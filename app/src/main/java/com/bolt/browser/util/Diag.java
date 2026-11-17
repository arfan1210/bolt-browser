package com.bolt.browser.util;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * v1.3.20 — tiny persistent diagnostics log (renderer deaths, uncaught
 * exceptions, app starts). Lives in the app's private files dir; capped so it
 * can never grow unbounded. Purpose: the v1.3.18/19 "YouTube crashes" field
 * reports could never be backed by a logcat capture (no PC / no adb on the
 * user side) — every future "it crashed" report now carries evidence: the
 * diag log is one tap away from the crash-notice page (Bolt.copyDiag() copies
 * it to the clipboard, the user pastes it into chat).
 *
 * v1.3.29 — BUFFERED writes. The v1.3.28 field log showed the chatty lines
 * (rendererPolicies/trim/media events) firing up to ~1 Hz while backgrounded,
 * and every single Diag.log did open+write+close on a FileOutputStream plus a
 * new SimpleDateFormat — real I/O burn in the exact state we are trying to
 * make cheap. log() now appends to an in-memory buffer flushed (a) when 64
 * lines are pending, (b) every 5 s by a daemon flusher, (c) before any read.
 * The lines that must SURVIVE the process dying (app start, PROC_KILL
 * forensics, watchdog recovery, uncaught exceptions) go through logSync(),
 * which flushes everything immediately. Buffering may lose the final <5 s of
 * chatty lines at a hard kill — the keepalive evidence channel (the
 * media_heartbeat file + logSync lines) is unaffected.
 *
 * Deliberately dependency-free and never-throwing: diagnostics must be the
 * last thing standing when things go wrong.
 */
public final class Diag {

    private static final String NAME = "diag.log";
    private static final long CAP = 64 * 1024;   // hard cap; over → reset (keep it simple)
    private static final int FLUSH_LINES = 64;
    private static final long FLUSH_MS = 5_000;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> PENDING = new ArrayDeque<>();
    private static Thread FLUSHER = null;
    private static Context APPCTX = null;        // application context only — no leaks

    private Diag() {}

    /** timestamped line (buffered — see class doc) */
    public static void log(Context c, String line) {
        try {
            synchronized (LOCK) {
                PENDING.add(stamp() + " " + line);
                ensureFlusherLocked(c);
                if (PENDING.size() >= FLUSH_LINES) flushLocked();
            }
        } catch (Throwable ignored) {
            // diagnostics must never crash the app
        }
    }

    /** timestamped line written IMMEDIATELY (plus everything pending) — for
     *  evidence that must survive the process dying mid-write. */
    public static void logSync(Context c, String line) {
        try {
            synchronized (LOCK) {
                PENDING.add(stamp() + " " + line);
                ensureFlusherLocked(c);
                flushLocked();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String stamp() {
        try {
            return new java.text.SimpleDateFormat(
                    "MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date());
        } catch (Throwable t) {
            return "-- --:--:--.---";
        }
    }

    private static void ensureFlusherLocked(Context c) {
        try {
            if (APPCTX == null && c != null) APPCTX = c.getApplicationContext();
        } catch (Throwable ignored) {}
        if (FLUSHER != null && FLUSHER.isAlive()) return;
        try {
            FLUSHER = new Thread(() -> {
                while (true) {
                    try { Thread.sleep(FLUSH_MS); } catch (InterruptedException e) { return; }
                    try {
                        synchronized (LOCK) { flushLocked(); }
                    } catch (Throwable ignored) {}
                }
            }, "bolt-diag");
            FLUSHER.setDaemon(true);
            FLUSHER.start();
        } catch (Throwable ignored) {}
    }

    /** LOCK must be held. Writes all pending lines in ONE file open/write/close. */
    private static void flushLocked() {
        if (PENDING.isEmpty() || APPCTX == null) return;
        try {
            File dir = APPCTX.getFilesDir();
            if (dir == null) return;
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, NAME);
            if (f.exists() && f.length() > CAP) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            StringBuilder sb = new StringBuilder(PENDING.size() * 96);
            while (!PENDING.isEmpty()) sb.append(PENDING.poll()).append('\n');
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // diagnostics must never crash the app
        }
    }

    /** last {@code maxChars} of the log ("" when empty) */
    public static String tail(Context c, int maxChars) {
        try {
            synchronized (LOCK) {
                ensureFlusherLocked(c);
                flushLocked();
            }
            File f = new File(c.getFilesDir(), NAME);
            if (!f.exists()) return "";
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            String s = new String(b, StandardCharsets.UTF_8);
            return s.length() > maxChars ? s.substring(s.length() - maxChars) : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * v1.3.21 — summary of the PREVIOUS session's trouble (the one that ended
     * before the current "app start" line was written). Returns null when the
     * previous session was clean, otherwise a short human-readable line the
     * startup stability dialog shows ("2 renderer restarts, 1 error, 1 repair").
     * This is the field-evidence channel: the user taps Copy and pastes the
     * report in chat — the diag log then carries the WebView version, renderer
     * didCrash/priority fingerprints and full exception stacks.
     */
    public static String lastSessionSummary(Context c) {
        try {
            synchronized (LOCK) {
                ensureFlusherLocked(c);
                flushLocked();
            }
            File f = new File(c.getFilesDir(), NAME);
            if (!f.exists()) return null;
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            String[] lines = new String(b, StandardCharsets.UTF_8).split("\n");
            // lastStart = current session's "app start" (already written by
            // BoltApp before any activity runs); prevStart = the session before
            int lastStart = -1, prevStart = -1;
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].contains("app start ")) continue;
                if (lastStart == -1) { lastStart = i; continue; }
                prevStart = i;
                break;
            }
            if (lastStart == -1 || prevStart == -1) return null;
            if (lastStart - prevStart > 500)
                return "previous session ended abruptly (log rotated)";
            int rendererGone = 0, uncaught = 0, repairs = 0;
            for (int i = prevStart + 1; i < lastStart; i++) {
                if (lines[i].contains("rendererGone")) rendererGone++;
                else if (lines[i].contains("UNCAUGHT")) uncaught++;
                else if (lines[i].contains("REPAIR")) repairs++;
            }
            if (rendererGone == 0 && uncaught == 0) return null;
            StringBuilder sb = new StringBuilder();
            if (rendererGone > 0) sb.append(rendererGone).append(rendererGone == 1
                    ? " renderer restart" : " renderer restarts");
            if (uncaught > 0) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(uncaught).append(uncaught == 1 ? " error" : " errors");
            }
            if (repairs > 0) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(repairs).append(repairs == 1 ? " repair" : " repairs");
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
