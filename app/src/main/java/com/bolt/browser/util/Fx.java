package com.bolt.browser.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Toast;

import com.bolt.browser.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Misc helpers: theming, colors, IO.
 *
 * v1.3.33 — THE SKIN ENGINE. Before this, every screen mixed theme attrs
 * (fine) with locally hardcoded {@code prefs.isDark() ? A : B} colors (the
 * reason menus/settings/tabs looked inconsistent and some text/icons were
 * invisible: white icons on light cards, dark-theme white text in Light
 * mode). All programmatic colors now come from here and resolve the SAME
 * attrs the XML layouts use, so code and XML can never disagree again.
 *
 * Skins (Prefs "skin"): 0 Classic (light/dark/system), 1 Aura (translucent
 * dark glass), 2 Mono (true black flat), 3 Origins (warm light soft),
 * 4 Pulse (vibrant dark). Fx.theme() maps skin → AppCompat theme; every
 * theme defines the color* attrs + android:textColor* family (the omnibox /
 * find-bar / dialog EditTexts were rendering white-on-light before).
 */
public class Fx {

    public static final int[] ACCENTS = {0xFF7C4DFF, 0xFF3E8BFF, 0xFF22C55E, 0xFFEC4899, 0xFFF97316, 0xFF9E9E9E};
    public static final int[] GROUP_COLORS = {0xFFEF5350, 0xFFFFA726, 0xFFFFCA28, 0xFF66BB6A,
            0xFF26A69A, 0xFF42A5F5, 0xFFAB47BC, 0xFFEC407A};

    /** bumped whenever the skin changes so long-lived activities
     * (BrowserActivity) can recreate() on the next onResume.
     * v1.3.53 RULE — skinGen is reserved for genuine LAYOUT-level changes
     * that NEED the recreate (urlBarBottom, fullscreen/notch, skin
     * switches; the themeHash covers pure color changes). Home-CONTENT
     * changes (favicons, wallpaper style, suggested sites) must bump
     * homeGen instead: a BrowserActivity recreate destroys EVERY tab's
     * WebView in onCreate (destroyStaleWebViews) and reloads the active
     * page — that was the v1.3.52 field report ("reloads aggressively on
     * returning from settings / switching apps / recents; reloads when I
     * try to login with saved passwords"). */
    public static volatile int skinGen = 0;

    /** v1.3.53 — the HOME-CONTENT channel. Bumped when the home view's
     *  CONTENT changed with no theme/layout change (a favicon landed, the
     *  wallpaper style was adjusted, the suggested-sites row toggled).
     *  Readers (BrowserActivity): showTab rebuilds the home when it is
     *  next SHOWN with a stale homeShownGen; onResume refreshes it when
     *  the home is the visible tab. NEVER recreates the activity — see
     *  the skinGen rule above for why. */
    public static volatile int homeGen = 0;

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static void toast(Context c, String s) {
        if (s == null || s.isEmpty()) return;
        Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
    }

    public static void theme(Activity a) {
        int skin = com.bolt.browser.util.Prefs.get(a).skin();
        boolean dark = com.bolt.browser.util.Prefs.get(a).isDark();
        int t;
        switch (skin) {
            case 1: t = dark ? R.style.Theme_Bolt_Aura_Dark : R.style.Theme_Bolt_Aura_Light; break;
            case 2: t = dark ? R.style.Theme_Bolt_Mono_Dark : R.style.Theme_Bolt_Mono_Light; break;
            case 3: t = dark ? R.style.Theme_Bolt_Origins_Dark : R.style.Theme_Bolt_Origins_Light; break;
            case 4: t = dark ? R.style.Theme_Bolt_Pulse_Dark : R.style.Theme_Bolt_Pulse_Light; break;
            default:
                t = dark ? R.style.Theme_Bolt_Dark : R.style.Theme_Bolt_Light;
                break;
        }
        a.setTheme(t);
    }

