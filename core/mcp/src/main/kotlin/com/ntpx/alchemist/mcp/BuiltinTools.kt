package com.ntpx.alchemist.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Word / character / line / sentence counts for a block of text. Offline, no permissions. */
class TextStatsTool : ToolProvider {
    override val definition = ToolDefinition(
        name = "text.stats",
        description = "Count words, characters, lines and sentences in text.",
        inputSchema = stringArgsSchema(required = listOf("text"), descriptions = mapOf("text" to "Text to analyse")),
    )

    override suspend fun invoke(arguments: JsonObject): CallToolResult {
        val text = arguments.string("text") ?: return CallToolResult.error("'text' must be a string")
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        val terminated = Regex("[^.!?]*[\\p{L}\\p{N}][^.!?]*[.!?]+").findAll(text).toList()
        val tail = text.substring(terminated.lastOrNull()?.range?.last?.plus(1) ?: 0)
        val sentences = terminated.size + if (tail.any { it.isLetterOrDigit() }) 1 else 0
        val lines = if (text.isEmpty()) 0 else text.lines().size
        return CallToolResult.text("words=$words chars=${text.length} lines=$lines sentences=$sentences")
    }
}

/** Current time in a given IANA zone. Reads the device clock only. */
class ClockTool(private val now: () -> Instant = Instant::now) : ToolProvider {
    override val definition = ToolDefinition(
        name = "clock.now",
        description = "Current date and time, optionally in an IANA time zone such as America/New_York.",
        inputSchema = stringArgsSchema(required = emptyList(), optional = listOf("zone")),
    )

    override suspend fun invoke(arguments: JsonObject): CallToolResult {
        val zoneName = arguments.string("zone")
        val zone = try {
            zoneName?.let(ZoneId::of) ?: ZoneId.systemDefault()
        } catch (e: Exception) {
            return CallToolResult.error("Unknown time zone: $zoneName")
        }
        return CallToolResult.text(ZonedDateTime.ofInstant(now(), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + " [$zone]")
    }
}

/**
 * Safe arithmetic evaluator: + - * / % ^, parentheses, unary minus, decimals.
 * Recursive-descent parser over BigDecimal; never executes code.
 */
class MathTool : ToolProvider {
    override val definition = ToolDefinition(
        name = "math.evaluate",
        description = "Evaluate an arithmetic expression, e.g. (2 + 3) * 4 ^ 2 / 5.",
        inputSchema = stringArgsSchema(required = listOf("expression")),
    )

    override suspend fun invoke(arguments: JsonObject): CallToolResult {
        val expression = arguments.string("expression") ?: return CallToolResult.error("'expression' must be a string")
        if (expression.length > MAX_LENGTH) return CallToolResult.error("Expression longer than $MAX_LENGTH characters")
        return try {
            CallToolResult.text(evaluate(expression).stripTrailingZeros().toPlainString())
        } catch (e: ArithmeticException) {
            CallToolResult.error("Arithmetic error: ${e.message}")
        } catch (e: IllegalArgumentException) {
            CallToolResult.error("Invalid expression: ${e.message}")
        }
    }

    fun evaluate(expression: String): BigDecimal = Parser(expression).parse()

    private class Parser(private val src: String) {
        private var pos = 0
        private var depth = 0

        fun parse(): BigDecimal {
            val value = expr()
            skipSpace()
            require(pos == src.length) { "Unexpected '${src[pos]}' at ${pos + 1}" }
            return value
        }

        private fun expr(): BigDecimal {
            var value = term()
            while (true) {
                value = when {
                    eat('+') -> value.add(term(), MC)
                    eat('-') -> value.subtract(term(), MC)
                    else -> return value
                }
            }
        }

        private fun term(): BigDecimal {
            var value = unary()
            while (true) {
                value = when {
                    eat('*') -> value.multiply(unary(), MC)
                    eat('/') -> {
                        val divisor = unary()
                        if (divisor.signum() == 0) throw ArithmeticException("division by zero")
                        value.divide(divisor, MC)
                    }
                    eat('%') -> {
                        val divisor = unary()
                        if (divisor.signum() == 0) throw ArithmeticException("modulo by zero")
                        value.remainder(divisor, MC)
                    }
                    else -> return value
                }
            }
        }

        // Precedence: unary signs bind looser than '^', so -2 ^ 2 = -(2 ^ 2) = -4,
        // while a signed exponent still works: 2 ^ -1 = 0.5. Right-associative via unary() -> power().
        private fun power(): BigDecimal {
            val base = primary()
            if (!eat('^')) return base
            val exponent = unary()
            require(exponent.stripTrailingZeros().scale() <= 0) { "exponent must be an integer" }
            // Compare as BigDecimal first: narrowing a huge value to Int would wrap and slip past the limit.
            require(exponent.abs() <= BigDecimal(MAX_EXPONENT)) { "exponent must be within ±$MAX_EXPONENT" }
            val n = exponent.intValueExact()
            return if (n >= 0) base.pow(n, MC) else BigDecimal.ONE.divide(base.pow(-n, MC), MC)
        }

        private fun unary(): BigDecimal = when {
            eat('-') -> unary().negate()
            eat('+') -> unary()
            else -> power()
        }

        private fun primary(): BigDecimal {
            skipSpace()
            if (eat('(')) {
                depth++
                require(depth <= MAX_DEPTH) { "nesting deeper than $MAX_DEPTH" }
                val value = expr()
                require(eat(')')) { "missing ')'" }
                depth--
                return value
            }
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            require(pos > start) { if (pos < src.length) "Unexpected '${src[pos]}' at ${pos + 1}" else "Unexpected end of expression" }
            return src.substring(start, pos).toBigDecimalOrNull() ?: throw IllegalArgumentException("Bad number '${src.substring(start, pos)}'")
        }

        private fun eat(c: Char): Boolean {
            skipSpace()
            if (pos < src.length && src[pos] == c) {
                pos++
                return true
            }
            return false
        }

        private fun skipSpace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
    }

    private companion object {
        const val MAX_LENGTH = 500
        const val MAX_DEPTH = 64
        const val MAX_EXPONENT = 999
        val MC = MathContext(34, RoundingMode.HALF_EVEN)
    }
}

fun builtinTools(): List<ToolProvider> = listOf(TextStatsTool(), ClockTool(), MathTool())
