package com.bolt.browser.util

/**
 * v1.3.39 — OMNIBOX INSTANT ANSWERS (new code → Kotlin; menu-2 item 2,
 * carried into round 3, user-picked).
 *
 * "12*34", "100kg in lbs", "2h30m in minutes"… answered LOCALLY — a
 * hand-rolled expression evaluator (recursive descent, no script engine)
 * plus a unit table. No network, no permission, works offline.
 *
 * The omnibox asks [answer] before falling back to search; null = "not an
 * answer" and the normal search row takes over.
 */
object InstantAnswers {

    // ---------------- units → canonical base factor ----------------
    private val LENGTH = mapOf(
            "km" to 1000.0, "m" to 1.0, "cm" to 0.01, "mm" to 0.001,
            "mi" to 1609.344, "mile" to 1609.344, "miles" to 1609.344,
            "yd" to 0.9144, "ft" to 0.3048, "feet" to 0.3048, "foot" to 0.3048,
            "in" to 0.0254, "inch" to 0.0254, "inches" to 0.0254,
            "nm" to 1852.0, "nmi" to 1852.0)
    private val MASS = mapOf(
            "kg" to 1.0, "g" to 0.001, "mg" to 1e-6, "t" to 1000.0, "tonne" to 1000.0,
            "lb" to 0.45359237, "lbs" to 0.45359237, "pound" to 0.45359237, "pounds" to 0.45359237,
            "oz" to 0.028349523125, "ounce" to 0.028349523125, "ounces" to 0.028349523125,
            "st" to 6.35029318, "stone" to 6.35029318)
    private val DATA = mapOf(
            "b" to 1.0, "byte" to 1.0, "bytes" to 1.0, "kb" to 1e3, "mb" to 1e6, "gb" to 1e9,
            "tb" to 1e12, "kib" to 1024.0, "mib" to 1024.0 * 1024, "gib" to 1024.0 * 1024 * 1024,
            "k" to 1e3, "m" to 1e6, "g" to 1e9)
    private val TIME = mapOf(
            "s" to 1.0, "sec" to 1.0, "second" to 1.0, "seconds" to 1.0,
            "min" to 60.0, "minute" to 60.0, "minutes" to 60.0,
            "h" to 3600.0, "hr" to 3600.0, "hour" to 3600.0, "hours" to 3600.0,
            "d" to 86400.0, "day" to 86400.0, "days" to 86400.0,
            "wk" to 604800.0, "week" to 604800.0, "weeks" to 604800.0,
            "ms" to 0.001)
    private val SPEED = mapOf(
            "kmh" to 1000.0 / 3600, "kph" to 1000.0 / 3600,
            "mph" to 1609.344 / 3600, "ms" to 1.0, "m/s" to 1.0, "knots" to 1852.0 / 3600)
    private val VOLUME = mapOf(
            "l" to 1.0, "liter" to 1.0, "liters" to 1.0, "litre" to 1.0, "litres" to 1.0,
            "ml" to 0.001, "gal" to 3.785411784, "gallon" to 3.785411784, "gallons" to 3.785411784,
            "qt" to 0.946352946, "pt" to 0.473176473, "cup" to 0.2365882365,
            "floz" to 0.0295735295625)
    private val AREA = mapOf(
            "m2" to 1.0, "sqm" to 1.0, "km2" to 1e6, "sqkm" to 1e6,
            "ha" to 10000.0, "acre" to 4046.8564224, "acres" to 4046.8564224,
            "ft2" to 0.09290304, "sqft" to 0.09290304, "mi2" to 2589988.110336)

    private val FAMILIES = listOf(
            "length" to LENGTH, "mass" to MASS, "data" to DATA, "time" to TIME,
            "speed" to SPEED, "volume" to VOLUME, "area" to AREA)

    private val TEMPS = setOf("c", "°c", "f", "°f", "celsius", "fahrenheit",
            "k", "kelvin", "centigrade")

    /** try to answer [q]; null when it is not an answerable query. */
    @JvmStatic
    fun answer(qRaw: String?): String? {
        val q = (qRaw ?: "").trim()
        if (q.isEmpty() || q.length > 60) return null
        val lower = q.lowercase()

        // percentage: "15% of 200"
        val pct = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*%\\s*of\\s*([0-9]+(?:\\.[0-9]+)?)$")
            .find(lower)
        if (pct != null) {
            val v = pct.groupValues[1].toDouble() * pct.groupValues[2].toDouble() / 100.0
            return fmt(v)
        }
        // sqrt / abs / round of a number
        val fn = Regex("^(sqrt|abs|round|floor|ceil)\\s*\\(?\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*\\)?$").find(lower)
        if (fn != null) {
            val v = fn.groupValues[2].toDouble()
            return when (fn.groupValues[1]) {
                "sqrt" -> if (v >= 0) fmt(Math.sqrt(v)) else null
                "abs" -> fmt(Math.abs(v))
                "round" -> fmt(Math.round(v).toDouble())
                "floor" -> fmt(Math.floor(v))
                else -> fmt(Math.ceil(v))
            }
        }

        // unit conversion: "100kg in lbs", "2h30m in minutes"
        val m = Regex("^([0-9a-z°.,\\s]+?)\\s+(?:in|to|as)\\s+([a-z°/]+)$").find(lower)
        if (m != null) {
            val target = m.groupValues[2]
            val conv = convert(m.groupValues[1].trim(), target)
            if (conv != null) return conv
        }
        // temperature conversion: "30c in f"
        val t = Regex("^(-?[0-9]+(?:\\.[0-9]+)?)\\s*(°?[cfk])\\s+(?:in|to|as)\\s+°?([cfk])$").find(lower)
        if (t != null) {
            val conv = temp(t.groupValues[1].toDouble(), t.groupValues[2], t.groupValues[3])
            if (conv != null) return conv
        }

        // plain math (only when it contains a digit and an operator)
        if (q.any { it.isDigit() } && q.any { "+-*/%^(".indexOf(it) >= 0 }) {
            val v = tryEval(q) ?: return null
            if (!v.isNaN() && !v.isInfinite()) return fmt(v)
        }
        return null
    }