    /** v1.3.51 — everything Fx.theme() resolves, folded into one comparable
     *  int: the skin, the resolved lightness (follows the system in Auto
     *  mode) and the accent COLOR (wallpaper accents included, so a wallpaper
     *  palette flip re-skins too). BoltApp's instant-re-skin channel compares
     *  this stamp per activity on every resume: a change means the screen
     *  re-themes the MOMENT it arrives — no menu-switching, no restart (the
     *  v1.3.51 field request: "make all changes appear instant — appearance,
     *  workspace etc. All."). Deliberately NOT skinGen-based: skinGen is the
     *  recreate channel, and favicon captures (homeGen since v1.3.53) must
     *  never re-create activities. */
    public static int themeStamp(Context c) {
        try {
            com.bolt.browser.util.Prefs p = com.bolt.browser.util.Prefs.get(c);
            return p.skin() * 10000000
                    + (p.isDark() ? 1 : 0) * 1000000
                    + (accent(c) & 0xFFFFFF);
        } catch (Throwable t) { return 0; }
    }

    // ---------------- v1.3.33 central palette (resolve the theme attrs) ----------------

    /** resolve a color attr from the context's CURRENT theme. Cheap (µs) and
     * always in sync with the XML — call from activities AFTER Fx.theme(). */
    public static int attrColor(Context c, int attr) {
        try {
            android.util.TypedValue v = new android.util.TypedValue();
            if (c.getTheme().resolveAttribute(attr, v, true)) return v.data;
        } catch (Throwable ignored) {}
        return 0xFF000000;
    }

    /** primary text color */
    public static int txt(Context c) { return attrColor(c, R.attr.colorText); }
    /** secondary / dimmed text color */
    public static int dim(Context c) { return attrColor(c, R.attr.colorTextDim); }
    /** icon tint (the gray toolbar/menu icons use) */
    public static int tint(Context c) { return attrColor(c, R.attr.colorTint); }
    /** window background */
    public static int bg(Context c) { return attrColor(c, R.attr.colorBg); }
    /** card/sheet surface */
    public static int surface(Context c) { return attrColor(c, R.attr.colorSurface); }
    /** chips / secondary surface */
    public static int surface2(Context c) { return attrColor(c, R.attr.colorSurface2); }
    /** hairline outline */
    public static int outline(Context c) { return attrColor(c, R.attr.colorOutline); }

    /** 0 classic, 1 Aura glass, 2 Mono flat, 3 Origins soft, 4 Pulse. */
    public static int skinStyle(Context c) {
        try {
            android.util.TypedValue v = new android.util.TypedValue();
            if (c.getTheme().resolveAttribute(R.attr.skinStyle, v, true)) return v.data;
        } catch (Throwable ignored) {}
        return 0;
    }

    public static boolean isGlass(Context c) { return skinStyle(c) == 1; }
    public static boolean isFlat(Context c) { return skinStyle(c) == 2; }
    public static boolean isSoft(Context c) { return skinStyle(c) == 3; }

    /**
     * WebView page background — resolves from the skin PREF directly because
     * Tab.buildWeb also runs in the service process (phoenix), where no
     * activity theme exists. Mirrors the colorBg of every skin's light AND
     * dark variant in colors.xml (v1.3.35).
     */
    public static int webBg(Context c) {
        com.bolt.browser.util.Prefs p = com.bolt.browser.util.Prefs.get(c);
        boolean dark = p.isDark();
        switch (p.skin()) {
            case 1: return dark ? 0xFF0C0A1E : 0xFFF4F1FD;   // Aura (v1.3.39 violet)
            case 2: return dark ? 0xFF000000 : 0xFFFFFFFF;    // Mono
            case 3: return dark ? 0xFF1B150E : 0xFFF0E9DB;    // Origins (v1.3.39 cream/ember)
            case 4: return dark ? 0xFF131033 : 0xFFF1EFFE;    // Pulse (v1.3.39 indigo)
        }
        return dark ? 0xFF0E0E13 : 0xFFFAFAFD;
    }

    // ---------------- v1.3.35 per-skin geometry + signature accents ----------------

