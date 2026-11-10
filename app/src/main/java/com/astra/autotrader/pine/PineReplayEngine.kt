package com.astra.autotrader.pine

data class PineReplayEvent(
    val index: Int,
    val timestamp: Long,
    val action: PineSignalAction,
    val activeRules: List<String>,
    val reason: String
)

data class PineReplayMismatch(
    val index: Int,
    val timestamp: Long,
    val expected: PineSignalAction,
    val actual: PineSignalAction
)

data class PineReplayReport(
    val errorArabic: String?,
    val totalBars: Int,
    val callCount: Int,
    val putCount: Int,
    val waitCount: Int,
    val comparedBars: Int,
    val matchedBars: Int,
    val mismatchCount: Int,
    val mismatchSamples: List<PineReplayMismatch>,
    val recentSignals: List<PineReplayEvent>,
    val exitsPresent: Boolean
) {
    val success: Boolean get() = errorArabic == null
    val matchRatePercent: Double?
        get() = if (comparedBars == 0) null else matchedBars * 100.0 / comparedBars
}

/**
 * إعادة تشغيل إشارات Pine على سلسلة OHLC محلية يختارها المستخدم.
 * هذه مقارنة إشارات فقط؛ لا تفتح صفقات ولا تحاكي الخروج أو SL/TP.
 */
object PineReplayEngine {
    const val MAX_REPLAY_BARS = 2_000
    private const val MAX_SAMPLES = 20

    fun run(
        conversion: PineScriptConverter.Conversion,
        dataset: PineOhlcDataset
    ): PineReplayReport {
        fun failure(message: String) = PineReplayReport(
            errorArabic = message,
            totalBars = 0,
            callCount = 0,
            putCount = 0,
            waitCount = 0,
            comparedBars = 0,
            matchedBars = 0,
            mismatchCount = 0,
            mismatchSamples = emptyList(),
            recentSignals = emptyList(),
            exitsPresent = conversion.exits.isNotEmpty()
        )

        if (!conversion.supported || conversion.kind == null) {
            return failure("لا يمكن تشغيل إعادة الاختبار قبل نجاح تحويل Pine Script بالكامل.")
        }
        if (conversion.signals.isEmpty() && conversion.entries.isEmpty()) {
            return failure("لم يستخرج المحوّل أي alertcondition أو strategy.entry قابل للتقييم.")
        }
        if (conversion.signals.any { it.compiledExpression == null }) {
            return failure("يوجد شرط إشارة غير مترجم؛ أُوقف الاختبار بدلاً من تجاهله.")
        }
        if (dataset.bars.isEmpty()) return failure("لا توجد شموع OHLC للاختبار.")
        if (dataset.bars.size > MAX_REPLAY_BARS) {
            return failure("عدد الشموع يتجاوز حد إعادة الاختبار الحالي (${MAX_REPLAY_BARS}) لحماية أداء الهاتف.")
        }
        if (dataset.expectedSignals != null && dataset.expectedSignals.size != dataset.bars.size) {
            return failure("عدد إشارات TradingView المرجعية لا يطابق عدد شموع OHLC.")
        }

        var previousTime: Long? = null
        for ((index, bar) in dataset.bars.withIndex()) {
            val valid = bar.time > 0L &&
                bar.open.isFinite() && bar.high.isFinite() &&
                bar.low.isFinite() && bar.close.isFinite() &&
                bar.volume.isFinite() && bar.open > 0.0 &&
                bar.high >= maxOf(bar.open, bar.close) &&
                bar.low <= minOf(bar.open, bar.close) &&
                bar.low > 0.0 && bar.low <= bar.high && bar.volume >= 0.0
            if (!valid) return failure("بيانات OHLC غير صالحة عند الشمعة ${index + 1}.")
            if (previousTime != null && bar.time <= previousTime!!) {
                return failure("توقيت الشموع غير متزايد عند الشمعة ${index + 1}.")
            }
            previousTime = bar.time
        }

        val evaluator = PineScriptEvaluator(dataset.bars, conversion.compiledVariables)
        var calls = 0
        var puts = 0
        var waits = 0
        var compared = 0
        var matched = 0
        var mismatchCount = 0
        val mismatchSamples = mutableListOf<PineReplayMismatch>()
        val recentSignals = mutableListOf<PineReplayEvent>()

        dataset.bars.indices.forEach { index ->
            val bar = dataset.bars[index]
            val signal = evaluator.signalAt(conversion, index)
            when (signal.action) {
                PineSignalAction.CALL -> calls++
                PineSignalAction.PUT -> puts++
                PineSignalAction.WAIT -> waits++
            }

            if (signal.action != PineSignalAction.WAIT) {
                recentSignals += PineReplayEvent(
                    index, bar.time, signal.action, signal.activeRules, signal.reason
                )
                if (recentSignals.size > MAX_SAMPLES) recentSignals.removeAt(0)
            }

            val expected = dataset.expectedSignals?.get(index)
            if (expected != null) {
                compared++
                if (expected == signal.action) {
                    matched++
                } else {
                    mismatchCount++
                    if (mismatchSamples.size < MAX_SAMPLES) {
                        mismatchSamples += PineReplayMismatch(index, bar.time, expected, signal.action)
                    }
                }
            }
        }

        return PineReplayReport(
            errorArabic = null,
            totalBars = dataset.bars.size,
            callCount = calls,
            putCount = puts,
            waitCount = waits,
            comparedBars = compared,
            matchedBars = matched,
            mismatchCount = mismatchCount,
            mismatchSamples = mismatchSamples,
            recentSignals = recentSignals,
            exitsPresent = conversion.exits.isNotEmpty()
        )
    }
}
