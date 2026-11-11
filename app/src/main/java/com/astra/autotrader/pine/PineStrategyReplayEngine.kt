package com.astra.autotrader.pine

enum class PineStrategyExitType { STOP, LIMIT }

data class PineStrategyClosedTrade(
    val signalIndex: Int,
    val entryIndex: Int,
    val exitIndex: Int,
    val direction: PineScriptConverter.StrategyDirection,
    val entryPrice: Double,
    val exitPrice: Double,
    val exitType: PineStrategyExitType,
    val profitPoints: Double,
    val ambiguousBar: Boolean
)

data class PineStrategyOpenPosition(
    val signalIndex: Int,
    val entryIndex: Int,
    val entryTime: Long,
    val direction: PineScriptConverter.StrategyDirection,
    val entryPrice: Double,
    val stopPrice: Double?,
    val limitPrice: Double?
)

data class PineStrategyReplayReport(
    val errorArabic: String?,
    val totalBars: Int,
    val entrySignals: Int,
    val trades: List<PineStrategyClosedTrade>,
    val openPosition: PineStrategyOpenPosition?,
    val pendingEntrySignalIndex: Int?,
    val ambiguousExitBars: Int,
    val netProfitPoints: Double,
    val warnings: List<String>
) {
    val success: Boolean get() = errorArabic == null
}

/**
 * محاكاة تاريخية محدودة لاستراتيجية Pine واحدة.
 * الدخول عند افتتاح الشمعة التالية لإشارة الإغلاق. مستويات الخروج المحسوبة
 * عند الإغلاق تصبح فعالة في الشمعة التالية. عند لمس stop وlimit معاً
 * يختار stop أولاً كافتراض محافظ، وليس كمحاكاة مطابقة لـ TradingView.
 * لا يفتح هذا المحرك صفقات PaperTradingEngine أو صفقات منصة.
 */
object PineStrategyReplayEngine {
    const val MAX_BARS = 2_000

    private data class PendingEntry(
        val signalIndex: Int,
        val direction: PineScriptConverter.StrategyDirection,
        val exitLevels: PineExitLevels?
    )

    private data class ResolvedExitLevels(val stopPrice: Double?, val limitPrice: Double?)
    private data class ActivePosition(
        val signalIndex: Int,
        val entryIndex: Int,
        val entryTime: Long,
        val direction: PineScriptConverter.StrategyDirection,
        val entryPrice: Double,
        val exitLevels: ResolvedExitLevels?
    )
    private data class LevelResolution(val levels: ResolvedExitLevels?, val errorArabic: String?)
    private data class ExitFill(val price: Double, val type: PineStrategyExitType, val ambiguous: Boolean)

