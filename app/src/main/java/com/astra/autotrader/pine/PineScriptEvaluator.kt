package com.astra.autotrader.pine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PineOhlcBar(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0
)

enum class PineSignalDirection { CALL, PUT, UNKNOWN }
enum class PineSignalAction { CALL, PUT, WAIT }

data class PineSignalResult(
    val action: PineSignalAction,
    val activeRules: List<String>,
    val reason: String
)

sealed interface PineValue {
    data class Number(val value: Double) : PineValue
    data class Boolean(val value: kotlin.Boolean) : PineValue
    data class Text(val value: String) : PineValue
}

/**
 * مقيّم حتمي يعمل على OHLC يقدمه المستدعي فقط.
 * لا يتصل بـ Pocket Option ولا ينفذ أوامر تداول.
 */
class PineScriptEvaluator(
    private val bars: List<PineOhlcBar>,
    private val variables: Map<String, PineExpr>
) {
    fun evaluate(expression: PineExpr, index: Int): PineValue? {
        if (index !in bars.indices) return null
        return eval(expression, index, emptySet())
    }

    fun signalAt(conversion: PineScriptConverter.Conversion, index: Int): PineSignalResult {
        if (index !in bars.indices) return PineSignalResult(PineSignalAction.WAIT, emptyList(), "فهرس الشمعة خارج البيانات.")
        val evaluator = PineScriptEvaluator(bars, conversion.compiledVariables)
        val active = conversion.signals.filter { rule ->
            val expression = rule.compiledExpression ?: return@filter false
            (evaluator.evaluate(expression, index) as? PineValue.Boolean)?.value == true
        }
        if (active.isEmpty()) return PineSignalResult(PineSignalAction.WAIT, emptyList(), "لا يوجد شرط إشارة نشط على هذه الشمعة.")
        val directions = active.map { it.direction }.toSet()
        return when {
            directions == setOf(PineSignalDirection.CALL) ->
                PineSignalResult(PineSignalAction.CALL, active.map { it.name }, "تحقق شرط CALL معلن صراحةً في السكربت.")
            directions == setOf(PineSignalDirection.PUT) ->
                PineSignalResult(PineSignalAction.PUT, active.map { it.name }, "تحقق شرط PUT معلن صراحةً في السكربت.")
            else ->
                PineSignalResult(PineSignalAction.WAIT, active.map { it.name }, "الشروط النشطة متعارضة أو لا تحمل اتجاهاً صريحاً؛ تم الامتناع عن التخمين.")
        }
    }

    private fun eval(expression: PineExpr, index: Int, visiting: Set<String>): PineValue? = when (expression) {
        is PineExpr.NumberLiteral -> PineValue.Number(expression.value)
        is PineExpr.StringLiteral -> PineValue.Text(expression.value)
        is PineExpr.Identifier -> identifier(expression.name, index, visiting)
        is PineExpr.Unary -> {
            val value = eval(expression.operand, index, visiting)
            when (expression.operator) {
                "-" -> value.numberOrNull()?.let { PineValue.Number(-it) }
                "+" -> value.numberOrNull()?.let { PineValue.Number(it) }
                "not" -> value.booleanOrNull()?.let { PineValue.Boolean(!it) }
                else -> null
            }
        }
        is PineExpr.Binary -> binary(expression.operator, eval(expression.left, index, visiting), eval(expression.right, index, visiting))
        is PineExpr.Call -> call(expression, index, visiting)
    }

    private fun identifier(name: String, index: Int, visiting: Set<String>): PineValue? {
        val bar = bars[index]
        when (name) {
            "open" -> return PineValue.Number(bar.open)
            "high" -> return PineValue.Number(bar.high)
            "low" -> return PineValue.Number(bar.low)
            "close" -> return PineValue.Number(bar.close)
            "volume" -> return PineValue.Number(bar.volume)
            "true" -> return PineValue.Boolean(true)
            "false" -> return PineValue.Boolean(false)
            "na" -> return null
            "strategy.long" -> return PineValue.Text("long")
            "strategy.short" -> return PineValue.Text("short")
        }
        if (name in visiting) return null
        val expression = variables[name] ?: return null
        return eval(expression, index, visiting + name)
    }

    private fun call(call: PineExpr.Call, index: Int, visiting: Set<String>): PineValue? {
        val positional = call.arguments.filter { it.name == null }.map { it.value }
        fun arg(position: Int, named: String? = null): PineExpr? =
            call.arguments.firstOrNull { named != null && it.name == named }?.value ?: positional.getOrNull(position)
        fun numberArg(position: Int, named: String? = null): Double? =
            arg(position, named)?.let { eval(it, index, visiting).numberOrNull() }

        return when (call.name) {
            "input.int", "input.float", "input.bool", "input.string", "input.source" ->
                arg(0, "defval")?.let { eval(it, index, visiting) }
            "math.abs" -> numberArg(0)?.let { PineValue.Number(abs(it)) }
            "math.min" -> {
                val a = numberArg(0); val b = numberArg(1)
                if (a != null && b != null) PineValue.Number(min(a, b)) else null
            }
            "math.max" -> {
                val a = numberArg(0); val b = numberArg(1)
                if (a != null && b != null) PineValue.Number(max(a, b)) else null
            }
            "ta.ema", "ta.sma", "ta.rsi" -> {
                val source = arg(0) ?: return null
                val length = numberArg(1)?.toInt() ?: return null
                if (length <= 0 || length > 500) return null
                val values = (0..index).map { i -> eval(source, i, visiting).numberOrNull() }
                val result = when (call.name) {
                    "ta.ema" -> ema(values, length)
                    "ta.sma" -> sma(values, length)
                    else -> rsi(values, length)
                }
                result.getOrNull(index)?.takeIf { it.isFinite() }?.let { PineValue.Number(it) }
            }
            "ta.crossover", "ta.crossunder" -> {
                val left = arg(0) ?: return null
                val right = arg(1) ?: return null
                if (index == 0) return PineValue.Boolean(false)
                val currentLeft = eval(left, index, visiting).numberOrNull() ?: return null
                val currentRight = eval(right, index, visiting).numberOrNull() ?: return null
                val previousLeft = eval(left, index - 1, visiting).numberOrNull() ?: return null
                val previousRight = eval(right, index - 1, visiting).numberOrNull() ?: return null
                val crossed = if (call.name == "ta.crossover") {
                    currentLeft > currentRight && previousLeft <= previousRight
                } else {
                    currentLeft < currentRight && previousLeft >= previousRight
                }
                PineValue.Boolean(crossed)
            }
            "ta.macd.line", "ta.macd.signal", "ta.macd.histogram" -> {
                val source = arg(0) ?: return null
                val fast = numberArg(1)?.toInt() ?: return null
                val slow = numberArg(2)?.toInt() ?: return null
                val signal = numberArg(3)?.toInt() ?: return null
                if (fast <= 0 || slow <= fast || signal <= 0 || slow > 500 || signal > 500) return null
                val sourceValues = (0..index).map { i -> eval(source, i, visiting).numberOrNull() }
                val fastValues = ema(sourceValues, fast)
                val slowValues = ema(sourceValues, slow)
                val macd = fastValues.indices.map { i ->
                    val a = fastValues[i]; val b = slowValues[i]
                    if (a.isFinite() && b.isFinite()) a - b else Double.NaN
                }
                val signalValues = ema(macd.map { it.takeIf { value -> value.isFinite() } }, signal)
                val value = when (call.name) {
                    "ta.macd.line" -> macd.getOrNull(index)
                    "ta.macd.signal" -> signalValues.getOrNull(index)
                    else -> {
                        val a = macd.getOrNull(index)
                        val b = signalValues.getOrNull(index)
                        if (a != null && b != null && a.isFinite() && b.isFinite()) a - b else null
                    }
                }
                value?.takeIf { it.isFinite() }?.let { PineValue.Number(it) }
            }
            else -> null
        }
    }

    private fun binary(operator: String, left: PineValue?, right: PineValue?): PineValue? {
        if (operator == "and" || operator == "or") {
            val a = left.booleanOrNull() ?: return null
            val b = right.booleanOrNull() ?: return null
            return PineValue.Boolean(if (operator == "and") a && b else a || b)
        }
        val a = left.numberOrNull()
        val b = right.numberOrNull()
        if (a != null && b != null) {
            return when (operator) {
                "+" -> PineValue.Number(a + b)
                "-" -> PineValue.Number(a - b)
                "*" -> PineValue.Number(a * b)
                "/" -> if (b == 0.0) null else PineValue.Number(a / b)
                "%" -> if (b == 0.0) null else PineValue.Number(a % b)
                ">" -> PineValue.Boolean(a > b)
                ">=" -> PineValue.Boolean(a >= b)
                "<" -> PineValue.Boolean(a < b)
                "<=" -> PineValue.Boolean(a <= b)
                "==" -> PineValue.Boolean(a == b)
                "!=" -> PineValue.Boolean(a != b)
                else -> null
            }
        }
        if (operator == "==" || operator == "!=") {
            val equal = left == right
            return PineValue.Boolean(if (operator == "==") equal else !equal)
        }
        return null
    }

    private fun ema(values: List<Double?>, length: Int): List<Double> {
        val output = MutableList(values.size) { Double.NaN }
        val alpha = 2.0 / (length + 1.0)
        var previous: Double? = null
        values.forEachIndexed { index, value ->
            if (value != null && value.isFinite()) {
                previous = if (previous == null) value else alpha * value + (1.0 - alpha) * previous!!
                output[index] = previous!!
            }
        }
        return output
    }

    private fun sma(values: List<Double?>, length: Int): List<Double> =
        values.indices.map { index ->
            if (index + 1 < length) Double.NaN
            else {
                val window = values.subList(index - length + 1, index + 1)
                if (window.any { it == null || !it.isFinite() }) Double.NaN
                else window.sumOf { it!! } / length
            }
        }

    private fun rsi(values: List<Double?>, length: Int): List<Double> {
        val output = MutableList(values.size) { Double.NaN }
        if (values.size <= length) return output
        val gains = mutableListOf<Double>()
        val losses = mutableListOf<Double>()
        var averageGain: Double? = null
        var averageLoss: Double? = null
        for (i in 1 until values.size) {
            val current = values[i]
            val previous = values[i - 1]
            if (current == null || previous == null || !current.isFinite() || !previous.isFinite()) continue
            val change = current - previous
            if (averageGain == null || averageLoss == null) {
                gains += max(change, 0.0)
                losses += max(-change, 0.0)
                if (gains.size == length) {
                    averageGain = gains.average()
                    averageLoss = losses.average()
                    val denominator = averageGain + averageLoss
                    output[i] = if (denominator == 0.0) 50.0 else 100.0 * averageGain / denominator
                    gains.clear(); losses.clear()
                }
            } else {
                averageGain = ((averageGain * (length - 1)) + max(change, 0.0)) / length
                averageLoss = ((averageLoss * (length - 1)) + max(-change, 0.0)) / length
                val denominator = averageGain + averageLoss
                output[i] = if (denominator == 0.0) 50.0 else 100.0 * averageGain / denominator
            }
        }
        return output
    }
}

private fun PineValue?.numberOrNull(): Double? = (this as? PineValue.Number)?.value?.takeIf { it.isFinite() }
private fun PineValue?.booleanOrNull(): Boolean? = (this as? PineValue.Boolean)?.value
