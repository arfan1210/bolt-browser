package com.bolt.browser.util;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * v1.4.18 — THE LAST-FOREGROUND-SESSION RECORD (the "external search
 * bounces an incognito user back to the normal tabs menu" report).
 *
 * THE HOLE: the system search doors (the launcher aliases' WEB_SEARCH /
 * SEND / PROCESS_TEXT filters, the home-screen widget) resolve into the
 * MAIN process's BrowserActivity — always. v1.4.17's SearchIncognito
 * alias only ADDED a second chooser row (frequently collapsed into the
 * toolbar's overflow), so a user whose last Bolt session was INCOGNITO
 * tapped "Search in Bolt", landed in the normal task — and because the
 * normal task's back stack still had the TABS SWITCHER on top, they were
 * staring at the NORMAL tabs menu with the normal cookie jar. A private
 * session must survive its own search door.
 *
 * THE CONTRACT: whichever BrowserActivity variant (process) comes to the
 * foreground writes ONE tiny record — incognito or normal — into
 * filesDir (shared by both processes of the app: a :incognito process is
 * the same app id and the same files dir; the v1.4.5 dirty-write law
 * documents the sharing). The main-process search faces read it FRESH on
 * every intent (no caching — the two processes' in-memory Prefs copies
 * cannot see each other's writes) and FORWARD the query to
 * IncognitoActivity when the record says incognito, finishing themselves
 * (an activity-to-activity forward, so the private process opens with
 * its own omnibox flow exactly as if the user had entered it directly).
 *
 * THE WRITE LAW: tmp-file + rename — POSIX rename is atomic, so a reader
 * racing a writer sees either the old or the new whole value, never a
 * torn one. Resume-time writes are rare (one per foreground hop), so the
 * two processes never contend in practice; correctness does not depend
 * on that anyway.
 */
public final class SessionState {

    private SessionState() {}

    /** the record file (filesDir/last_session.txt). */
    static final String FILE_NAME = "last_session.txt";

    /** record which session was last foreground (call from onResume of
     *  either BrowserActivity variant — the incognito flag decides the
     *  value, the process placement is irrelevant to the write). */
    public static void noteForeground(Context c, boolean incognito) {
        noteForeground(c.getFilesDir(), incognito);
    }

    /** the File-dir core — java.io only, transcribed verbatim into the
     *  v1.4.18 gate's compiled probe (the parse-gate law). */
    static void noteForeground(File dir, boolean incognito) {
        try {
            File tmp = new File(dir, FILE_NAME + ".tmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            try {
                fo.write((incognito ? "incognito" : "normal")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                try { fo.close(); } catch (Throwable ignored) {}
            }
            // atomic on the same filesystem (POSIX rename); the reader
            // in the OTHER process sees one whole value, never a torn one
            File dst = new File(dir, FILE_NAME);
            if (!tmp.renameTo(dst)) {
                dst.delete();
                tmp.renameTo(dst);
            }
        } catch (Throwable ignored) {}
    }

    /** true when the last foreground Bolt session was the incognito one.
     *  A missing, torn or unreadable record reads as NORMAL — the
     *  pre-v1.4.18 behavior — so the feature can never make a NORMAL
     *  user's search land in private mode by accident. */
    public static boolean lastForegroundIncognito(Context c) {
        return lastForegroundIncognito(c.getFilesDir());
    }

    /** the File-dir core (probe-pinned together with the writer). */
    static boolean lastForegroundIncognito(File dir) {
        try {
            File f = new File(dir, FILE_NAME);
            FileInputStream fi = new FileInputStream(f);
            byte[] b;
            try {
                int len = (int) Math.min(f.length(), 64L);
                b = new byte[len];
                int off = 0;
                while (off < len) {
                    int n = fi.read(b, off, len - off);
                    if (n < 0) break;
                    off += n;
                }
            } finally {
                try { fi.close(); } catch (Throwable ignored) {}
            }
            return "incognito".equals(new String(
                    b, java.nio.charset.StandardCharsets.UTF_8).trim());
        } catch (Throwable t) {
            return false;
        }
    }
}
