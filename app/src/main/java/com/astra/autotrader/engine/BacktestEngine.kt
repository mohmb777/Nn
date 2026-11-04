package com.astra.autotrader.engine

import kotlin.math.max

data class BacktestTrade(
    val candleTime: Long,
    val direction: TradeDirection,
    val entryPrice: Double,
    val exitPrice: Double,
    val stake: Double,
    val payoutRate: Double,
    val profit: Double
)

data class BacktestReport(
    val trades: List<BacktestTrade>,
    val wins: Int,
    val losses: Int,
    val netProfit: Double,
    val maxDrawdown: Double,
    val winRatePercent: Double
)

/**
 * اختبار تاريخي مبسط على إغلاق الشمعة التالية.
 * لا يحاكي فروق الأسعار أو تأخير التنفيذ أو تغيّر عائد المنصة.
 * لا تستخدم نتائجه كتوقع للأداء المستقبلي.
 */
object BacktestEngine {
    fun runMacd(
        candles: List<Candle>,
        stake: Double,
        payoutRate: Double,
        expiryCandles: Int = 1
    ): BacktestReport {
        require(stake > 0.0) { "يجب أن يكون مبلغ الصفقة موجباً" }
        require(payoutRate in 0.0..1.0) { "نسبة العائد يجب أن تكون بين صفر وواحد" }
        require(expiryCandles >= 1)

        val closed = candles.filter { it.isClosed }
        val trades = mutableListOf<BacktestTrade>()
        var balance = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0

        for (i in 40 until closed.size - expiryCandles) {
            val history = closed.subList(0, i + 1)
            val signal = StrategyEngine.macdSignal(history) ?: continue
            if (signal.direction == TradeDirection.WAIT) continue

            // تدخل الصفقة عند افتتاح الشمعة التالية لإشارة أُغلقت بالفعل.
            val entry = closed[i + 1].open
            val exit = closed[i + expiryCandles].close
            val won = when (signal.direction) {
                TradeDirection.CALL -> exit > entry
                TradeDirection.PUT -> exit < entry
                TradeDirection.WAIT -> false
            }
            val profit = if (won) stake * payoutRate else -stake
            balance += profit
            peak = max(peak, balance)
            maxDrawdown = max(maxDrawdown, peak - balance)
            trades += BacktestTrade(
                candleTime = closed[i].timestamp,
                direction = signal.direction,
                entryPrice = entry,
                exitPrice = exit,
                stake = stake,
                payoutRate = payoutRate,
                profit = profit
            )
        }

        val wins = trades.count { it.profit > 0.0 }
        val losses = trades.size - wins
        return BacktestReport(
            trades = trades,
            wins = wins,
            losses = losses,
            netProfit = trades.sumOf { it.profit },
            maxDrawdown = maxDrawdown,
            winRatePercent = if (trades.isEmpty()) 0.0 else wins * 100.0 / trades.size
        )
    }
}
