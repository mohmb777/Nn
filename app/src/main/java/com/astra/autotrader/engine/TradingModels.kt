package com.astra.autotrader.engine

enum class TradeDirection { CALL, PUT, WAIT }

data class Candle(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val isClosed: Boolean
)

data class TradingSignal(
    val direction: TradeDirection,
    val strategyId: String,
    val reasonArabic: String,
    val candleTimestamp: Long
)

data class StrategyDefinition(
    val id: String,
    val nameArabic: String,
    val source: String,
    val rulesJson: String,
    val validated: Boolean
)