    /**
     * The corner radius (dp) that gives each skin its silhouette — one of
     * the "make the themes look different" levers: Classic 16, Aura 20,
     * Mono 8 (sharp), Origins 24 (soft), Pulse 18.
     */
    public static int radius(Context c) {
        switch (skinStyle(c)) {
            case 1: return 20;   // Aura glass
            case 2: return 8;    // Mono sharp
            case 3: return 24;   // Origins round
            case 4: return 18;   // Pulse
            default: return 16;  // Classic
        }
    }

    /** icon-container corner radius per skin (keeps proportions at 40dp). */
    public static int iconRadius(Context c) {
        switch (skinStyle(c)) {
            case 1: return 14;
            case 2: return 5;
            case 3: return 16;
            case 4: return 13;
            default: return 12;
        }
    }

    /** each skin's SIGNATURE accent — the "Auto" choice and the XML default.
     * Mono is deliberately monochrome: paper-white on black, ink-black on
     * white (the Nothing-OS-style minimal look); the rest are colored. */
    public static int skinAccent(int skin, boolean dark) {
        switch (skin) {
            case 1: return dark ? 0xFF9A7BFF : 0xFF7B5CFF;   // Aura violet
            case 2: return dark ? 0xFFEDEDED : 0xFF111111;   // Mono paper / ink
            case 3: return dark ? 0xFFF0A05A : 0xFFC96F4A;   // Origins amber / terracotta
            case 4: return dark ? 0xFF00E5C0 : 0xFF00A878;   // Pulse electric teal
            default: return 0xFF3E8BFF;                      // Classic blue
        }
    }

    /** cached wallpaper accent (API 31+ Material You); 0 = unavailable. */
    private static int sWallAccent;
    private static boolean sWallTried;

