package com.astra.autotrader.engine

interface MarketDataSource {
    suspend fun connect(): DataSourceStatus
    suspend fun disconnect()
    fun status(): DataSourceStatus
    fun latestCandles(limit: Int): List<Candle>
}

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class DataSourceStatus(
    val state: ConnectionState,
    val messageArabic: String,
    val lastUpdateMillis: Long? = null
)

/** مصدر تجريبي فارغ؛ لا ينتحل أسعار الوسيط ولا ينشئ إشارات حقيقية. */
class InMemoryMarketDataSource : MarketDataSource {
    private var current = DataSourceStatus(
        ConnectionState.DISCONNECTED,
        "مصدر الشموع الحقيقي غير مهيأ"
    )
    private val candles = mutableListOf<Candle>()

    override suspend fun connect(): DataSourceStatus {
        current = DataSourceStatus(
            ConnectionState.ERROR,
            "لا يوجد موصل موثّق لبيانات الشارت. التداول معطل."
        )
        return current
    }

    override suspend fun disconnect() {
        current = DataSourceStatus(ConnectionState.DISCONNECTED, "تم فصل مصدر البيانات")
    }

    override fun status(): DataSourceStatus = current

    override fun latestCandles(limit: Int): List<Candle> {
        require(limit > 0)
        return candles.takeLast(limit)
    }

    /** للاختبارات ووضع العرض فقط. */
    fun loadDemoCandles(items: List<Candle>) {
        require(items.all {
            it.open.isFinite() && it.high.isFinite() && it.low.isFinite() && it.close.isFinite() &&
                it.high >= maxOf(it.open, it.close) && it.low <= minOf(it.open, it.close)
        })
        candles.clear()
        candles.addAll(items)
        current = DataSourceStatus(
            ConnectionState.DISCONNECTED,
            "بيانات اصطناعية للعرض فقط — ليست أسعار السوق"
        )
    }
}