    fun run(
        conversion: PineScriptConverter.Conversion,
        dataset: PineOhlcDataset,
        tickSize: Double? = null
    ): PineStrategyReplayReport {
        fun failure(message: String) = PineStrategyReplayReport(
            message, 0, 0, emptyList(), null, null, 0, 0.0, emptyList()
        )

        if (!conversion.supported || conversion.kind != PineScriptConverter.ScriptKind.STRATEGY) {
            return failure("محاكاة SL/TP تتطلب strategy() ناجحة التحويل؛ لا يمكن تشغيل indicator هنا.")
        }
        if (conversion.entries.size != 1) {
            return failure("يدعم المحاكي قاعدة strategy.entry واحدة فقط؛ تعدد الدخول أو غيابه مرفوض.")
        }
        val entry = conversion.entries.single()
        if (entry.conditionText.trim() == "true") {
            return failure("الدخول غير المشروط مرفوض؛ يلزم شرط دخول صريح لتجنب تكرار الأوامر.")
        }
        if (entry.quantityText != null || entry.compiledQuantity != null) {
            return failure("وسيط qty غير مدعوم في محاكاة الرصيد الحالية؛ لا يمكن مساواة وحدات Pine بحجم مركز ثابت.")
        }
        if (conversion.exits.size > 1) {
            return failure("يدعم المحاكي strategy.exit واحدة فقط؛ تعدد أوامر الخروج أو ترتيب أولويتها غير مدعوم.")
        }

        val exitRule = conversion.exits.singleOrNull()
        if (exitRule?.fromEntry != null && exitRule.fromEntry != entry.id) {
            return failure("from_entry في strategy.exit لا يطابق id لقاعدة الدخول الوحيدة.")
        }
        val usesTicks = exitRule?.lossTicksText != null || exitRule?.profitTicksText != null
        if (usesTicks && tickSize == null) {
            return failure("تستخدم strategy.exit وحدات loss/profit؛ أدخل tick size موثوقاً من مواصفات الأداة.")
        }
        if (tickSize != null && (!tickSize.isFinite() || tickSize <= 0.0)) {
            return failure("قيمة tick size يجب أن تكون رقماً موجباً ومنتهياً.")
        }
        if (dataset.bars.isEmpty()) return failure("لا توجد شموع OHLC للاختبار.")
        if (dataset.bars.size > MAX_BARS) {
            return failure("عدد الشموع يتجاوز حد محاكاة الاستراتيجية الحالي (" + MAX_BARS + ").")
        }
        if (dataset.expectedSignals != null && dataset.expectedSignals.size != dataset.bars.size) {
            return failure("عدد الإشارات المرجعية لا يطابق عدد شموع OHLC.")
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
            if (!valid) return failure("بيانات OHLC غير صالحة عند الشمعة " + (index + 1) + ".")
            if (previousTime != null && bar.time <= previousTime!!) {
                return failure("توقيت الشموع غير متزايد عند الشمعة " + (index + 1) + ".")
            }
            previousTime = bar.time
        }

        val evaluator = PineScriptEvaluator(dataset.bars, conversion.compiledVariables)
        val trades = mutableListOf<PineStrategyClosedTrade>()
        var pending: PendingEntry? = null
        var position: ActivePosition? = null
        var entrySignals = 0
        var ambiguousExitBars = 0

        for (index in dataset.bars.indices) {
            val bar = dataset.bars[index]
            val queued = pending
            if (queued != null) {
                pending = null
                val resolution = queued.exitLevels?.let { raw ->
                    resolveLevels(
                        raw, queued.direction, bar.open,
                        dataset.bars[queued.signalIndex].close, tickSize
                    )
                }
                if (resolution?.errorArabic != null) return failure(resolution.errorArabic)
                position = ActivePosition(
                    queued.signalIndex, index, bar.time, queued.direction,
                    bar.open, resolution?.levels
                )
            }

            var closedOnThisBar = false
            val currentPosition = position
            if (currentPosition != null && currentPosition.exitLevels != null) {
                val fill = findExitFill(currentPosition, bar)
                if (fill != null) {
                    if (fill.ambiguous) ambiguousExitBars++
                    val sign = if (currentPosition.direction == PineScriptConverter.StrategyDirection.LONG) 1.0 else -1.0
                    trades += PineStrategyClosedTrade(
                        currentPosition.signalIndex, currentPosition.entryIndex, index,
                        currentPosition.direction, currentPosition.entryPrice, fill.price,
                        fill.type, (fill.price - currentPosition.entryPrice) * sign, fill.ambiguous
                    )
                    position = null
                    closedOnThisBar = true
                }
            }

            val stillOpen = position
            if (stillOpen != null && exitRule != null) {
                val raw = evaluator.exitLevelsAt(exitRule, index)
                    ?: return failure(
                        "تعذر تقييم strategy.exit عند الشمعة " + (index + 1) +
                            "؛ أُوقف الاختبار بدلاً من إبقاء مستويات قديمة أو تخمينها."
                    )
                val resolution = resolveLevels(
                    raw, stillOpen.direction, stillOpen.entryPrice, bar.close, tickSize
                )
                if (resolution.errorArabic != null) return failure(resolution.errorArabic)
                position = stillOpen.copy(exitLevels = resolution.levels)
            }

            if (position == null && pending == null && !closedOnThisBar &&
                evaluator.entryConditionAt(entry, index) == true
            ) {
                val raw = if (exitRule == null) null else {
                    evaluator.exitLevelsAt(exitRule, index)
                        ?: return failure("تعذر تقييم مستويات strategy.exit عند شمعة إشارة الدخول " + (index + 1) + ".")
                }
                if (raw != null) {
                    val validation = resolveLevels(raw, entry.direction, bar.close, bar.close, tickSize)
                    if (validation.errorArabic != null) return failure(validation.errorArabic)
                }
                pending = PendingEntry(index, entry.direction, raw)
                entrySignals++
            }
        }

        val finalPosition = position
        val openPosition = finalPosition?.let {
            PineStrategyOpenPosition(
                it.signalIndex, it.entryIndex, it.entryTime, it.direction,
                it.entryPrice, it.exitLevels?.stopPrice, it.exitLevels?.limitPrice
            )
        }
        val warnings = buildList {
            add("إشارة الدخول تُسجّل عند إغلاق الشمعة وتُملأ عند افتتاح الشمعة التالية؛ هذا افتراض محاكاة معلن.")
            add("إذا لمس stop وlimit داخل الشمعة نفسها، يختار المحاكي stop أولاً بشكل محافظ؛ لا يطابق ذلك بالضرورة محاكي TradingView.")
            add("لا تشمل النتائج العمولة أو الانزلاق أو حجم المركز أو الرافعة، ولا يُغلق المركز المفتوح تلقائياً عند نهاية الملف.")
            add("المحاكاة تعمل على ملف OHLC محلي فقط؛ لا تتصل بـ Pocket Option ولا تفتح صفقات.")
            if (conversion.signals.isNotEmpty()) add("تم تجاهل alertcondition وplotshape/plotchar في محاكاة الأوامر؛ مصدر الدخول الوحيد هو strategy.entry.")
            if (usesTicks) add("تم تحويل loss/profit باستخدام tick size الذي أدخله المستخدم؛ صحة النتيجة تعتمد على صحة هذه القيمة.")
        }

        return PineStrategyReplayReport(
            null, dataset.bars.size, entrySignals, trades, openPosition,
            pending?.signalIndex, ambiguousExitBars, trades.sumOf { it.profitPoints }, warnings
        )
    }

