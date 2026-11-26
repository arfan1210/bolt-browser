package com.bolt.browser.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bolt.browser.R;
import com.bolt.browser.util.Diag;
import com.bolt.browser.util.Fx;
import com.bolt.browser.util.Prefs;
import com.bolt.browser.util.Qr;
import com.bolt.browser.util.QrCore;
import com.bolt.browser.util.Ui;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/**
 * v1.3.37 — the live QR scanner (menu → "Scan QR code", plus the long-press
 * quick action). Pure camera2 + the ZXing core that already ships for the
 * authenticator — NO new dependency, NO CameraX, the APK stays ~4.7 MB.
 *
 * The scan pipeline: ImageReader YUV_420_888 frames arrive on a background
 * camera thread; only the Y (luminance) plane is extracted — de-strided into
 * a tight w*h buffer — and handed to {@link QrCore#decodeLuma}. Throttled to
 * one decode per 250 ms: acquireLatestImage() drops everything in between,
 * so a 30 fps preview decodes at 4 fps and the CPU stays idle the rest of
 * the time (3 GB devices).
 *
 * Rotation: the YUV stream is raw sensor orientation (usually rotated 90°
 * for a portrait-held phone) — the ZXing detector infers orientation from
 * the finder patterns, so it decodes as-is; no pixel rotation needed. The
 * PREVIEW stream is NOT rotated by camera2 (that was camera1's
 * setDisplayOrientation; the v1.3.37 comment believed otherwise and portrait
 * shipped with no transform = the squished preview) —
 * applyPreviewTransform rotates by (sensorOrientation - displayRotation)
 * and cover-scales, upright and aspect-true in every orientation
 * (the v1.3.88 fix).
 *
 * Permission / hardware failures degrade to the gallery path (decode a
 * photo/screenshot via {@link Qr#decodeUri} — the v1.3.32 engine), never a
 * dead end. The decoded TEXT comes back through setResult ("qr_text");
 * the caller decides what to do with URLs vs plain text, so the scanner is
 * reusable (future: live-scanning authenticator setup QRs).
 */
public class QrScanActivity extends AppCompatActivity {

    public static final String EXTRA_TEXT = "qr_text";
    private static final int REQ_PERM = 201;
    private static final int REQ_GALLERY = 300;

    private TextureView tvPreview;
    // v1.4.26 — THE BUFFER PIN (the half-screen / 90-degree mismatch corpse
    // on the portrait Realme): the dims the SurfaceTexture buffer is ACTUALLY
    // pinned to. The commit-visible surface arrives BEFORE the camera opens,
    // so the first pin is the 640x480 placeholder; openCamera/createSession
    // re-pin to the camera preview size and RE-APPLY the transform against
    // the ACTUAL buffer dims — the old code computed the matrix against
    // previewSize but only re-ran it on view-size changes (a buffer-size
    // change never fired onSurfaceTextureSizeChanged), so the rotate/
    // cover-scale never landed after the re-pin and the landscape sensor
    // buffer rode the view untransformed (half-screen + the 90-degree
    // mismatch). probe-pinned in scripts/test_v1426.py.
    private int bufW = 640, bufH = 480;
    // v1.4.27 — THE FIRST-FRAME RE-DERIVE + the re-pin budget: the first
    // delivered frame re-applies the transform (a surface that configured
    // before the view laid out carried a stale/identity matrix — the
    // bottom-half-black report), and the fills-both-axes assertion gets
    // ONE re-pin attempt (a surface resized after the pin re-pins its own
    // buffer). Both flags are one-shot so no layout storm loops.
    private boolean firstFrameSeen = false;
    private boolean pinRetried = false;
    // v1.4.30 — THE PRODUCER-TRANSFORM PROBE (the still-90°-off corpse on
    // the Realme after the v1.4.29 view-space fix: the cover/center
    // geometry was RIGHT but the ANGLE input could be wrong). Some HALs
    // encode a display rotation into the SurfaceTexture PRODUCER transform
    // (getTransformMatrix) — and TextureView ALREADY applies that matrix
    // internally when it draws, so a rotation found there is part of the
    // DISPLAYED content. Our theta must SUBTRACT it, not add it again.
    // probeProducer arms on the first frame and after every re-pin (the
    // producer can appear/change late — the buffer pin may be what wakes
    // it); the probe reads the matrix on the next delivered frame and
    // re-applies the transform when the rotation moved.
    private final float[] stMatrix = new float[16];
    private int producerRot = 0;
    private boolean probeProducer = true;
    // v1.4.30 — THE USER'S OVERRIDE. v1.4.32 — THE BASE-ANGLE LAW SPLIT:
    // this field is now ONLY the additive USER offset (the rotate button's
    // own degrees, persisted under qrUserOffset.<sensor>.<display>, default
    // 0). The device's standing correction moved to the immutable
    // baseAngleFor table (below) — the v1.4.31 seed conflated the two under
    // one key, so a tap-cycle wrap (270 -> 0) or a reset destroyed the
    // device default and landscape (sensor=90, display=90) was never
    // seeded: the field needed a manual +90 tap EVERY session. Old
    // qrRotOffset.* keys are IGNORED (they conflate base+tap; migration
    // note dated 2026-11-26 — they are never read again).
    private int userOffset = 0;
    // v1.4.32 — the fallback leg BELOW measured in the chain
    // (measured -> table -> fill): the acceptance composes the solved undo
    // against THIS leg's implicit (the contamination guard).
    private String chainMode = "fill";
    // v1.4.31 — THE MEASURED-IMPLICIT LAW (the stretched-preview corpse:
    // the v1.4.29-30 matrix assumed the implicit buffer->view mapping is
    // the anisotropic FILL stretch, but the field device renders the
    // buffer UNIFORM COVER — the undo's own anisotropy
    // (bw/vw)/(bh/vh) = 3.784 was exactly the reported ~3.8x stretch, and
    // the producer=0-always-0 diag disproved every HAL-rotation story; the
    // user's +270 offset was the REAL rotation fix). MEASURE, DON'T ASSUME: once per
    // scan session, after the session configures and BEFORE the repeating
    // request starts (no camera frame can overwrite the pattern), a CPU
    // test pattern is posted through the SAME Surface the camera will use
    // and the delivered frame is read back with TextureView.getBitmap —
    // the stripe positions in the view bitmap solve the implicit affine
    // (view = i*buf + t per axis) exactly, and the undo becomes the
    // inverse of what THIS device actually does.
    private String undoMode = null;      // "measured" | "table" | "fill"
    private boolean calibArmed = false, calibDone = false;
    private float calibIx = -1f, calibIy = -1f, calibTx = Float.NaN, calibTy = Float.NaN;
    private ImageButton btnTorch;
    private View scanLine;
    private TextView tvHint;
    private TextView rotBadge;
    private LinearLayout topBar, bottomBar;

    private CameraDevice camera;
    private CameraCaptureSession session;
    private Surface previewSurface;
    private Size previewSize, yuvSize;
    private ImageReader reader;
    private HandlerThread camThread;
    private Handler camHandler;
    private android.animation.ValueAnimator scanAnim;
    private int sensorOrientation = 90;
    private boolean torchOn, cameraFailed, denied, done, flashAvailable;
    private long lastDecodeAt;
    // v1.4.33 — THE ACTIVE-ARRAY PADDING LAW (the slight-vertical-stretch
    // corpse: buf=1920x1088 is stride-aligned; the ACTIVE image is
    // 1920x1080 — the cover/undo math that used 1088 carried a 0.74%
    // vertical aspect error). activeW/H hold the ACTIVE (scene) dims the
    // buffer carries; 0 = unknown (every pad factor degenerates to 1 and
    // the law no-ops). Read from SENSOR_INFO_ACTIVE_ARRAY_SIZE at
    // openCamera (clamped to the stream) + the stride-alignment fallback.
    private int activeW = 0, activeH = 0;

