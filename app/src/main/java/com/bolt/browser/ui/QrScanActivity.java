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
    private ImageButton btnTorch;
    private View scanLine;
    private TextView tvHint;
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
                if (!firstFrameSeen) {
                    firstFrameSeen = true;
                    try {
                        applyPreviewTransform(tvPreview.getWidth(), tvPreview.getHeight());
                    } catch (Throwable ignored) {}
                }
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
        } catch (Throwable ignored) {}
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
            int theta = (sensorOrientation - degrees + 360) % 360;
            // v1.4.26 — the ACTUAL pinned buffer dims (the 640x480 pre-open
            // placeholder made the old previewSize-null early return leave
            // the view UNTRANSFORMED forever — the race); the recipe is the
            // v1.3.88 general one: rotate by the JPEG-orientation angle, then
            // cover-scale the upright content, centered
            float bw = bufW;
            float bh = bufH;
            // upright content dimensions after the rotation
            float uw = (theta % 180 == 0) ? bw : bh;
            float uh = (theta % 180 == 0) ? bh : bw;
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
            // the buffer ALREADY stretched to the view bounds, so the first
            // op maps view coords BACK to buffer coords (undo the implicit
            // stretch); then the classic recipe runs in buffer coords and
            // centers in the view. Uniform scale only — aspect preserved;
            // both axes covered (the fills assertion below); centered.
            matrix.postScale(bw / viewW, bh / viewH);   // undo the implicit buffer->view stretch
            matrix.postTranslate(-bw / 2f, -bh / 2f);   // buffer center -> origin
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
                Diag.log(this, "qr preview view=" + viewW + "x" + viewH +
                        " buf=" + Math.round(bw) + "x" + Math.round(bh) +
                        " sensor=" + sensorOrientation + " display=" + degrees +
                        " theta=" + theta +
                        " content=" + Math.round(uw * s) + "x" + Math.round(uh * s) +
                        " scale=" + String.format(java.util.Locale.US, "%.3f", s) +
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
