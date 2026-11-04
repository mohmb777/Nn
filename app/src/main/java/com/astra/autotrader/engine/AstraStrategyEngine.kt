package com.astra.autotrader.engine

import kotlin.math.max
import kotlin.math.min

/**
 * استراتيجية توافق اتجاه EMA(9/21) مع RSI(14).
 * تعمل على الشموع المغلقة فقط، ولا تتصل بالوسيط أو تنفذ أوامر.
 */
object AstraStrategyEngine {
    data class Snapshot(
        val emaFast: Double,
        val emaSlow: Double,
        val rsi: Double,
        val direction: TradeDirection,
        val reasonArabic: String,
        val candleTimestamp: Long
    )

    fun evaluate(candles: List<Candle>): Snapshot? {
        val closed = candles.filter { it.isClosed }.sortedBy { it.timestamp }
        if (closed.size < 35) return null
        if (!validCandles(closed)) return null

        val closes = closed.map { it.close }
        val fast = ema(closes, 9)
        val slow = ema(closes, 21)
        val rsi = rsiWilder(closes, 14) ?: return null
        val last = closes.last()

        val direction = when {
            fast > slow && last > fast && rsi in 52.0..68.0 -> TradeDirection.CALL
            fast < slow && last < fast && rsi in 32.0..48.0 -> TradeDirection.PUT
            else -> TradeDirection.WAIT
        }
        val reason = when (direction) {
            TradeDirection.CALL -> "اتجاه صاعد: EMA9 أعلى EMA21 والسعر فوق EMA9 وRSI ضمن نطاق التأكيد"
            TradeDirection.PUT -> "اتجاه هابط: EMA9 أسفل EMA21 والسعر تحت EMA9 وRSI ضمن نطاق التأكيد"
            TradeDirection.WAIT -> "شروط توافق الاتجاه والزخم غير مكتملة؛ الانتظار أفضل"
        }
        return Snapshot(fast, slow, rsi, direction, reason, closed.last().timestamp)
    }

    private fun validCandles(candles: List<Candle>): Boolean {
        if (candles.any {
                !it.open.isFinite() || !it.high.isFinite() || !it.low.isFinite() || !it.close.isFinite() ||
                    it.open <= 0.0 || it.close <= 0.0 ||
                    it.high < max(it.open, it.close) || it.low > min(it.open, it.close) ||
                    it.low <= 0.0
            }) return false
        return candles.zipWithNext().all { (a, b) -> b.timestamp > a.timestamp }
    }

    private fun ema(values: List<Double>, period: Int): Double {
        val alpha = 2.0 / (period + 1.0)
        var value = values.first()
        for (i in 1 until values.size) value = alpha * values[i] + (1.0 - alpha) * value
        return value
    }

    private fun rsiWilder(values: List<Double>, period: Int): Double? {
        if (values.size <= period) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val change = values[i] - values[i - 1]
            gain += max(change, 0.0)
            loss += max(-change, 0.0)
        }
        var averageGain = gain / period
        var averageLoss = loss / period
        for (i in period + 1 until values.size) {
            val change = values[i] - values[i - 1]
            averageGain = (averageGain * (period - 1) + max(change, 0.0)) / period
            averageLoss = (averageLoss * (period - 1) + max(-change, 0.0)) / period
        }
        if (averageLoss == 0.0) return if (averageGain == 0.0) 50.0 else 100.0
        val relativeStrength = averageGain / averageLoss
        return 100.0 - (100.0 / (1.0 + relativeStrength))
    }
}