    // ------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Fx.theme(this);          // status-bar / nav-bar colors follow the skin
        buildUi();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_PERM);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        tvPreview = new TextureView(this);
        root.addView(tvPreview, new FrameLayout.LayoutParams(-1, -1));
        tvPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                pinBuffer();
                openCamera();
                applyPreviewTransform(w, h);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                applyPreviewTransform(w, h);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            // v1.4.27 — THE FIRST-DELIVERED-FRAME RE-DERIVE (the camera
            // bottom-half-black corpse): the surface may deliver its first
            // frame AFTER the transform was last computed (the camera
            // opened late, the buffer took the real producer size only
            // then, the view laid out after). Re-deriving the matrix on the
            // FIRST delivered frame — with the ACTUAL pinned buffer dims +
            // the CURRENT view dims — guarantees a delivered frame is
            // never shown under a stale transform.
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
                try {
                    // v1.4.31 — THE CALIBRATION CAPTURE: the posted test
                    // pattern's frame is the one measured frame the session
                    // gets before the camera starts repeating. Grab it,
                    // solve the implicit affine, then let the camera in.
                    if (calibArmed && !calibDone) {
                        calibArmed = false;
                        if (finishCalibration()) {
                            reapplyTransform();
                            startRepeating();
                            return;
                        }
                        // solve failed: the bounded timeout arm (posted with
                        // the pattern) re-applies the table/fill undo and
                        // starts the repeating request
                        return;
                    }
                    // v1.4.30 — probe the producer transform on the FIRST
                    // delivered frame and after every re-pin (a freshly
                    // pinned buffer may carry a fresh producer transform)
                    if (probeProducer) {
                        probeProducer = false;
                        st.getTransformMatrix(stMatrix);
                        int pr = extractProducerRotation(stMatrix);
                        if (pr != producerRot) {
                            producerRot = pr;
                            applyPreviewTransform(tvPreview.getWidth(),
                                    tvPreview.getHeight());
                            return;
                        }
                    }
                    if (!firstFrameSeen) {
                        firstFrameSeen = true;
                        applyPreviewTransform(tvPreview.getWidth(), tvPreview.getHeight());
                    }
                } catch (Throwable ignored) {}
            }
        });

        addViewfinder(root);
        addTopBar(root);
        addBottomBar(root);

        // status-bar / nav-bar clearance without XML (API 24+); absolute
        // values — insets can re-fire, incremental padding would drift
        try {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = 0, bottom = 0, left = 0, right = 0;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets si = insets.getInsets(WindowInsets.Type.statusBars());
                    android.graphics.Insets ni = insets.getInsets(WindowInsets.Type.navigationBars());
                    top = si.top; bottom = ni.bottom;
                    left = ni.left; right = ni.right;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                    left = insets.getSystemWindowInsetLeft();
                    right = insets.getSystemWindowInsetRight();
                }
                topBar.setPadding(Fx.dp(this, 10) + left, Fx.dp(this, 10) + top,
                        Fx.dp(this, 10) + right, 0);
                bottomBar.setPadding(Fx.dp(this, 18) + left, 0,
                        Fx.dp(this, 18) + right, Fx.dp(this, 16) + bottom);
                return insets;
            });
        } catch (Throwable ignored) {}

        setContentView(root);
    }

    /** the dimmed mask with a clear square hole + corner brackets + a
     *  sweeping scan line — plain Views only (no custom drawing, no
     *  PorterDuff CLEAR pitfalls on GPU layers). v1.3.89: the hole grew —
     *  the field said "the qr scanning area is too small"; 250dp became
     *  ~78% of the screen's short edge (capped 340dp), the sweet spot
     *  between a generous target and leaving mask room to aim. */
    private void addViewfinder(FrameLayout root) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int shortEdge = Math.min(dm.widthPixels, dm.heightPixels);
        int hole = Math.min(Fx.dp(this, 340), (int) (shortEdge * 0.78f));
        int mask = 0x99000000;
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(hole, hole, Gravity.CENTER);

        // four dim rects framing the hole — sides span ONLY the hole's
        // vertical band so the dim layer never stacks (uniform 60% mask)
        View top = new View(this); top.setBackgroundColor(mask);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        View bottom = new View(this); bottom.setBackgroundColor(mask);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        View left = new View(this); left.setBackgroundColor(mask);
        root.addView(left, new FrameLayout.LayoutParams(-2, -1, Gravity.TOP | Gravity.START));
        View right = new View(this); right.setBackgroundColor(mask);
        root.addView(right, new FrameLayout.LayoutParams(-2, -1, Gravity.TOP | Gravity.END));
        // a centered transparent spacer drives the four rects' sizes
        View center = new View(this);
        center.setBackgroundColor(0x00000000);
        root.addView(center, hp);
        center.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            try {
                FrameLayout.LayoutParams p;
                p = (FrameLayout.LayoutParams) top.getLayoutParams();
                p.height = Math.max(0, t);
                p = (FrameLayout.LayoutParams) bottom.getLayoutParams();
                p.height = Math.max(0, root.getHeight() - b);
                p.topMargin = b;
                p = (FrameLayout.LayoutParams) left.getLayoutParams();
                p.width = Math.max(0, l);
                p.height = b - t;
                p.topMargin = t;
                p = (FrameLayout.LayoutParams) right.getLayoutParams();
                p.width = Math.max(0, root.getWidth() - r);
                p.height = b - t;
                p.topMargin = t;
                top.requestLayout(); bottom.requestLayout();
                left.requestLayout(); right.requestLayout();
            } catch (Throwable ignored) {}
        });

        // corner bracket frame
        View frame = new View(this);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setStroke(Fx.dp(this, 2), 0xE6FFFFFF);
        g.setCornerRadius(Fx.dp(this, 14));
        frame.setBackground(g);
        root.addView(frame, hp);

        // the sweeping scan line (accent) — centered on the hole, swept by
        // the animator's translationY
        scanLine = new View(this);
        android.graphics.drawable.GradientDrawable lg =
                new android.graphics.drawable.GradientDrawable();
        lg.setCornerRadius(Fx.dp(this, 2));
        lg.setColor(Fx.accent(this));
        scanLine.setBackground(lg);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                hole - Fx.dp(this, 28), Fx.dp(this, 3), Gravity.CENTER);
        root.addView(scanLine, slp);
        try {
            scanAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            scanAnim.setDuration(1400);
            scanAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            scanAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            scanAnim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                try {
                    scanLine.setTranslationY((f - 0.5f) * (hole - Fx.dp(this, 40)));
                } catch (Throwable ignored) {}
            });
            scanAnim.start();
        } catch (Throwable ignored) {}

        tvHint = new TextView(this);
        tvHint.setText(R.string.scan_qr_desc);
        tvHint.setTextColor(0xCCFFFFFF);
        tvHint.setTextSize(14);
        tvHint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        hlp.bottomMargin = Fx.dp(this, 120);
        root.addView(tvHint, hlp);
    }

    private void addTopBar(FrameLayout root) {
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Fx.dp(this, 10), Fx.dp(this, 10), Fx.dp(this, 10), 0);
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        ImageButton close = quietBtn(R.drawable.ic_close);
        close.setOnClickListener(v -> finish());
        topBar.addView(close, new LinearLayout.LayoutParams(-2, -2));

        TextView title = new TextView(this);
        title.setText(R.string.scan_qr);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(17);
        title.setTypeface(Ui.medium());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = Fx.dp(this, 8);
        topBar.addView(title, tlp);

        // v1.4.30 — THE ROTATION OVERRIDE (the rotation-input law): a
        // preview that still sits turned after the producer compensation
        // gets a bounded, PERSISTED user correction — tap shifts +90°,
        // long-press resets. The pill shows the live offset when non-zero.
        rotBadge = new TextView(this);
        rotBadge.setTextColor(0xFFFFFFFF);
        rotBadge.setTextSize(12);
        rotBadge.setTypeface(Ui.medium());
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Fx.dp(this, 10));
        bg.setColor(0x66FFFFFF);
        rotBadge.setBackground(bg);
        rotBadge.setPadding(Fx.dp(this, 8), Fx.dp(this, 3),
                Fx.dp(this, 8), Fx.dp(this, 3));
        rotBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.rightMargin = Fx.dp(this, 8);
        topBar.addView(rotBadge, blp);

        ImageButton rot = quietBtn(R.drawable.ic_reload);
        rot.setContentDescription("Rotate preview");
        // v1.4.32 — THE BASE-ANGLE LAW: the button cycles ONLY the user
        // offset; the device base (baseAngleFor) is immutable by any button.
        rot.setOnClickListener(v -> {
            userOffset = (userOffset + 90) % 360;
            saveUserOffset();
            Fx.toast(this, userOffset == 0
                    ? "Preview offset +90° (back to 0)"
                    : "Preview offset +" + userOffset + "° — long-press to reset");
            reapplyTransform();
        });
        rot.setOnLongClickListener(v -> {
            // v1.4.32 — reset lands on 0 — ONLY the user offset; the device
            // base is untouched (the upright-on-open angle rides the table).
            if (userOffset == 0) return false;
            userOffset = 0;
            saveUserOffset();
            Fx.toast(this, "Preview offset reset");
            reapplyTransform();
            return true;
        });
        topBar.addView(rot, new LinearLayout.LayoutParams(-2, -2));
    }

    /** the offset pill: visible only while the USER offset is non-zero
     *  (the device base never shows — it is the table's business). */
    private void renderRotBadge() {
        if (rotBadge == null) return;
        if (userOffset == 0) {
            rotBadge.setVisibility(View.GONE);
        } else {
            rotBadge.setVisibility(View.VISIBLE);
            rotBadge.setText("+" + userOffset + "°");
        }
    }

    /** v1.4.32 — the USER offset key: PER (sensor, display) pair — a fix
     *  made in portrait never leaks into landscape. The v1.4.31 key
     *  (qrRotOffset.*) held the CONFLATED angle (seed + taps) and is
     *  deliberately never read again: a tap-cycle wrap or a reset destroyed
     *  the device default under it. NEVER logged with values beyond the
     *  pair itself (no device identifiers ride the diag). */
    private String userOffsetKey(int degrees) {
        return "qrUserOffset." + sensorOrientation + "." + degrees;
    }

    /** the display rotation in degrees for the current (sensor, display)
     *  pair — one helper for the save/reset paths. */
    private int displayDegrees() {
        try {
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            return rotation == Surface.ROTATION_90 ? 90
                    : rotation == Surface.ROTATION_180 ? 180
                    : rotation == Surface.ROTATION_270 ? 270 : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void saveUserOffset() {
        try {
            Prefs.get(this).set(userOffsetKey(displayDegrees()), userOffset);
            renderRotBadge();
        } catch (Throwable ignored) {}
    }

    /** reads the persisted USER offset for the CURRENT (sensor, display)
     *  pair (a fresh read every apply: cheap, and a button tap lands on
     *  the very next transform). v1.4.32 — the DEFAULT is 0: the upright-
     *  on-open angle rides the baseAngleFor table, NOT this key — the two
     *  angles are one additive pair and only the offset is a user thing. */
    private int loadUserOffset(int degrees) {
        try {
            return Prefs.get(this).i(userOffsetKey(degrees), 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private void addBottomBar(FrameLayout root) {
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomBar.setPadding(Fx.dp(this, 18), 0, Fx.dp(this, 18), Fx.dp(this, 16));
        root.addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);

        btnTorch = quietBtn(R.drawable.ic_sun);
        btnTorch.setOnClickListener(v -> {
            torchOn = !torchOn;
            btnTorch.getBackground().setAlpha(torchOn ? 255 : 90);
            startRepeating();
        });
        btnTorch.getBackground().setAlpha(90);
        btns.addView(btnTorch, new LinearLayout.LayoutParams(-2, -2));

        View space = new View(this);
        btns.addView(space, new LinearLayout.LayoutParams(Fx.dp(this, 18), 1));

        ImageButton gallery = quietBtn(R.drawable.ic_folder);
        gallery.setOnClickListener(v -> pickFromGallery());
        btns.addView(gallery, new LinearLayout.LayoutParams(-2, -2));

        bottomBar.addView(btns, new LinearLayout.LayoutParams(-2, -2));
    }

    private ImageButton quietBtn(int icon) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setColorFilter(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(0x33FFFFFF);
        b.setBackground(g);
        int s = Fx.dp(this, 46);
        b.setLayoutParams(new ViewGroup.LayoutParams(s, s));
        b.setPadding(Fx.dp(this, 11), Fx.dp(this, 11), Fx.dp(this, 11), Fx.dp(this, 11));
        return b;
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        done = false;
        startThread();
        if (tvPreview.isAvailable()) openCamera();
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (scanAnim != null) try { scanAnim.cancel(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private void startThread() {
        if (camThread == null) {
            camThread = new HandlerThread("bolt-qr-cam");
            camThread.start();
            camHandler = new Handler(camThread.getLooper());
        }
    }

    private void stopThread() {
        if (camThread == null) return;
        camThread.quitSafely();
        try { camThread.join(300); } catch (InterruptedException ignored) {}
        camThread = null;
        camHandler = null;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERM) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            denied = false;
            if (tvPreview.isAvailable()) openCamera();
        } else {
            denied = true;
            tvHint.setText(R.string.qr_camera_denied);
        }
    }

    // ------------------------------------------------------------ camera

    private void openCamera() {
        if (done || denied || cameraFailed || camera != null || camHandler == null) return;
        // v1.4.31 — every camera open is a fresh measurement session
        calibDone = false;
        calibArmed = false;
        // v1.4.32 — the chain prior (the leg below measured) is fixed at
        // open; the calibration may promote undoMode to measured but never
        // demotes the prior
        chainMode = undoModeFor(android.os.Build.MODEL);
        undoMode = chainMode;
        try {
            Diag.log(this, "qr open model=" + android.os.Build.MODEL
                    + " sensor=" + sensorOrientation
                    + " display=" + displayDegrees()
                    + " base=" + baseAngleFor(android.os.Build.MODEL,
                            sensorOrientation, displayDegrees())
                    + " offset=" + loadUserOffset(displayDegrees())
                    + " mode=" + undoMode);
        } catch (Throwable ignored) {}
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            String back = null;
            for (String c : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(c);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (back == null && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) back = c;
                if (id == null) id = c;
            }
            if (back != null) id = back;
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (so != null) sensorOrientation = so;
            flashAvailable = Boolean.TRUE.equals(
                    ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            btnTorch.setVisibility(flashAvailable ? View.VISIBLE : View.GONE);
            StreamConfigurationMap map =
                    ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) { cameraFailure(); return; }
            previewSize = pickLargest(Arrays.asList(map.getOutputSizes(SurfaceTexture.class)), 1920);
            Size yuv = pickLargest(Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)), 1920);
            if (previewSize == null || yuv == null) { cameraFailure(); return; }
            // v1.4.33 — THE ACTIVE-ARRAY READ (the padding-compensation
            // input): the ACTIVE (scene) dims the buffer carries. The
            // characteristics may report the FULL sensor array (bigger than
            // any stream) — clamped to the stream dims it contributes
            // nothing and pad degenerates to 1.000; a HAL that reports the
            // stream-active crop lands directly. The stride-alignment
            // fallback below covers the field shape (buf 1920x1088 carries
            // a 1920x1080 16:9 scene — the 8 extra rows are allocation
            // padding, never scene).
            activeW = previewSize.getWidth();
            activeH = previewSize.getHeight();
            try {
                android.graphics.Rect aa = ch.get(
                        CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (aa != null && aa.width() > 0 && aa.height() > 0) {
                    activeW = Math.min(aa.width(), previewSize.getWidth());
                    activeH = Math.min(aa.height(), previewSize.getHeight());
                }
            } catch (Throwable ignored) {}
            int nine = Math.round(previewSize.getWidth() * 9f / 16f);
            if (activeH >= previewSize.getHeight()
                    && previewSize.getHeight() > nine
                    && previewSize.getHeight() - nine
                            <= Math.max(16, previewSize.getHeight() / 32)) {
                activeH = nine;
            }
            if (activeW <= 0 || activeH <= 0
                    || activeW > previewSize.getWidth()
                    || activeH > previewSize.getHeight()) {
                activeW = 0; activeH = 0;   // junk guard: the law no-ops
            }
            try {
                Diag.log(this, "qr active " + previewSize.getWidth() + "x"
                        + previewSize.getHeight() + " active=" + activeW + "x" + activeH
                        + " pad=" + String.format(java.util.Locale.US, "%.3f",
                                padY(previewSize.getWidth(), previewSize.getHeight())));
            } catch (Throwable ignored) {}
            // v1.4.26 — pin + re-apply (the surface may already be live with
            // the 640x480 placeholder buffer)
            pinBuffer();
            reapplyTransform();

            yuvSize = yuv;
            reader = ImageReader.newInstance(yuv.getWidth(), yuv.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onFrame, camHandler);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) return;
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice dev) {
                    camera = dev;
                    createSession();
                }
                @Override public void onDisconnected(CameraDevice dev) {
                    try { dev.close(); } catch (Throwable ignored) {}
                    if (camera == dev) camera = null;
                }
                @Override public void onError(CameraDevice dev, int error) { cameraFailure(); }
            }, camHandler);
        } catch (Throwable t) {
            cameraFailure();
        }
    }

    /** v1.3.37 — smallest size whose long edge is ≥ ~target (retired from
     *  the live path by v1.3.89, kept for reference/tests) */
    private Size pickSize(List<Size> sizes, int target) {
        try {
            Size best = null;
            for (Size s : sizes) {
                int longE = Math.max(s.getWidth(), s.getHeight());
                if (longE >= target / 2 && (best == null || longE < Math.max(
                        best.getWidth(), best.getHeight()))) best = s;
            }
            return best != null ? best : sizes.get(0);
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.3.89 — the BEST-QUALITY pick (the field: "make sure to give it
     *  the best camera quality available"): the LARGEST size whose long
     *  edge is ≤ cap (1920 — full HD; 4K preview buffers burn battery and
     *  bandwidth for zero decode gain, and some sensors only expose 4K in
     *  opaque formats). If every candidate exceeds the cap the SMALLEST
     *  wins (a 4K-only exotic beats a crash). Replaces the v1.3.37
     *  smallest-≥-target pick that ran 640-1280 lines: dense/small codes
     *  at arm's length were mush. */
    private Size pickLargest(List<Size> sizes, int cap) {
        try {
            Size best = null;
            for (Size s : sizes) {
                int longE = Math.max(s.getWidth(), s.getHeight());
                if (longE > cap) continue;
                if (best == null || longE > Math.max(best.getWidth(), best.getHeight())) best = s;
            }
            if (best == null) {
                for (Size s : sizes) {
                    int longE = Math.max(s.getWidth(), s.getHeight());
                    if (best == null || longE < Math.max(best.getWidth(), best.getHeight())) best = s;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private void createSession() {
        if (camera == null || reader == null) return;
        try {
            // v1.4.26 — THE PIN: the buffer the Surface is about to be built
            // on is pinned to the ACTUAL camera preview size here (and the
            // transform re-applied against those dims once the session
            // configures — never against a stale placeholder)
            pinBuffer();
            SurfaceTexture st = tvPreview.getSurfaceTexture();
            if (st == null) return;
            previewSurface = new Surface(st);
            camera.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            // v1.4.31 — THE MEASURED-IMPLICIT SESSION GATE:
                            // calibrate FIRST (the pattern post + one frame),
                            // start the repeating request only after the
                            // calibration frame was captured (or the bounded
                            // timeout fell back) — no camera frame can
                            // overwrite the test pattern mid-measure.
                            if (armCalibration()) return;
                            reapplyTransform();
                            startRepeating();
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            cameraFailure();
                        }
                    }, camHandler);
        } catch (Throwable t) {
            cameraFailure();
        }
    }

    // ------------------------------------------------------------ v1.4.31
    // THE MEASURED-IMPLICIT CALIBRATION (one pass per scan session)

    /** posts the stripe test pattern through the session's own preview
     *  Surface and arms the capture. The buffer is the REAL pinned camera
     *  size; the canvas maps 1:1 onto it. Runs on the camera handler after
     *  onConfigured, BEFORE startRepeating. Any failure falls back to the
     *  device table (undoModeFor). */
    private boolean armCalibration() {
        try {
            undoMode = chainMode;   // v1.4.32 — the chain prior, every fresh arm
            calibDone = false;
            calibArmed = false;
            SurfaceTexture st = tvPreview.getSurfaceTexture();
            if (st == null || previewSurface == null || bufW <= 0 || bufH <= 0) return false;
            // v1.4.32 — THE IDENTITY RESET (the contaminated-solve corpse:
            // the v1.4.31 arm posted the pattern WITHOUT resetting the view
            // transform, so finishCalibration solved implicit∘A_current —
            // the "measured" undo re-introduced the very stretch it exists
            // to undo). The capture must see the view EXACTLY as the
            // implicit mapping leaves it: setTransform(identity) BEFORE the
            // pattern posts. Restored right after the solve (the ok path)
            // and on the 900ms timeout path below.
            try { tvPreview.post(() -> { try {
                tvPreview.setTransform(new Matrix());
            } catch (Throwable ignored) {} }); } catch (Throwable ignored) {}
            android.graphics.Canvas c = previewSurface.lockCanvas(null);
            if (c == null) return false;
            try {
                c.drawColor(0xFF000000);
                android.graphics.Paint red = new android.graphics.Paint();
                red.setColor(0xFFFF2222);
                int sw = Math.max(2, bufW / 128);
                for (int j = 1; j <= stripeCount(); j++) {
                    int x = stripePos(bufW, j);
                    c.drawRect(x - sw / 2f, 0, x + sw / 2f, bufH, red);
                }
                android.graphics.Paint green = new android.graphics.Paint();
                green.setColor(0xFF22DD22);
                int sh = Math.max(2, bufH / 128);
                // green paints AFTER red — intersections read green, so a
                // scan line that happens to ride a red stripe still finds
                // the green bands crossing it
                for (int j = 1; j <= stripeCount(); j++) {
                    int y = stripePos(bufH, j);
                    c.drawRect(0, y - sh / 2f, bufW, y + sh / 2f, green);
                }
            } finally {
                try { previewSurface.unlockCanvasAndPost(c); } catch (Throwable ignored) {}
            }
            calibArmed = true;
            try {
                Diag.log(this, "qr calib arm buf=" + bufW + "x" + bufH
                        + " mode=" + undoMode);
            } catch (Throwable ignored) {}
            // bounded fallback: if the pattern frame never lands (compositor
            // busy, surface replaced) the table/fill mode + the camera win
            tvPreview.postDelayed(() -> {
                try {
                    if (!calibDone) {
                        calibArmed = false;
                        undoMode = chainMode;   // v1.4.32 — fall down the chain
                        try {
                            Diag.log(this, "qr calib timeout mode=" + undoMode);
                        } catch (Throwable ignored) {}
                    }
                    // v1.4.32 — restore the REAL transform on the timeout
                    // path too (the identity capture transform must never
                    // outlive the calibration)
                    reapplyTransform();
                    if (session != null && camera != null && !cameraFailed) startRepeating();
                } catch (Throwable ignored) {}
            }, 900L);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** the calibration frame arrived: read the view bitmap, locate the
     *  stripe runs, solve the implicit affine. UI thread (getBitmap).
     *  Returns true when the measured undo was stored. */
    private boolean finishCalibration() {
        try {
            int vw = tvPreview.getWidth(), vh = tvPreview.getHeight();
            if (vw <= 0 || vh <= 0) return false;
            // v1.4.32 — identity AGAIN right before the read (belt and
            // braces: the capture frame must be the implicit map alone —
            // if a stale transform rode the render, the acceptance below
            // rejects the solve down the chain instead of applying it)
            try { tvPreview.setTransform(new Matrix()); } catch (Throwable ignored) {}
            android.graphics.Bitmap b = tvPreview.getBitmap();
            if (b == null) return false;
            boolean ok = false;
            try {
                int[] px = new int[vw * vh];
                b.getPixels(px, 0, vw, 0, 0, vw, vh);
                // scan lines at 7/16, 9/16, 8/16 of the view: 7/16 and 9/16
                // fall BETWEEN the stripes under every center-anchored
                // mapping (fill/cover/fit), 8/16 rides the center stripe —
                // the first line that yields runs wins
                int[] rx = null;
                int[] rows = {7 * vh / 16, 9 * vh / 16, 8 * vh / 16};
                for (int ry : rows) {
                    if (rx != null && rx.length >= 2) break;
                    if (ry < 0 || ry >= vh) continue;
                    int[] row = rowPixels(px, vw, vh, ry);
                    int[] r = scanRunsArgb(row, vw, 1, true, 1);
                    if (r != null && r.length >= 2) rx = r;
                }
                int[] gy = null;
                int[] cols = {7 * vw / 16, 9 * vw / 16, 8 * vw / 16};
                for (int cx : cols) {
                    if (gy != null && gy.length >= 2) break;
                    if (cx < 0 || cx >= vw) continue;
                    int[] col = colPixels(px, vw, vh, cx);
                    int[] g = scanRunsArgb(col, 1, vh, false, 2);
                    if (g != null && g.length >= 2) gy = g;
                }
                float[] sx = rx != null ? solveAxis(rx, bufW, vw) : null;
                float[] sy = gy != null ? solveAxis(gy, bufH, vh) : null;
                // v1.4.32 — THE ACCEPTANCE GATE: the solve is applied ONLY
                // when the whole-pattern bijection passed (solveAxis non-null)
                // AND the compound anisotropy of the solved undo against the
                // chain prior's implicit sits within [0.98, 1.02] — a solve
                // captured under a stale non-identity transform reads off-
                // window and is REJECTED down the chain (measured -> table
                // -> fill), never applied.
                if (sx != null && sy != null) {
                    float priorX, priorY;
                    if ("table".equals(chainMode)) {
                        // v1.4.33 — the prior rides the ACTIVE (scene) dims
                        // (the padding law: k on the padded 1088 carried the
                        // 0.74% error into the acceptance window too)
                        float k = Math.max(vw / (float) awPrior(),
                                vh / (float) ahPrior());
                        priorX = k; priorY = k;
                    } else {
                        priorX = vw / (float) awPrior();
                        priorY = vh / (float) ahPrior();
                    }
                    if (measuredAcceptable(sx[0], sy[0], priorX, priorY)) {
                        calibIx = sx[0]; calibTx = sx[1];
                        calibIy = sy[0]; calibTy = sy[1];
                        undoMode = "measured";
                        calibDone = true;
                        ok = true;
                    } else {
                        undoMode = chainMode;
                    }
                }
            } finally {
                try { b.recycle(); } catch (Throwable ignored) {}
            }
            try {
                Diag.log(this, ok
                        ? "qr calib ok mode=measured ix=" + fmt(calibIx) + " iy=" + fmt(calibIy)
                        : "qr calib fail mode=" + chainMode);
            } catch (Throwable ignored) {}
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /** one row of the raster as a fresh array (the scanner walks it as a
     *  1-row image). */
    private static int[] rowPixels(int[] px, int w, int h, int y) {
        int[] out = new int[w];
        if (y < 0 || y >= h) return out;
        System.arraycopy(px, y * w, out, 0, w);
        return out;
    }

    private static int[] colPixels(int[] px, int w, int h, int x) {
        int[] out = new int[h];
        if (x < 0 || x >= w) return out;
        for (int y = 0; y < h; y++) out[y] = px[y * w + x];
        return out;
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.4f", v);
    }

    private void startRepeating() {
        if (session == null || camera == null) return;
        try {
            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            b.addTarget(reader.getSurface());
            // v1.3.89 — CONTINUOUS autofocus: the v1.3.37 session ran the
            // lens at whatever default the HAL chose (often fixed-focus-ish
            // passive), which softens close codes into mush. The picture
            // mode is the scanning contract: fast re-focus, tuned for still
            // subjects. Devices without AF silently ignore the key.
            try {
                b.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            } catch (Throwable ignored) {}
            if (flashAvailable) b.set(CaptureRequest.FLASH_MODE, torchOn
                    ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            session.setRepeatingRequest(b.build(), null, camHandler);
        } catch (Throwable t) {
            cameraFailure();
        }
    }

    private void closeCamera() {
        try {
            if (session != null) { session.close(); session = null; }
        } catch (Throwable ignored) {}
        try {
            if (camera != null) { camera.close(); camera = null; }
        } catch (Throwable ignored) {}
        try {
            if (reader != null) { reader.close(); reader = null; }
        } catch (Throwable ignored) {}
        previewSurface = null;
    }

    private void cameraFailure() {
        cameraFailed = true;
        closeCamera();
        runOnUiThread(() -> tvHint.setText(R.string.qr_camera_failed));
    }

    /** v1.4.26 — pin the SurfaceTexture buffer to the camera preview size
     *  (placeholder 640x480 until the camera opens) and remember the ACTUAL
     *  dims in bufW/bufH — the transform computes against what the buffer
     *  really is, never against a null guard. Producer-side call, safe off
     *  the UI thread. */
    private void pinBuffer() {
        try {
            SurfaceTexture st = tvPreview.getSurfaceTexture();
            if (st == null) return;
            bufW = previewSize != null ? previewSize.getWidth() : 640;
            bufH = previewSize != null ? previewSize.getHeight() : 480;
            st.setDefaultBufferSize(bufW, bufH);
            // v1.4.30 — a re-pinned buffer may carry a fresh producer
            // transform: re-probe on the next delivered frame
            probeProducer = true;
        } catch (Throwable ignored) {}
    }

    /** v1.4.30 — THE PRODUCER-ROTATION PROBE (PURE, probe-pinned by
     *  scripts/test_v1430.py across all four quarter-turns x both flip
     *  signs): reads the rotation a HAL bakes into the SurfaceTexture
     *  PRODUCER transform. getTransformMatrix() is what TextureView
     *  ALREADY applies internally when it draws — a rotation found there
     *  is part of the DISPLAYED content, so applyPreviewTransform must
     *  SUBTRACT it from theta, never add it again (the still-90°-off
     *  corpse). atan2(m[1], m[0]) is the screen-space rotation of the
     *  matrix, snapped to the nearest quarter-turn; the y-flip lives in
     *  m[5]/m[9] and its sign is DELIBERATELY ignored (every preview
     *  surface carries one — it is a flip, not a rotation). */
    static int extractProducerRotation(float[] m) {
        try {
            if (m == null || m.length < 16) return 0;
            double deg = Math.toDegrees(Math.atan2((double) m[1], (double) m[0]));
            if (Double.isNaN(deg)) return 0;
            int q = (int) Math.round(deg / 90.0);
            return ((q % 4) + 4) % 4 * 90;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------ v1.4.31
    // THE MEASURED-IMPLICIT LAW — the device table + the stripe solver.

    /** v1.4.31 — L2 THE DEVICE TABLE (probe-pinned): devices whose implicit
     *  buffer->view mapping is known-uniform-COVER get the cover-undo when
     *  measurement cannot run; every other device keeps the fill-undo (the
     *  historical default, correct on the majority). The table grows only
     *  from field read-backs (diag mode= tells which leg fired). */
    static final String[] COVER_UNDO_MODELS = { "RMX2020" };

    /** v1.4.31 — PURE (gate-pinned): the fallback undo mode for a device —
     *  "table" (cover-undo) for known devices, "fill" otherwise. */
    static String undoModeFor(String model) {
        if (model == null) return "fill";
        for (String m : COVER_UNDO_MODELS) {
            if (m.equals(model)) return "table";
        }
        return "fill";
    }

    /** v1.4.32 — THE BASE-ANGLE LAW (PURE, gate-pinned): the DEVICE base
     *  rotation correction — IMMUTABLE by any button. The v1.4.31 seed
     *  wrote its +270 into the SAME key the rotate button writes, so a
     *  tap-cycle wrap (270 -> 0) or a reset destroyed the device default
     *  and landscape was never seeded — the field needed a manual +90 tap
     *  EVERY session. Now the base is a pure table answer and the user's
     *  taps live in a separate additive offset (qrUserOffset.*).
     *  v1.4.33 — THE PORTRAIT-ONLY LAW: the scanner activity is LOCKED
     *  portrait in the manifest (QR decoding is orientation-independent —
     *  ZXing infers rotation from the finder patterns — and the landscape
     *  preview path was pure liability). The table keeps ONLY the portrait
     *  entry (RMX2020 sensor=90 display=0 -> 270, field-proven); every
     *  landscape display branch is DELETED (dated note 2026-11-27: the
     *  v1.4.32 (270 + display) % 360 rows for display 90/180/270 are
     *  retired with the landscape path — display= is always 0 now).
     *  Unknown models / sensors -> 0. The table grows only from field
     *  read-backs. */
    static int baseAngleFor(String model, int sensor, int display) {
        if ("RMX2020".equals(model) && sensor == 90 && display == 0) return 270;
        return 0;
    }

    /** v1.4.32 — PURE (gate-pinned): THE COMPOUND LINEAR ANISOTROPY — the
     *  x/y stretch ratio of the DISPLAYED map (assumed implicit ∘ undo ∘
     *  the recipe's linear part). The recipe's linear part (the uniform
     *  cover scale after a rotation) is isometric — the ratio rides
     *  diag(undo)∘diag(implicit) alone; 1.000 = square pixels. The old net=
     *  measured only the undo's own x/y ratio and could not see the corpse:
     *  the v1.4.30 configuration (anisotropic-fill undo on a device whose
     *  real implicit is uniform COVER) composes to 3.784 here — exactly
     *  the reported ~3.8x stretch; every self-consistent leg reads 1.000. */
    static float compoundAnisotropy(float impX, float impY, float undoX, float undoY) {
        try {
            float ax = undoX * impX, ay = undoY * impY;
            if (!(ax > 0f) || !(ay > 0f) || Float.isNaN(ax) || Float.isNaN(ay)) return 1f;
            return Math.max(ax, ay) / Math.min(ax, ay);
        } catch (Throwable t) {
            return 1f;
        }
    }

    /** v1.4.32 — PURE (gate-pinned): THE MEASURED-SOLVE ACCEPTANCE. The
     *  stripe solve is applied ONLY when its whole-pattern bijection passed
     *  (the caller holds that) AND the compound anisotropy of the solved
     *  undo against the chain prior's implicit sits within [0.98, 1.02].
     *  A capture that rode a stale non-identity transform (the
     *  contamination corpse) reads off-window and is rejected down the
     *  chain — never applied. */
    static boolean measuredAcceptable(float ix, float iy, float priorX, float priorY) {
        try {
            if (!(ix > 0f) || !(iy > 0f) || !(priorX > 0f) || !(priorY > 0f)) return false;
            float ax = priorX / ix, ay = priorY / iy;
            if (Float.isNaN(ax) || Float.isNaN(ay) || Float.isInfinite(ax)
                    || Float.isInfinite(ay)) return false;
            float r = Math.max(ax, ay) / Math.min(ax, ay);
            return r >= 0.98f && r <= 1.02f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** v1.4.33 — PURE (gate-pinned): the padding factor of an axis — the
     *  buffer span over the ACTIVE (scene) span. 1.000 when no active dims
     *  are known (the law no-ops) or the axis carries no padding. The
     *  field corpse: buf 1920x1088 carries a 1920x1080 scene — the
     *  vertical factor is 1088/1080 = 1.00741, exactly the reported
     *  slight vertical stretch. */
    static float padFactor(int buf, int active) {
        try {
            if (buf <= 0 || active <= 0 || active > buf) return 1f;
            return buf / (float) active;
        } catch (Throwable t) {
            return 1f;
        }
    }

    /** the buffer's vertical padding factor for the CURRENT open session
     *  (the diag's pad= and the transform's compensation). */
    private float padY(int bw, int bh) {
        if (activeW <= 0 || activeH <= 0) return 1f;
        return padFactor(bh, activeH);
    }

    /** v1.4.33 — the ACTIVE (scene) dims the chain-prior composes against:
     *  the active read when known, the buffer dims otherwise. */
    private int awPrior() { return activeW > 0 ? activeW : bufW; }
    private int ahPrior() { return activeH > 0 ? activeH : bufH; }

    /** v1.4.31 — PURE (gate-compiled): the stripe test-pattern geometry.
     *  7 vertical stripes at bufW*j/8 and 7 horizontal stripes at bufH*j/8
     *  (j=1..7) — NOT just the quarters: under a uniform-COVER implicit
     *  mapping (exactly the devices this law exists for) the buffer's
     *  overflow axis is cropped and the quarter stripes fall OFF-view;
     *  the 1/8 grid guarantees a center-symmetric visible subset on any
     *  monotonic center-anchored mapping. */
    static int stripeCount() { return 7; }

    static int stripePos(int span, int j) {
        return Math.round(span * j / 8f);
    }

    /** v1.4.31 — PURE (gate-compiled): stripe-run centers from an ARGB
     *  raster. Walks alongX (x within the middle row) or y within the
     *  middle column. test 1 = red (r>=192, g<=96, b<=96), 2 = green
     *  (g>=192, r<=96, b<=96). Adjacent hits within 3 samples join; runs
     *  shorter than 2 samples drop. Returns ascending centers, null on
     *  nothing found. Center rows/columns only — a row inside a
     *  perpendicular stripe finds nothing there, which the multi-row
     *  caller absorbs. */
    static int[] scanRunsArgb(int[] argb, int w, int h, boolean alongX, int test) {
        try {
            if (argb == null || w <= 0 || h <= 0) return null;
            int len = alongX ? w : h;
            int mid = (alongX ? h : w) / 2;
            int[] raw = new int[64];
            int n = 0, runStart = -1, last = -10;
            for (int p = 0; p < len; p++) {
                int c = alongX ? argb[mid * w + p] : argb[p * w + mid];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                boolean hit = (test == 1) ? (r >= 192 && g <= 96 && b <= 96)
                        : (g >= 192 && r <= 96 && b <= 96);
                if (hit) {
                    if (runStart < 0) runStart = p;
                    last = p;
                } else if (runStart >= 0 && p - last > 3) {
                    if (last - runStart >= 1 && n < raw.length) raw[n++] = (runStart + last) / 2;
                    runStart = -1;
                }
            }
            if (runStart >= 0 && last - runStart >= 1 && n < raw.length) raw[n++] = (runStart + last) / 2;
            if (n == 0) return null;
            int[] out = new int[n];
            System.arraycopy(raw, 0, out, 0, n);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.4.31 — PURE (gate-compiled): THE AXIS SOLVE. Stripes live at
     *  span*j/8 (j=1..7); a center-anchored mapping shows a CENTER-SYMMETRIC
     *  subset of them. From the detected run centers: uniform step d ->
     *  i = 8*d/span; the first visible stripe's index j1 follows from the
     *  symmetry around j=4; t = runs[0] - i*span*j1/8. Then the WHOLE
     *  pattern is predicted and bijected against the detection — any
     *  mismatch rejects the observation (a rotated or junk mapping lands
     *  in the fallback chain, never a wrong matrix). Returns {i, t} (view
     *  = i*buf + t) or null.
     *  Fills/cover/fit synthetic bitmaps solve exactly — probe-pinned by
     *  scripts/test_v1431.py section B. */
    static float[] solveAxis(int[] runs, int span, int viewLen) {
        try {
            if (runs == null || span <= 0 || viewLen <= 0) return null;
            int n = runs.length;
            if (n < 2 || n > stripeCount()) return null;
            for (int k = 1; k < n; k++) {
                if (runs[k] <= runs[k - 1]) return null;   // must ascend
            }
            double sum = 0;
            for (int k = 1; k < n; k++) sum += runs[k] - runs[k - 1];
            double d = sum / (n - 1);
            if (!(d > 0.5) || Double.isNaN(d)) return null;   // sub-pixel step = junk
            float i = (float) (8.0 * d / span);
            if (!(i > 0.01f) || !(i < 40f) || Float.isNaN(i) || Float.isInfinite(i)) return null;
            int j1 = (int) Math.ceil(4.0 - (n - 1) / 2.0);
            if (j1 < 1 || j1 + n - 1 > stripeCount()) return null;
            float t = runs[0] - i * span * j1 / 8f;
            // whole-pattern verification: every stripe predicted on-view
            // must match a detected run, and vice versa (bijection)
            float tol = Math.max(2f, viewLen / 64f);
            boolean[] matched = new boolean[n];
            int visible = 0;
            for (int j = 1; j <= stripeCount(); j++) {
                float p = i * span * j / 8f + t;
                if (p < -tol || p > viewLen + tol) continue;
                visible++;
                boolean hit = false;
                for (int k = 0; k < n; k++) {
                    if (!matched[k] && Math.abs(runs[k] - p) <= tol) { matched[k] = true; hit = true; break; }
                }
                if (!hit) return null;
            }
            if (visible != n) return null;
            for (boolean mm : matched) if (!mm) return null;
            return new float[]{i, t};
        } catch (Throwable t) {
            return null;
        }
    }

    /** v1.4.26 — re-apply the transform against the ACTUAL pinned buffer
     *  dims (a buffer-size change never fires onSurfaceTextureSizeChanged —
     *  only a view-size change does); posted to the UI thread because
     *  TextureView.setTransform is a view operation and this is called from
     *  the camera handler. */
    private void reapplyTransform() {
        try {
            runOnUiThread(() -> {
                try { applyPreviewTransform(tvPreview.getWidth(), tvPreview.getHeight()); }
                catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** v1.3.88 — the preview transform, FIXED. The v1.3.37 comment believed
     *  "preview streams are display-rotated by the framework" — that is
     *  camera1 setDisplayOrientation behavior; camera2 does NOT rotate, and
     *  portrait (ROTATION_0) applied NO transform at all, so the landscape
     *  sensor buffer was stretched onto the portrait TextureView — the field
     *  report: "QR scanning opens the camera with a squished view of the
     *  camera video". The transform is now the general recipe: rotate the
     *  buffer by (sensorOrientation - displayRotation + 360) % 360 (the
     *  classic JPEG-orientation formula — the angle that makes the image
     *  upright for how the device is held) and cover-scale so the
     *  upright content COVERS the view (aspect preserved, never squished).
     *  The YUV analysis path is untouched: ZXing infers rotation from the
     *  finder patterns, so raw sensor frames still decode as-is.
     *
     *  v1.4.29 — THE VIEW-SPACE FIX (the rotated-90° + part-of-the-screen
     *  + black-region corpse): TextureView.setTransform() transforms the
     *  VIEW's own local geometry — the buffer is ALREADY stretched to the
     *  view bounds by the implicit buffer-to-view stretch before the
     *  matrix sees it. The old matrix reasoned about raw BUFFER dimensions
     *  (it omitted the stretch-undo step), so the matrix that shipped was
     *  not the matrix the view applied: a buffer-space "fills the view"
     *  check could pass while the APPLIED matrix covered only part of the
     *  view (the reported 90° rotation + a large black lower region in
     *  portrait). The matrix is now computed in the coordinates the
     *  TextureView consumes:
     *    M = T(viewCenter) · S(cover) · R(theta) · T(-bufCenter) · S(bw/vw, bh/vh)
     *  — undo the implicit stretch (view coords -> buffer coords), run the
     *  cover recipe in buffer coords, center in the view. Uniform scale
     *  only (never squish), both axes covered, content centered. */
    private void applyPreviewTransform(int viewW, int viewH) {
        if (viewW == 0 || viewH == 0) return;
        try {
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = rotation == Surface.ROTATION_90 ? 90
                    : rotation == Surface.ROTATION_180 ? 180
                    : rotation == Surface.ROTATION_270 ? 270 : 0;
            // v1.4.30 — THE COMPENSATED ANGLE: the producer's own rotation
            // (already on screen via TextureView's internal transform) is
            // SUBTRACTED; the user override ADDS last (the user sees the
            // real render — their tap must win over every heuristic).
            // v1.4.32 — THE BASE-ANGLE LAW: the angle is now TWO additive
            // pieces — the immutable device base (baseAngleFor, per model+
            // sensor+display) + the user offset (the button's key, default
            // 0) — the old conflation let a tap-cycle wrap or a reset
            // destroy the device default. Both wrap in +720 so the result
            // stays non-negative.
            userOffset = loadUserOffset(degrees);
            int base = baseAngleFor(android.os.Build.MODEL, sensorOrientation, degrees);
            int theta = (sensorOrientation - degrees - producerRot + base
                    + userOffset + 720) % 360;
            renderRotBadge();
            // v1.4.26 — the ACTUAL pinned buffer dims (the 640x480 pre-open
            // placeholder made the old previewSize-null early return leave
            // the view UNTRANSFORMED forever — the race); the recipe is the
            // v1.3.88 general one: rotate by the JPEG-orientation angle, then
            // cover-scale the upright content, centered.
            // v1.4.33 — THE ACTIVE-ARRAY PADDING LAW: the cover/undo math
            // rides the ACTIVE (scene) dims — the buffer's stride-aligned
            // overhang (1088 for a 1920x1080 scene) is never scene, and
            // math that used it carried the 0.74% vertical aspect error the
            // field reported. Unknown active dims degenerate to the buffer.
            float bw = bufW;
            float bh = bufH;
            float aw = activeW > 0 ? activeW : bw;
            float ah = activeH > 0 ? activeH : bh;
            // upright content dimensions after the rotation (ACTIVE dims)
            float uw = (theta % 180 == 0) ? aw : ah;
            float uh = (theta % 180 == 0) ? ah : aw;
            // cover scale: fill the view, keep the aspect, crop the overflow
            float s = Math.max(viewW / uw, viewH / uh);
            // v1.4.27 — THE FILLS-BOTH-AXES ASSERTION (the bottom-half-black
            // corpse): the cover recipe's own invariant is that the scaled
            // upright content is >= the view on BOTH axes (that is what
            // "cover" means — a stale/mismatched buffer dims pair breaks it
            // and a half-black panel is exactly the symptom). When the
            // assertion fails, the surface's buffer is re-pinned to the
            // camera preview size (a surface resized after the pin) and the
            // matrix recomputed once — bounded by the one-shot pinRetried.
            boolean fills = uw * s >= viewW - 1f && uh * s >= viewH - 1f;
            if (!fills && !pinRetried) {
                pinRetried = true;
                try { pinBuffer(); } catch (Throwable ignored) {}
                try { Diag.log(this, "qr preview re-pin view=" + viewW + "x" + viewH +
                        " buf=" + bufW + "x" + bufH + " sensor=" + sensorOrientation +
                        " theta=" + theta); } catch (Throwable ignored) {}
                applyPreviewTransform(viewW, viewH);
                return;
            }
            float cx = viewW / 2f, cy = viewH / 2f;
            Matrix matrix = new Matrix();
            // v1.4.29 — THE VIEW-SPACE MATRIX (the buffer-space corpse):
            // setTransform() consumes view-local geometry whose content is
            // the buffer ALREADY mapped to the view bounds by the IMPLICIT
            // buffer->view mapping, so the first ops map view coords BACK
            // to buffer coords; then the classic recipe runs in buffer
            // coords and centers in the view.
            // v1.4.31 — THE UNDO IS NO LONGER ASSUMED, IT IS MEASURED (the
            // stretched-preview corpse: the anisotropic-FILL undo below
            // multiplied the display stretch by (bw/vw)/(bh/vh) = 3.784 on
            // the field device, whose implicit mapping is uniform COVER).
            // Three modes:
            //   measured — the stripe calibration solved the implicit
            //     affine (view = i*buf + t per axis); the undo is its exact
            //     inverse [1/i, 0, -t/i; 0, 1/i, -t/i].
            //   table — the device table says uniform COVER: undo k =
            //     max(vw/bw, vh/bh), centered (1/k scale + the centering
            //     translate).
            //   fill — the historical anisotropic assumption (correct on
            //     the majority of devices; unchanged behavior).
            // The rest of the recipe (T(-bufCenter) R(theta) S(cover)
            // T(center)) is untouched in all three modes.
            float undoSx, undoSy;
            if ("measured".equals(undoMode) && calibDone
                    && calibIx > 0f && calibIy > 0f) {
                undoSx = 1f / calibIx;
                undoSy = 1f / calibIy;
                matrix.postScale(undoSx, undoSy);
                matrix.postTranslate(-calibTx / calibIx, -calibTy / calibIy);
            } else if ("table".equals(undoMode)) {
                // v1.4.33 — the cover-undo k rides the ACTIVE (scene) dims:
                // k built on the padded buffer height (1088) picked the
                // wrong governing axis by the 0.74% padding factor
                float k = Math.max(viewW / (float) aw, viewH / (float) ah);
                if (!(k > 0f) || Float.isNaN(k) || Float.isInfinite(k)) k = 1f;
                undoSx = 1f / k;
                undoSy = 1f / k;
                matrix.postScale(undoSx, undoSy);
                matrix.postTranslate(aw / 2f - viewW / (2f * k),
                        ah / 2f - viewH / (2f * k));
            } else {
                undoSx = bw / viewW;
                undoSy = bh / viewH;
                matrix.postScale(undoSx, undoSy);   // undo the implicit buffer->view stretch (FILL)
            }
            if (undoMode == null) undoMode = "fill";
            // v1.4.33 — THE PADDING COMPENSATION (after the undo ops, per
            // the active-array law): the undo ops above leave BUFFER
            // coordinates; the scene occupies only the ACTIVE region
            // (1920x1080 of the 1920x1088 buffer). postScale(buf/active)
            // maps buffer coords to SCENE coords — the 0.74% vertical
            // aspect error the field reported is exactly the factor this
            // cancels. activeW/H == 0 (unknown) degenerates to identity.
            float padX = padFactor(bufW, activeW);
            float padYf = padFactor(bufH, activeH);
            if (padX != 1f || padYf != 1f) matrix.postScale(padX, padYf);
            matrix.postTranslate(-aw / 2f, -ah / 2f);   // scene center -> origin
            matrix.postRotate(theta);                    // upright
            matrix.postScale(s, s);                      // cover BOTH axes
            matrix.postTranslate(cx, cy);                // center in the view
            tvPreview.setTransform(matrix);
            // v1.4.27 — THE READABLE RESIDUAL (v1.4.29 format: view/buffer
            // dims, sensor + display rotation, theta, the upright CONTENT
            // size the applied matrix shows, and the cover scale — a field
            // mismatch is readable from the log line; a covered transform
            // carries no MISMATCH mark). No URLs, no tokens, nothing
            // device-identifying rides this line.
            try {
                // v1.4.30 — the readable residual grew the producer angle,
                // the user offset and the COMPENSATED theta (a field
                // mismatch is now readable end to end: which input lied —
                // sensor, producer, or neither+user-corrected). MISMATCH
                // still marks ONLY a real cover failure. No URLs, no
                // tokens, nothing device-identifying rides this line.
                // v1.4.31 — the line grew mode= (measured|table|fill —
                // which undo leg fired) and net=. v1.4.32 — net= became the
                // COMPOUND linear anisotropy (assumed implicit ∘ undo — the
                // recipe's uniform-cover/rotation part is isometric) and
                // the line grew base= (the immutable device base — the
                // v1.4.31 line could not separate the base from the user
                // offset, so a tap-destroyed seed read as "no offset").
                // 1.000 = square pixels under the mode's model. Same
                // privacy posture as before.
                // v1.4.33 — THE HONEST NET (the tautology corpse): the
                // v1.4.32 net= composed each mode with its OWN undo —
                // table and fill read 1.000 BY CONSTRUCTION, so the log
                // could not see the padding corpse (mode=table net=1.000
                // on a stretched preview). Now: a MEASURED solve exists →
                // net = compound(measured implicit, APPLIED undo incl. the
                // padding compensation) — a real agreement number; any
                // other mode → agree=unknown (never a fake 1.000). The
                // line grew active= + pad= (the padding law's inputs).
                String agree;
                if ("measured".equals(undoMode) && calibDone && calibIx > 0f) {
                    float net = compoundAnisotropy(calibIx, calibIy,
                            undoSx * padFactor(bufW, activeW),
                            undoSy * padFactor(bufH, activeH));
                    agree = "net=" + String.format(java.util.Locale.US, "%.3f", net);
                } else {
                    agree = "agree=unknown";
                }
                Diag.log(this, "qr preview view=" + viewW + "x" + viewH +
                        " buf=" + Math.round(bw) + "x" + Math.round(bh) +
                        " active=" + Math.round(aw) + "x" + Math.round(ah) +
                        " pad=" + String.format(java.util.Locale.US, "%.3f",
                                Math.max(padFactor(bufW, activeW),
                                        padFactor(bufH, activeH))) +
                        " sensor=" + sensorOrientation + " display=" + degrees +
                        " base=" + base +
                        " producer=" + producerRot + " offset=" + userOffset +
                        " theta=" + theta +
                        " content=" + Math.round(uw * s) + "x" + Math.round(uh * s) +
                        " scale=" + String.format(java.util.Locale.US, "%.3f", s) +
                        " mode=" + undoMode +
                        " " + agree +
                        (fills ? "" : " MISMATCH"));
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ decode

    private void onFrame(ImageReader r) {
        if (done || yuvSize == null) { Image skip = r.acquireLatestImage();
            if (skip != null) try { skip.close(); } catch (Throwable ignored) {} return; }
        Image im = null;
        try {
            long now = SystemClock.uptimeMillis();
            im = r.acquireLatestImage();
            if (im == null || im.getFormat() != ImageFormat.YUV_420_888) return;
            if (now - lastDecodeAt < 250) return;     // 4 decodes/sec max
            lastDecodeAt = now;
            byte[] y = yPlane(im);
            String text = QrCore.decodeLuma(y, im.getWidth(), im.getHeight());
            // v1.4.32 — THE CHROMA LEG (failure-only: the colour-on-colour
            // corpse — blue-on-green modules of equal luma are invisible to
            // every Y-plane binarizer). U/V are extracted HERE and ONLY here,
            // after the whole luma ladder failed — the success path never
            // pays the extraction (the 4 fps budget law holds). The tight
            // half-res buffers persist across frames (the plane-reuse law).
            if (text == null || text.trim().isEmpty()) {
                try {
                    Image.Plane pu = im.getPlanes()[1];
                    Image.Plane pv = im.getPlanes()[2];
                    int cw = (im.getWidth() + 1) / 2, ch = (im.getHeight() + 1) / 2;
                    if (uBuf == null || uBuf.length < cw * ch) uBuf = new byte[cw * ch];
                    if (vBuf == null || vBuf.length < cw * ch) vBuf = new byte[cw * ch];
                    destridePlane(pu, uBuf, cw, ch);
                    destridePlane(pv, vBuf, cw, ch);
                    byte[] chroma = QrCore.chromaOf(uBuf, vBuf, cw, ch);
                    if (chroma != null) {
                        text = QrCore.decodeChroma(chroma, cw, ch);
                    }
                } catch (Throwable ignored) {}
            }
            // v1.4.33 — THE LIVE DOT-CLOSING LEG (failure-only, the LAST
            // leg): dot/rounded modules (chess.com-style) mis-bin under
            // every binarizer; the closing morphology merges them. The
            // live leg rides DILATE-ONLY at HALF resolution (the frame-
            // budget permission — one max-filter pass on a quarter-size
            // plane, then the dual-binarizer core there) so the failure
            // path stays inside the 4 fps budget. decodeLuma's own
            // full-res closing leg ran above; this is the final geometry
            // (half + dilated) for the frames where only the combination
            // cracks it.
            if (text == null || text.trim().isEmpty()) {
                try {
                    int hw = im.getWidth() / 2, hh = im.getHeight() / 2;
                    if (hw >= 64 && hh >= 64) {
                        byte[] hs = QrCore.halfLuma(y, im.getWidth(), im.getHeight());
                        if (hs != null) {
                            byte[] dl = QrCore.dilate3x3(hs, hw, hh);
                            if (dl != null) {
                                text = QrCore.decodeClosed(dl, hw, hh);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (text != null && !text.trim().isEmpty()) deliver(text.trim());
        } catch (Throwable ignored) {
        } finally {
            if (im != null) try { im.close(); } catch (Throwable ignored) {}
        }
    }

    /** the Y plane de-strided into a tight w*h buffer (rowStride can exceed
     *  width; some devices also use pixelStride > 1 — handled). v1.3.89: the
     *  buffer is REUSED across frames — at 1920-wide YUV the old per-frame
     *  allocation was ~2 MB x 4/s of pure GC churn on the camera thread. */
    private static byte[] yBuf = null;

    private static byte[] yPlane(Image im) {
        Image.Plane p = im.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int w = im.getWidth(), h = im.getHeight();
        int rs = p.getRowStride(), ps = p.getPixelStride();
        if (yBuf == null || yBuf.length < w * h) yBuf = new byte[w * h];
        byte[] out = yBuf;
        int cap = buf.capacity();
        if (ps == 1) {
            for (int row = 0; row < h; row++) {
                int off = row * rs;
                if (off >= cap) break;
                int len = Math.min(w, cap - off);
                buf.position(off);
                buf.get(out, row * w, len);
            }
        } else {
            for (int row = 0; row < h; row++) {
                for (int col = 0; col < w; col++) {
                    int idx = row * rs + col * ps;
                    if (idx >= cap) break;
                    out[row * w + col] = buf.get(idx);
                }
            }
        }
        return out;
    }

    // v1.4.32 — the chroma leg's reused half-res planes (the plane-reuse
    // law: allocated once, grown only when a bigger frame arrives — the
    // failure-leg extraction never churns GC on the camera thread).
    private static byte[] uBuf = null, vBuf = null;

    /** v1.4.32 — de-stride one YUV plane (rowStride can exceed width;
     *  pixelStride > 1 handled) into a tight w*h buffer. Shared by the
     *  U/V failure-leg extraction. */
    private static void destridePlane(Image.Plane p, byte[] out, int w, int h) {
        ByteBuffer buf = p.getBuffer();
        int rs = p.getRowStride(), ps = p.getPixelStride();
        int cap = buf.capacity();
        if (ps == 1) {
            for (int row = 0; row < h; row++) {
                int off = row * rs;
                if (off >= cap) break;
                int len = Math.min(w, cap - off);
                buf.position(off);
                buf.get(out, row * w, len);
            }
        } else {
            for (int row = 0; row < h; row++) {
                for (int col = 0; col < w; col++) {
                    int idx = row * rs + col * ps;
                    if (idx >= cap) break;
                    out[row * w + col] = buf.get(idx);
                }
            }
        }
    }

    private void deliver(String text) {
        runOnUiThread(() -> {
            if (done || isFinishing()) return;
            done = true;
            try {
                tvPreview.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            } catch (Throwable ignored) {}
            Intent out = new Intent();
            out.putExtra(EXTRA_TEXT, text);
            setResult(RESULT_OK, out);
            finish();
        });
    }

    // ------------------------------------------------------------ gallery fallback

    private void pickFromGallery() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(Intent.createChooser(i, getString(R.string.qr_from_gallery)),
                    REQ_GALLERY);
        } catch (Throwable t) {
            Fx.toast(this, getString(R.string.qr_not_found));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_GALLERY || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        new Thread(() -> {
            final String text = Qr.decodeUri(this, data.getData(), 2000);
            runOnUiThread(() -> {
                if (text != null && !text.trim().isEmpty()) deliver(text.trim());
                else Fx.toast(this, getString(R.string.qr_not_found));
            });
        }, "bolt-qr-gallery").start();
    }
}