    /**
     * Real Material You: the system wallpaper's dominant color (no
     * permission needed — WallpaperManager.getWallpaperColors), nudged into
     * a legible band for the current skin lightness. Falls back to the
     * skin signature when the wallpaper is unavailable (lock-screen-only /
     * live wallpaper edge cases).
     */
    public static int wallpaperAccent(Context c) {
        if (!sWallTried) {
            sWallTried = true;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    android.app.WallpaperManager wm =
                            android.app.WallpaperManager.getInstance(c.getApplicationContext());
                    android.app.WallpaperColors wc = wm.getWallpaperColors(
                            android.app.WallpaperManager.FLAG_SYSTEM);
                    if (wc != null && wc.getPrimaryColor() != null) {
                        sWallAccent = wc.getPrimaryColor().toArgb();
                    }
                } catch (Throwable ignored) {}
            }
        }
        boolean dark = com.bolt.browser.util.Prefs.get(c).isDark();
        if (sWallAccent == 0) return skinAccent(com.bolt.browser.util.Prefs.get(c).skin(), dark);
        // luminance guard: too-dark accents vanish on black, too-pastel on white
        double l = androidx.core.graphics.ColorUtils.calculateLuminance(sWallAccent);
        if (dark && l < 0.18)
            return androidx.core.graphics.ColorUtils.blendARGB(sWallAccent, 0xFFFFFFFF, 0.45f);
        if (!dark && l > 0.72)
            return androidx.core.graphics.ColorUtils.blendARGB(sWallAccent, 0xFF000000, 0.35f);
        if (!dark && l < 0.12)
            return androidx.core.graphics.ColorUtils.blendARGB(sWallAccent, 0xFFFFFFFF, 0.4f);
        return sWallAccent;
    }

    /** reset the cached wallpaper accent (wallpaper changed mid-session). */
    public static void invalidateWallpaper() { sWallAccent = 0; sWallTried = false; }

    /**
     * The glass treatment for the floating browser bars (Aura) and the
     * fallback opaque treatment for every other skin. Returns a drawable:
     * Aura = translucent smoked glass with a hairline rim on the content
     * edge; Origins = warm surface with a soft stroke; Mono = pure flat bg;
     * classic = plain colorBg. topEdge=true for the top bar (rim at the
     * bottom edge), false for the bottom bar (rim on top).
     */
    public static android.graphics.drawable.Drawable barBg(Context c, boolean topEdge) {
        int style = skinStyle(c);
        GradientDrawable g = new GradientDrawable();
        if (style == 1) {
            boolean dark = com.bolt.browser.util.Prefs.get(c).isDark();
            // glass: faint sheen on the outer edge fading into smoked glass —
            // dark skin = dark smoke, light skin = bright frost; the page
            // glows through without turning the bar into noise
            g.setOrientation(topEdge ? GradientDrawable.Orientation.TOP_BOTTOM
                    : GradientDrawable.Orientation.BOTTOM_TOP);
            if (dark) {
                // v1.3.44 field fix ("glass icons in dark background not
                // quite visible"): the smoke went 90% -> 94% so bright
                // pages glow through LESS behind the (now brighter) icons
                g.setColors(new int[]{0x18FFFFFF, 0xF00E0E1A});
                g.setStroke(Math.max(1, dp(c, 1)), 0x33FFFFFF);
            } else {
                g.setColors(new int[]{0x66FFFFFF, 0xE6F4F5FB});
                g.setStroke(Math.max(1, dp(c, 1)), 0x2E1B1B2E);
            }
        } else {
            g.setColor(bg(c));
            if (style == 3) g.setStroke(Math.max(1, dp(c, 1)), outline(c));
        }
        return g;
    }

    /**
     * Skin-aware card treatment for programmatic surfaces: Aura gets the
     * translucent glass fill + hairline rim (dark smoke or bright frost per
     * lightness), Origins the soft outlined surface, Mono flat, classic the
     * plain surface. v1.3.35: radiusDp <= 0 means "use the skin's signature
     * radius" (Fx.radius).
     */
    public static android.graphics.drawable.Drawable cardBg(Context c, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, radiusDp > 0 ? radiusDp : radius(c)));
        int style = skinStyle(c);
        if (style == 1) {
            boolean dark = com.bolt.browser.util.Prefs.get(c).isDark();
            if (dark) {
                // v1.3.44: 92% glass fill — same wash-out fix as barBg
                g.setColor(0xF0141420);
                g.setStroke(Math.max(1, dp(c, 1)), 0x33FFFFFF);
            } else {
                g.setColor(0xE6F7F8FC);
                g.setStroke(Math.max(1, dp(c, 1)), 0x291B1B2E);
            }
        } else if (style == 3) {
            g.setColor(surface(c));
            g.setStroke(Math.max(1, dp(c, 1)), outline(c));
        } else {
            g.setColor(surface(c));
        }
        return g;
    }

    public static int accent(Context c) {
        com.bolt.browser.util.Prefs p = com.bolt.browser.util.Prefs.get(c);
        int i = p.accentIndex();
        if (i == -2) return wallpaperAccent(c);              // Material You
        if (i == -1) return skinAccent(p.skin(), p.isDark()); // Auto
        return ACCENTS[Math.max(0, Math.min(ACCENTS.length - 1, i))];
    }

    /** accent with alpha for soft backgrounds */
    public static int accentSoft(Context c) {
        int base = accent(c);
        // light accents (Mono paper, pale wallpaper tones) need a bit more
        // alpha to read as a tint on white surfaces
        double l = androidx.core.graphics.ColorUtils.calculateLuminance(base);
        int alpha = l > 0.6 ? 0x3C : 0x2E;
        return (base & 0x00FFFFFF) | (alpha << 24);
    }

    /**
     * A readable TEXT color ON an accent-filled surface (FABs, filled
     * buttons, active segments): white normally, near-black when the accent
     * itself is light (Mono's paper accent, bright wallpaper tones).
     */
    public static int onAccent(Context c) {
        return androidx.core.graphics.ColorUtils.calculateLuminance(accent(c)) > 0.55
                ? 0xFF14141A : 0xFFFFFFFF;
    }

    /**
     * The accent as a text/chip color: the accent itself, darkened when it
     * would be too pale to read on a light surface (light wallpapers).
     */
    public static int accentText(Context c) {
        int a = accent(c);
        if (!com.bolt.browser.util.Prefs.get(c).isDark()
                && androidx.core.graphics.ColorUtils.calculateLuminance(a) > 0.62)
            return androidx.core.graphics.ColorUtils.blendARGB(a, 0xFF000000, 0.4f);
        return a;
    }

    public static Bitmap screenshot(View v) {
        try {
            if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
            Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight() / 2, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            cv.translate(0, -v.getHeight() / 4f);
            v.draw(cv);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- JSON file IO ----------
    public static String readText(File f) {
        try {
            if (!f.exists()) return null;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int off = 0, n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
                return new String(buf, StandardCharsets.UTF_8);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    public static String readAsset(Context c, String name) {
        try (InputStream in = c.getAssets().open(name)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    /** v1.4.5 — ATOMIC WRITE: the old body truncated the target in place
     *  (new FileOutputStream(f)) and streamed the bytes — a process death
     *  mid-write (the OEM killer, a crash, a user swipe from recents)
     *  left a HALF-WRITTEN file, and every reader in this app swallows
     *  the parse error into an EMPTY array: the whole tab list, every
     *  folder, the whole home layout could vanish in one unlucky write
     *  (the v1.4.4 report: "some tabs or tab groups seem to disappear").
     *  The fix writes a temp file beside the target and RENAMES it over
     *  (POSIX rename is atomic: readers see either the whole old file or
     *  the whole new one, never a truncation). */
    public static boolean writeText(File f, String s) {
        try {
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(s.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(f)) {
                // a same-directory rename can only fail on exotic mounts —
                // fall back to the direct write rather than lose the save
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(s.getBytes(StandardCharsets.UTF_8));
                }
                boolean ignored = new File(f.getParentFile(), f.getName() + ".tmp").delete();
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static JSONObject readJson(File f) {
        String s = readText(f);
        if (s == null || s.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(s);
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    public static boolean writeJson(File f, JSONObject o) {
        return writeText(f, o.toString());
    }

    public static JSONArray readJsonArray(File f) {
        String s = readText(f);
        if (s == null || s.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(s);
        } catch (Throwable e) {
            return new JSONArray();
        }
    }

    public static boolean writeJsonArray(File f, JSONArray o) {
        return writeText(f, o.toString());
    }

    // ---------- domains ----------
    private static final Set<String> TWO_PART_TLDS = new HashSet<>();

    static {
        String[] t = {"co.uk", "org.uk", "ac.uk", "gov.uk", "co.jp", "co.kr", "co.in", "co.id",
                "com.au", "net.au", "org.au", "com.br", "com.cn", "com.tw", "com.hk", "com.sg",
                "com.my", "com.ph", "com.vn", "com.ar", "com.mx", "com.co", "com.ng", "com.eg",
                "com.pk", "com.bd", "org.bd", "net.bd", "ac.bd", "gov.bd", "co.za", "com.tr",
                "com.sa", "com.ua", "com.ru", "net.cn", "org.cn", "gov.cn", "edu.cn", "ne.jp",
                "or.jp", "co.nz", "org.nz", "co.il", "com.pl"};
        for (String x : t) TWO_PART_TLDS.add(x);
    }

    /** e.g. www.youtube.com -> youtube.com ; keeps 2-part TLDs intact */
    public static String baseDomain(String host) {
        if (host == null) return "";
        host = host.toLowerCase();
        if (host.startsWith("www.")) host = host.substring(4);
        String[] p = host.split("\\.");
        if (p.length <= 2) return host;
        String last2 = p[p.length - 2] + "." + p[p.length - 1];
        if (TWO_PART_TLDS.contains(last2) && p.length >= 3)
            return p[p.length - 3] + "." + last2;
        return last2;
    }

    public static String hostOf(String url) {
        try {
            if (url == null) return "";
            if (!url.startsWith("http")) {
                int s = url.indexOf("//");
                if (s >= 0) url = "http:" + url.substring(s);
                else if (!url.contains(":")) url = "http://" + url;
            }
            java.net.URL u = new java.net.URL(url);
            String h = u.getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (Throwable e) {
            return "";
        }
    }

    public static String pathOf(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String p = u.getPath();
            return p == null ? "" : p;
        } catch (Throwable e) {
            return "";
        }
    }

    public static boolean sameSite(String a, String b) {
        if (a == null || b == null) return false;
        return baseDomain(a).equals(baseDomain(b));
    }
}
