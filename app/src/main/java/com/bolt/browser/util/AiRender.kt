package com.bolt.browser.util

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.TextView
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.ins.Ins
import org.commonmark.ext.ins.InsExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * v1.4.20 — MARKDOWN + LATEX FOR BOLT AI (the "render assistant replies
 * with Markdown and LaTeX" ask).
 *
 * THE SHAPE (the constraint that decided everything): the chat is NATIVE —
 * TextView bubbles in a LinearLayout, not a WebView — so rendering must
 * produce Android [Spanned], never HTML. The pipeline:
 *
 *   raw reply --codeProtect()--> text with math held out as U+E000-range
 *   placeholders (code spans/fences NEVER feed the math extractor)
 *          --commonmark--> AST (core + tables extension, ~130 KB of pure
 *   Java, zero fonts — the 6 MB law's "no heavy bundled font packs" clause
 *   rules out jlatexmath-android's ~1.5 MB Latin Modern pack for a chat
 *   nicety)
 *          --visitor--> SpannableStringBuilder (headings, lists, bold/
 *   italic, links, tables as aligned monospace grids, code blocks with
 *   background)
 *          --math re-insert--> the placeholders become LaTeX-subset spans
 *   on the SYSTEM font: sup/sub, greek + operator unicode map, \frac
 *   flattening, \text, \sqrt — an honest subset, with the raw source
 *   surviving long-press copy (the bubble's tag carries the raw text).
 *
 * ASSISTANT messages only — user input stays plain (their markdown is
 * theirs to quote literally). The thinking/error bubbles stay plain too;
 * the reply re-renders the moment it replaces the "…" (the stream
 * completion of this non-streaming chat).
 *
 * The pure halves ([codeProtect], [texMap], [fracFlat], [renderTable])
 * are probe-pinned in scripts/test_v1420.py; the Android span half is
 * structural (the library owns the parsing correctness).
 */
object AiRender {

    // ---------------- the math extractor (PURE — probe-pinned) ----------------

    /** one extracted math segment: block math ($$…$$ / \[…\]) centers its
     *  own paragraph; inline math ($…$ / \(…\)) rides the line. */
    class MathSeg(val block: Boolean, val tex: String)

    /** placeholder bases: private-use chars the model never emits in
     *  prose; inline and block math take two ranges so the visitor can
     *  re-insert the right kind from the char alone. */
    const val PH_BASE = '\uE000'   // inline: E000..E7FF
    const val PH_BLOCK = '\uF000'  // block:  F000..F7FF

    /**
     * PURE (probe-pinned) — hold every math segment out of the markdown
     * text, replacing each with one placeholder char. WHY pre-parse: the
     * markdown parser would eat the underscores and asterisks INSIDE
     * `x_i * y` as emphasis. WHY code-aware: a `$…$` inside a code span
     * or fence is LITERAL (the model quoting shell or TeX source) and
     * must never become math — a fence/inline-code MASK is computed
     * first and the math scanner only runs on unmasked zones.
     *
     * Delimiter rules: `$$…$$` and `\[…\]` are block math and may span
     * lines; `$…$` is inline (single line, opening $ not followed by
     * whitespace, closing $ not preceded by whitespace — the currency
     * heuristic every markdown-with-math dialect uses); `\(…\)` is
     * inline. An escaped \$ never opens math.
     */
    fun codeProtect(text: String): String {
        val segs = ArrayList<MathSeg>()
        val out = StringBuilder()
        val n = text.length
        // ---- phase 1: the literal mask (true = inside a fence or inline code)
        val mask = BooleanArray(n)
        var i = 0
        var fence = false
        var fenceMark = ""
        while (i < n) {
            val lineEnd = text.indexOf('\n', i).let { if (it < 0) n else it }
            val line = text.substring(i, lineEnd)
            val trimmed = line.trim()
            if (fence) {
                java.util.Arrays.fill(mask, i, lineEnd, true)
                if (trimmed.startsWith(fenceMark)) fence = false
            } else if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                java.util.Arrays.fill(mask, i, lineEnd, true)
                fence = true
                fenceMark = trimmed.substring(0, 3)
            } else {
                // inline `code` runs (possibly doubled ticks)
                var j = 0
                val ln = line.length
                while (j < ln) {
                    if (line[j] == '`') {
                        var t = j
                        while (t < ln && line[t] == '`') t++
                        val ticks = line.substring(j, t)
                        val close = line.indexOf(ticks, t)
                        val end = if (close >= 0) close + ticks.length else t
                        java.util.Arrays.fill(mask, i + j, i + end, true)
                        j = end
                    } else j++
                }
            }
            i = lineEnd + 1
        }
        // ---- phase 2: the math scanner over unmasked zones
        var phInline = 0
        var phBlock = 0
        i = 0
        while (i < n) {
            if (mask[i]) { out.append(text[i]); i++; continue }
            val c = text[i]
            when {
                c == '\\' && i + 1 < n && text[i + 1] == '$' -> {
                    out.append(text, i, i + 2); i += 2
                }
                c == '$' && i + 1 < n && text[i + 1] == '$' -> {
                    val end = text.indexOf("$$", i + 2)
                    if (end > i + 2) {
                        segs.add(MathSeg(true, text.substring(i + 2, end)))
                        out.append(PH_BLOCK + (phBlock++ % 0x800))
                        i = end + 2
                    } else { out.append(text, i, i + 2); i += 2 }
                }
                c == '$' -> {
                    val after = if (i + 1 < n) text[i + 1] else ' '
                    val end = text.indexOf('$', i + 1)
                    val nl = text.indexOf('\n', i + 1)
                    if (!after.isWhitespace() && after != '$' && end > i + 1
                            && !text[end - 1].isWhitespace()
                            && (nl < 0 || end < nl)
                            && !mask[end]) {
                        segs.add(MathSeg(false, text.substring(i + 1, end)))
                        out.append(PH_BASE + (phInline++ % 0x800))
                        i = end + 1
                    } else { out.append(c); i++ }
                }
                c == '\\' && i + 1 < n && text[i + 1] == '(' -> {
                    val end = text.indexOf("\\)", i + 2)
                    val nl = text.indexOf('\n', i + 2)
                    if (end > i + 2 && (nl < 0 || end < nl)) {
                        segs.add(MathSeg(false, text.substring(i + 2, end)))
                        out.append(PH_BASE + (phInline++ % 0x800))
                        i = end + 2
                    } else { out.append(text, i, i + 2); i += 2 }
                }
                c == '\\' && i + 1 < n && text[i + 1] == '[' -> {
                    val end = text.indexOf("\\]", i + 2)
                    if (end > i + 2) {   // may span lines (block math)
                        segs.add(MathSeg(true, text.substring(i + 2, end)))
                        out.append(PH_BLOCK + (phBlock++ % 0x800))
                        i = end + 2
                    } else { out.append(text, i, i + 2); i += 2 }
                }
                else -> { out.append(c); i++ }
            }
        }
        pendingSegs = segs
        return out.toString()
    }

    /** the segments [codeProtect] extracted for the CURRENT text (the
     *  placeholder → segment mapping is positional: inline placeholders
     *  count up from PH_BASE, block ones from PH_BLOCK). */
    @Volatile private var pendingSegs: List<MathSeg> = emptyList()

    // ---------------- the LaTeX subset (PURE core — probe-pinned) ----------------

    /** PURE (probe-pinned) — the command → unicode map: greek letters,
     *  operators, arrows, relations, double-struck classics. null = not
     *  one of ours (the caller keeps the raw source, honestly). */
    fun texMap(cmd: String): String? = when (cmd) {
        "alpha" -> "α"; "beta" -> "β"; "gamma" -> "γ"; "delta" -> "δ"
        "epsilon" -> "ε"; "varepsilon" -> "ε"; "zeta" -> "ζ"; "eta" -> "η"
        "theta" -> "θ"; "vartheta" -> "ϑ"; "iota" -> "ι"; "kappa" -> "κ"
        "lambda" -> "λ"; "mu" -> "μ"; "nu" -> "ν"; "xi" -> "ξ"
        "pi" -> "π"; "rho" -> "ρ"; "sigma" -> "σ"; "tau" -> "τ"
        "upsilon" -> "υ"; "phi" -> "φ"; "varphi" -> "φ"; "chi" -> "χ"
        "psi" -> "ψ"; "omega" -> "ω"
        "Gamma" -> "Γ"; "Delta" -> "Δ"; "Theta" -> "Θ"; "Lambda" -> "Λ"
        "Xi" -> "Ξ"; "Pi" -> "Π"; "Sigma" -> "Σ"; "Upsilon" -> "Υ"
        "Phi" -> "Φ"; "Psi" -> "Ψ"; "Omega" -> "Ω"
        "times" -> "×"; "cdot" -> "⋅"; "div" -> "÷"; "pm" -> "±"; "mp" -> "∓"
        "leq" -> "≤"; "le" -> "≤"; "geq" -> "≥"; "ge" -> "≥"
        "neq" -> "≠"; "ne" -> "≠"; "approx" -> "≈"; "equiv" -> "≡"
        "sim" -> "∼"; "propto" -> "∝"; "ll" -> "≪"; "gg" -> "≫"
        "infty" -> "∞"; "partial" -> "∂"; "nabla" -> "∇"
        "sum" -> "∑"; "prod" -> "∏"; "int" -> "∫"; "iint" -> "∬"; "oint" -> "∮"
        "in" -> "∈"; "notin" -> "∉"; "ni" -> "∋"
        "subset" -> "⊂"; "supset" -> "⊃"; "subseteq" -> "⊆"; "supseteq" -> "⊇"
        "cup" -> "∪"; "cap" -> "∩"; "emptyset" -> "∅"; "varnothing" -> "∅"
        "forall" -> "∀"; "exists" -> "∃"; "neg" -> "¬"; "land" -> "∧"; "lor" -> "∨"
        "rightarrow" -> "→"; "to" -> "→"; "leftarrow" -> "←"
        "Rightarrow" -> "⇒"; "Leftarrow" -> "⇐"; "leftrightarrow" -> "↔"
        "Leftrightarrow" -> "⇔"; "mapsto" -> "↦"; "uparrow" -> "↑"; "downarrow" -> "↓"
        "ldots" -> "…"; "cdots" -> "⋯"; "vdots" -> "⋮"; "ddots" -> "⋱"
        "prime" -> "′"; "circ" -> "∘"; "bullet" -> "•"; "star" -> "⋆"
        "oplus" -> "⊕"; "otimes" -> "⊗"; "perp" -> "⊥"; "parallel" -> "∥"
        "angle" -> "∠"; "triangle" -> "△"; "square" -> "□"
        "degree" -> "°"
        "mathbb{R}" -> "ℝ"; "mathbb{N}" -> "ℕ"; "mathbb{Z}" -> "ℤ"
        "mathbb{Q}" -> "ℚ"; "mathbb{C}" -> "ℂ"
        "hbar" -> "ℏ"; "ell" -> "ℓ"; "Re" -> "ℜ"; "Im" -> "ℑ"
        else -> null
    }

    /** PURE (probe-pinned) — the \frac flattening: a/b, with each side
     *  parenthesized when it is compound (carries an operator). \frac{a+b}
     *  {c} reads (a+b)/c, \frac{a}{b} reads a/b, a bare number or single
     *  letter stays bare. */
    fun fracFlat(num: String, den: String): String {
        return parenIfCompound(num.trim()) + "/" + parenIfCompound(den.trim())
    }

    private fun parenIfCompound(s: String): String {
        if (s.isEmpty() || s.length == 1) return s
        if (s.matches(Regex("^[0-9]+(\\.[0-9]+)?$"))) return s
        if (!s.contains(Regex("[+\\-*/^_=<>]"))) return s
        return "(" + s + ")"
    }

    // ---------------- the markdown visitor ----------------

    /** render one reply: markdown + math → Spanned (assistant bubbles).
     *  NEVER throws — worst case the raw text rides out unchanged.
     *  v1.4.24 — [view] (the bubble TextView) rides along when the caller
     *  can hand it over: while the math FONT PACK is still downloading,
     *  the reply renders through the v1.4.20 subset and the bubble is
     *  held for a full re-render the moment the pack lands (the raw text
     *  rides the view's tag). */
    fun render(c: Context, md: String, view: android.widget.TextView? = null): Spanned {
        val sb = SpannableStringBuilder()
        try {
            if (view != null) holdForFonts(c, md, view)
            val prepared = codeProtect(md)
            val segs = pendingSegs
            // v1.4.23 — THE FOUR-PACK joins the parser set: task-list items
            // (the tappable [x] checkboxes), footnotes (inlineFootnotes ON —
            // [^n] references plus the appended trailer), and ins (<ins> ==
            // underline == ++text++). Same plain -ext-<name> artifact family
            // as the GFM pair — pure Java, R8 tree-shakes the rest.
            // v1.4.26 — THE SUB/SUP DELIMITERS: ~text~ is a SUBSCRIPT and
            // ^text^ a SUPERSCRIPT (the model-markdown forms; the HTML
            // <sub>/<sup> tags already worked). The strikethrough extension
            // re-configures to requireTwoTildes — its own documented knob
            // for single-tilde coexistence — so ~~strike~~ keeps working and
            // a single ~ run falls to the sub processor (the staggered
            // resolver dispatches by run length).
            val parser = Parser.builder()
                    .extensions(listOf(TablesExtension.create(),
                            StrikethroughExtension.builder()
                                    .requireTwoTildes(true).build(),
                            TaskListItemsExtension.create(),
                            InsExtension.create(),
                            FootnotesExtension.builder()
                                    .inlineFootnotes(true).build()))
                    .customDelimiterProcessor(SubSupDelimiterProcessor(false))
                    .customDelimiterProcessor(SubSupDelimiterProcessor(true))
                    .build()
            val doc = parser.parse(prepared)
            val v = V(c, sb, segs)
            v.children(doc)
            // v1.4.23 — the footnote trailer prints AFTER the visit (the
            // definitions were filed while walking the document)
            v.footnotes()
        } catch (_: Throwable) {
            // the renderer must never eat a reply
            sb.clear()
            sb.append(md)
        }
        return sb
    }

    // ---------------- v1.4.21 — full TeX through jlatexmath-android ----------------

    /** v1.4.21 — lazy library init (the AAR ships an auto-init provider;
     *  Bolt removes it from the merged manifest and inits HERE instead so
     *  a browser start never pays for a chat nicety — the startup law).
     *  v1.4.25: jlmInit moved INTO the builder path below — with the core
     *  fonts bundled, the first CORE render pays the (cheap, lazy) init
     *  itself; a lazy Typeface load means the 23 optional fonts cannot
     *  break it. */
    @Volatile private var jlmInited = false
    private fun jlmInit(c: Context) {
        if (jlmInited) return
        try {
            ru.noties.jlatexmath.JLatexMathAndroid.init(c.applicationContext)
        } catch (_: Throwable) {}
        jlmInited = true
    }

    /** v1.4.21 — FULL TeX rendering (the matrices ask): the whole
     *  jlatexmath engine (\begin{matrix}/pmatrix/bmatrix/vmatrix, cases,
     *  align, fractions, integrals, Greek, accents) as a native drawable
     *  for the TextView chat — no WebView, no bitmap of our own (the
     *  drawable paints on the bubble's canvas). Theme-aware text color;
     *  a formula that cannot parse (or a runaway size) returns null and
     *  the caller falls back to the v1.4.20 span subset, then verbatim.
     */
    fun mathDrawable(c: Context, tex: String, block: Boolean): android.graphics.drawable.Drawable? {
        return try {
            // v1.4.25 — THE CORE-FONT LAW: the 7 CORE fonts ride the APK
            // (scripts/thin_jlatexmath.py's CORE set), so fractions,
            // matrices, Greek, integrals, sums and AMS symbols render
            // OFFLINE/METERED immediately — the v1.4.24 pack-present gate
            // is GONE (the metered corpse: "math dl fail dl base/
            // jlm_cmex10.ttf" meant a plain fraction died waiting for a
            // download that metered connections skipped entirely). The
            // engine loads typefaces LAZILY (FontInfo.getFont), so a
            // formula that needs an OPTIONAL font (\mathfrak, \mathscr,
            // rare CM variants) THROWS here — caught below: the span
            // subset shows honestly, the optional pack download is kicked
            // (retry law — no process lockout), and the held bubble
            // re-renders the moment it lands (holdForFonts).
            jlmInit(c)          // cheap, once; the lazy font loads follow
            val d = ru.noties.jlatexmath.JLatexMathDrawable.builder(tex)
                    .textSize(if (block) Fx.dp(c, 19).toFloat() else Fx.dp(c, 15).toFloat())
                    .padding(0)
                    .color(Fx.txt(c))
                    .build()
            val w = d.intrinsicWidth
            val h = d.intrinsicHeight
            if (w <= 0 || h <= 0 || w > 4096 || h > 4096) null else d
        } catch (t: Throwable) {
            // an optional-font miss (or a parse failure): the subset is
            // the honest fallback, and the download gets a nudge — the
            // RETRY LAW makes the next attempt cheap
            try {
                com.bolt.browser.util.MathFonts.ensureAsync(c, onReady = {
                    try { refreshPendings() } catch (t2: Throwable) {}
                })
            } catch (t2: Throwable) {}
            null
        }
    }

    // ---------------- v1.4.24 — the on-demand font pack's re-render ----------------

    /** bubbles that rendered math through the subset fallback while the
     *  pack was still downloading (weak — a dismissed bubble dies) */
    private val fontPending = java.util.concurrent.CopyOnWriteArrayList<
            java.lang.ref.WeakReference<android.widget.TextView>>()

    /** the [render] half: a reply that CARRIES math, rendered while the
     *  font pack is absent, is held for a re-render on pack-ready. */
    private fun holdForFonts(c: Context, md: String, view: android.widget.TextView) {
        try {
            if (com.bolt.browser.util.MathFonts.present(c)) return
            val mathPossible = md.contains('$') || md.contains("\\[") || md.contains("\\(")
            if (!mathPossible) return
            for (w in fontPending) {
                if (w.get() === view) return   // already held (stream refreshes)
            }
            fontPending.add(java.lang.ref.WeakReference(view))
            com.bolt.browser.util.MathFonts.ensureAsync(c, onReady = {
                try { refreshPendings() } catch (t: Throwable) {}
            })
        } catch (t: Throwable) {}
    }

    /** MAIN thread (the MathFonts completion posts here): re-render every
     *  held bubble through the now-complete font pack — the raw source
     *  rides each bubble's tag (long-press copy already relies on it). */
    @JvmStatic
    fun refreshPendings() {
        for (w in fontPending) {
            val v = w.get() ?: continue
            try {
                val raw = v.tag as? String ?: continue
                v.text = render(v.context, raw)
            } catch (t: Throwable) {}
        }
        fontPending.clear()
    }

    /** the visitor — one branch per node kind, spans applied as the
     *  builder fills. Bold/italic/links/code ride commonmark's inline
     *  nodes; lists get BulletSpan / leading numbers; tables flatten to
     *  aligned monospace grids ([renderTable], pure); math placeholders
     *  expand into the LaTeX-subset spans. */
    private class V(val c: Context, val sb: SpannableStringBuilder,
                    val segs: List<MathSeg>) {

        var listLevel = 0
        // v1.4.23 — the four-pack per-render state: footnote numbering
        // (label -> number, first-citation order — references and the
        // trailer share it), the rendered definition texts (label ->
        // trimmed text), the OPEN HTML TAG STACK (one HtmlTag per
        // unclosed inline tag; a matching close pops it and spans the
        // covered range), and the quote depth for the nested bars.
        val fnNum = LinkedHashMap<String, Int>()
        val fnText = LinkedHashMap<String, String>()
        val tags = ArrayList<HtmlTag>()
        var quoteLevel = 0

        /** one OPEN inline tag: its name, the span start, and the span
         *  objects to apply when it closes. */
        class HtmlTag(val name: String, val start: Int, val spans: ArrayList<Any>)

        // v1.4.23 — the tag token matcher (name / attrs / self-close) and
        // the href extractor inside the attrs
        private val TAG_RE = Regex("</?([a-zA-Z][a-zA-Z0-9]*)((?:\\s[^>]*)?)(/?)>")
        private val ATTR_HREF = Regex("href\\s*=\\s*[\"']([^\"']*)[\"']")
        // v1.4.25 — THE COLOUR ATTRS: <font color=…> and any tag's
        // style="color: …" (inline styles commonly carry background-color
        // too — only the text colour is honoured here, the chat's own
        // <mark> stays the highlight)
        private val ATTR_COLOR = Regex("(?:^|\\s)color\\s*=\\s*[\"']([^\"']*)[\"']")
        private val ATTR_STYLE = Regex("style\\s*=\\s*[\"']([^\"']*)[\"']")
        private val STYLE_COLOR = Regex("(^|;)\\s*color\\s*:\\s*([^;!}\"]+)")

        fun children(parent: Node) {
            var n = parent.firstChild
            while (n != null) {
                visit(n)
                n = n.next
            }
        }

        fun visit(n: Node) {
            when (n) {
                is Paragraph -> { children(n); closeAllTags(); blockGap() }
                is Heading -> {
                    val start = sb.length
                    val size = when (n.level) {
                        1 -> 1.45f; 2 -> 1.3f; 3 -> 1.18f; else -> 1.08f
                    }
                    children(n)
                    val end = sb.length
                    if (end > start) {
                        sb.setSpan(RelativeSizeSpan(size), start, end, 0)
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
                    }
                    blockGap()
                }
                is BulletList -> {
                    listLevel++
                    children(n)
                    listLevel--
                    blockGap()
                }
                is OrderedList -> {
                    listLevel++
                    var idx = n.startNumber
                    var ch = n.firstChild
                    while (ch != null) {
                        if (ch is ListItem) {
                            // v1.4.21 — nested lists indent: every level past
                            // the first pads its markers so the model's
                            // outline-shaped replies read as an outline
                            val pad = if (listLevel > 1) "    ".repeat(listLevel - 1) else ""
                            sb.append(pad).append(idx.toString()).append(". ")
                            children(ch)
                            idx++
                        }
                        ch = ch.next
                    }
                    listLevel--
                    blockGap()
                }
                is ListItem -> {
                    val start = sb.length
                    // v1.4.21 — the same indent on the bullet side
                    val pad = if (listLevel > 1) "    ".repeat(listLevel - 1) else ""
                    sb.append(pad)
                    // v1.4.23 — a TASK item (first child is the checkbox
                    // marker) renders the tappable box, never a bullet dot
                    val task = n.firstChild is TaskListItemMarker
                    children(n)
                    if (!task) sb.setSpan(BulletSpan(Fx.dp(c, 10) * listLevel.coerceAtMost(4)),
                            start + pad.length, sb.length, 0)
                }
                // v1.4.23 — NESTED QUOTES: every level carries its own
                // QuoteSpan bar + leading margin — the spans stack, so a
                // quote inside a quote draws two bars and indents past the
                // outer one (the four-pack's quote half; the flat italic
                // is retired).
                is BlockQuote -> {
                    val lvl = quoteLevel + 1
                    val start = sb.length
                    quoteLevel++
                    children(n)
                    quoteLevel--
                    val end = sb.length
                    if (end > start) {
                        sb.setSpan(QuoteSpan(Fx.dim(c)), start, end, 0)
                        sb.setSpan(LeadingMarginSpan.Standard(
                                Fx.dp(c, 10) * lvl.coerceAtMost(4)), start, end, 0)
                    }
                    blockGap()
                }
                // v1.4.23 — the task checkbox: the marker renders the box
                // glyph with a ClickableSpan that flips it in the bubble
                is TaskListItemMarker -> {
                    val start = sb.length
                    sb.append(if (n.isChecked) "\u2611" else "\u2610")
                    val end = start + 1
                    sb.append(' ')
                    sb.setSpan(TaskSpan(n.isChecked, Fx.accentText(c)),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                // v1.4.23 — the footnote REFERENCE: a superscript number,
                // assigned in first-citation order (the trailer below
                // prints the definitions)
                is FootnoteReference -> {
                    val num = fnNum.getOrPut(n.label) { fnNum.size + 1 }
                    val start = sb.length
                    sb.append(num.toString())
                    val end = sb.length
                    sb.setSpan(SuperscriptSpan(), start, end, 0)
                    sb.setSpan(RelativeSizeSpan(0.75f), start, end, 0)
                    sb.setSpan(ForegroundColorSpan(Fx.accentText(c)), start, end, 0)
                }
                // v1.4.23 — the footnote DEFINITION: filed for the trailer,
                // never rendered in place
                is FootnoteDefinition -> {
                    if (!fnText.containsKey(n.label)) {
                        fnText[n.label] = collectText(n).trim()
                    }
                }
                // v1.4.23 — ins == underline (both the tag form <ins> and
                // the delimiter form ++text++)
                is Ins -> {
                    val start = sb.length
                    children(n)
                    sb.setSpan(UnderlineSpan(), start, sb.length, 0)
                }
                // v1.4.26 — the markdown DELIMITER forms: ~sub~ / ^sup^ (the
                // model-markdown sub/superscripts; the same span kit the
                // <sub>/<sup> HTML tags ride)
                is SubMd -> {
                    val start = sb.length
                    children(n)
                    sb.setSpan(SubscriptSpan(), start, sb.length, 0)
                    sb.setSpan(RelativeSizeSpan(0.72f), start, sb.length, 0)
                }
                is SupMd -> {
                    val start = sb.length
                    children(n)
                    sb.setSpan(SuperscriptSpan(), start, sb.length, 0)
                    sb.setSpan(RelativeSizeSpan(0.72f), start, sb.length, 0)
                }
                // v1.4.23 — HTML emphasis: inline tags push/pop the span
                // stack; unknown tags render their inner text only — the
                // markup never leaks into the bubble
                is HtmlInline -> htmlTag(n.literal)
                // v1.4.23 — block-level raw HTML: the literal rides through
                // with every <tag> fed to the same stack; tags still open at
                // the block's end are force-closed here (an HTML block is
                // its own scope)
                is HtmlBlock -> htmlBlockLit(n.literal)
                is FencedCodeBlock -> codeBlock(n.literal, n.info)
                is IndentedCodeBlock -> codeBlock(n.literal, "")
                is ThematicBreak -> {
                    sb.append("──────────────")
                    blockGap()
                }
                is TableBlock -> table(n)
                is StrongEmphasis -> {
                    val start = sb.length
                    children(n)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, 0)
                }
                // v1.4.21 — GFM strikethrough (~~text~~), the "complete it"
                // ask's first gap
                is Strikethrough -> {
                    val start = sb.length
                    children(n)
                    sb.setSpan(StrikethroughSpan(), start, sb.length, 0)
                }
                is Emphasis -> {
                    val start = sb.length
                    children(n)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, 0)
                }
                is Code -> {
                    val start = sb.length
                    sb.append(n.literal)
                    val end = sb.length
                    sb.setSpan(TypefaceSpan("monospace"), start, end, 0)
                    sb.setSpan(BackgroundColorSpan(codeBg()), start, end, 0)
                }
                is Link -> {
                    val start = sb.length
                    children(n)
                    val end = sb.length
                    if (end > start) {
                        sb.setSpan(URLSpan(n.destination), start, end, 0)
                        sb.setSpan(ForegroundColorSpan(Fx.accentText(c)), start, end, 0)
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
                    }
                }
                // v1.4.21 — IMAGES render as their alt text riding a link
                // to the source (no image fetch in a chat bubble: the
                // network stays where the user put it — the page). The
                // bracketed glyph marks it as media without an emoji.
                is Image -> {
                    val label = (n.firstChild as? Text)?.literal
                            ?.takeIf { it.isNotBlank() } ?: "image"
                    val start = sb.length
                    sb.append("\u3010").append(label).append("\u3011")
                    val end = sb.length
                    sb.setSpan(URLSpan(n.destination), start, end, 0)
                    sb.setSpan(ForegroundColorSpan(Fx.accentText(c)), start, end, 0)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, 0)
                }
                is SoftLineBreak -> sb.append('\n')
                is HardLineBreak -> sb.append('\n')
                is Text -> inlineText(n.literal)
                is Document -> children(n)
                else -> children(n)
            }
        }

        // ================= v1.4.23 — the four-pack helpers =================

        /** one inline tag token: <name attrs>, </name>, or a self-closed
         *  form. <br> is a newline; known names open a stack entry carrying
         *  their span kit; a closing token pops the nearest open entry of
         *  that name and spans the covered range. Unknown tags open an
         *  EMPTY entry so their close matches — inner text renders plain,
         *  never literal angle brackets. */
        fun htmlTag(lit: String) {
            val s = lit.trim()
            val m = TAG_RE.find(s) ?: return
            val name = m.groupValues[1].lowercase()
            if (name == "br") { sb.append('\n'); return }
            if (s.startsWith("</")) { closeTag(name); return }
            if (s.endsWith("/>")) return      // self-closed: nothing to span
            val spans = ArrayList<Any>()
            when (name) {
                "mark" -> spans.add(BackgroundColorSpan(markBg()))
                "u", "ins" -> spans.add(UnderlineSpan())
                "del", "s", "strike" -> spans.add(StrikethroughSpan())
                "sub" -> { spans.add(SubscriptSpan()); spans.add(RelativeSizeSpan(0.72f)) }
                "sup" -> { spans.add(SuperscriptSpan()); spans.add(RelativeSizeSpan(0.72f)) }
                "b", "strong" -> spans.add(StyleSpan(Typeface.BOLD))
                "i", "em" -> spans.add(StyleSpan(Typeface.ITALIC))
                // v1.4.25 — THE COLOUR LAW (the "AiRender ignores explicit
                // color" corpse): <span style="color:…"> and <font
                // color="…"> paint their range through a
                // ForegroundColorSpan — hex (#rgb/#rrggbb/#rrggbbaa),
                // rgb()/rgba() and the common named colours, parsed
                // SAFELY ([parseHtmlColor] never throws, never guesses:
                // an unparseable colour colours nothing)
                "span", "font" -> {
                    val col = htmlColor(name, m.groupValues[2])
                    if (col != null) spans.add(ForegroundColorSpan(col))
                }
                "code" -> { spans.add(TypefaceSpan("monospace"))
                            spans.add(BackgroundColorSpan(codeBg())) }
                "a" -> {
                    val href = ATTR_HREF.find(m.groupValues[2])?.groupValues?.get(1)
                    if (!href.isNullOrEmpty()) {
                        spans.add(URLSpan(href))
                        spans.add(ForegroundColorSpan(Fx.accentText(c)))
                    }
                }
            }
            tags.add(HtmlTag(name, sb.length, spans))
        }

        /** the closing token: pop the NEAREST open entry with this name and
         *  span its range; entries above it stay open (bounded leak —
         *  force-closed at the paragraph / block end). */
        fun closeTag(name: String) {
            for (i in tags.indices.reversed()) {
                if (tags[i].name == name) {
                    val t = tags.removeAt(i)
                    applySpans(t, sb.length)
                    return
                }
            }
        }

        /** v1.4.25 — the colour an opening span/font tag carries, or null:
         *  font -> its color= attribute; span -> the color property of its
         *  style= attribute (both single- and double-quoted). */
        fun htmlColor(tag: String, attrs: String): Int? {
            val raw = if (tag == "font")
                ATTR_COLOR.find(attrs)?.groupValues?.get(1)
            else
                ATTR_STYLE.find(attrs)?.groupValues?.get(1)?.let { st ->
                    STYLE_COLOR.find(st)?.groupValues?.get(2)
                }
            return raw?.let { parseHtmlColor(it.trim()) }
        }

        fun applySpans(t: HtmlTag, end: Int) {
            if (end <= t.start) return
            for (sp in t.spans) sb.setSpan(sp, t.start, end, 0)
        }

        /** force-close everything still open (paragraph + block scope). */
        fun closeAllTags() {
            while (tags.isNotEmpty()) applySpans(tags.removeAt(tags.size - 1), sb.length)
        }

        /** block-level raw HTML: strip the tags through the SAME stack (the
         *  known ones keep their span meaning), keep the inner text riding
         *  inlineText so math placeholders still work, then close what is
         *  still open — the block is its own scope. */
        fun htmlBlockLit(lit: String) {
            var i = 0
            for (m in Regex("<[^>]*>").findAll(lit)) {
                val between = lit.substring(i, m.range.first)
                if (between.isNotEmpty()) inlineText(between)
                htmlTag(m.value)
                i = m.range.last + 1
            }
            if (i < lit.length) inlineText(lit.substring(i))
            closeAllTags()
            blockGap()
        }

        /** plain-text collector for a footnote definition's content. */
        fun collectText(node: Node): String {
            val out = StringBuilder()
            var ch = node.firstChild
            while (ch != null) {
                when (ch) {
                    is Text -> out.append(ch.literal)
                    is SoftLineBreak, is HardLineBreak -> out.append(' ')
                    else -> out.append(collectText(ch))
                }
                ch = ch.next
            }
            return out.toString()
        }

        /** the appended FOOTNOTES section: only the REFERENCED definitions,
         *  in first-citation order — the numbers match the superscripts. */
        fun footnotes() {
            if (fnNum.isEmpty()) return
            var wrote = false
            for (label in fnNum.keys) {
                val txt = fnText[label] ?: continue
                val num = fnNum[label] ?: continue
                if (!wrote) { sb.append("\n\u2014\u2014\u2014\u2014 Footnotes\n"); wrote = true }
                val start = sb.length
                sb.append(num.toString()).append(". ").append(txt)
                sb.setSpan(ForegroundColorSpan(Fx.dim(c)), start, sb.length, 0)
                sb.setSpan(RelativeSizeSpan(0.92f), start, sb.length, 0)
                sb.append('\n')
            }
        }

        /** v1.4.23 — THE TAPPABLE CHECKBOX: a ClickableSpan over the box
         *  glyph; the tap flips the glyph in the bubble (and stays tappable
         *  — the replacement carries a fresh TaskSpan). The color is baked
         *  at construction so the span never holds a Context. */
        class TaskSpan(val on: Boolean, val accent: Int) : ClickableSpan() {
            override fun onClick(widget: View) {
                try {
                    val tv = widget as? TextView ?: return
                    val sp = tv.text as? Editable ?: return
                    val s = sp.getSpanStart(this)
                    val e = sp.getSpanEnd(this)
                    if (s < 0 || e <= s) return
                    val flipped = !on
                    sp.removeSpan(this)
                    sp.replace(s, e, if (flipped) "\u2611" else "\u2610")
                    sp.setSpan(TaskSpan(flipped, accent), s, s + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    tv.invalidate()
                } catch (_: Throwable) {}
            }
            override fun updateDrawState(tp: TextPaint) {
                tp.color = accent
                tp.isUnderlineText = false
            }
        }

        /** v1.4.23 — the <mark> highlight (theme-aware, legible on both). */
        fun markBg(): Int {
            val dark = try { Prefs.get(c).isDark() } catch (_: Throwable) { false }
            return if (dark) 0xFF4A442A.toInt() else 0xFFFFE9A8.toInt()
        }

        fun blockGap() {
            if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
            sb.append('\n')
        }

        /** v1.4.21 — code block + the lightweight syntax colorizer
         *  (tokenizeCode, PURE): keywords / strings / comments / numbers
         *  for the common chat languages, theme-aware, bounded. No
         *  highlighter dependency — a ~40-line scanner, per the 6 MB law. */
        fun codeBlock(lit: String, info: String) {
            val code = lit.trimEnd()
            val start = sb.length
            sb.append(code)
            val end = sb.length
            sb.setSpan(TypefaceSpan("monospace"), start, end, 0)
            sb.setSpan(RelativeSizeSpan(0.92f), start, end, 0)
            sb.setSpan(BackgroundColorSpan(codeBg()), start, end, 0)
            try {
                val toks = tokenizeCode(code, info.trim().substringBefore(' ').lowercase())
                val colors = codeColors()
                for (t in toks) {
                    if (t.kind == TOK_PLAIN) continue
                    val cs = start + t.start
                    val ce = start + t.end
                    if (ce <= cs || ce > end) continue
                    sb.setSpan(ForegroundColorSpan(colors[t.kind]), cs, ce, 0)
                }
            } catch (_: Throwable) {}
            blockGap()
        }

        fun codeBg(): Int {
            val dark = try { Prefs.get(c).isDark() } catch (_: Throwable) { false }
            return if (dark) 0xFF24303C.toInt() else 0xFFE8EDF2.toInt()
        }

        /** v1.4.21 — theme-aware highlight colors (One Dark / One Light
         *  families; the span subset stays legible on the codeBg above). */
        fun codeColors(): IntArray {
            val dark = try { Prefs.get(c).isDark() } catch (_: Throwable) { false }
            return if (dark) intArrayOf(0, 0xFF7F8C98.toInt(), 0xFF98C379.toInt(),
                    0xFFC678DD.toInt(), 0xFFD19A66.toInt())
            else intArrayOf(0, 0xFF8A94A0.toInt(), 0xFF50A14F.toInt(),
                    0xFFA626A4.toInt(), 0xFF986801.toInt())
        }

        fun table(t: TableBlock) {
            // flatten to an aligned monospace grid (renderTable, pure)
            // v1.4.26 — THE WRAPPER TRAVERSAL (the empty-table corpse): the
            // commonmark GFM AST wraps the rows in TableHead/TableBody
            // blocks — TableBlock's direct children are the WRAPPERS, not
            // TableRows, so the old firstChild-only walk collected ZERO
            // rows and every GFM table rendered empty. The walk now descends
            // one level into BOTH wrappers (in document order) and gathers
            // every TableRow either way.
            val rows = ArrayList<List<String>>()
            fun cellsOf(row: TableRow) {
                val cells = ArrayList<String>()
                var cell = row.firstChild
                while (cell != null) {
                    if (cell is TableCell) {
                        val sub = SpannableStringBuilder()
                        V(c, sub, emptyList()).children(cell)
                        cells.add(sub.toString())
                    }
                    cell = cell.next
                }
                rows.add(cells)
            }
            var node = t.firstChild
            while (node != null) {
                if (node is TableRow) {
                    cellsOf(node)
                } else {
                    // the TableHead/TableBody wrappers (and any other block
                    // wrapper): descend into their children for TableRows
                    var kid = node.firstChild
                    while (kid != null) {
                        if (kid is TableRow) cellsOf(kid)
                        kid = kid.next
                    }
                }
                node = node.next
            }
            val start = sb.length
            sb.append(renderTable(rows))
            val end = sb.length
            if (end > start) {
                sb.setSpan(TypefaceSpan("monospace"), start, end, 0)
                sb.setSpan(RelativeSizeSpan(0.9f), start, end, 0)
                // v1.4.21 — THE STYLED HEADER: the header line (up to the
                // first newline of the grid, before the dashed rule) goes
                // BOLD in the accent color — a real table reads with a
                // header, not just a monospace slab
                val nl = sb.indexOf('\n', start)
                if (nl in (start + 1) until end) {
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, nl, 0)
                    sb.setSpan(ForegroundColorSpan(Fx.accentText(c)), start, nl, 0)
                }
            }
            blockGap()
        }

        /** plain text: expand any math placeholders it carries.
         *  v1.4.21 — FULL TeX FIRST: every segment tries
         *  [AiRender.mathDrawable] (jlatexmath — matrices, cases, align,
         *  the works) as an ImageSpan on a placeholder-width slot; a
         *  formula the engine cannot shape falls back to the v1.4.20 span
         *  subset ([appendTex]), which renders its honest subset and
         *  keeps the rest verbatim. The raw source still rides the
         *  bubble's tag (long-press copy). */
        fun inlineText(s: String) {
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch >= PH_BASE && ch < PH_BLOCK) {
                    val idx = ch - PH_BASE
                    if (idx < segs.size) appendMath(segs[idx].tex, false)
                    i++
                } else if (ch >= PH_BLOCK && ch < '\uF800') {
                    val idx = ch - PH_BLOCK
                    if (idx < segs.size) {
                        sb.append('\n')
                        appendMath(segs[idx].tex, true)
                        sb.append('\n')
                    }
                    i++
                } else {
                    sb.append(ch); i++
                }
            }
        }

        /** one math segment → full-TeX ImageSpan, else the span subset.
         *  The slot char carries the span; the drawable's own width (via
         *  ImageSpan's bounds) grows the line for INLINE math and centers
         *  for BLOCK math. */
        fun appendMath(tex: String, block: Boolean) {
            try {
                val d = mathDrawable(c, tex, block)
                if (d != null) {
                    val st = sb.length
                    sb.append('\u2009')   // thin space: the span's slot
                    sb.setSpan(ImageSpan(d, ImageSpan.ALIGN_BOTTOM), st, sb.length, 0)
                    if (block) {
                        sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                                st, sb.length, 0)
                    }
                    return
                }
            } catch (_: Throwable) {}
            // v1.4.20 fallback: the hand-rolled subset
            if (block) {
                val st = sb.length
                appendTex(sb, tex)
                sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                        st, sb.length, 0)
                sb.setSpan(RelativeSizeSpan(1.08f), st, sb.length, 0)
            } else {
                appendTex(sb, tex)
            }
        }
    }

    // ---------------- the LaTeX-subset span writer ----------------

    /** expand one math segment into the builder: the unicode command map,
     *  sup/sub spans, \frac flattening, \sqrt, \text, spacing commands,
     *  \left/\right stripped, unknown commands kept verbatim (the honest
     *  fallback — the raw source survives long-press copy anyway). */
    fun appendTex(sb: SpannableStringBuilder, tex: String) {
        var i = 0
        val n = tex.length
        while (i < n) {
            val c = tex[i]
            when {
                c == '\\' && i + 1 < n -> {
                    val nxt = tex[i + 1]
                    if (nxt == '{' || nxt == '}' || nxt == '$' || nxt == '\\') {
                        sb.append(nxt); i += 2; continue
                    }
                    var j = i + 1
                    while (j < n && tex[j].isLetter()) j++
                    val cmd = tex.substring(i + 1, j)
                    when {
                        cmd.isEmpty() -> { sb.append('\\'); i = j }
                        cmd == "," || cmd == ";" || cmd == ":" || cmd == "!" -> i = j
                        cmd == "quad" || cmd == "qquad" -> { sb.append(' '); i = j }
                        cmd == "left" || cmd == "right" -> i = j
                        cmd == "displaystyle" || cmd == "textstyle" ||
                                cmd == "limits" -> i = j
                        cmd == "frac" || cmd == "dfrac" || cmd == "tfrac" -> {
                            val g1 = readGroup(tex, j)
                            val g2 = readGroup(tex, g1.second)
                            val flat: String
                            var next: Int
                            if (g1.first != null) {
                                flat = fracFlat(g1.first!!, g2.first ?: "")
                                next = g2.second
                            } else {
                                // \frac12 shorthand: two bare characters
                                var a = ""
                                if (j < n) a = tex[j].toString()
                                var b = ""
                                val k = g2.second + if (g2.second == j) 1 else 0
                                if (k < n) b = tex[k].toString()
                                flat = fracFlat(a, b)
                                next = if (k < n) k + 1 else n
                            }
                            appendTex(sb, flat)
                            i = next
                        }
                        cmd == "sqrt" -> {
                            sb.append('√')
                            val g = readGroup(tex, j)
                            if (g.first != null) { appendTex(sb, g.first!!); i = g.second }
                            else i = j
                        }
                        cmd == "text" || cmd == "mathrm" || cmd == "operatorname" -> {
                            val g = readGroup(tex, j)
                            if (g.first != null) { sb.append(g.first!!); i = g.second }
                            else i = j
                        }
                        cmd == "mathbb" || cmd == "mathcal" || cmd == "mathbf" ||
                                cmd == "bm" || cmd == "boldsymbol" -> {
                            val g = readGroup(tex, j)
                            if (g.first != null) {
                                val mapped = texMap("mathbb{" + g.first!!.trim() + "}")
                                if (mapped != null) sb.append(mapped)
                                else {
                                    val st = sb.length
                                    sb.append(g.first!!)
                                    sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, 0)
                                }
                                i = g.second
                            } else i = j
                        }
                        cmd == "bar" || cmd == "overline" || cmd == "hat" || cmd == "vec" -> {
                            val g = readGroup(tex, j)
                            if (g.first != null) {
                                appendTex(sb, g.first!!)
                                sb.append('̄')
                                i = g.second
                            } else i = j
                        }
                        else -> {
                            val m = texMap(cmd)
                            if (m != null) { sb.append(m); i = j }
                            else { sb.append(tex, i, j); i = j }  // honest: verbatim
                        }
                    }
                }
                c == '^' || c == '_' -> {
                    val g = readGroup(tex, i + 1)
                    if (g.first != null) {
                        val st = sb.length
                        appendTex(sb, g.first!!)
                        sb.setSpan(if (c == '^') SuperscriptSpan() else SubscriptSpan(),
                                st, sb.length, 0)
                        sb.append(' ')   // breathing room after the script
                        i = g.second
                    } else if (i + 1 < n) {
                        // single bare token: x^2, x^\alpha
                        var j = i + 1
                        if (tex[j] == '\\') {
                            j++
                            val cmdStart = j
                            while (j < n && tex[j].isLetter()) j++
                            val m = texMap(tex.substring(cmdStart, j))
                            val st = sb.length
                            if (m != null) sb.append(m) else sb.append(tex, i + 1, j)
                            sb.setSpan(if (c == '^') SuperscriptSpan() else SubscriptSpan(),
                                    st, sb.length, 0)
                        } else {
                            val st = sb.length
                            sb.append(tex[j])
                            sb.setSpan(if (c == '^') SuperscriptSpan() else SubscriptSpan(),
                                    st, sb.length, 0)
                            j++
                        }
                        sb.append(' ')
                        i = j
                    } else { sb.append(c); i++ }
                }
                else -> { sb.append(c); i++ }
            }
        }
    }

    /** read one {…} group (returns content + index AFTER the closing
     *  brace), or (null, from) when no brace follows. */
    private fun readGroup(tex: String, from: Int): Pair<String?, Int> {
        if (from >= tex.length || tex[from] != '{') return Pair(null, from)
        var depth = 0
        var i = from
        while (i < tex.length) {
            if (tex[i] == '{') depth++
            else if (tex[i] == '}') {
                depth--
                if (depth == 0) return Pair(tex.substring(from + 1, i), i + 1)
            }
            i++
        }
        return Pair(tex.substring(from + 1), tex.length)
    }

    // ---------------- tables (PURE — probe-pinned) ----------------

    /**
     * PURE (probe-pinned) — flatten parsed table rows into an aligned
     * monospace grid: every column padded to its widest cell, a dashed
     * rule under the header row, two spaces of gutters. Rows shorter
     * than the widest row are padded out (ragged model output never
     * misaligns the grid).
     */
    fun renderTable(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        var cols = 0
        for (r in rows) cols = maxOf(cols, r.size)
        if (cols == 0) return ""
        val w = IntArray(cols)
        for (r in rows) for (i in 0 until cols) {
            val cell = if (i < r.size) r[i] else ""
            if (cell.length > w[i]) w[i] = cell.length
        }
        val out = StringBuilder()
        for ((ri, r) in rows.withIndex()) {
            val sb2 = StringBuilder()
            for (i in 0 until cols) {
                val cell = if (i < r.size) r[i] else ""
                sb2.append(cell)
                if (i < cols - 1) {
                    repeat(w[i] - cell.length + 2) { sb2.append(' ') }
                }
            }
            out.append(sb2.toString().trimEnd()).append('\n')
            if (ri == 0) {
                for (i in 0 until cols) {
                    repeat(w[i]) { out.append('─') }
                    if (i < cols - 1) out.append("  ")
                }
                out.append('\n')
            }
        }
        return out.toString().trimEnd('\n')
    }

    // ---------------- v1.4.21 — the lightweight syntax colorizer (PURE) ----------------

    /** token kinds: 0 plain, 1 comment, 2 string, 3 keyword, 4 number */
    const val TOK_PLAIN = 0
    const val TOK_COMMENT = 1
    const val TOK_STRING = 2
    const val TOK_KEYWORD = 3
    const val TOK_NUMBER = 4

    /** one highlight range (end exclusive). */
    class Tok(val start: Int, val end: Int, val kind: Int)

    /** per-language tables: line-comment prefixes + keywords. */
    private val LANG_LINE_COMMENT = mapOf(
            "kotlin" to "//", "java" to "//", "js" to "//", "javascript" to "//",
            "ts" to "//", "typescript" to "//", "swift" to "//", "go" to "//",
            "rust" to "//", "c" to "//", "cpp" to "//", "c++" to "//",
            "python" to "#", "py" to "#", "bash" to "#", "sh" to "#", "shell" to "#",
            "ruby" to "#", "yaml" to "#", "yml" to "#", "r" to "#",
            "sql" to "--", "json" to null, "html" to null, "css" to null, "" to null)

    private val LANG_KEYWORDS = mapOf(
            "kotlin" to "fun val var if else when for while return class object interface data sealed enum import package null true false is in as this super companion override private public internal protected suspend try catch finally throw when do break continue by where typealias init constructor lateinit abstract open operator inline crossinline noinline reified const external infix tailrec vararg out expect actual it lateinit get set",
            "java" to "public private protected static final void int long double float boolean char byte short class interface enum extends implements new return if else for while do switch case break continue try catch finally throw throws import package this super null true false abstract synchronized volatile transient native strictfp default instanceof record var sealed non-sealed permits yield",
            "python" to "def class if elif else for while return import from as pass break continue try except finally raise with lambda yield global nonlocal assert del in is not and or None True False async await match case self print range len int str float list dict set tuple",
            "js" to "function const let var if else for while do switch case break continue return try catch finally throw new class extends super this typeof instanceof in of delete void yield async await import export default null undefined true false console window document",
            "javascript" to "function const let var if else for while do switch case break continue return try catch finally throw new class extends super this typeof instanceof in of delete void yield async await import export default null undefined true false console window document",
            "ts" to "function const let var if else for while do switch case break continue return try catch finally throw new class extends implements super this typeof instanceof in of delete void yield async await import export default null undefined true false interface type enum namespace declare public private protected readonly abstract as is keyof infer never unknown any string number boolean",
            "typescript" to "function const let var if else for while do switch case break continue return try catch finally throw new class extends implements super this typeof instanceof in of delete void yield async await import export default null undefined true false interface type enum namespace declare public private protected readonly abstract as is keyof infer never unknown any string number boolean",
            "bash" to "if then else elif fi for while do done case esac function return in export local echo cd ls cat grep sed awk curl sudo apt yum rm mv cp mkdir chmod chown set trap",
            "sh" to "if then else elif fi for while do done case esac function return in export local echo cd ls cat grep sed awk curl sudo apt yum rm mv cp mkdir chmod chown set trap",
            "shell" to "if then else elif fi for while do done case esac function return in export local echo cd ls cat grep sed awk curl sudo apt yum rm mv cp mkdir chmod chown set trap",
            "sql" to "select from where insert into values update set delete create table drop alter add primary key foreign references index view join inner left right outer full on group by order having limit offset distinct as and or not null like between in exists union all case when then else end begin commit rollback",
            "json" to "true false null",
            "html" to "html head body div span a p ul ol li table tr td th img script style link meta title form input button h1 h2 h3 h4 h5 h6 section header footer nav article class id href src",
            "css" to "important media keyframes import from to and not only screen print display position top right bottom left width height margin padding border background color font text align justify center flex grid none block inline absolute relative fixed sticky",
            "c" to "int char long short float double void unsigned signed struct union enum typedef static extern const volatile register return if else for while do switch case break continue goto sizeof null define include",
            "cpp" to "int char long short float double void unsigned signed struct union enum typedef static extern const volatile register return if else for while do switch case break continue goto sizeof null define include class public private protected virtual override template typename namespace using new delete this nullptr try catch throw auto constexpr",
            "c++" to "int char long short float double void unsigned signed struct union enum typedef static extern const volatile register return if else for while do switch case break continue goto sizeof null define include class public private protected virtual override template typename namespace using new delete this nullptr try catch throw auto constexpr")

    /** PURE (probe-pinned) — the bounded one-pass highlighter. Line
     *  comments first (whole tail), then strings (quote-matched, escapes
     *  honored), then word-boundary keywords, then numbers. Bounded: code
     *  over 20000 chars or 4000 tokens returns empty (no highlight beats
     *  a jank). Unknown languages still get strings + numbers. */
    fun tokenizeCode(code: String, lang: String): List<Tok> {
        if (code.isEmpty() || code.length > 20000) return emptyList()
        val lc = LANG_LINE_COMMENT[lang]
        val kws = LANG_KEYWORDS[lang]?.split(' ')?.toHashSet()
        val out = ArrayList<Tok>()
        var i = 0
        val n = code.length
        var tokCount = 0
        while (i < n) {
            if (tokCount >= 4000) return emptyList()
            val c = code[i]
            when {
                // ---- line comment ----
                lc != null && c == lc[0] && code.startsWith(lc, i) -> {
                    val e = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out.add(Tok(i, e, TOK_COMMENT)); i = e
                }
                // ---- strings (double, single, backtick) ----
                c == '"' || c == '\'' || c == '`' -> {
                    var j = i + 1
                    while (j < n) {
                        if (code[j] == '\\') j += 2
                        else if (code[j] == c || code[j] == '\n') break
                        else j++
                    }
                    val e = if (j < n && code[j] == c) j + 1 else j
                    out.add(Tok(i, e, TOK_STRING)); i = e; tokCount++
                }
                // ---- numbers ----
                c.isDigit() && (i == 0 || !(code[i - 1].isLetterOrDigit() || code[i - 1] == '_')) -> {
                    var j = i
                    while (j < n && (code[j].isDigit() || code[j] == '.' ||
                                    code[j] == 'x' || code[j] == 'X' ||
                                    (code[j] in 'a'..'f') || (code[j] in 'A'..'F') ||
                                    code[j] == '_' || code[j] == 'L' || code[j] == 'l')) j++
                    out.add(Tok(i, j, TOK_NUMBER)); i = j; tokCount++
                }
                // ---- identifiers / keywords ----
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    if (kws != null && kws.contains(code.substring(i, j))) {
                        out.add(Tok(i, j, TOK_KEYWORD)); tokCount++
                    }
                    i = j
                }
                else -> i++
            }
        }
        return out
    }

    // ---------------- v1.4.25 — the HTML colour parser (PURE) ----------------

    /** v1.4.25 — PURE (probe-pinned): a CSS colour literal -> ARGB int, or
     *  null when it cannot be parsed SAFELY (an unparseable colour colours
     *  nothing — never a throw, never a guess). Hex (#rgb / #rrggbb /
     * #rrggbbaa), rgb()/rgba() (0-255 or 0%-100% channels), and the common
     * named colours (the HTML legacy set + the frequent extended ones —
     * lowercase-insensitive). Alpha < 0.4 reads as translucent: the text
     * colour would wash out on either theme, so it returns null (no colour
     * beats an invisible one). */
    fun parseHtmlColor(v: String): Int? {
        if (v.isEmpty() || v.length > 40) return null
        val s = v.trim().lowercase()
        try {
            if (s[0] == '#') {
                val hex = s.substring(1)
                return when (hex.length) {
                    3 -> {
                        val r = hex2(hex[0], hex[0]); val g = hex2(hex[1], hex[1])
                        val b = hex2(hex[2], hex[2])
                        if (r == null || g == null || b == null) null
                        else 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                    }
                    6 -> {
                        val r = hex2(hex[0], hex[1]); val g = hex2(hex[2], hex[3])
                        val b = hex2(hex[4], hex[5])
                        if (r == null || g == null || b == null) null
                        else 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                    }
                    8 -> {
                        val a = hex2(hex[0], hex[1]); val r = hex2(hex[2], hex[3])
                        val g = hex2(hex[4], hex[5]); val b = hex2(hex[6], hex[7])
                        if (r == null || g == null || b == null || a == null) null
                        else if (a < 102) null          // alpha < 0.4: wash-out guard
                        else (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    else -> null
                }
            }
            val m = Regex("rgba?\\(\\s*([^,)]+)\\s*,\\s*([^,)]+)\\s*,\\s*([^,)]+)\\s*(?:,\\s*([\\d.]+)\\s*)?\\)?").find(s)
            if (m != null) {
                val r = chan(m.groupValues[1]) ?: return null
                val g = chan(m.groupValues[2]) ?: return null
                val b = chan(m.groupValues[3]) ?: return null
                var a = 255
                val av = m.groupValues.getOrNull(4)
                if (!av.isNullOrEmpty()) {
                    val fa = av.toFloatOrNull() ?: return null
                    if (fa < 0f || fa > 1f) return null
                    a = (fa * 255f).toInt().coerceIn(0, 255)
                    if (a < 102) return null           // alpha < 0.4: wash-out guard
                }
                return (a shl 24) or ((r and 0xFF) shl 16) or
                        ((g and 0xFF) shl 8) or (b and 0xFF)
            }
            return NAMED_COLORS[s]
        } catch (_: Throwable) {
            return null
        }
    }

    /** one hex digit pair -> 0-255, or null (bad digit). */
    private fun hex2(c1: Char, c2: Char): Int? {
        val h1 = Character.digit(c1, 16); val h2 = Character.digit(c2, 16)
        return if (h1 < 0 || h2 < 0) null else (h1 shl 4) or h2
    }

    /** one rgb() channel: 0-255 or 0%-100% -> 0-255, or null. */
    private fun chan(raw: String): Int? {
        val s = raw.trim()
        if (s.endsWith("%")) {
            val f = s.dropLast(1).toFloatOrNull() ?: return null
            if (f < 0f || f > 100f) return null
            return (f * 2.55f).toInt().coerceIn(0, 255)
        }
        val n = s.toIntOrNull() ?: return null
        return if (n in 0..255) n else null
    }

    /** the common named colours (lowercase keys — the CSS legacy set plus
     *  the frequent extended ones models actually emit). */
    private val NAMED_COLORS = mapOf(
            "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(),
            "red" to 0xFFFF0000.toInt(), "green" to 0xFF008000.toInt(),
            "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(),
            "cyan" to 0xFF00FFFF.toInt(), "magenta" to 0xFFFF00FF.toInt(),
            "gray" to 0xFF808080.toInt(), "grey" to 0xFF808080.toInt(),
            "silver" to 0xFFC0C0C0.toInt(), "maroon" to 0xFF800000.toInt(),
            "navy" to 0xFF000080.toInt(), "teal" to 0xFF008080.toInt(),
            "olive" to 0xFF808000.toInt(), "purple" to 0xFF800080.toInt(),
            "fuchsia" to 0xFFFF00FF.toInt(), "aqua" to 0xFF00FFFF.toInt(),
            "lime" to 0xFF00FF00.toInt(), "orange" to 0xFFFFA500.toInt(),
            "pink" to 0xFFFFC0CB.toInt(), "brown" to 0xFFA52A2A.toInt(),
            "gold" to 0xFFFFD700.toInt(), "indigo" to 0xFF4B0082.toInt(),
            "crimson" to 0xFFDC143C.toInt(), "darkred" to 0xFF8B0000.toInt(),
            "darkblue" to 0xFF00008B.toInt(), "darkgreen" to 0xFF006400.toInt(),
            "darkgray" to 0xFFA9A9A9.toInt(), "darkgrey" to 0xFFA9A9A9.toInt(),
            "lightgray" to 0xFFD3D3D3.toInt(), "lightgrey" to 0xFFD3D3D3.toInt(),
            "lightblue" to 0xFFADD8E6.toInt(), "lightgreen" to 0xFF90EE90.toInt(),
            "tomato" to 0xFFFF6347.toInt(), "coral" to 0xFFFF7F50.toInt(),
            "steelblue" to 0xFF4682B4.toInt(), "royalblue" to 0xFF4169E1.toInt(),
            "whitesmoke" to 0xFFF5F5F5.toInt(), "rebeccapurple" to 0xFF663399.toInt(),
            "goldenrod" to 0xFFDAA520.toInt(), "firebrick" to 0xFFB22222.toInt())

    // ---------------- the assistant-bubble gate ----------------

    /** does this text carry markdown signals worth rendering? ASSISTANT
     *  bubbles render through [render] when true; a reply without any
     *  signal skips the parser (fast path). User bubbles never render —
     *  their markdown is theirs to quote literally.
     *  v1.4.21: ~~ (strikethrough) joins the signal set. */
    fun looksMarkdown(s: String): Boolean {
        if (s.isEmpty()) return false
        return s.contains("##") || s.contains("**") || s.contains("`") ||
                s.contains("$$") || s.contains("$") || s.contains("\\(") ||
                s.contains("\\[") || s.contains("~~") || s.contains("++") ||
                // v1.4.26 — the sub/sup delimiter signals (the fast path
                // must route ~x~/^x^ through the parser too)
                s.contains("~") || s.contains("^") ||
                Regex("(^|\\n)#{1,6} ").containsMatchIn(s) ||
                Regex("(^|\\n)\\s*[-*] ").containsMatchIn(s) ||
                Regex("(^|\\n)\\s*\\d+\\. ").containsMatchIn(s) ||
                Regex("\\|.*\\|").containsMatchIn(s) ||
                // v1.4.23 — the four-pack triggers: task items, footnote
                // refs, HTML tags, block quotes
                Regex("(^|\\n)\\s*[-*] \\[\\s*[xX]\\s*\\] ").containsMatchIn(s) ||
                Regex("\\[\\^[^\\]]+\\]").containsMatchIn(s) ||
                Regex("</?[a-zA-Z][^>]*>").containsMatchIn(s) ||
                Regex("(^|\\n)\\s*> ").containsMatchIn(s)
    }
}

// ---------------- v1.4.26 — the markdown ~sub~ / ^sup^ delimiter nodes ----------------

/** v1.4.26 — the markdown SUBSCRIPT node: `~text~` through
 *  [SubSupDelimiterProcessor] (the model-markdown form; the `<sub>` HTML
 *  tag already worked and renders the same span kit). File-private — the
 *  visitor's `is SubMd` arm lives inside [AiRender]. */
private class SubMd : org.commonmark.node.CustomNode()

/** v1.4.26 — the markdown SUPERSCRIPT node: `^text^`. */
private class SupMd : org.commonmark.node.CustomNode()

/**
 * v1.4.26 — THE SUB/SUP DELIMITER PROCESSOR (the model-markdown
 * sub/superscript ask): `~text~` wraps a [SubMd], `^text^` a [SupMd].
 * The GFM strikethrough extension is re-configured with
 * requireTwoTildes(true) — its own documented coexistence knob — so a
 * two-tilde run still matches strikethrough while a SINGLE tilde run
 * falls to this processor (commonmark's staggered resolver dispatches by
 * run length, largest minimum first). `^` has no other claimant.
 * Follows the Ins extension's process shape exactly: wrap the nodes
 * between the delimiter Text nodes, insert after the opener, return the
 * one delimiter used. Thread-safe (stateless beyond the immutable flag).
 */
private class SubSupDelimiterProcessor(private val up: Boolean) :
        org.commonmark.parser.delimiter.DelimiterProcessor {

    override fun getOpeningCharacter(): Char = if (up) '^' else '~'
    override fun getClosingCharacter(): Char = if (up) '^' else '~'
    override fun getMinLength(): Int = 1

    override fun process(openingRun: org.commonmark.parser.delimiter.DelimiterRun,
                         closingRun: org.commonmark.parser.delimiter.DelimiterRun): Int {
        if (openingRun.length() < 1 || closingRun.length() < 1) return 0
        try {
            val opener = openingRun.opener
            val node = if (up) SupMd() else SubMd()
            for (n in org.commonmark.node.Nodes.between(opener, closingRun.closer)) {
                node.appendChild(n)
            }
            opener.insertAfter(node)
            return 1
        } catch (t: Throwable) {
            return 0
        }
    }
}
