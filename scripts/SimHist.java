/**
 * v1.4.24 — THE STALE-SNAPSHOT NAVIGATION PROBE (scripts/SimHist.java).
 *
 * A faithful transcription of the Tab navigation-snapshot decision chain
 * that MUST track the real code — edit the two together:
 *   - Tab.saveHist (sleep/trim/render-death captures the page's state)
 *   - Tab.clearHist (v1.4.24: every EXPLICIT new navigation retires the
 *     snapshot BEFORE showTab/loadUrl)
 *   - Tab.restoreHist (v1.4.24: the URL guard — a snapshot whose page
 *     disagrees with t.url is STALE and restores nothing)
 *   - BrowserActivity.showTab's rebuild path (v1.4.24: the warm consume —
 *     a successful restore spends the Bundle)
 *
 * The corpse (root-caused from the v1.4.23 source + the user's diag.log on
 * Realme RMX2020 / Android 10): on facebook.com -> home -> search youtube
 * -> Facebook returns; opening youtube.com directly ALSO returned
 * Facebook; "tab hist restore idx=N warm" fired repeatedly for the same
 * tab. The OLD shape (v1.4.23 law: restore-first, no clear, no guard, no
 * consume) is kept in [showRebuildOld] to demonstrate the resurrection;
 * [showRebuild] is the v1.4.24 law. Exit code 0 = every verdict holds.
 */
public class SimHist {

    // ---- the tab model -------------------------------------------------
    private String url = "";            // t.url
    private String sleepStatePage;      // the page the warm Bundle holds
    private String histBlobPage;         // the page the cold blob holds
    private String loadedUrl;            // what loadUrl actually fetched
    private int restores;                // restoreHist successes (warm + cold)
    private int warmRestores;            // warm-Bundle restores (the consume count)
    private int backEntries;             // canGoBack truth of the live web

    /** Tab.saveHist — a snapshot is captured for the page the tab is ON. */
    void saveHist(String page) {
        sleepStatePage = page;
        histBlobPage = page;
        url = page;
    }

    /** v1.4.24 — Tab.clearHist: an explicit new navigation retires the
     *  snapshot. Called by loadInTab BEFORE showTab/loadUrl. */
    void clearHist() {
        sleepStatePage = null;
        histBlobPage = null;
    }

    /** v1.4.24 — the loadInTab law (the funnel of every explicit
     *  navigation: omnibox GO, home search, direct URL, suggestions,
     *  bangs): set the new url, RETIRE the old snapshot, then load. */
    void loadInTab(String newUrl) {
        url = newUrl;
        clearHist();
        loadedUrl = newUrl;
        backEntries = 0;   // the first navigation of the fresh view
    }

    /** v1.4.21 — TabSleep/sleepTab: the genuinely-slept tab. */
    void sleep() {
        if (sleepStatePage == null) saveHist(url);
    }

    /** BrowserActivity.showTab's rebuild path under the v1.4.24 law:
     *  restoreHist (URL-guarded) — else loadUrl(t.url). Returns the page
     *  the user SEES. Mirrors the real guard order exactly. */
    String showRebuild() {
        loadedUrl = null;
        if (sleepStatePage != null || histBlobPage != null) {
            // Tab.restoreHist: warm Bundle preferred, cold blob next
            String snap = sleepStatePage != null ? sleepStatePage : histBlobPage;
            // v1.4.24 — THE URL GUARD: the snapshot's current item must be
            // the tab's own url, else it is stale and restores nothing
            if (snap != null && snap.equals(url)) {
                // v1.4.24 — THE WARM CONSUME: a successful restore spends
                // the Bundle (it can never re-restore on a later rebuild)
                if (sleepStatePage != null) { sleepStatePage = null; warmRestores++; }
                restores++;
                backEntries = 2;   // the restored back/forward list
                return snap;
            }
        }
        // restoreHist false -> loadUrl(t.url): the NEW page loads
        loadedUrl = url;
        backEntries = 0;
        return url;
    }

    /** THE OLD SHAPE (v1.4.23): restore-first, no clear in loadInTab, no
     *  URL guard, no warm consume — kept to DEMONSTRATE the corpse. */
    String showRebuildOld() {
        loadedUrl = null;
        if (sleepStatePage != null || histBlobPage != null) {
            restores++;
            backEntries = 2;
            return sleepStatePage != null ? sleepStatePage : histBlobPage;
        }
        loadedUrl = url;
        backEntries = 0;
        return url;
    }

