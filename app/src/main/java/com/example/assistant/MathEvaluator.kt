package com.example.assistant

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pure-Kotlin recursive-descent mathematical expression evaluator.
 */
object MathEvaluator {

    fun evaluate(expression: String): Result<Double> {
        return runCatching {
            val sanitized = expression.trim()
                .replace("×", "*")
                .replace("÷", "/")
                .replace("−", "-")
            require(sanitized.isNotEmpty()) { "Expression cannot be empty." }
            val parser = Parser(sanitized)
            val value = parser.parseExpression()
            parser.skipWhitespace()
            if (!parser.isAtEnd()) {
                throw IllegalArgumentException("Unexpected character '${parser.peek()}' at position ${parser.pos}")
            }
            require(!value.isNaN() && !value.isInfinite()) { "Result is undefined or infinite." }
            value
        }
    }

    fun formatResult(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
        }
    }

    private class Parser(private val input: String) {
        var pos: Int = 0

        fun isAtEnd(): Boolean = pos >= input.length
        fun peek(): Char = input[pos]

        fun skipWhitespace() {
            while (!isAtEnd() && input[pos].isWhitespace()) pos++
        }

        private fun match(expected: Char): Boolean {
            skipWhitespace()
            if (!isAtEnd() && input[pos] == expected) {
                pos++
                return true
            }
            return false
        }

        fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                x = when {
                    match('+') -> x + parseTerm()
                    match('-') -> x - parseTerm()
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parsePower()
            while (true) {
                x = when {
                    match('*') -> x * parsePower()
                    match('/') -> {
                        val divisor = parsePower()
                        require(divisor != 0.0) { "Division by zero." }
                        x / divisor
                    }
                    match('%') -> {
                        val divisor = parsePower()
                        require(divisor != 0.0) { "Modulo by zero." }
                        x % divisor
                    }
                    else -> return x
                }
            }
        }

        private fun parsePower(): Double {
            val base = parseFactor()
            return if (match('^')) base.pow(parsePower()) else base
        }

        private fun parseFactor(): Double {
            if (match('+')) return parseFactor()
            if (match('-')) return -parseFactor()

            skipWhitespace()
            val startPos = pos
            return when {
                match('(') -> {
                    val value = parseExpression()
                    require(match(')')) { "Missing closing parenthesis ')'" }
                    value
                }
                !isAtEnd() && (peek().isDigit() || peek() == '.') -> {
                    while (!isAtEnd() && (peek().isDigit() || peek() == '.')) pos++
                    input.substring(startPos, pos).toDoubleOrNull()
                        ?: throw IllegalArgumentException("Invalid number near index $startPos")
                }
                !isAtEnd() && peek().isLetter() -> {
                    while (!isAtEnd() && (peek().isLetter() || peek().isDigit())) pos++
                    val name = input.substring(startPos, pos).lowercase()
                    if (match('(')) {
                        val arg = parseExpression()
                        require(match(')')) { "Missing closing parenthesis for function $name" }
                        when (name) {
                            "sqrt" -> {
                                require(arg >= 0.0) { "Cannot take square root of negative number." }
                                sqrt(arg)
                            }
                            "sin" -> sin(Math.toRadians(arg))
                            "cos" -> cos(Math.toRadians(arg))
                            "tan" -> tan(Math.toRadians(arg))
                            "log" -> {
                                require(arg > 0.0) { "Logarithm domain error." }
                                log10(arg)
                            }
                            "ln" -> {
                                require(arg > 0.0) { "Natural logarithm domain error." }
                                ln(arg)
                            }
                            "abs" -> abs(arg)
                            "round" -> round(arg)
                            else -> throw IllegalArgumentException("Unknown function: $name")
                        }
                    } else {
                        when (name) {
                            "pi" -> PI
                            "e" -> E
                            else -> throw IllegalArgumentException("Unknown identifier: $name")
                        }
                    }
                }
                else -> throw IllegalArgumentException("Unexpected token at position $pos")
            }
        }
    }
}
