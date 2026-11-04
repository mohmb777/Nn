package com.astra.autotrader.engine

data class RiskConfig(
    val startingBalance: Double,
    val stake: Double,
    val maxStake: Double,
    val maxConsecutiveLosses: Int = 2,
    val maxDailyLoss: Double = startingBalance * 0.05
) {
    init {
        require(startingBalance > 0.0 && startingBalance.isFinite())
        require(stake > 0.0 && stake.isFinite())
        require(maxStake >= stake && maxStake.isFinite())
        require(maxConsecutiveLosses >= 1)
        require(maxDailyLoss > 0.0 && maxDailyLoss.isFinite())
    }
}

data class PaperTrade(
    val id: Long,
    val direction: TradeDirection,
    val stake: Double,
    val entryPrice: Double,
    val openedAt: Long,
    val strategyId: String
)

data class PaperAccount(
    val balance: Double,
    val dailyNet: Double,
    val consecutiveLosses: Int,
    val trades: List<PaperTrade>,
    val halted: Boolean,
    val haltReasonArabic: String?
)

/** محاكي داخلي فقط؛ لا يتصل بالوسيط ولا يرسل أوامر تداول. */
class PaperTradingEngine(private val config: RiskConfig) {
    private var nextId = 1L
    private var balance = config.startingBalance
    private var dailyNet = 0.0
    private var consecutiveLosses = 0
    private val openTrades = mutableListOf<PaperTrade>()
    private var halted = false
    private var haltReason: String? = null

    @Synchronized
    fun open(direction: TradeDirection, entryPrice: Double, timestamp: Long, strategyId: String): PaperTrade? {
        if (direction == TradeDirection.WAIT || !entryPrice.isFinite() || entryPrice <= 0.0) return null
        if (halted || openTrades.isNotEmpty()) return null
        val amount = config.stake
        if (amount > config.maxStake || amount > balance) return null
        val trade = PaperTrade(nextId++, direction, amount, entryPrice, timestamp, strategyId)
        openTrades += trade
        return trade
    }

    /** payoutRate=0.80 يعني ربح 80% من مبلغ الصفقة عند الفوز. */
    @Synchronized
    fun settle(tradeId: Long, won: Boolean, payoutRate: Double): Boolean {
        if (!payoutRate.isFinite() || payoutRate < 0.0) return false
        val trade = openTrades.firstOrNull { it.id == tradeId } ?: return false
        openTrades.remove(trade)
        val net = if (won) trade.stake * payoutRate else -trade.stake
        balance += net
        dailyNet += net
        consecutiveLosses = if (won) 0 else consecutiveLosses + 1
        if (consecutiveLosses >= config.maxConsecutiveLosses) {
            halted = true
            haltReason = "توقف وقائي بعد " + config.maxConsecutiveLosses + " خسائر متتالية"
        } else if (dailyNet <= -config.maxDailyLoss) {
            halted = true
            haltReason = "تم بلوغ حد الخسارة اليومية"
        } else if (balance <= 0.0) {
            halted = true
            haltReason = "الرصيد التجريبي نفد"
        }
        return true
    }

    @Synchronized
    fun snapshot() = PaperAccount(balance, dailyNet, consecutiveLosses, openTrades.toList(), halted, haltReason)

    @Synchronized
    fun resetDay() {
        dailyNet = 0.0
        consecutiveLosses = 0
        halted = false
        haltReason = null
    }
}
