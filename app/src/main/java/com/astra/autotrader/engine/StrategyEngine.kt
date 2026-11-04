package com.astra.autotrader.engine

import kotlin.math.abs

/**
 * محرك حسابات أولي يعمل على الشموع المغلقة فقط.
 * لا يرسل أوامر تداول ولا يعتبر الإشارة ضماناً للربح.
 */
object StrategyEngine {

    data class MacdResult(
        val macd: Double,
        val signal: Double,
        val histogram: Double,
        val previousHistogram: Double
    )

    fun calculateMacd(
        candles: List<Candle>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9
    ): MacdResult? {
        require(fastPeriod > 0 && slowPeriod > fastPeriod && signalPeriod > 0)
        val closes = candles.filter { it.isClosed }.map { it.close }
        if (closes.size < slowPeriod + signalPeriod + 2) return null

        val fast = emaSeries(closes, fastPeriod)
        val slow = emaSeries(closes, slowPeriod)
        val macdLine = fast.indices.map { fast[it] - slow[it] }
        val signalLine = emaSeries(macdLine, signalPeriod)
        val last = macdLine.lastIndex
        return MacdResult(
            macd = macdLine[last],
            signal = signalLine[last],
            histogram = macdLine[last] - signalLine[last],
            previousHistogram = macdLine[last - 1] - signalLine[last - 1]
        )
    }

    fun macdSignal(candles: List<Candle>): TradingSignal? {
        val closed = candles.filter { it.isClosed }
        val result = calculateMacd(closed) ?: return null
        val direction = when {
            result.previousHistogram <= 0.0 && result.histogram > 0.0 -> TradeDirection.CALL
            result.previousHistogram >= 0.0 && result.histogram < 0.0 -> TradeDirection.PUT
            else -> TradeDirection.WAIT
        }
        return TradingSignal(
            direction = direction,
            strategyId = "macd_12_26_9",
            reasonArabic = when (direction) {
                TradeDirection.CALL -> "تحول هيستوغرام MACD من السالب إلى الموجب على شمعة مغلقة"
                TradeDirection.PUT -> "تحول هيستوغرام MACD من الموجب إلى السالب على شمعة مغلقة"
                TradeDirection.WAIT -> "لا يوجد تقاطع مؤكد وفق قاعدة MACD الحالية"
            },
            candleTimestamp = closed.last().timestamp
        )
    }

    data class Levels(val supports: List<Double>, val resistances: List<Double>)

    fun detectSupportResistance(candles: List<Candle>, pivotRadius: Int = 3): Levels {
        require(pivotRadius >= 1)
        val closed = candles.filter { it.isClosed }
        if (closed.size < pivotRadius * 2 + 1) return Levels(emptyList(), emptyList())

        val supports = mutableListOf<Double>()
        val resistances = mutableListOf<Double>()
        for (i in pivotRadius until closed.size - pivotRadius) {
            val current = closed[i]
            val window = closed.subList(i - pivotRadius, i + pivotRadius + 1)
            if (window.all { current.low <= it.low }) supports += current.low
            if (window.all { current.high >= it.high }) resistances += current.high
        }
        return Levels(
            supports = supports.distinctBy { (it * 100000.0).toLong() },
            resistances = resistances.distinctBy { (it * 100000.0).toLong() }
        )
    }

    private fun emaSeries(values: List<Double>, period: Int): List<Double> {
        require(values.isNotEmpty() && period > 0)
        val alpha = 2.0 / (period + 1.0)
        val output = ArrayList<Double>(values.size)
        var ema = values.first()
        values.forEachIndexed { index, value ->
            ema = if (index == 0) value else alpha * value + (1.0 - alpha) * ema
            output += ema
        }
        return output
    }
}
