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
    fun visionTranslate(ctx: Context, b64: String, tl: String, iw: Int, ih: Int): ArrayList<AiBox>? {
        lastError = ""
        val p = prompt(tl, iw, ih)
        return if (prov(ctx) == "gemini") {
            val m = model(ctx)
            val chain = if (m == GEMINI_DEFAULT) arrayOf(m, *GEMINI_FALLBACKS) else arrayOf(m)
            for (name in chain) {
                val r = geminiCall(ctx, name, p, b64, iw, ih)
                if (r != null) return r
                // only the model-not-found shape advances the chain
                if (!lastError.contains("model")) return null
            }
            null
        } else {
            openaiCall(ctx, p, b64, iw, ih)
        }
    }

    /** Gemini native: POST v1beta/models/{model}:generateContent.
     * v1.3.76 — thinkingLevel minimal: the 3.x flash models think by
     * default and the field called Bolt AI "kind of slow and performance
     * pretty mediocre"; minimal is the floor the models allow and it
     * cuts the visible latency hard. A 400 that names thinking (older /
     * custom models) is retried ONCE with the field removed. */
    private fun geminiCall(ctx: Context, mdl: String, p: String, b64: String,
                           iw: Int, ih: Int): ArrayList<AiBox>? {
        var body = visionBody(p, b64, true)
        var r = geminiPostRetry(ctx, mdl, body)
        if (r.first == 400 && r.second.contains("thinking", true)) {
            body = visionBody(p, b64, false)
            r = geminiPostRetry(ctx, mdl, body)
        }
        val (code, txt) = r
        if (code != 200) {
            lastError = shortErr(code, txt)
            return null
        }
        // v1.3.75 — the envelope is unwrapped FIRST (the v1.3.74 bug:
        // the raw body went to the box parser and every run "found no
        // text"), THEN the model's text is parsed for boxes
        return parseBoxes(geminiText(txt), iw, ih)
    }

    /** the vision request body (think=false strips thinkingConfig) */
    private fun visionBody(p: String, b64: String, think: Boolean): JSONObject {
        val body = JSONObject()
        val parts = JSONArray()
        parts.put(JSONObject().put("text", p))
        parts.put(JSONObject().put("inline_data",
            JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
        body.put("contents", JSONArray().put(
            JSONObject().put("role", "user").put("parts", parts)))
        // v1.3.75: responseMimeType json makes the model emit a raw
        // JSON array (no fences, no commentary) and maxOutputTokens
        // 16384 leaves room for thinking + dense pages (8192 could cut
        // off busy manga — "the AI reply was cut off")
        val gc = JSONObject()
            .put("temperature", 0.1).put("maxOutputTokens", 16384)
            .put("responseMimeType", "application/json")
        if (think) gc.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
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
            val mdl = model(ctx)
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

    // ---------------- the chat engine (v1.3.75 — Bolt AI's brain) ----------------

    /** one chat turn ("user" or "assistant" — mapped to "model" on Gemini) */
    class Msg(val role: String, val text: String)

    /**
     * BLOCKING (worker thread only). Multi-turn text chat with the
     * configured provider. [system] is the assistant's standing instruction;
     * [history] is the turns so far (the caller caps it). Returns the reply
     * text, or null with [lastError] set. Never sees a screenshot — page
     * context arrives as plain text the caller already showed a confirm for.
     */
    @JvmStatic
    fun chat(ctx: Context, system: String, history: List<Msg>): String? {
        lastError = ""
        return if (prov(ctx) == "gemini") {
            val m = model(ctx)
            val chain = if (m == GEMINI_DEFAULT) arrayOf(m, *GEMINI_FALLBACKS) else arrayOf(m)
            for (name in chain) {
                val r = geminiChat(ctx, name, system, history)
                if (r != null) return r
                if (!lastError.contains("model")) return null
            }
            null
        } else {
            openaiChat(ctx, system, history)
        }
    }

    /** Gemini native chat: systemInstruction + contents[user/model turns].
     * v1.3.76 — thinkingLevel minimal (the latency cut) with a one-shot
     * retry without it for models that reject the field, and the chat cap
     * trimmed 4096 -> 2048 (snappier turns, replies truncate gracefully). */
    private fun geminiChat(ctx: Context, mdl: String, system: String,
                           history: List<Msg>): String? {
        var reply = geminiChatPost(ctx, mdl, system, history, true)
        if (reply == null && lastError.contains("thinking", true)) {
            reply = geminiChatPost(ctx, mdl, system, history, false)
        }
        return reply
    }

    private fun geminiChatPost(ctx: Context, mdl: String, system: String,
                               history: List<Msg>, think: Boolean): String? {
        val contents = JSONArray()
        for (m in history) {
            val parts = JSONArray().put(JSONObject().put("text", m.text))
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
    private fun openaiChat(ctx: Context, system: String, history: List<Msg>): String? {
        var c: HttpURLConnection? = null
        return try {
            val mdl = model(ctx)
            if (mdl.isBlank()) { lastError = "set a model in the AI settings"; return null }
            val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            for (m in history) {
                msgs.put(JSONObject().put("role", m.role).put("content", m.text))
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
     * the overlay never cares who answered. */
    private fun parseBoxes(txt: String, iw: Int, ih: Int): ArrayList<AiBox>? {
        val text = extractJsonArray(txt) ?: run { lastError = "the AI reply was not JSON"; return null }
        val arr = try { JSONArray(text) } catch (_: Throwable) {
            lastError = "the AI reply was cut off (try a less dense screen)"; return null
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
