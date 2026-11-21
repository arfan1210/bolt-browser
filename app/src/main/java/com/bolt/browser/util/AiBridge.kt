package com.bolt.browser.util

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * v1.3.74 — THE AI BRIDGE (new code → Kotlin; the standing language rule).
 * v1.3.75 — THE BUGFIX + CHAT TURN: the Gemini envelope is unwrapped
 * before parsing (see [geminiText] — the v1.3.74 "AI found no text"
 * killer), the model chain moved to the current 3.x free tier (2.5 is
 * deprecated 2026-12-01), the vision request forces JSON output with a
 * 16384-token budget, configDialog is re-editable any time with an
 * onSaved continuation, and [chat] gives Bolt AI multi-turn text chat.
 *
 * THE USER'S IDEA, VERBATIM: "For this onscreen non text translations, I
 * suppose an ai powered translator like gemini might be better and more
 * accurate, is it possible to like link my google account from the google
 * accounts logged in in bolt to use my accounts gemini to translate the
 * onscreen stuff? Or some other open ai that needs no log in? … can we
 * built a bolt wise ai powered by any ai of my choice that is already
 * logged in in bolt?"
 *
 * THE FEASIBILITY VERDICT (recorded here so nobody re-opens it blind):
 *  - Driving gemini.google.com through the account session Bolt already
 *    holds (a hidden WebView typing into the web app) was evaluated and
 *    REJECTED: the web app is an undocumented, obfuscated RPC that
 *    changes without notice, UI automation breaks on every redesign, and
 *    automating a personal account risks anti-abuse flags. Not shippable.
 *  - A keyless "open AI" does not exist — every reliable vision endpoint
 *    needs a credential. The nearest thing to "already logged in" is the
 *    Gemini API key from Google AI Studio: the SAME Google account, a
 *    one-minute setup, free tier, plain HTTPS (works on de-Googled
 *    devices — no GMS involvement).
 *  - So "Bolt-wise AI" ships as THIS bridge: one locally-stored key, two
 *    provider shapes (Gemini native + any OpenAI-compatible endpoint —
 *    OpenRouter, Groq, z.ai, a local Ollama), and a strict
 *    OCR+translate-in-one-shot prompt. The vision model reads manga
 *    better than any classical OCR the APK could ever afford.
 *
 * Privacy posture: the key and the config never leave the device except
 * as the Authorization header to the endpoint THE USER chose; the caller
 * confirms before any screenshot is sent; errors never echo the key.
 */
object AiBridge {

    /** v1.4.20 — the standing cut-off error as a CONTRACT (the tile door in
     * ScreenTranslate keys on it; the literal is the one the v1.4.19 gate
     * pinned — it lives here now). */
    const val CUT_OFF_ERR = "the AI reply was cut off (try a less dense screen)"

    /**
     * v1.3.75 — Java-friendly link opener (BrowserActivity implements it
     * with its openNewTab path; a Kotlin (String)->Unit would force Java
     * callers to return kotlin.Unit). A `fun interface` compiles to a plain
     * SAM interface Java can lambda directly.
     */
    fun interface LinkOpener { fun open(url: String) }

    /** the Gemini REST host — pinned (the relay discipline) */
    private const val GEMINI_HOST = "generativelanguage.googleapis.com"

    /** default model + the fallback chain when the default 404s.
     *  v1.3.75: 2.5-flash is DEPRECATED (Google retires it 2026-12-01,
     *  auto-routing to 3.5-flash-lite; new keys already 404 on it) — the
     *  chain now leads with the CURRENT free-tier flash-lite and falls back
     *  to the -latest alias (always exists) then the current flagship
     *  flash. Verified against aistudio.google.com/docs/models 2026-11-08. */
    private val GEMINI_DEFAULT = "gemini-3.5-flash-lite"
    private val GEMINI_FALLBACKS = arrayOf("gemini-flash-latest", "gemini-3.8-flash")

    private const val DEFAULT_OPENAI_BASE = "https://openrouter.ai/api/v1"

    /** last short error (toast-safe, key-free) */
    @Volatile var lastError: String = ""

    /** v1.4.20 — set by parseBoxes when the array parse FAILED but the
     *  LENIENT SALVAGE recovered complete objects (a partial reply): the
     *  ladder reads it to prefer the tile road over showing half a screen
     *  when the envelope says MAX_TOKENS. */
    @Volatile private var lastParseSalvaged: Boolean = false
        private set

    // ---------------- config (SharedPreferences "bolt_ai") ----------------

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("bolt_ai", Context.MODE_PRIVATE)

    @JvmStatic
    fun configured(ctx: Context): Boolean =
        prefs(ctx).getString("key", "")!!.isNotBlank()

    private fun prov(ctx: Context): String =
        if (prefs(ctx).getString("prov", "gemini") == "openai") "openai" else "gemini"

    private fun key(ctx: Context): String = prefs(ctx).getString("key", "")!!

    private fun model(ctx: Context): String =
        prefs(ctx).getString("model", "")!!.ifBlank {
            if (prov(ctx) == "gemini") GEMINI_DEFAULT else ""
        }

    /** v1.4.21 — THE TRANSLATE-MODEL PICKER (the field ask's better half):
    *  screen translate may ride a DIFFERENT model than chat — a dense
    *  screen needs a higher output ceiling than a lite chat model
    *  offers (the field corpse was gemini-3.5-flash-lite capping out).
    *  Empty = the shared chat model, exactly as before. */
    private fun translateModel(ctx: Context): String {
        val t = prefs(ctx).getString("translateModel", "")!!.trim()
        return t.ifEmpty { model(ctx) }
    }

    private fun baseUrl(ctx: Context): String =
        prefs(ctx).getString("base", "")!!.ifBlank { DEFAULT_OPENAI_BASE }

    /** the human provider name for dialogs + diag lines */
    @JvmStatic
    fun providerName(ctx: Context): String =
        if (prov(ctx) == "gemini") "Gemini" else "OpenAI-compatible"

    /** the config dialog — reachable from the Translate-screen picker (its
     *  "AI settings" button, v1.3.75: editable ANY time, not just when the
     *  key is empty), from the Bolt AI panel's gear, and from the AI row
     *  when it runs keyless. [onSaved] fires after a NON-EMPTY key lands so
     *  the caller can continue the action that asked for setup (the v1.3.74
     *  flow dead-ended here — the user had to re-pick AI and re-capture). */
    @JvmStatic
    fun configDialog(a: Activity, onOpenUrl: (String) -> Unit) =
            configDialog(a, onOpenUrl, null)

