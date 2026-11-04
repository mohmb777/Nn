package com.astra.autotrader.engine

/** لا تمرر الإشارات إلا بعد إثبات اتصال مصدر بيانات موثوق وحداثة الشموع. */
object SignalGate {
    fun validate(
        candles: List<Candle>,
        sourceStatus: DataSourceStatus,
        nowMillis: Long,
        maxAgeMillis: Long = 30_000L
    ): GateResult {
        if (sourceStatus.state != ConnectionState.CONNECTED) {
            return GateResult(false, "مصدر البيانات غير متصل")
        }
        val last = candles.lastOrNull() ?: return GateResult(false, "لا توجد شموع")
        if (!last.isClosed) return GateResult(false, "الشمعة الحالية لم تغلق بعد")
        val updated = sourceStatus.lastUpdateMillis
            ?: return GateResult(false, "وقت تحديث البيانات غير معروف")
        if (nowMillis < updated || nowMillis - updated > maxAgeMillis) {
            return GateResult(false, "بيانات الشموع قديمة أو توقيتها غير صحيح")
        }
        if (candles.any {
            !it.open.isFinite() || !it.high.isFinite() || !it.low.isFinite() || !it.close.isFinite() ||
                it.high < maxOf(it.open, it.close) || it.low > minOf(it.open, it.close)
        }) return GateResult(false, "بيانات شموع غير صالحة")
        return GateResult(true, "البيانات اجتازت الفحوص الأساسية")
    }
}

data class GateResult(val allowed: Boolean, val reasonArabic: String)
