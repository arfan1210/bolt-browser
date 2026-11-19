package com.bolt.browser.util

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
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
     *  NEVER throws — worst case the raw text rides out unchanged. */
    fun render(c: Context, md: String): Spanned {
        val sb = SpannableStringBuilder()
        try {
            val prepared = codeProtect(md)
            val segs = pendingSegs
            val parser = Parser.builder()
                    .extensions(listOf(TablesExtension.create())).build()
            val doc = parser.parse(prepared)
            val v = V(c, sb, segs)
            v.children(doc)
        } catch (_: Throwable) {
            // the renderer must never eat a reply
            sb.clear()
            sb.append(md)
        }
        return sb
    }

    /** the visitor — one branch per node kind, spans applied as the
     *  builder fills. Bold/italic/links/code ride commonmark's inline
     *  nodes; lists get BulletSpan / leading numbers; tables flatten to
     *  aligned monospace grids ([renderTable], pure); math placeholders
     *  expand into the LaTeX-subset spans. */
    private class V(val c: Context, val sb: SpannableStringBuilder,
                    val segs: List<MathSeg>) {

        var listLevel = 0

        fun children(parent: Node) {
            var n = parent.firstChild
            while (n != null) {
                visit(n)
                n = n.next
            }
        }

        fun visit(n: Node) {
            when (n) {
                is Paragraph -> { children(n); blockGap() }
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
                            val st = sb.length
                            sb.append(idx.toString()).append(". ")
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
                    children(n)
                    sb.setSpan(BulletSpan(Fx.dp(c, 10)), start, sb.length, 0)
                }
                is BlockQuote -> {
                    val start = sb.length
                    children(n)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, 0)
                    sb.setSpan(RelativeSizeSpan(0.97f), start, sb.length, 0)
                    blockGap()
                }
                is FencedCodeBlock -> codeBlock(n.literal)
                is IndentedCodeBlock -> codeBlock(n.literal)
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
                is SoftLineBreak -> sb.append('\n')
                is HardLineBreak -> sb.append('\n')
                is Text -> inlineText(n.literal)
                is Document -> children(n)
                else -> children(n)
            }
        }

        fun blockGap() {
            if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
            sb.append('\n')
        }

        fun codeBlock(lit: String) {
            val start = sb.length
            sb.append(lit.trimEnd())
            val end = sb.length
            sb.setSpan(TypefaceSpan("monospace"), start, end, 0)
            sb.setSpan(RelativeSizeSpan(0.92f), start, end, 0)
            sb.setSpan(BackgroundColorSpan(codeBg()), start, end, 0)
            blockGap()
        }

        fun codeBg(): Int {
            val dark = try { Prefs.get(c).isDark() } catch (_: Throwable) { false }
            return if (dark) 0xFF24303C.toInt() else 0xFFE8EDF2.toInt()
        }

        fun table(t: TableBlock) {
            // flatten to an aligned monospace grid (renderTable, pure)
            val rows = ArrayList<List<String>>()
            var row = t.firstChild
            while (row != null) {
                if (row is TableRow) {
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
                row = row.next
            }
            val start = sb.length
            sb.append(renderTable(rows))
            val end = sb.length
            if (end > start) {
                sb.setSpan(TypefaceSpan("monospace"), start, end, 0)
                sb.setSpan(RelativeSizeSpan(0.9f), start, end, 0)
            }
            blockGap()
        }

        /** plain text: expand any math placeholders it carries */
        fun inlineText(s: String) {
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch >= PH_BASE && ch < PH_BLOCK) {
                    val idx = ch - PH_BASE
                    if (idx < segs.size) appendTex(sb, segs[idx].tex)
                    i++
                } else if (ch >= PH_BLOCK && ch < '\uF800') {
                    val idx = ch - PH_BLOCK
                    if (idx < segs.size) {
                        sb.append('\n')
                        val st = sb.length
                        appendTex(sb, segs[idx].tex)
                        sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                                st, sb.length, 0)
                        sb.setSpan(RelativeSizeSpan(1.08f), st, sb.length, 0)
                        sb.append('\n')
                    }
                    i++
                } else {
                    sb.append(ch); i++
                }
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

    // ---------------- the assistant-bubble gate ----------------

    /** does this text carry markdown signals worth rendering? ASSISTANT
     *  bubbles render through [render] when true; a reply without any
     *  signal skips the parser (fast path). User bubbles never render —
     *  their markdown is theirs to quote literally. */
    fun looksMarkdown(s: String): Boolean {
        if (s.isEmpty()) return false
        return s.contains("##") || s.contains("**") || s.contains("`") ||
                s.contains("$$") || s.contains("$") || s.contains("\\(") ||
                s.contains("\\[") || Regex("(^|\\n)#{1,6} ").containsMatchIn(s) ||
                Regex("(^|\\n)\\s*[-*] ").containsMatchIn(s) ||
                Regex("(^|\\n)\\s*\\d+\\. ").containsMatchIn(s)
    }
}