    @JvmStatic
    fun configDialog(a: Activity, onOpenUrl: (String) -> Unit,
                     onSaved: (() -> Unit)?) {
        try {
            val pad = Fx.dp(a, 18).toInt()
            val wrap = LinearLayout(a)
            wrap.orientation = LinearLayout.VERTICAL
            wrap.setPadding(pad, pad / 2, pad, 0)

            fun tv(text: String, size: Int, color: Int, bold: Boolean): TextView {
                val t = TextView(a)
                t.text = text
                t.textSize = size.toFloat()
                t.setTextColor(color)
                t.setPadding(0, Fx.dp(a, 10).toInt(), 0, Fx.dp(a, 4).toInt())
                if (bold) t.typeface = android.graphics.Typeface.DEFAULT_BOLD
                return t
            }
            fun et(prefill: String, hint: String, secret: Boolean): EditText {
                val e = EditText(a)
                e.setText(prefill)
                e.hint = hint
                e.textSize = 13f
                if (secret) e.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                return e
            }

            wrap.addView(tv("Bolt AI translation (beta)", 16, Color.rgb(20, 21, 25), true))
            wrap.addView(tv(
                "Uses a vision AI to read and translate what is on screen — " +
                        "far better on manga, handwriting and stylized text than the " +
                        "built-in OCR.\n\n" +
                        "Recommended: a FREE Gemini key from Google AI Studio (works with " +
                        "your Google account, no billing). Or point it at any " +
                        "OpenAI-compatible endpoint (OpenRouter, Groq, z.ai, local Ollama…).\n\n" +
                        "The key is stored only on this device. When you use AI translate, " +
                        "the screenshot is sent to the provider you choose.",
                13, Color.rgb(70, 73, 80), false))

            wrap.addView(tv("Provider", 12, Color.rgb(120, 124, 134), true))
            val provSpinner = Spinner(a)
            val provs = arrayOf("Gemini (Google AI Studio — free)", "OpenAI-compatible endpoint")
            provSpinner.adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, provs)
            provSpinner.setSelection(if (prov(a) == "openai") 1 else 0)
            wrap.addView(provSpinner)

            wrap.addView(tv("API key", 12, Color.rgb(120, 124, 134), true))
            val keyEt = et(key(a), "Paste the key here", true)
            wrap.addView(keyEt)

            wrap.addView(tv("Model (optional)", 12, Color.rgb(120, 124, 134), true))
            val modelEt = et(prefs(a).getString("model", "")!!, if (prov(a) == "gemini") GEMINI_DEFAULT else "e.g. google/gemini-3.5-flash-lite", false)
            wrap.addView(modelEt)

            // v1.4.21 — THE TRANSLATE-MODEL PICKER (the dense-screen cure's
            // better half): screen translate can ride its own higher-output
            // model; empty = the shared model above
            wrap.addView(tv("Translate model (optional — screen translate only)", 12, Color.rgb(120, 124, 134), true))
            val tlModelEt = et(prefs(a).getString("translateModel", "")!!, if (prov(a) == "gemini") "e.g. gemini-3.5-flash (a higher ceiling than -lite)" else "e.g. google/gemini-3.5-flash", false)
            wrap.addView(tlModelEt)

            wrap.addView(tv("Base URL (OpenAI-compatible only)", 12, Color.rgb(120, 124, 134), true))
            val baseEt = et(baseUrl(a), DEFAULT_OPENAI_BASE, false)
            wrap.addView(baseEt)

            val keyLink = tv("Get a free Gemini key \u2192 aistudio.google.com/apikey", 13,
                Color.rgb(50, 110, 220), true)
            keyLink.setPadding(0, Fx.dp(a, 12).toInt(), 0, 0)
            wrap.addView(keyLink)

            val scroll = ScrollView(a)
            scroll.addView(wrap)

            AlertDialog.Builder(a)
                .setTitle("Bolt AI (beta)")
                .setView(scroll)
                .setPositiveButton("Save") { d, _ ->
                    val k = keyEt.text.toString().trim()
                    val p = if (provSpinner.selectedItemPosition == 1) "openai" else "gemini"
                    prefs(a).edit()
                        .putString("prov", p)
                        .putString("key", k)
                        .putString("model", modelEt.text.toString().trim())
                        .putString("translateModel", tlModelEt.text.toString().trim())
                        .putString("base", baseEt.text.toString().trim())
                        .apply()
                    Toast.makeText(a,
                        if (k.isBlank()) "AI key removed" else "AI key saved (stays on this device)",
                        Toast.LENGTH_SHORT).show()
                    try { d.dismiss() } catch (_: Throwable) {}
                    if (k.isNotBlank() && onSaved != null) {
                        try { onSaved() } catch (_: Throwable) {}
                    }
                }
                .setNeutralButton("Get key") { _, _ ->
                    onOpenUrl("https://aistudio.google.com/apikey")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            keyLink.setOnClickListener { onOpenUrl("https://aistudio.google.com/apikey") }
        } catch (t: Throwable) {
            try { Toast.makeText(a, "Could not open the AI settings", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        }
    }

    // ---------------- the vision request ----------------

    /** one recognized+translated text placement. x/y/w/h and th are
     * FRACTIONS of the sent image (0-1) — normalized in the parser so the
     * caller never cares which coordinate dialect the model answered in.
     * th is the original text's single-line height (0 = unknown);
     * vert marks a vertically-reading original (manga columns). */
    class AiBox(val x: Float, val y: Float, val w: Float, val h: Float,
                val orig: String, val trans: String,
                val th: Float = 0f, val vert: Boolean = false)

    /** language code → the name the prompt reads better with */
    private fun langName(tl: String): String = when (tl) {
        "en" -> "English"; "bn" -> "Bengali"; "hi" -> "Hindi"; "ar" -> "Arabic"
        "ur" -> "Urdu"; "ru" -> "Russian"; "zh-CN" -> "Simplified Chinese"
        "zh-TW" -> "Traditional Chinese"; "ja" -> "Japanese"; "ko" -> "Korean"
        "fr" -> "French"; "de" -> "German"; "es" -> "Spanish"; "pt" -> "Portuguese"
        "it" -> "Italian"; "id" -> "Indonesian"; "tr" -> "Turkish"; "vi" -> "Vietnamese"
        "th" -> "Thai"
        else -> tl
    }

    /** v1.3.77 — THE NATIVE-DIALECT PROMPT. v1.3.76 asked for [x,y,w,h]
     * PIXEL boxes "because models are better at pixel coordinates" — the
     * field proved the opposite: "the manga text place detection and
     * overlaying covering only the old text portion got worse". The
     * Gemini family is POST-TRAINED on box_2d = [ymin, xmin, ymax, xmax]
     * as INTEGERS normalized to 0-1000 (Google's own image-understanding
     * contract, stable since 2.0 — verified 2026-11-09 against the live
     * docs and third-party evals). Asking for any other dialect invites
     * the model to answer in its native one anyway, and a 0-1000 answer
     * read as pixels of a 2200 px image is a semi-random box. The prompt
     * now speaks the model's own language EXACTLY, and the parser
     * (see [parseBoxes]) still normalizes every legacy dialect an
     * OpenAI-compatible endpoint might answer in. */
    private fun prompt(tl: String, iw: Int, ih: Int): String {
        return ("You are an expert manga and comic translator with perfect OCR. " +
                "Find EVERY piece of text, including small, stylised or VERTICAL " +
                "Japanese manga text inside speech bubbles, sound effects, signs " +
                "and captions. Read vertical manga text column by column, right " +
                "to left. Respond with ONLY a JSON array (no markdown fences, no " +
                "commentary) where each element is " +
                "{\"box_2d\":[ymin,xmin,ymax,xmax],\"th\":n," +
                "\"text\":\"exact original text\"," +
                "\"translation\":\"natural ${langName(tl)} translation\"} where " +
                "box_2d holds INTEGERS normalized to 0-1000 across the whole " +
                "image (ymin,xmin = the top-left corner of the TIGHT bounding " +
                "box around that text, ymax,xmax = its bottom-right corner — " +
                "the same 0-1000 grid every box_2d answer uses); one speech " +
                "bubble or text group is ONE object with ONE tight box; th is the " +
                "approximate pixel height of ONE line of that text, also as an " +
                "integer 0-1000 of the image height. Translate stylised sound effects by " +
                "their meaning (e.g. \u30c9\u30ad\u30c9\u30ad -> ba-dump). Keep " +
                "translations fluent, concise and in the speaker's tone. If the " +
                "image contains no text at all, respond [].")
    }

    /**
     * BLOCKING (call from a worker thread only). Sends the JPEG screenshot
     * (base64, no data: prefix) to the configured provider and returns the
     * parsed boxes, or null with [lastError] set. The key is never echoed.
     * [iw]/[ih] are the SENT image's pixel dimensions — the prompt grounds
     * the model's boxes to them (v1.3.76).
     */
    @JvmStatic
    fun visionTranslate(ctx: Context, b64: String, tl: String, iw: Int, ih: Int): ArrayList<AiBox>? =
            visionTranslateX(ctx, b64, tl, iw, ih, null)

    /** v1.4.23 — THE TILE-CONTEXT VARIANT: the tile request carries the
     *  full-screen OCR transcript (bounded) so a model reading ONE quadrant
     *  still knows the whole screen's text — headers cut by the seam join,
     *  terminology stays consistent; it reports ONLY the region's own text.
     *  The pure [visionTranslate] is the no-context full-screen call. */
    @JvmStatic
    fun visionTranslateX(ctx: Context, b64: String, tl: String, iw: Int, ih: Int,
                         screenCtx: String?): ArrayList<AiBox>? {
        lastError = ""
        var p = prompt(tl, iw, ih)
        if (!screenCtx.isNullOrEmpty()) {
            p += "\nContext: the FULL screen this region was cut from contains this text: " +
                    screenCtx.take(4000) +
                    " — use it for terminology and continuity; report ONLY the text " +
                    "visible in THIS region's image."
        }
        // v1.4.21: the translate-model picker — the dedicated screen-
        // translate model when set, the shared chat model otherwise
        return if (prov(ctx) == "gemini") {
            val m = translateModel(ctx)
            val chain = buildChain(m)
            for (name in chain) {
                val r = geminiCall(ctx, name, p, b64, iw, ih)
                if (r != null) {
                    noteAnsweredModel(ctx, m, name)
                    return r
                }
                // v1.4.18: the chain advances on model-not-found AND the
                // 5xx provider bucket (see advanceChain)
                if (!advanceChain(lastError)) return null
            }
            null
        } else {
            openaiCall(ctx, p, b64, iw, ih)
        }
    }

    /** v1.4.25 — THE GRID VISION CALL (text-only, NO box_2d — the thin-strip
     *  corpse: "vision ok lines=1", ONE hallucinated box painted a right-
     *  edge strip while the rest of the page stayed untranslated). The
     *  model reads the TILE image and answers with ONLY the translation as
     *  plain text; the FIXED GRID CELL places it (ScreenTranslate never
     *  trusts the model's coordinates again). [iw]/[ih] ride the prompt
     *  only for grounding; [screenCtx] is the OCR transcript when one
     *  exists (terminology + continuity). Returns null with [lastError]
     *  set on failure. */
    @JvmStatic
    fun visionTileTranslate(ctx: Context, b64: String, tl: String, iw: Int, ih: Int,
                            screenCtx: String?): String? {
        lastError = ""
        val p = tilePrompt(tl, screenCtx)
        return if (prov(ctx) == "gemini") {
            val m = translateModel(ctx)
            val chain = buildChain(m)
            for (name in chain) {
                val r = geminiTileText(ctx, name, p, b64)
                if (r != null) {
                    noteAnsweredModel(ctx, m, name)
                    return r
                }
                if (!advanceChain(lastError)) return null
            }
            null
        } else {
            openaiTileText(ctx, p, b64)
        }
    }

    /** v1.4.25 — PURE (probe-pinned): the TEXT-ONLY tile prompt — read every
     *  piece of text in the image (small, stylised, any script, UI labels
     *  included) and answer with ONLY the natural [tlName] translation as
     *  plain text; no coordinates, no JSON, no commentary, no quotes. */
    internal fun tilePrompt(tl: String, screenCtx: String?): String {
        val tlName = langName(tl)
        var p = ("You are an expert OCR translator. Read EVERY piece of text " +
                "in this image — small, stylised, any language or script, " +
                "including UI labels, menus, headings and captions. Reply " +
                "with ONLY the natural $tlName translation of ALL that text, " +
                "as plain text with line breaks roughly where the source lines " +
                "were. No coordinates, no JSON, no markdown, no commentary. " +
                "If the image contains no text at all, reply with nothing.")
        if (!screenCtx.isNullOrEmpty()) {
            p += "\nContext: OCR already read this text elsewhere on the same " +
                    "screen: " + screenCtx.take(4000) +
                    " — use it for terminology; report ONLY the text visible " +
                    "in THIS image."
        }
        return p
    }

    /** v1.4.25 — the Gemini text-only tile call: the vision body WITHOUT
     *  responseMimeType (the reply is PLAIN TEXT, not a JSON array — a JSON
     *  mime would force fences/quotes onto the translation), an 8192 output
     *  cap (a tile is a sixth of a screen; the 400 ladder-down for
     *  low-ceiling models rides), a MAX_TOKENS retry at 16384 whose partial
     *  text is accepted (a sixth of a screen cannot need more). Returns
     *  the trimmed text, or null with lastError set. */
    private fun geminiTileText(ctx: Context, mdl: String, p: String, b64: String): String? {
        for (cap in intArrayOf(8192, 16384)) {
            var c = cap
            var r = geminiPostRetry(ctx, mdl, tileBody(p, b64, c))
            while (r.first == 400) {
                val nxt = cap400Next(c)
                if (nxt <= 0) break
                c = nxt
                r = geminiPostRetry(ctx, mdl, tileBody(p, b64, c))
            }
            if (r.first != 200) {
                lastError = shortErr(r.first, r.second)
                return null
            }
            val fr = finishReason(r.second)
            val txt = stripFences(geminiText(r.second))
            try {
                Diag.log(ctx, usageDiag(fr,
                        usageInt(r.second, "thoughtsTokenCount"),
                        usageInt(r.second, "candidatesTokenCount"), txt.length) + " tile")
            } catch (_: Throwable) {}
            if (txt.isBlank()) {
                if (fr == "MAX_TOKENS" && cap < 16384) continue
                lastError = "the model read no text in this region"
                return null
            }
            return txt
        }
        return null
    }

    /** v1.4.25 — the tile request body: [visionBody] WITHOUT the JSON
     *  responseMimeType (plain text out) — the ONLY difference. */
    private fun tileBody(p: String, b64: String, cap: Int): JSONObject {
        val body = JSONObject()
        val parts = JSONArray()
        parts.put(JSONObject().put("text", p))
        parts.put(JSONObject().put("inline_data",
            JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
        body.put("contents", JSONArray().put(
            JSONObject().put("role", "user").put("parts", parts)))
        body.put("generationConfig", JSONObject()
                .put("temperature", 0.1).put("maxOutputTokens", cap))
        return body
    }

    /** v1.4.25 — PURE: strip stray markdown fences a model may still wrap a
     *  plain-text reply in (the prompt forbids them; some models add them
     *  anyway). */
    internal fun stripFences(s: String): String {
        var t = s.trim()
        while (t.startsWith("```")) {
            t = t.removePrefix("```").trim()
            if (t.startsWith("text") || t.startsWith("plain")) {
                t = t.substringAfter('\n', t)
            }
            t = t.removeSuffix("```").trim()
            if (t.length > 2) break
        }
        return t.trim()
    }

    /** v1.4.25 — the OpenAI-compatible text-only tile call (the same shape
     *  as [openaiCall] minus the box parsing: the content string IS the
     *  translation). */
    private fun openaiTileText(ctx: Context, p: String, b64: String): String? {
        var c: HttpURLConnection? = null
        return try {
            val mdl = translateModel(ctx)
            if (mdl.isBlank()) { lastError = "set a model in the AI settings"; return null }
            val content = JSONArray()
            content.put(JSONObject().put("type", "text").put("text", p))
            content.put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64," + b64)))
            val body = JSONObject()
                .put("model", mdl)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", content)))
                .put("max_tokens", 6000)
                .put("temperature", 0.1)
            val base = baseUrl(ctx).trimEnd('/')
            c = URL(base + "/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 120000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer " + key(ctx))
            val out = body.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(out.size)
            c.outputStream.use { it.write(out) }
            val code = try { c.responseCode } catch (_: Throwable) { -1 }
            val txt = readText(if (code in 200..299) c.inputStream else c.errorStream)
            if (code != 200) { lastError = shortErr(code, txt); return null }
            val o = JSONObject(txt)
            val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: run { lastError = "empty reply"; return null }
            val t = stripFences(msg.optString("content", ""))
            if (t.isBlank()) { lastError = "the model read no text in this region"; return null }
            t
        } catch (t: Throwable) {
            lastError = (t.message ?: t.javaClass.simpleName).take(80)
            null
        } finally {
            try { c?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** Gemini native: POST v1beta/models/{model}:generateContent.
     * v1.3.76 — thinkingLevel minimal: the 3.x flash models think by
     * default and the field called Bolt AI "kind of slow and performance
     * pretty mediocre"; minimal is the floor the models allow and it
     * cuts the visible latency hard. A 400 that names thinking (older /
     * custom models) is retried ONCE with the field removed.
     * v1.4.20 — THINKING IS NOW OFF FOR TRANSLATE (see visionBody): the
     * ~3 s field cut-offs were never 32768-token generations — the
     * thinking tokens raid maxOutputTokens and the visible JSON
     * truncates mid-array long before the cap. */
    private fun geminiCall(ctx: Context, mdl: String, p: String, b64: String,
                           iw: Int, ih: Int): ArrayList<AiBox>? {
        // v1.4.19 — THE TOKEN-CAP LADDER (the "AI translate: the reply was
        // cut off (try a less dense screen)" report, root-caused): a dense
        // screen produces dozens of boxes, each with original + translation,
        // and the JSON array overflowed the old 16384 maxOutputTokens — the
        // reply truncated mid-array and parseBoxes failed exactly on that.
        // The first try rides 32768; when the envelope's finishReason says
        // MAX_TOKENS the SAME model is retried ONCE at 65536 (the 3.x flash
        // ceiling) before the honest cut-off error. The decision is a PURE
        // function ([translateCapAction], probe-pinned).
        // v1.4.20 — THE EVIDENCE LINES: every attempt logs finishReason +
        // thoughtsTokenCount vs candidatesTokenCount + reply length
        // ([usageDiag], pure) — the pair that separates a real token-cap
        // cut (MAX_TOKENS, cand at the cap) from a thinking-budget raid
        // (STOP, th large, cand small) from a shape mismatch (everything
        // small, parse still failing — the reply head rides out after).
        var lastCutOff = false
        for (cap in intArrayOf(32768, 65536)) {
            var r = postVision(ctx, mdl, p, b64, cap)
            if (r.first != 200) {
                lastError = shortErr(r.first, r.second)
                return null
            }
            val fr = finishReason(r.second)
            // v1.3.75 — the envelope is unwrapped FIRST (the v1.3.74 bug:
            // the raw body went to the box parser and every run "found no
            // text"), THEN the model's text is parsed for boxes
            val txt = geminiText(r.second)
            try {
                Diag.log(ctx, usageDiag(fr,
                        usageInt(r.second, "thoughtsTokenCount"),
                        usageInt(r.second, "candidatesTokenCount"), txt.length))
            } catch (_: Throwable) {}
            val boxes = parseBoxes(txt, iw, ih)
            if (boxes == null) {
                try { Diag.log(ctx, "ai translate head " + txt.take(160)) } catch (_: Throwable) {}
            }
            when (translateCapAction(boxes != null, fr, cap)) {
                0 -> {
                    // v1.4.20 — a SALVAGED parse (lenient object recovery)
                    // under MAX_TOKENS is a partial reply of a genuinely
                    // truncated one: the ladder / the tile road beats
                    // showing half a screen
                    if (fr == "MAX_TOKENS" && lastParseSalvaged) {
                        try { Diag.log(ctx, "ai translate MAX_TOKENS at $cap, retrying higher") } catch (_: Throwable) {}
                        lastCutOff = true
                    } else return boxes
                }
                1 -> {                              // truncated: raise the cap
                    try { Diag.log(ctx, "ai translate MAX_TOKENS at $cap, retrying higher") } catch (_: Throwable) {}
                    lastCutOff = true
                }
                else -> return null                 // a non-cap parse failure (its own lastError)
            }
        }
        if (lastCutOff) {
            // v1.4.20 — THE TILE HANDOFF: the ladder exhausted on MAX_TOKENS
            // is the one honest cut left, and the CALLER (ScreenTranslate)
            // holds the bitmap it can tile into regions — the error string
            // IS the contract (shouldTile, pure)
            lastError = CUT_OFF_ERR
        }
        return null
    }

    /** v1.4.19 — one vision POST at a token cap. v1.4.20: the thinking-400
     *  retry. v1.4.21 — THE 400 LADDER-DOWN (root-caused: the field report
     *  said "HTTP 400" — gemini-3.5-flash-lite REJECTS thinkingBudget:0,
     *  and the v1.4.20 retry only fired when the 400 body named "thinking",
     *  which this model's rejection does not — so the request died with no
     *  recovery. Translate now never SENDS thinkingConfig (see visionBody),
     *  and on ANY 400 the RAW body rides to Diag (shortErr hid the exact
     *  rejection — never again) and the cap steps DOWN the model's own
     *  output ceiling until it fits or the floor is reached). */
    private fun postVision(ctx: Context, mdl: String, p: String, b64: String,
                           cap: Int): Pair<Int, String> {
        var c = cap
        var r = geminiPostRetry(ctx, mdl, visionBody(p, b64, c))
        while (r.first == 400) {
            try {
                Diag.log(ctx, "ai translate 400 raw cap=$c body=" + r.second.take(240))
            } catch (_: Throwable) {}
            val next = cap400Next(c)
            if (next <= 0) return r
            c = next
            r = geminiPostRetry(ctx, mdl, visionBody(p, b64, c))
        }
        return r
    }

    /** v1.4.21 — PURE (probe-pinned): the HTTP-400 cap ladder. A 400 can be
     *  the model's own maxOutputTokens ceiling sitting BELOW the rung we
     *  sent (lite models cap low); the honest recovery steps the cap down
     *  one rung at a time — 65536 -> 32768 -> 16384 -> 8192 -> give up (the
     *  400 is then about something else and the raw-body Diag line above
     *  names it). */
    @JvmStatic
    internal fun cap400Next(cur: Int): Int = when (cur) {
        65536 -> 32768
        32768 -> 16384
        16384 -> 8192
        else -> -1
    }

    /** v1.4.19 — the envelope's finishReason ("MAX_TOKENS" = the reply hit
     *  the output cap; anything else / absent = ""). */
    private fun finishReason(body: String): String {
        return try {
            org.json.JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optString("finishReason", "") ?: ""
        } catch (_: Throwable) { "" }
    }

    /** v1.4.19 — PURE (probe-pinned): the token-cap ladder verdict for one
     *  translate attempt. 0 = parsed, return the boxes; 1 = the reply hit
     *  the cap and a higher rung exists — retry the SAME model at 65536;
     *  2 = fail with the standing error (a parse failure that was NOT a
     *  cap hit has its own lastError already set by parseBoxes). */
    @JvmStatic
    internal fun translateCapAction(parseOk: Boolean, finishReason: String, cap: Int): Int {
        if (parseOk) return 0
        if (finishReason == "MAX_TOKENS" && cap < 65536) return 1
        return 2
    }

    /** v1.4.20 — the usage-evidence reader: one int out of the envelope's
     *  usageMetadata (thoughtsTokenCount vs candidatesTokenCount — the pair
     *  that pins a thinking-budget raid vs a real token-cap cut). */
    private fun usageInt(body: String, key: String): Int = try {
        org.json.JSONObject(body).optJSONObject("usageMetadata")?.optInt(key, 0) ?: 0
    } catch (_: Throwable) { 0 }

    /** v1.4.20 — PURE (probe-pinned): the one-line evidence form for a
     *  translate attempt — finishReason, the thinking/visible token pair
     *  and the reply length. A real cap cut reads fr=MAX_TOKENS with cand
     *  AT the cap; a thinking raid reads fr=STOP with th large and cand
     *  small; a shape mismatch reads all-small with the parse still
     *  failing (the reply head rides out right after it). */
    @JvmStatic
    internal fun usageDiag(fr: String, thoughts: Int, candidates: Int, len: Int): String {
        return "ai translate fr=" + (if (fr.isEmpty()) "?" else fr) +
                " th=" + thoughts + " cand=" + candidates + " len=" + len
    }

    /** v1.4.20 — PURE (probe-pinned): after a failed visionTranslate, does
     *  the error call for TILING? The cap ladder exhausted on MAX_TOKENS
     *  is the one failure more (smaller) requests can cure — the caller
     *  holds the bitmap and splits it into regions. */
    @JvmStatic
    internal fun shouldTile(err: String): Boolean = err == CUT_OFF_ERR

    /** the vision request body.
     *  v1.4.19: the output cap is a PARAMETER — 32768 on the first rung
     *  (dense screens overflowed the old 16384 mid-array), 65536 on the
     *  MAX_TOKENS retry (see geminiCall's ladder).
     *  v1.4.21 — THINKINGCONFIG OMITTED ENTIRELY (supersedes the
     *  v1.4.20 thinkingBudget-0 idea): the field 400 was the LITE model
     *  rejecting the thinkingBudget FIELD ITSELF, and translation needs
     *  no thinking config at all — a field we never send cannot be
     *  rejected, and whatever thinking the model does by default is
     *  bounded by its own output ceiling (the ladder-down covers a low
     *  ceiling; the tile road covers density). The CHAT body keeps its
     *  thinkingLevel minimal (chat image questions work — the report
     *  said so — and its 2048 cap rides a different budget entirely). */
    private fun visionBody(p: String, b64: String, cap: Int): JSONObject {
        val body = JSONObject()
        val parts = JSONArray()
        parts.put(JSONObject().put("text", p))
        parts.put(JSONObject().put("inline_data",
            JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
        body.put("contents", JSONArray().put(
            JSONObject().put("role", "user").put("parts", parts)))
        // v1.3.75: responseMimeType json makes the model emit a raw
        // JSON array (no fences, no commentary); the output cap rides
        // the v1.4.19 ladder (and the v1.4.21 400 ladder-down)
        val gc = JSONObject()
            .put("temperature", 0.1).put("maxOutputTokens", cap)
            .put("responseMimeType", "application/json")
        body.put("generationConfig", gc)
        return body
    }

    /** v1.3.76 — THE FREE-TIER PATIENCE. The user's worry: "the API will
     * slow down with use or hit limits quickly in free using the google
     * ai studio api". Gemini's free tier answers a burst with 429 and a
     * retry hint measured in seconds. Blocking-worker context only: wait
     * 15 s and try the SAME request once more before giving up — most
     * free-tier 429s clear within one window, and the panel's "…"
     * thinking bubble rides it out instead of erroring at the user. */
    private fun geminiPostRetry(ctx: Context, mdl: String, body: JSONObject): Pair<Int, String> {
        var r = geminiPost(ctx, mdl, body)
        if (r.first == 429) {
            try { Diag.log(ctx, "ai 429 retry") } catch (_: Throwable) {}
            try { Thread.sleep(15000) } catch (_: Throwable) { return r }
            r = geminiPost(ctx, mdl, body)
        }
        // v1.4.18 — THE 503 LADDER (the "custom model + provider error =
        //  dead end" report): gemini-3.8-flash had documented 503 "model
        //  overloaded" waves in 2026-11 — a custom model meeting one had
        //  NO retry, NO fallback (the chain was arrayOf(m) alone), and
        //  the user ate a bare "provider error". The SAME model now
        //  retries with a bounded backoff — the delay honors the
        //  provider's retryInfo.retryDelay hint when the body carries
        //  one (retryDelaySeconds), else 5 s, capped at 30 s — up to 2
        //  retries, then the chain walks (advanceChain advances on 5xx
        //  now too). The 429 free-tier patience above is unchanged.
        var tries503 = 0
        while (r.first == 503 && tries503 < 2) {
            val delay = retryDelaySeconds(r.second, 5).coerceIn(1, 30)
            try { Diag.log(ctx, "ai 503 retry " + (tries503 + 1) + " in " + delay + "s") } catch (_: Throwable) {}
            try { Thread.sleep(delay * 1000L) } catch (_: Throwable) { return r }
            r = geminiPost(ctx, mdl, body)
            tries503++
        }
        return r
    }

    /** one Gemini generateContent POST — returns (code, body-text) */
    private fun geminiPost(ctx: Context, mdl: String, body: JSONObject): Pair<Int, String> {
        var c: HttpURLConnection? = null
        return try {
            val u = URL("https://$GEMINI_HOST/v1beta/models/$mdl:generateContent")
            c = u.openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 120000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key(ctx))
            val out = body.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(out.size)
            c.outputStream.use { it.write(out) }
            val code = try { c.responseCode } catch (_: Throwable) { -1 }
            val txt = readText(if (code in 200..299) c.inputStream else c.errorStream)
            Pair(code, txt)
        } catch (t: Throwable) {
            Pair(-1, (t.message ?: t.javaClass.simpleName).take(80))
        } finally {
            try { c?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** OpenAI-compatible: POST {base}/chat/completions with an image part */
    private fun openaiCall(ctx: Context, p: String, b64: String, iw: Int, ih: Int): ArrayList<AiBox>? {
        var c: HttpURLConnection? = null
        return try {
            val mdl = translateModel(ctx)   // v1.4.21: the translate picker applies here too
            if (mdl.isBlank()) { lastError = "set a model in the AI settings"; return null }
            val content = JSONArray()
            content.put(JSONObject().put("type", "text").put("text", p))
            content.put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64," + b64)))
            val body = JSONObject()
                .put("model", mdl)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", content)))
                .put("max_tokens", 6000)
                .put("temperature", 0.1)
            val base = baseUrl(ctx).trimEnd('/')
            c = URL(base + "/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 120000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer " + key(ctx))
            val out = body.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(out.size)
            c.outputStream.use { it.write(out) }
            val code = try { c.responseCode } catch (_: Throwable) { -1 }
            val txt = readText(if (code in 200..299) c.inputStream else c.errorStream)
            if (code != 200) { lastError = shortErr(code, txt); return null }
            val o = JSONObject(txt)
            val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: run { lastError = "empty reply"; return null }
            parseBoxes(msg.optString("content", ""), iw, ih)
        } catch (t: Throwable) {
            lastError = (t.message ?: t.javaClass.simpleName).take(80)
            null
        } finally {
            try { c?.disconnect() } catch (_: Throwable) {}
        }
    }


    // ---------------- v1.4.23 — the pure-text translate stage (the hybrid) ----------------

    /** v1.4.23 — PURE (probe-pinned): the numbered-lines protocol prompt for
     *  [textTranslate]. ONE request carries the OCR-read lines with the FULL
     *  screen as context — no image, and no box_2d for the model to
     *  hallucinate; the numbers map the reply back onto the OCR boxes 1:1
     * (coordinates never leave the device). */
    internal fun textLinesPrompt(tl: String, lines: List<String>, markBreaks: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("You are a precise screen translator. The numbered lines below " +
                "were read from ONE screen in top-to-bottom, left-to-right order. " +
                "Translate EVERY line into natural, concise ")
        sb.append(langName(tl))
        sb.append(". Reply with ONLY the translated lines, one per input line, each " +
                "prefixed by its number and a pipe character, in the same order " +
                "(for example 7|translated text). Keep terminology consistent across " +
                "lines - the same term translates the same way everywhere, because " +
                "these lines share one screen. A line that is a URL, an email, a code " +
                "fragment, a number or pure punctuation stays exactly as it is. " +
                "A translation must fit the line's on-screen box: concise beats literal.\n")
        if (markBreaks) {
            // v1.4.26 — the manga law: bubbles stack 1-3 lines; the marker
            // survives the numbered protocol (the parser splits on newlines)
            sb.append("These lines come from SPEECH BUBBLES on a comic page: a " +
                    "translation that needs 2-3 stacked lines marks every break " +
                    "with ' ~ ' (tilde with one space on each side) — the overlay " +
                    "converts the markers into the stacked line breaks. Never use " +
                    "the marker inside a URL.\n")
        }
        sb.append("Lines:\n")
        for ((i, l) in lines.withIndex()) sb.append(i + 1).append('|').append(l).append('\n')
        return sb.toString()
    }

    /** v1.4.23 — PURE (probe-pinned): parse a numbered-lines reply
     *  (7|translated) into the slot array; entries the reply did not cover
     *  stay null (the caller counts coverage). Tolerates stray prose lines,
     *  markdown fences and blank lines; out-of-range or repeated numbers
     *  are skipped (first write to a slot wins). */
    @JvmStatic
    internal fun parseNumberedReply(txt: String, want: Int): Array<String?> {
        val out = arrayOfNulls<String>(want)
        for (raw in txt.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = NUMBERED_LINE.find(line) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n < 1 || n > want) continue
            val t = m.groupValues[2].trim()
            if (t.isEmpty()) continue
            if (out[n - 1] == null) out[n - 1] = t
        }
        return out
    }

    /** v1.4.23 — the numbered-reply line matcher (PURE; probe-pinned). */
    private val NUMBERED_LINE = Regex("^(\\d{1,4})\\s*\\|\\s?(.*)$")

    /**
     * BLOCKING (call from a worker thread only). v1.4.23 — THE PURE-TEXT
     * TRANSLATE STAGE of the screen-translate hybrid: ONE request carries
     * the OCR-read lines (numbered) with the FULL screen as context — no
     * image, no box_2d to hallucinate; coordinates never leave the device.
     * thinkingLevel "low" rides the first try (quality); ANY 400 drops the
     * field cleanly and retries (the v1.4.20 lesson — thinkingBudget=0 is
     * NEVER sent, that field caused the 400s), then the cap steps down the
     * model's own ceiling like the vision ladder. Returns the per-line
     * translations (null entries = lines the reply did not cover; the
     * overlay shows the original for those), or null with [lastError] set
     * on failure — CUT_OFF_ERR ONLY on a genuine MAX_TOKENS cut with poor
     * coverage, which is the caller's key for the vision fallback.
     */
    @JvmStatic
    fun textTranslate(ctx: Context, lines: List<String>, tl: String): List<String?>? =
            textTranslate(ctx, lines, tl, false)

    /** v1.4.26 — the MANGA variant: [markBreaks] tells the model the lines
     *  are SPEECH BUBBLES whose translations may stack 1-3 lines — it marks
     *  the break points with ' ~ ' (tilde with spaces) and the caller
     *  converts the markers to newlines (the numbered reply protocol
     *  cannot carry raw newlines — the parser splits on them). */
    @JvmStatic
    fun textTranslate(ctx: Context, lines: List<String>, tl: String,
                     markBreaks: Boolean): List<String?>? {
        lastError = ""
        return if (prov(ctx) == "gemini") {
            val m = translateModel(ctx)
            val chain = buildChain(m)
            for (name in chain) {
                val r = geminiTextTranslate(ctx, name, lines, tl, markBreaks)
                if (r != null) {
                    noteAnsweredModel(ctx, m, name)
                    return r
                }
                // v1.4.18: the chain advances on model-not-found AND the
                // 5xx provider bucket (see advanceChain)
                if (!advanceChain(lastError)) return null
            }
            null
        } else {
            openaiTextTranslate(ctx, lines, tl, markBreaks)
        }
    }

    /** v1.4.23 — the Gemini text pass: the 16384 -> 32768 cap rungs (a
     *  160-line dense screen is rare but real), thinkingLevel low with the
     *  clean 400 drop, coverage-gated delivery — the numbered protocol
     *  makes a STOP-shape partial usable (missing lines keep the original),
     *  so ONLY a MAX_TOKENS cut under the 70% coverage floor hands off to
     *  the vision fallback (CUT_OFF_ERR). */
    private fun geminiTextTranslate(ctx: Context, mdl: String, lines: List<String>,
                                    tl: String, markBreaks: Boolean): List<String?>? {
        val p = textLinesPrompt(tl, lines, markBreaks)
        for (cap in intArrayOf(16384, 32768)) {
            val r = postText(ctx, mdl, p, cap)
            if (r.first != 200) {
                lastError = shortErr(r.first, r.second)
                return null
            }
            val fr = finishReason(r.second)
            val txt = geminiText(r.second)
            val out = parseNumberedReply(txt, lines.size)
            var got = 0
            for (o in out) if (o != null) got++
            try {
                Diag.log(ctx, "ai texttranslate fr=" + (if (fr.isEmpty()) "?" else fr) +
                        " lines=" + lines.size + " got=" + got + " len=" + txt.length)
            } catch (_: Throwable) {}
            if (got == 0) {
                try { Diag.log(ctx, "ai texttranslate head " + txt.take(160)) } catch (_: Throwable) {}
                lastError = "the AI reply was not numbered lines"
                return null
            }
            // full or majority coverage: deliver (the numbered protocol
            // maps partials safely — missing lines show the original text)
            if (got * 10 >= lines.size * 7) return out.toList()
            if (fr == "MAX_TOKENS" && cap < 32768) {
                try { Diag.log(ctx, "ai texttranslate MAX_TOKENS at $cap, retrying higher") }
                catch (_: Throwable) {}
                continue
            }
            if (fr == "MAX_TOKENS") {
                // the ONE honest hand-off to the vision fallback
                lastError = CUT_OFF_ERR
                return null
            }
            return out.toList()
        }
        return null
    }

    /** v1.4.23 — one text POST: thinkingLevel low first; ANY 400 drops the
     *  thinking field cleanly and retries once (never thinkingBudget — the
     *  v1.4.20 corpse); a STILL-400 walks the cap down the model's own
     *  output ceiling (the v1.4.21 ladder-down) until the floor, with the
     *  RAW body on Diag so a rejection is never a silent dead end. */
    private fun postText(ctx: Context, mdl: String, p: String, cap: Int): Pair<Int, String> {
        var c = cap
        var r = geminiPostRetry(ctx, mdl, textBody(p, c, true))
        if (r.first == 400) {
            try { Diag.log(ctx, "ai texttranslate 400 raw cap=$c body=" + r.second.take(240)) }
            catch (_: Throwable) {}
            r = geminiPostRetry(ctx, mdl, textBody(p, c, false))
        }
        while (r.first == 400) {
            try { Diag.log(ctx, "ai texttranslate 400 raw cap=$c body=" + r.second.take(240)) }
            catch (_: Throwable) {}
            val next = cap400Next(c)
            if (next <= 0) return r
            c = next
            r = geminiPostRetry(ctx, mdl, textBody(p, c, false))
        }
        return r
    }

    /** v1.4.23 — the pure-text request body: a plain user turn, NO image,
     *  NO responseMimeType (the reply is numbered plain text, not JSON);
     *  thinkingLevel low only when [think] (dropped cleanly on any 400). */
    private fun textBody(p: String, cap: Int, think: Boolean): JSONObject {
        val body = JSONObject()
        body.put("contents", JSONArray().put(
            JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", p)))))
        val gc = JSONObject()
            .put("temperature", 0.1)
            .put("maxOutputTokens", cap)
        if (think) gc.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        body.put("generationConfig", gc)
        return body
    }

    /** v1.4.23 — the OpenAI-compatible text pass (same numbered protocol,
     *  no image part). A length cut with poor coverage is the vision
     *  hand-off; everything else delivers what arrived. */
    private fun openaiTextTranslate(ctx: Context, lines: List<String>, tl: String,
                                    markBreaks: Boolean = false): List<String?>? {
        var c: HttpURLConnection? = null
        return try {
            val mdl = translateModel(ctx)
            if (mdl.isBlank()) { lastError = "set a model in the AI settings"; return null }
            val body = JSONObject()
                .put("model", mdl)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("content", textLinesPrompt(tl, lines, markBreaks))))
                .put("max_tokens", 6000)
                .put("temperature", 0.1)
            val base = baseUrl(ctx).trimEnd('/')
            c = URL(base + "/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 120000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer " + key(ctx))
            val out = body.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(out.size)
            c.outputStream.use { it.write(out) }
            val code = try { c.responseCode } catch (_: Throwable) { -1 }
            val txt = readText(if (code in 200..299) c.inputStream else c.errorStream)
            if (code != 200) { lastError = shortErr(code, txt); return null }
            val o = JSONObject(txt)
            val choice = o.optJSONArray("choices")?.optJSONObject(0)
                ?: run { lastError = "empty reply"; return null }
            val fr = choice.optString("finish_reason", "")
            val reply = choice.optJSONObject("message")?.optString("content", "") ?: ""
            val outArr = parseNumberedReply(reply, lines.size)
            var got = 0
            for (x in outArr) if (x != null) got++
            try {
                Diag.log(ctx, "ai texttranslate fr=" + (if (fr.isEmpty()) "?" else fr) +
                        " lines=" + lines.size + " got=" + got)
            } catch (_: Throwable) {}
            if (got == 0) {
                try { Diag.log(ctx, "ai texttranslate head " + reply.take(160)) } catch (_: Throwable) {}
                lastError = "the AI reply was not numbered lines"
                return null
            }
            if (got * 10 < lines.size * 7 && fr == "length") {
                lastError = CUT_OFF_ERR
                return null
            }
            outArr.toList()
        } catch (t: Throwable) {
            lastError = (t.message ?: t.javaClass.simpleName).take(80)
            null
        } finally {
            try { c?.disconnect() } catch (_: Throwable) {}
        }
    }

    // ---------------- the chat engine (v1.3.75 — Bolt AI's brain) ----------------

    /** one chat turn ("user" or "assistant" — mapped to "model" on Gemini) */
    class Msg(val role: String, val text: String)

    /** v1.4.19 — one TEMPORARY chat attachment (the image/file upload ask):
     *  an image as inline base64 (b64 != null) or a text file's extracted
     *  content (text != null). In-memory only — BoltAi never persists
     *  attachments, and they are wiped on send / clear / process death. */
    class Attach(@JvmField val name: String, @JvmField val mime: String,
            @JvmField val b64: String?, @JvmField val text: String?)

    /**
     * BLOCKING (worker thread only). Multi-turn text chat with the
     * configured provider. [system] is the assistant's standing instruction;
     * [history] is the turns so far (the caller caps it). Returns the reply
     * text, or null with [lastError] set. Never sees a screenshot — page
     * context arrives as plain text the caller already showed a confirm for.
     */
    @JvmStatic
    fun chat(ctx: Context, system: String, history: List<Msg>): String? =
            chatX(ctx, system, history, null)

    /** v1.4.19 — chat with TEMPORARY attachments: images ride the LAST user
     *  turn as inline_data (Gemini) / image_url data URLs (OpenAI-compat),
     *  text files as extra labeled text parts. Attachments ride ONLY the
     *  turn they were sent with — the stored history keeps the plain text
     *  (re-sending an image on every follow-up would burn the token
     *  budget for no gain). */
    @JvmStatic
    fun chatX(ctx: Context, system: String, history: List<Msg>,
            attaches: List<Attach>?): String? {
        lastError = ""
        return if (prov(ctx) == "gemini") {
            val m = model(ctx)
            val chain = buildChain(m)
            for (name in chain) {
                val r = geminiChat(ctx, name, system, history, attaches)
                if (r != null) {
                    noteAnsweredModel(ctx, m, name)
                    return r
                }
                if (!advanceChain(lastError)) return null
            }
            null
        } else {
            openaiChat(ctx, system, history, attaches)
        }
    }

    /** Gemini native chat: systemInstruction + contents[user/model turns].
     * v1.3.76 — thinkingLevel minimal (the latency cut) with a one-shot
     * retry without it for models that reject the field, and the chat cap
     * trimmed 4096 -> 2048 (snappier turns, replies truncate gracefully). */
    private fun geminiChat(ctx: Context, mdl: String, system: String,
                           history: List<Msg>, attaches: List<Attach>? = null): String? {
        var reply = geminiChatPost(ctx, mdl, system, history, true, attaches)
        if (reply == null && lastError.contains("thinking", true)) {
            reply = geminiChatPost(ctx, mdl, system, history, false, attaches)
        }
        return reply
    }

    private fun geminiChatPost(ctx: Context, mdl: String, system: String,
                               history: List<Msg>, think: Boolean,
                               attaches: List<Attach>? = null): String? {
        val contents = JSONArray()
        for ((i, m) in history.withIndex()) {
            val parts = JSONArray().put(JSONObject().put("text", m.text))
            // v1.4.19 — attachments ride the LAST user turn only
            if (!attaches.isNullOrEmpty() && i == history.size - 1
                    && m.role != "assistant") {
                for (at in attaches) {
                    if (at.b64 != null) {
                        parts.put(JSONObject().put("inline_data",
                            JSONObject().put("mime_type", at.mime).put("data", at.b64)))
                    } else if (at.text != null) {
                        parts.put(JSONObject().put("text",
                            "[Attached file: " + at.name + "]\n" + at.text))
                    }
                }
            }
            contents.put(JSONObject()
                .put("role", if (m.role == "assistant") "model" else "user")
                .put("parts", parts))
        }
        val gc = JSONObject()
            .put("temperature", 0.4).put("maxOutputTokens", 2048)
        if (think) gc.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
        val body = JSONObject()
            .put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", gc)
        val r = geminiPostRetry(ctx, mdl, body)
        val (code, txt) = r
        if (code != 200) { lastError = shortErr(code, txt); return null }
        val out = geminiText(txt)
        if (out.isBlank()) { lastError = "empty reply (rate limited? wait a minute)"; return null }
        return out
    }

    /** OpenAI-compatible chat: messages[system/user/assistant] */
    private fun openaiChat(ctx: Context, system: String, history: List<Msg>,
                           attaches: List<Attach>? = null): String? {
        var c: HttpURLConnection? = null
        return try {
            val mdl = model(ctx)
            if (mdl.isBlank()) { lastError = "set a model in the AI settings"; return null }
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            for ((i, m) in history.withIndex()) {
                // v1.4.19 — attachments ride the LAST user turn only
                if (!attaches.isNullOrEmpty() && i == history.size - 1
                        && m.role != "assistant") {
                    val content = JSONArray()
                    content.put(JSONObject().put("type", "text").put("text", m.text))
                    for (at in attaches) {
                        if (at.b64 != null) {
                            content.put(JSONObject().put("type", "image_url").put("image_url",
                                JSONObject().put("url",
                                    "data:" + at.mime + ";base64," + at.b64)))
                        } else if (at.text != null) {
                            content.put(JSONObject().put("type", "text").put("text",
                                "[Attached file: " + at.name + "]\n" + at.text))
                        }
                    }
                    msgs.put(JSONObject().put("role", m.role).put("content", content))
                } else {
                    msgs.put(JSONObject().put("role", m.role).put("content", m.text))
                }
            }
            val body = JSONObject()
                .put("model", mdl)
                .put("messages", msgs)
                .put("max_tokens", 2048)
                .put("temperature", 0.4)
            val base = baseUrl(ctx).trimEnd('/')
            c = URL(base + "/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 120000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer " + key(ctx))
            val out = body.toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(out.size)
            c.outputStream.use { it.write(out) }
            val code = try { c.responseCode } catch (_: Throwable) { -1 }
            val txt = readText(if (code in 200..299) c.inputStream else c.errorStream)
            if (code != 200) { lastError = shortErr(code, txt); return null }
            val o = JSONObject(txt)
            val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: run { lastError = "empty reply"; return null }
            val reply = msg.optString("content", "").trim()
            if (reply.isEmpty()) { lastError = "empty reply"; return null }
            reply
        } catch (t: Throwable) {
            lastError = (t.message ?: t.javaClass.simpleName).take(80)
            null
        } finally {
            try { c?.disconnect() } catch (_: Throwable) {}
        }
    }

    // ---------------- reply parsing ----------------

    /**
     * v1.3.75 — THE ENVELOPE FIX. v1.3.74 fed the RAW Gemini HTTP body to
     * the box parser: the first '[' it found was the CANDIDATES array, the
     * last ']' its close, so the "parse" succeeded on a candidate object
     * with no x/y and every run ended "the AI found no text" — the "AI
     * mode is pretty buggy" report. This pulls candidates[].content.parts[]
     * .text (SKIPPING thought-summary parts, which 2.5+/3.x models emit
     * with "thought":true) and joins them into the model's actual text.
     */
    private fun geminiText(body: String): String {
        return try {
            val cands = JSONObject(body).optJSONArray("candidates") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until cands.length()) {
                val parts = cands.optJSONObject(i)?.optJSONObject("content")
                    ?.optJSONArray("parts") ?: continue
                for (j in 0 until parts.length()) {
                    val p = parts.optJSONObject(j) ?: continue
                    if (p.optBoolean("thought", false)) continue
                    sb.append(p.optString("text", ""))
                }
            }
            sb.toString()
        } catch (_: Throwable) { "" }
    }

    /** the box parser — takes the MODEL'S TEXT (already extracted from the
     * provider envelope), not a raw HTTP body. v1.3.77: the primary dialect
     * is the model-native one the prompt now asks for — box_2d =
     * [ymin,xmin,ymax,xmax] normalized 0-1000 — plus a response-level
     * dialect DETECTION so a model that answers 0-1 floats or raw pixels
     * in corner order still lands right (pick ONE scale per response, not
     * per box: mixed scales inside one reply are nonsense). Legacy
     * [x,y,w,h] dialects stay supported for OpenAI-compatible endpoints.
     * Everything leaves normalized to 0-1 fractions of the sent image so
     * the overlay never cares who answered.
     * v1.4.20 — THE LENIENT SALVAGE (the "cut off" that was not always a
     * cut): a strict JSONArray parse fails on ONE malformed object and
     * used to burn the WHOLE reply as "cut off" even when finishReason
     * said STOP — a shape mismatch mislabeled as truncation. Now the
     * complete {...} objects are salvaged out of the text (balanced-brace
     * scan, string-aware — [salvageObjects], PURE) and the good ones
     * parse on: one bad box costs ONE box, not the screen. Only a reply
     * with no salvageable object keeps the standing cut-off error. */
    private fun parseBoxes(txt: String, iw: Int, ih: Int): ArrayList<AiBox>? {
        lastParseSalvaged = false
        val text = extractJsonArray(txt) ?: run { lastError = "the AI reply was not JSON"; return null }
        val arr = try { JSONArray(text) } catch (_: Throwable) {
            // v1.4.20 — the lenient salvage first: recover the COMPLETE
            // objects and parse those (one malformed box must cost one box,
            // not the screen); the standing cut-off error survives only
            // when nothing at all could be recovered
            val salvaged = salvageObjects(text)
            if (salvaged == null) {
                lastError = CUT_OFF_ERR; return null
            }
            lastParseSalvaged = true
            salvaged
        }
        // ---- response-level dialect detection (v1.3.77) ----
        // box_2d: [ymin,xmin,ymax,xmax]. Any value > 1000 in ANY box_2d →
        // the model answered in PIXELS of the sent image (corner order);
        // all values ≤ 1.5 → 0-1 float fractions; otherwise the native
        // 0-1000 grid. One decision for the whole reply keeps every box
        // on the same scale.
        var b2Pixels = false
        var b2Floats = false
        var sawB2 = false
        for (i in 0 until arr.length()) {
            val b2 = arr.optJSONObject(i)?.optJSONArray("box_2d") ?: continue
            sawB2 = true
            var mx = 0f
            for (j in 0 until minOf(4, b2.length())) {
                mx = maxOf(mx, kotlin.math.abs(b2.optDouble(j, 0.0).toFloat()))
            }
            if (mx > 1000.5f) { b2Pixels = true; break }
        }
        if (sawB2 && !b2Pixels) {
            // all ≤ 1000: floats only if EVERY box stayed under 1.5
            var allTiny = true
            for (i in 0 until arr.length()) {
                val b2 = arr.optJSONObject(i)?.optJSONArray("box_2d") ?: continue
                for (j in 0 until minOf(4, b2.length())) {
                    if (kotlin.math.abs(b2.optDouble(j, 0.0).toFloat()) > 1.5f) {
                        allTiny = false; break
                    }
                }
                if (!allTiny) break
            }
            b2Floats = allTiny
        }
        val out = ArrayList<AiBox>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val orig = o.optString("text", "").trim()
            val trans = o.optString("translation", "").trim()
            if (orig.isEmpty() || trans.isEmpty()) continue
            var th = 0f
            var vert = o.optBoolean("vertical", false)
            var x: Float; var y: Float; var w: Float; var h: Float
            val b2 = o.optJSONArray("box_2d")
            if (b2 != null && b2.length() >= 4) {
                // v1.3.77 primary: CORNER order [ymin, xmin, ymax, xmax]
                val a = b2.optDouble(0, -1.0).toFloat()
                val b = b2.optDouble(1, -1.0).toFloat()
                val c = b2.optDouble(2, -1.0).toFloat()
                val d = b2.optDouble(3, -1.0).toFloat()
                if (a < 0 || b < 0 || c < 0 || d < 0) continue
                var y0 = a; var x0 = b; var y1 = c; var x1 = d
                when {
                    b2Pixels && iw > 0 && ih > 0 -> { y0 = a / ih; x0 = b / iw; y1 = c / ih; x1 = d / iw }
                    b2Floats -> { /* 0-1 fractions as-is */ }
                    else -> { y0 = a / 1000f; x0 = b / 1000f; y1 = c / 1000f; x1 = d / 1000f }
                }
                // tolerate a swapped corner pair (some models emit xmax<xmin)
                if (y1 < y0) { val t = y0; y0 = y1; y1 = t }
                if (x1 < x0) { val t = x0; x0 = x1; x1 = t }
                if (y1 <= y0 || x1 <= x0) continue
                x = x0; y = y0; w = x1 - x0; h = y1 - y0
                th = o.optDouble("th", 0.0).toFloat()
                // th dialect: ≤1.5 → fraction; ≤1000 → 0-1000 of image
                // height; >1000 → pixels of the sent image (harmlessly
                // close either way — 1000 ≈ ih — but be exact)
                th = when {
                    th <= 0f -> 0f
                    th <= 1.5f -> th
                    th <= 1000f -> th / 1000f
                    ih > 0 -> th / ih
                    else -> 0f
                }
            } else {
                // legacy dialect: [x,y,w,h] — 0-1 fractions, 0-1000 grid,
                // or raw pixels of the sent image (v1.3.76 contract, kept
                // for OpenAI-compatible endpoints that still answer it)
                val box = o.optJSONArray("box")
                if (box == null || box.length() < 4) {
                    x = o.optDouble("x", -1.0).toFloat()
                    y = o.optDouble("y", -1.0).toFloat()
                    w = o.optDouble("w", -1.0).toFloat()
                    h = o.optDouble("h", -1.0).toFloat()
                } else {
                    x = box.optDouble(0, -1.0).toFloat()
                    y = box.optDouble(1, -1.0).toFloat()
                    w = box.optDouble(2, -1.0).toFloat()
                    h = box.optDouble(3, -1.0).toFloat()
                }
                if (x < 0 || y < 0 || w <= 0 || h <= 0) continue
                if (x <= 1.5f && y <= 1.5f && w <= 1.5f && h <= 1.5f &&
                    (x + w) <= 1.51f && (y + h) <= 1.51f) {
                    // 0-1 fractions already
                } else {
                    // some models answer beyond the 1000 grid with raw
                    // pixels of their own rescale — treat >1000 as pixels
                    // of the sent image when dims are known
                    if ((x > 1000f || y > 1000f || w > 1000f || h > 1000f)
                        && iw > 0 && ih > 0) {
                        x /= iw; w /= iw; y /= ih; h /= ih
                    } else {
                        x /= 1000f; y /= 1000f; w /= 1000f; h /= 1000f
                    }
                }
            }
            if (x >= 1f || y >= 1f) continue
            if (w > 1f) w = 1f - x
            if (h > 1f) h = 1f - y
            if (w <= 0f || h <= 0f) continue
            out.add(AiBox(x, y, w, h, orig, trans,
                th.coerceIn(0f, 1f), vert))
        }
        if (out.isEmpty()) { lastError = "the AI found no text"; return null }
        return out
    }

    /** v1.4.20 — PURE (probe-pinned): the lenient salvage. Scan the reply
     *  for COMPLETE top-level {...} objects (balanced braces, string- and
     *  escape-aware, so a brace inside a translation string never counts)
     *  and return them as a JSONArray; the trailing object of a genuinely
     *  truncated reply is dropped by construction (it never closes), and a
     *  reply with ZERO recoverable objects returns null (the standing
     *  cut-off error). One malformed box now costs one box, not the screen. */
    @JvmStatic
    internal fun salvageObjects(text: String): JSONArray? {
        val out = JSONArray()
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (i in text.indices) {
            val c = text[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            try { out.put(JSONObject(text.substring(start, i + 1))) } catch (_: Throwable) {}
                            start = -1
                        }
                    }
                }
            }
        }
        return if (out.length() > 0) out else null
    }

    /** strips markdown fences; returns the outermost [...] slice */
    private fun extractJsonArray(s: String): String? {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end)
            t = t.trim()
        }
        val a = t.indexOf('[')
        val b = t.lastIndexOf(']')
        if (a < 0 || b <= a) return null
        return t.substring(a, b + 1)
    }

    /** the model that actually answered the last successful call (the
     *  configured pick or a fallback — the evidence channel for the
     *  v1.4.18 chain law). */
    private var lastAnsweredModel: String? = null

    /** v1.4.18 — THE ANSWERED-MODEL EVIDENCE: with the chain always
     *  carrying fallbacks, a 503-ing custom model can be silently
     *  answered by a default — the toast says so when that happens (a
     *  silent fallback reads as "my model works" while it is down), and
     *  logcat + the diag ring ALWAYS carry who answered. */
    private fun noteAnsweredModel(ctx: Context, configured: String, answered: String) {
        lastAnsweredModel = answered
        try { Log.i("AiBridge", "answered by model: " + answered) } catch (_: Throwable) {}
        try { Diag.log(ctx, "ai answered by " + answered) } catch (_: Throwable) {}
        if (answered != configured) {
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(ctx.applicationContext,
                                "AI fell back to " + answered, Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }

    /** v1.4.18 — THE CHAIN LAW (the "custom model + provider error = dead
     *  end" report): a CUSTOM model used to disable every fallback
     *  (arrayOf(m) alone), so a 503 wave or a bad name had nowhere to
     *  go. The chain now ALWAYS carries the default + the fallbacks
     *  AFTER the custom pick (deduped, order preserved). PURE
     *  (probe-pinned per the v1.4.11 parse-gate law). */
    internal fun buildChain(m: String): Array<String> {
        val seen = LinkedHashSet<String>()
        seen.add(m)
        seen.add(GEMINI_DEFAULT)
        for (f in GEMINI_FALLBACKS) seen.add(f)
        return seen.toTypedArray()
    }

    /** v1.4.18 — when does the chain walk to the next model? The 404
     *  model-not-found shape AND the 5xx provider bucket (a dead or
     *  overloaded model is exactly what a fallback exists for). Auth,
     *  quota, rate-limit and parse errors return false — another model
     *  on the same key would fail identically. PURE (probe-pinned). */
    internal fun advanceChain(err: String): Boolean {
        return err.contains("model not found") || err.contains("provider error")
    }

    /** v1.4.18 — parse the provider's retry hint (PURE, probe-pinned):
     *  Gemini 5xx bodies carry error.details[] with retryInfo.retryDelay
     *  as "NNs"; a plain scan (no JSON parser — the probe must run
     *  dependency-free) finds the FIRST retryDelay, reads its seconds,
     *  and returns [dflt] when absent or malformed. The caller caps and
     *  floors it. */
    internal fun retryDelaySeconds(body: String, dflt: Int): Int {
        if (body.isEmpty()) return dflt
        val i = body.indexOf("retryDelay")
        if (i < 0) return dflt
        val m = java.util.regex.Pattern.compile("(\\d{1,4})s").matcher(body.substring(i))
        if (!m.find()) return dflt
        return try { Integer.parseInt(m.group(1)) } catch (_: Throwable) { dflt }
    }

    /** HTTP-code + provider message, key-free, toast-safe.
     *  v1.4.17 — THE RAW-RESPONSE LEG (the "Provider error" report): the
     *  toast-safe shortening hid the exact HTTP shape (401 vs 429 vs 400
     * vs a 5xx) — the raw provider body is the only evidence that
     * separates "model shut down" from "key invalid" from "quota" in a
     * field report, so every error path (they all funnel through here)
     * now mirrors the raw response to logcat under the AiBridge tag,
     * capped at 2000 chars so a 5xx HTML error page cannot flood the
     * log ring. The model chain itself was already verified current
     * (gemini-3.5-flash-lite default + gemini-flash-latest/3.8 fallbacks,
     * re-checked against aistudio 2026-11-08 — the report's gemini-1.0
     * diagnosis predates the v1.3.75 chain move); what was missing was
     * the evidence channel, not a newer model name. */
    private fun shortErr(code: Int, body: String): String {
        try {
            Log.e("AiBridge", "Raw Response: " + body.take(2000))
        } catch (_: Throwable) {}
        val base = when (code) {
            401, 403 -> "invalid API key"
            404 -> "model not found"
            429 -> "rate limited (free tier — wait a minute)"
            in 500..599 -> "provider error"
            else -> "HTTP " + code
        }
        // 404 shape decides the Gemini fallback chain
        if (code == 404 && body.contains("not found", true)) return "model not found"
        if (code == 400 && body.contains("API key", true)) return "invalid API key"
        if (code == 400 && body.contains("quota", true)) return "quota exceeded (free tier)"
        // v1.3.76: a 400 naming the thinking config must stay recognizable —
        // the chat/vision callers retry once WITHOUT thinkingConfig on it
        if (code == 400 && body.contains("thinking", true)) return "thinking config not supported"
        return base
    }

    private fun readText(s: InputStream?): String {
        if (s == null) return ""
        return try {
            val bo = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var n = s.read(buf)
            while (n >= 0 && bo.size() < 2_000_000) {
                bo.write(buf, 0, n)
                n = s.read(buf)
            }
            String(bo.toByteArray(), Charsets.UTF_8)
        } catch (_: Throwable) { "" }
    }
}
