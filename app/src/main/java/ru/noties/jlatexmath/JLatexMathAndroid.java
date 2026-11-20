package ru.noties.jlatexmath;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * v1.4.24 — THE FONT-PACK SPLIT (the size law, take two; the seam re-point).
 *
 * Upstream's JLatexMathAndroid read EVERYTHING the engine touches from the
 * APK assets (its two doors: getResourceAsStream for the XML descriptors
 * and loadTypeface for the TTF binaries). The thin AAR
 * (app/libs/jlatexmath-thin.aar, built by scripts/thin_jlatexmath.py) drops
 * this class and the 30 bundled TTFs; THIS file is the same-FQCN
 * replacement with the same public surface (init/getResourceAsStream/
 * loadTypeface) so the engine bytecode links unchanged. The doors now try
 * the APK assets first (the XML descriptors stayed bundled), then the
 * ON-DEMAND font pack directory (com.bolt.browser.util.MathFonts —
 * filesDir/jlmfonts, fetched on the first math render exactly like the
 * v1.3.73 tesseract models). A missing/failed pack simply throws here,
 * AiRender.mathDrawable catches, and the reply renders through the
 * v1.4.20 LaTeX-subset spans — never a crash.
 */
public abstract class JLatexMathAndroid {

    private static final String BASE = "org/scilab/forge/jlatexmath/";

    @SuppressLint("StaticFieldLeak")
    private static Context sContext = null;

    public static void init(Context context) {
        sContext = context.getApplicationContext();
    }

    public static InputStream getResourceAsStream(String path) {
        final Context c = context();
        try {
            return c.getAssets().open(BASE + path);
        } catch (IOException e) {
            // v1.4.24: the stripped TTF binaries live in the downloaded pack
            File f = com.bolt.browser.util.MathFonts.packFile(c, path);
            if (f != null) {
                try {
                    return new FileInputStream(f);
                } catch (IOException ignored) {}
            }
            throw new RuntimeException(e);
        }
    }

    @NonNull
    public static Typeface loadTypeface(@NonNull String path) {
        final Context c = context();
        try {
            return Typeface.createFromAsset(c.getAssets(), BASE + path);
        } catch (Throwable t) {
            // v1.4.24: the stripped TTF binaries live in the downloaded pack
            File f = com.bolt.browser.util.MathFonts.packFile(c, path);
            if (f != null && f.length() > 0) {
                return Typeface.createFromFile(f);
            }
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            throw new RuntimeException(t);
        }
    }

    private JLatexMathAndroid() {
    }

    private static Context context() {
        Context context = sContext;
        if (context == null) {
            throw new NullPointerException("Please call `#init(Context)` method to initialize jLatexMath");
        }
        return context;
    }
}
