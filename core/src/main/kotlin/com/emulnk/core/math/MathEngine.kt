package com.emulnk.core.math

/**
 * Parses and evaluates simple math expressions using a recursive-descent parser.
 * Pure Kotlin — no Android dependencies.
 *
 * Supports: +, -, *, /, %, ^ (power), parentheses.
 * The placeholder `v` in formulas is replaced with the actual value before parsing.
 * This is NOT arbitrary code evaluation — it's a safe, bounded arithmetic parser.
 */
object MathEngine {

    const val MAX_EXPRESSION_LENGTH = 256
    const val MAX_NESTING_DEPTH = 20

    private val VALUE_PLACEHOLDER = Regex("\\bv\\b")

    fun evaluate(formula: String, value: Double): Double {
        if (formula.length > MAX_EXPRESSION_LENGTH) return value
        val valueStr = java.math.BigDecimal(value).stripTrailingZeros().toPlainString()
        val expression = formula.replace(VALUE_PLACEHOLDER, valueStr)
        return try {
            val result = parseAndCompute(expression)
            if (!result.isFinite()) value else result
        } catch (_: Exception) {
            value
        }
    }

    /** Standard recursive-descent (precedence-climbing) parser: expression -> term -> factor. */
    private fun parseAndCompute(str: String): Double {
        return object : Any() {
            var pos = -1
            var ch = 0
            var depth = 0

            fun nextChar() {
                ch = if (++pos < str.length) str[pos].code else -1
            }

            fun eat(charToEat: Int): Boolean {
                while (ch == ' '.code) nextChar()
                if (ch == charToEat) {
                    nextChar()
                    return true
                }
                return false
            }

            fun parse(): Double {
                nextChar()
                val x = parseExpression()
                if (pos < str.length) throw RuntimeException("Unexpected: " + ch.toChar())
                return x
            }

            fun parseExpression(): Double {
                var x = parseTerm()
                while (true) {
                    if (eat('+'.code)) x += parseTerm()
                    else if (eat('-'.code)) x -= parseTerm()
                    else return x
                }
            }

            fun parseTerm(): Double {
                var x = parseFactor()
                while (true) {
                    if (eat('*'.code)) x *= parseFactor()
                    else if (eat('/'.code)) x /= parseFactor()
                    else if (eat('%'.code)) x %= parseFactor()
                    else return x
                }
            }

            fun parseFactor(): Double {
                if (eat('+'.code)) return parseFactor()
                if (eat('-'.code)) return -parseFactor()

                var x: Double
                val startPos = pos
                if (eat('('.code)) {
                    depth++
                    if (depth > MAX_NESTING_DEPTH) {
                        throw RuntimeException("Max nesting depth exceeded ($MAX_NESTING_DEPTH)")
                    }
                    x = parseExpression()
                    eat(')'.code)
                    depth--
                } else if (ch >= '0'.code && ch <= '9'.code || ch == '.'.code) {
                    while (ch >= '0'.code && ch <= '9'.code || ch == '.'.code) nextChar()
                    x = str.substring(startPos, pos).toDouble()
                } else {
                    throw RuntimeException("Unexpected: " + ch.toChar())
                }

                if (eat('^'.code)) x = Math.pow(x, parseFactor())

                return x
            }
        }.parse()
    }
}