    private fun resolveLevels(
        raw: PineExitLevels,
        direction: PineScriptConverter.StrategyDirection,
        entryPrice: Double,
        referencePrice: Double,
        tickSize: Double?
    ): LevelResolution {
        if (raw.stopPrice != null && raw.lossTicks != null) {
            return LevelResolution(null, "لا يمكن الجمع بين stop وloss في المحاكاة.")
        }
        if (raw.limitPrice != null && raw.profitTicks != null) {
            return LevelResolution(null, "لا يمكن الجمع بين limit وprofit في المحاكاة.")
        }
        if (raw.lossTicks != null && (!raw.lossTicks.isFinite() || raw.lossTicks <= 0.0)) {
            return LevelResolution(null, "قيمة loss يجب أن تكون موجبة.")
        }
        if (raw.profitTicks != null && (!raw.profitTicks.isFinite() || raw.profitTicks <= 0.0)) {
            return LevelResolution(null, "قيمة profit يجب أن تكون موجبة.")
        }
        if ((raw.lossTicks != null || raw.profitTicks != null) && tickSize == null) {
            return LevelResolution(null, "tick size مطلوب لتحويل loss/profit إلى سعر.")
        }

        val sign = if (direction == PineScriptConverter.StrategyDirection.LONG) 1.0 else -1.0
        val stop = raw.stopPrice ?: raw.lossTicks?.let { entryPrice - sign * it * tickSize!! }
        val limit = raw.limitPrice ?: raw.profitTicks?.let { entryPrice + sign * it * tickSize!! }
        if (stop != null && (!stop.isFinite() || stop <= 0.0)) return LevelResolution(null, "سعر stop غير صالح أو غير موجب.")
        if (limit != null && (!limit.isFinite() || limit <= 0.0)) return LevelResolution(null, "سعر limit غير صالح أو غير موجب.")

        if (raw.stopPrice != null) {
            val validSide = if (direction == PineScriptConverter.StrategyDirection.LONG) {
                raw.stopPrice < referencePrice
            } else raw.stopPrice > referencePrice
            if (!validSide) return LevelResolution(null, "مستوى stop المطلق يقع على الجانب غير المتوقع من سعر الإغلاق؛ رُفض لتجنب تفسير أمر فوري.")
        }
        if (raw.limitPrice != null) {
            val validSide = if (direction == PineScriptConverter.StrategyDirection.LONG) {
                raw.limitPrice > referencePrice
            } else raw.limitPrice < referencePrice
            if (!validSide) return LevelResolution(null, "مستوى limit المطلق يقع على الجانب غير المتوقع من سعر الإغلاق؛ رُفض لتجنب تفسير أمر فوري.")
        }
        return LevelResolution(ResolvedExitLevels(stop, limit), null)
    }

    private fun findExitFill(position: ActivePosition, bar: PineOhlcBar): ExitFill? {
        val levels = position.exitLevels ?: return null
        val isLong = position.direction == PineScriptConverter.StrategyDirection.LONG
        val stop = levels.stopPrice
        val limit = levels.limitPrice
        val stopHit = stop != null && (if (isLong) bar.low <= stop else bar.high >= stop)
        val limitHit = limit != null && (if (isLong) bar.high >= limit else bar.low <= limit)
        if (!stopHit && !limitHit) return null

        if (stopHit) {
            val stopPrice = stop!!
            val fillPrice = when {
                isLong && bar.open <= stopPrice -> bar.open
                !isLong && bar.open >= stopPrice -> bar.open
                else -> stopPrice
            }
            return ExitFill(fillPrice, PineStrategyExitType.STOP, stopHit && limitHit)
        }

        val target = limit!!
        val fillPrice = when {
            isLong && bar.open >= target -> bar.open
            !isLong && bar.open <= target -> bar.open
            else -> target
        }
        return ExitFill(fillPrice, PineStrategyExitType.LIMIT, false)
    }
}
