package com.bolt.browser.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.bolt.browser.R
import com.bolt.browser.util.Fx
import com.bolt.browser.util.Prefs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * v1.4.1 — BOLT DASH, the About-page easter egg (the ask: "add an android
 * like easter egg that unlocks by tapping something 7 times in the about
 * page to open a short offline game, like some infinite rng game that is
 * very small in size").
 *
 * THE LINEAGE: Android's own eggs are tapped out on the version row and
 * hide a game (Flappy Droid, the Llama, Nyan) — Bolt's door is the About
 * version row (7 taps, [AboutActivity.eggTap]) and this is the game.
 *
 * THE SHAPE: a one-tap infinite dodger. The bolt glides forward forever;
 * every obstacle is PURE RNG (gap center, gap size, spacing — no two runs
 * ever repeat, "infinite rng" by construction); tap to dash up, gravity
 * pulls back; one touch ends the run; score = bars passed; best persists.
 *
 * THE SIZE LAW (the APK budget, v1.3.73): ONE file, pure Canvas drawing,
 * no image assets, no sound, no engine, no deps — a few KB in the dex.
 */
class BoltEggActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the game holds the screen awake — a run is short but intense
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val game = GameView(this)
        val root = FrameLayout(this)
        root.addView(game, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private class GameView(ctx: BoltEggActivity) : View(ctx) {

        // ---- the physics, all in px/s and px — sized from dp once ----
        // v1.4.4 — THE PACING PASS (the report: "the game has too fast
        // vertical movements, making playing unenjoyable and finishes too
        // quickly"). The v1.4.1 constants were flappy-twitch: the flap arc
        // peaked in 0.28 s (680/2400) — a full rise-and-fall inside half a
        // second, so the bolt spent the run either slamming the ceiling or
        // diving at the floor and a bar arrived every ~1 s at 250 px/s. The
        // retune keeps the JUMP HEIGHT IDENTICAL (h = v²/2g: 480²/2360 =
        // 97.6 dp ≈ the old 680²/4800 = 96.3 dp) while stretching the arc
        // to 0.41 s (+44% more air per tap), slows the world 18% (more
        // reaction time per bar) and widens every gap ~8% — the run reads
        // as floaty and lasts, not as a coin toss.
        private val density = Fx.dp(ctx, 1)
        private val gravity = 1180f * density          // px/s^2 (was 2400)
        private val flapV = -480f * density            // px/s impulse (was -680)
        private val speed0 = 205f * density            // px/s forward speed (was 250)
        private val playerR = 15f * density            // the bolt's hit circle

        // ---- state ----
        private var started = false
        private var dead = false
        private var score = 0
        private var best = Prefs.get(ctx).i("eggBest", 0)
        private var py = 0f                            // the bolt's center y
        private var vy = 0f
        private var worldX = 0f                        // distance travelled
        private var lastT = 0L
        private var titleH = 0f

        /** one obstacle: x is its LEFT edge in screen px, gap the pass band */
        private class Bar(var x: Float, var gapTop: Float, var gapBot: Float,
                          var w: Float, var scored: Boolean)

        private val bars = ArrayList<Bar>()
        private var rng = Random(System.currentTimeMillis())

        // ---- colors resolve the live theme (the skin engine's law) ----
        private val cBg = Fx.surface(ctx)
        private val cBar = Fx.dim(ctx)
        private val cBolt = Fx.accentText(ctx)
        private val cText = Fx.txt(ctx)
        private val cDim = Fx.dim(ctx)
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val boltPath = android.graphics.Path()

        /** the lightning-bolt silhouette, unit coords scaled to r at draw */
        private val BOLT = arrayOf(
            0.05f to -1.00f, -0.52f to 0.08f, -0.10f to 0.08f,
            -0.34f to 1.00f, 0.52f to -0.12f, 0.10f to -0.12f)

        init {
            resetRun()
        }

        private fun resetRun() {
            started = false
            dead = false
            score = 0
            // v1.4.3 — THE RETRY CORPSE (the "cannot continue the game after
            // first failure, the screen freezes and does nothing" report):
            // this used to be `py = -1f // placed at first layout` — but
            // onSizeChanged fires ONCE per view lifetime, not per RUN. On
            // retry the bolt stayed at py = -1 (above the ceiling), so the
            // FIRST physics frame after the tap hit the ceiling-collision
            // branch and died instantly, off-screen — every retry tap drew
            // the SAME dead frame and the game read as frozen. THE LAW: a
            // "placed at layout" sentinel is a FIRST-RUN contract; every
            // later reset must re-derive the placement from the live size.
            py = if (height > 0) height * 0.44f else -1f
            vy = 0f
            worldX = 0f
            bars.clear()
            rng = Random(System.currentTimeMillis())
            lastT = SystemClock.elapsedRealtime()
            postInvalidateOnAnimation()
        }

        /** spawn obstacles ahead of the right edge; EVERY parameter is RNG.
         *  v1.4.2 — THE FROZEN-FRONTIER CORPSE (the "no game launches, just
         *  a black screen" report): lastRight was a val read from the last
         *  bar ONCE, before the loop — the frontier never advanced, so the
         *  first call (bars empty: lastRight = w+40dp < horizon = w+80dp)
         *  looped FOREVER, adding bars during the FIRST layout pass. The
         *  main thread never finished the first frame (a black screen — the
         *  window never drew), while allocating Bar after Bar at 100% CPU —
         *  the whole app went molasses-slow and every later tap felt dead.
         *  THE LAW: a spawn loop must advance the frontier it tests, every
         *  iteration — a while whose condition reads a val it never writes
         *  is an infinite loop wearing a for-loop costume. */
        private fun maybeSpawn() {
            val w = width.toFloat()
            if (w <= 0) return
            var right = if (bars.isEmpty()) w + 40f * density
                else bars[bars.size - 1].x + bars[bars.size - 1].w
            val horizon = w + 80f * density
            while (right < horizon) {
                // v1.4.4 — the pacing pass rides the spawn too: wider
                // spacing (235–375 dp, was 220–350), wider gaps (200–280 dp,
                // was 185–255) — with the slowed world each bar now leaves a
                // beat to line up instead of demanding a twitch.
                val spacing = (235f + rng.nextFloat() * 140f) * density
                val bw = (64f + rng.nextFloat() * 22f) * density
                val gap = (200f + rng.nextFloat() * 80f) * density
                val top = titleH + (26f + rng.nextFloat()
                        * max(1f, height - titleH - gap - 60f * density))
                val x = right + spacing
                bars.add(Bar(x, top, top + gap, bw, false))
                right = x + bw                // the frontier advances — always
            }
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            titleH = h * 0.12f
            if (py < 0) py = h * 0.44f
            maybeSpawn()
        }

        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawColor(cBg)

            // the obstacles: rounded bars, the gap is the pass band
            paint.color = cBar
            val r = 10f * density
            for (b in bars) {
                if (b.x > w || b.x + b.w < 0) continue
                c.drawRoundRect(b.x, titleH, b.x + b.w, b.gapTop, r, r, paint)
                c.drawRoundRect(b.x, b.gapBot, b.x + b.w, h, r, r, paint)
            }

            // the bolt (a filled lightning silhouette, rotating with vy)
            val px = w * 0.30f
            c.save()
            // v1.4.4 — the tilt divisor follows the retuned physics (14 → 10:
            // the slowed flap/fall vy range is narrower, so the same tilt
            // needs a softer divisor to keep the full ±38° sweep readable)
            c.rotate(min(38f, max(-38f, vy / (10f * density))), px, py)
            boltPath.reset()
            val s = playerR * 1.25f
            for (i in BOLT.indices) {
                val x = px + BOLT[i].first * s
                val y = py + BOLT[i].second * s
                if (i == 0) boltPath.moveTo(x, y) else boltPath.lineTo(x, y)
            }
            boltPath.close()
            paint.color = cBolt
            paint.style = android.graphics.Paint.Style.FILL
            c.drawPath(boltPath, paint)
            c.restore()

            // the score + best
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.textSize = 34f * density
            paint.color = cText
            paint.style = android.graphics.Paint.Style.FILL
            c.drawText(score.toString(), w / 2f, titleH * 0.72f, paint)
            if (best > 0) {
                paint.textSize = 13f * density
                paint.color = cDim
                c.drawText(context.getString(R.string.egg_best, best),
                        w / 2f, titleH * 0.95f, paint)
            }

            // the prompt lines
            if (!started || dead) {
                paint.textSize = 17f * density
                paint.color = cText
                c.drawText(context.getString(if (dead) R.string.egg_tap_to_retry
                        else R.string.egg_tap_to_start).uppercase(),
                        w / 2f, h * 0.60f, paint)
                if (dead) {
                    paint.textSize = 13f * density
                    paint.color = cDim
                    c.drawText(context.getString(R.string.egg_game_name),
                            w / 2f, h * 0.60f + 26f * density, paint)
                }
            }
        }

        private fun step(now: Long) {
            var dt = (now - lastT) / 1000f
            lastT = now
            if (!started || dead) return
            dt = min(dt, 0.033f)                       // cap the frame leap
            vy += gravity * dt
            py += vy * dt
            worldX += speed0 * dt
            for (b in bars) b.x -= speed0 * dt
            // retire what left the screen; keep the world infinite
            while (bars.isNotEmpty() && bars[0].x + bars[0].w < -20f * density) bars.removeAt(0)
            maybeSpawn()
            // score: the bolt cleared the bar's right edge
            val px = width * 0.30f
            for (b in bars) {
                if (!b.scored && b.x + b.w < px) {
                    b.scored = true
                    score++
                }
            }
            // collision: the hit circle vs the two bar rects + the floor/ceiling
            val top = titleH
            val bot = height.toFloat()
            if (py - playerR < top || py + playerR > bot) { die(); return }
            for (b in bars) {
                if (px + playerR < b.x || px - playerR > b.x + b.w) continue
                if (py - playerR < b.gapTop || py + playerR > b.gapBot) { die(); return }
            }
        }

        private fun die() {
            dead = true
            if (score > best) {
                best = score
                try { Prefs.get(context).set("eggBest", best) } catch (ignored: Throwable) {}
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_DOWN) return true
            // v1.4.3 — the retry door flows THROUGH the start door: a tap on
            // the death screen resets (which re-places the bolt — see
            // resetRun) and then takes the exact first-tap path (start + a
            // flap), so a retry plays identically to a first run. The old
            // `if (dead) { resetRun(); started = true; return }` branch also
            // skipped the flap, dropping the bolt from rest.
            if (dead) resetRun()
            if (!started) { started = true; lastT = SystemClock.elapsedRealtime() }
            vy = flapV
            postInvalidateOnAnimation()
            return true
        }

        override fun onWindowVisibilityChanged(visible: Int) {
            super.onWindowVisibilityChanged(visible)
            if (visible != VISIBLE) { if (started && !dead) started = false }  // pause off-screen
            else lastT = SystemClock.elapsedRealtime()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            lastT = SystemClock.elapsedRealtime()
        }

        // THE LOOP — no timer, no choreographer callback stack: each frame
        // steps on the frame clock, then schedules the next vsync
        // (postInvalidateOnAnimation). ~60fps, zero work when not visible.
        override fun computeScroll() {
            if (visibility == VISIBLE && isShown) {
                step(SystemClock.elapsedRealtime())
                if (started && !dead) postInvalidateOnAnimation()
            }
        }
    }
}