    private fun temp(v: Double, fromRaw: String, toRaw: String): String? {
        val from = fromRaw.replace("°", "").takeIf { it.isNotEmpty() } ?: return null
        val to = toRaw.replace("°", "").takeIf { it.isNotEmpty() } ?: return null
        if (from !in TEMPS || to !in TEMPS) return null
        val c = when (from) {
            "c", "celsius", "centigrade" -> v
            "f", "fahrenheit" -> (v - 32) / 1.8
            else -> v - 273.15
        }
        val out = when (to) {
            "c", "celsius", "centigrade" -> c
            "f", "fahrenheit" -> c * 1.8 + 32
            else -> c + 273.15
        }
        val unit = when (to) { "f", "fahrenheit" -> "°F"; "k", "kelvin" -> "K"; else -> "°C" }
        return fmt(out) + " $unit"
    }

    /** "2h30m" style compound values (time only) or "100 kg" pairs. */
    private fun convert(srcRaw: String, target: String): String? {
        // compound time: 2h30m / 1h 5m 20s
        val comp = Regex("^([0-9]+(?:\\.[0-9]+)?)(h|hr|hour|hours|m|min|minutes|s|sec|seconds)(?:\\s*([0-9]+(?:\\.[0-9]+)?)(m|min|minutes|s|sec|seconds))?(?:\\s*([0-9]+(?:\\.[0-9]+)?)(s|sec|seconds))?$").find(srcRaw.replace(" ", ""))
        if (comp != null && target in TIME) {
            var secs = 0.0
            val pairs = listOf(
                    comp.groupValues[1] to comp.groupValues[2],
                    comp.groupValues[3] to comp.groupValues[4],
                    comp.groupValues[5] to comp.groupValues[6])
            for ((num, unit) in pairs) {
                if (num.isEmpty() || unit.isEmpty()) continue
                val f = TIME[unit] ?: return null
                secs += num.toDouble() * f
            }
            val f = TIME[target] ?: return null
            return fmt(secs / f) + " " + target
        }
        // simple "100 kg"
        val simple = Regex("^(-?[0-9]+(?:\\.[0-9]+)?)\\s*([a-z°/]+)$").find(srcRaw.replace(" ", ""))
        if (simple != null) {
            val v = simple.groupValues[1].toDouble()
            val from = simple.groupValues[2]
            if (from in TEMPS || target in TEMPS) return null   // temps need their own path
            for ((_, fam) in FAMILIES) {
                if (from in fam && target in fam) {
                    val base = v * (fam[from] ?: return null)
                    return fmt(base / (fam[target] ?: return null)) + " " + target
                }
            }
        }
        return null
    }

    // ---------------- tiny recursive-descent evaluator ----------------
    // grammar: expr := term (('+'|'-') term)* ; term := pow (('*'|'/'|'%') pow)* ;
    //            pow := unary ('^' pow)? ; unary := '-'? primary ; primary := number | '(' expr ')'
    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun expr(): Double {
            var v = term(); ws()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i]; i++
                val r = term()
                v = if (op == '+') v + r else v - r
                ws()
            }
            return v
        }
        fun term(): Double {
            var v = pow(); ws()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                val op = s[i]; i++
                val r = pow()
                v = when (op) {
                    '*' -> v * r; '/' -> v / r; else -> v % r
                }
                ws()
            }
            return v
        }
        fun pow(): Double {
            val base = unary(); ws()
            if (i < s.length && s[i] == '^') { i++; return Math.pow(base, pow()) }
            return base
        }
        fun unary(): Double {
            ws()
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            return primary()
        }
        fun primary(): Double {
            ws()
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                ws()
                if (i < s.length && s[i] == ')') i++
                return v
            }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalArgumentException("not math")
            return s.substring(st, i).toDouble()
        }
    }

    private fun tryEval(q: String): Double? {
        // strip thousand separators in numbers like 1,000 (not the operator use)
        val cleaned = q.replace(",", "")
        return try {
            val p = P(cleaned)
            val v = p.expr()
            p.ws()
            if (p.i != cleaned.length) null else v     // trailing junk = not math
        } catch (t: Throwable) { null }
    }

    /** trim trailing zeros; cap size. */
    private fun fmt(v: Double): String {
        if (v == Math.floor(v) && Math.abs(v) < 1e15) {
            val l = Math.round(v)
            return String.format(java.util.Locale.US, "%,d", l)
        }
        var s = String.format(java.util.Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
        if (s.length > 14) s = String.format(java.util.Locale.US, "%.4g", v)
        return s
    }
}
