package com.emulnk.core

import android.util.Log
import com.emulnk.BuildConfig

/**
 * Parses and evaluates simple math expressions.
 */
object MathEngine {

    fun evaluate(formula: String, value: Double): Double {
        if (formula.length > MathConstants.MAX_EXPRESSION_LENGTH) {
            if (BuildConfig.DEBUG) Log.w("MathEngine", "Formula exceeds max length (${formula.length} > ${MathConstants.MAX_EXPRESSION_LENGTH}): ${formula.take(50)}...")
            return value
        }
        // Use BigDecimal to avoid scientific notation (e.g., 1.0E7) which the parser can't handle
        val valueStr = java.math.BigDecimal(value).stripTrailingZeros().toPlainString()
        val expression = formula.replace(Regex("\\bv\\b"), valueStr)
        return try {
            val result = eval(expression)
            if (!result.isFinite()) {
                if (BuildConfig.DEBUG) Log.w("MathEngine", "Formula produced non-finite result ($result): $formula")
                return value
            }
            result
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("MathEngine", "Formula eval failed: $formula", e)
            value
        }
    }

    private fun eval(str: String): Double {
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
                    if (depth > MathConstants.MAX_NESTING_DEPTH) {
                        throw RuntimeException("Max nesting depth exceeded (${MathConstants.MAX_NESTING_DEPTH})")
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