    boolean canGoBack() { return backEntries > 0; }
    String loadedUrl() { return loadedUrl; }
    int restores() { return restores; }
    int warmRestores() { return warmRestores; }
    boolean holdsSnapshot() { return sleepStatePage != null || histBlobPage != null; }

    // ---- the verdicts --------------------------------------------------
    private static int bad = 0;
    private static void t(boolean cond, String name) {
        if (cond) System.out.println("  ok   - " + name);
        else { System.out.println("  FAIL - " + name); bad++; }
    }

    public static void main(String[] args) {
        // ===== 1. THE FIELD CORPSE, v1.4.23 shape demonstrated =====
        SimHist old = new SimHist();
        old.saveHist("facebook.com");            // on facebook, snapshot kept
        old.url = "youtube.com";                 // loadInTab set the NEW url
        old.loadedUrl = null;                    // ...but never retired the snapshot
        t("facebook.com".equals(old.showRebuildOld()),
                "CORPSE (v1.4.23 law): stale snapshot resurrects facebook over youtube");
        t(old.restores() == 1, "CORPSE: the restore path ran (diag: tab hist restore warm)");
        t(old.holdsSnapshot(), "CORPSE: the snapshot survives for the next rebuild");
        t("facebook.com".equals(old.showRebuildOld()),
                "CORPSE: the double-restore loop (warm fires AGAIN for the same tab)");

        // ===== 2. THE v1.4.24 FIX, the same field walk =====
        SimHist fix = new SimHist();
        fix.saveHist("facebook.com");            // browsing facebook
        fix.sleep();
        fix.loadInTab("youtube.com");            // home -> search youtube
        t(!fix.holdsSnapshot(), "FIX: loadInTab retired the stale snapshot BEFORE the load");
        t("youtube.com".equals(fix.showRebuild()), "FIX: the NEW url loads (youtube, not facebook)");
        t(fix.restores() == 0, "FIX: no restore fired for the new navigation");
        t(!fix.canGoBack(), "FIX: canGoBack reflects the new page (fresh back list)");
        t("facebook.com".equals(old.showRebuildOld()) && "youtube.com".equals(fix.showRebuild()),
                "FIX vs CORPSE: the same walk, opposite outcomes");

        // ===== 3. THE URL GUARD (the safety net for any path that sets
        //          t.url without the clear — e.g. a stale tabs.json blob) =====
        SimHist guard = new SimHist();
        guard.saveHist("facebook.com");          // snapshot = facebook
        guard.url = "youtube.com";               // new url assigned WITHOUT clear
        t("youtube.com".equals(guard.showRebuild()),
                "URL GUARD: a snapshot whose page disagrees with t.url restores nothing");
        t("youtube.com".equals(guard.loadedUrl()), "URL GUARD: loadUrl(t.url) ran instead");

        // ===== 4. THE PRESERVED FEATURE — a genuinely slept tab still
        //          restores its back/forward list (the v1.4.21 law) =====
        SimHist wake = new SimHist();
        wake.saveHist("wikipedia.org/article");
        wake.sleep();
        t("wikipedia.org/article".equals(wake.showRebuild()),
                "PRESERVED: returning to a slept tab restores its page");
        t(wake.restores() == 1, "PRESERVED: the restore path ran");
        t(wake.canGoBack(), "PRESERVED: the back/forward list came back");
        t(wake.sleepStatePage == null,
                "PRESERVED + CONSUME: the warm Bundle is spent after one restore");
        t("wikipedia.org/article".equals(wake.showRebuild()),
                "CONSUME: a SECOND rebuild (new trim) cannot RE-restore the warm "
                + "snapshot — the cold blob (same page, URL-guarded) serves it");
        t(wake.restores() == 2, "CONSUME: the second restore is a COLD one (blob)");
        t(wake.warmRestores() == 1, "CONSUME: only ONE warm restore ever fired "
                + "(the double-warm-restore loop is dead)");

        // ===== 5. direct URL from home — the second field shape =====
        SimHist direct = new SimHist();
        direct.saveHist("facebook.com");
        direct.loadInTab("youtube.com");         // direct URL entry
        t("youtube.com".equals(direct.showRebuild()),
                "DIRECT-URL: opening youtube.com directly loads youtube");

        System.out.println(bad == 0 ? "SimHist: all verdicts hold"
                : "SimHist: " + bad + " FAILURES");
        System.exit(bad == 0 ? 0 : 1);
    }
}
