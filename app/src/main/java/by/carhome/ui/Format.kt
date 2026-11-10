package by.carhome.ui

import java.util.Locale

/** Thousands separated by a space, minus sign kept: 25012 → "25 012", -1200 → "−1 200". */
internal fun groupThousands(n: Int): String {
    val s = kotlin.math.abs(n.toLong()).toString()
    val sb = StringBuilder()
    if (n < 0) sb.append('−')
    for (i in s.indices) {
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(' ')
        sb.append(s[i])
    }
    return sb.toString()
}

/** One decimal, "—" without data. */
internal fun oneDecimal(v: Float?): String = if (v == null) "—" else String.format(Locale.US, "%.1f", v)
