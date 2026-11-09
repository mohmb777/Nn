package com.astra.autotrader.pine

import com.astra.autotrader.engine.PaperTrade
import com.astra.autotrader.engine.PaperTradingEngine
import com.astra.autotrader.engine.TradeDirection

enum class PinePaperStatus {
    OPENED,
    WAIT,
    DUPLICATE_BAR,
    STALE_BAR,
    BAR_NOT_CLOSED,
    INVALID_INPUT,
    UNSUPPORTED_SCRIPT,
    UNSUPPORTED_ENTRY_SEMANTICS,
    UNSUPPORTED_EXIT_RULES,
    UNSUPPORTED_QUANTITY,
    OPEN_TRADE_EXISTS,
    RISK_HALTED,
    PAPER_REJECTED
}

data class PinePaperResult(
    val status: PinePaperStatus,
    val reasonArabic: String,
    val signal: PineSignalResult? = null,
    val trade: PaperTrade? = null
)

/**
 * بوابة محلية بين نتائج Pine ومحاكي PaperTradingEngine.
 *
 * لا تجلب OHLC ولا تتصل بـ Pocket Option. يجب على المستدعي تمرير سلسلة
 * OHLC موثوقة وإثبات أن الشمعة الأخيرة مغلقة. قواعد الخروج والكمية وتعدد
 * أوامر الدخول لا تُحاكى هنا، لذلك تُرفض بدلاً من تغيير معناها أو تجاهلها.
 */
class PinePaperTradingBridge(
    private val paperTrading: PaperTradingEngine
) {
    private val lastProcessedByStrategy = mutableMapOf<String, Long>()

    @Synchronized
    fun processClosedBar(
        strategyId: String,
        conversion: PineScriptConverter.Conversion,
        bars: List<PineOhlcBar>,
        index: Int,
        isClosed: Boolean
    ): PinePaperResult {
        if (strategyId.isBlank()) {
            return result(PinePaperStatus.INVALID_INPUT, "معرّف الاستراتيجية فارغ.")
        }
        if (!conversion.supported || conversion.kind == null) {
            return result(PinePaperStatus.UNSUPPORTED_SCRIPT, "يجب أن يجتاز السكربت التحويل الكامل قبل المحاكاة.")
        }
        if (conversion.signals.any { it.compiledExpression == null }) {
            return result(PinePaperStatus.UNSUPPORTED_SCRIPT, "يوجد شرط إشارة لم يتحول إلى تعبير داخلي قابل للتقييم.")
        }
        if (conversion.entries.size > 1 || conversion.entries.any { it.conditionText.trim() == "true" }) {
            return result(
                PinePaperStatus.UNSUPPORTED_ENTRY_SEMANTICS,
                "تعدد strategy.entry أو الدخول غير المشروط يحتاج محاكاة حالة المركز وpyramiding؛ رُفض لتجنب فتح صفقات لا تمثل منطق Pine."
            )
        }
        if (conversion.exits.isNotEmpty()) {
            return result(
                PinePaperStatus.UNSUPPORTED_EXIT_RULES,
                "تحتوي الاستراتيجية على strategy.exit؛ لم تُنفذ محاكاة حالة المركز أو SL/TP بعد، لذلك رُفض فتح صفقة ناقصة المحاكاة."
            )
        }
        if (conversion.entries.any { it.quantityText != null || it.compiledQuantity != null }) {
            return result(
                PinePaperStatus.UNSUPPORTED_QUANTITY,
                "تحتوي قاعدة الدخول على qty؛ لا يمكن مساواة وحدات Pine بمبلغ الرهان الثابت في محاكي Paper."
            )
        }
        if (index !in bars.indices) {
            return result(PinePaperStatus.INVALID_INPUT, "فهرس الشمعة خارج سلسلة OHLC.")
        }
        if (!isClosed) {
            return result(PinePaperStatus.BAR_NOT_CLOSED, "لن تُقيّم الشمعة قبل تأكيد إغلاقها.")
        }

        val window = bars.take(index + 1)
        if (!validBars(window)) {
            return result(PinePaperStatus.INVALID_INPUT, "بيانات OHLC غير صالحة أو أوقاتها غير متزايدة.")
        }
        val bar = window.last()
        val lastProcessed = lastProcessedByStrategy[strategyId]
        if (lastProcessed != null) {
            if (bar.time == lastProcessed) {
                return result(PinePaperStatus.DUPLICATE_BAR, "تمت معالجة هذه الشمعة سابقاً لهذه الاستراتيجية.")
            }
            if (bar.time < lastProcessed) {
                return result(PinePaperStatus.STALE_BAR, "وصلت شمعة أقدم من آخر شمعة تمت معالجتها.")
            }
        }

        val signal = PineScriptEvaluator(window, conversion.compiledVariables).signalAt(conversion, index)
        // حتى WAIT يُستهلك مرة واحدة؛ إعادة الاستدعاء على الشمعة نفسها لا تعيد إطلاقها.
        lastProcessedByStrategy[strategyId] = bar.time

        val direction = when (signal.action) {
            PineSignalAction.CALL -> TradeDirection.CALL
            PineSignalAction.PUT -> TradeDirection.PUT
            PineSignalAction.WAIT -> TradeDirection.WAIT
        }
        if (direction == TradeDirection.WAIT) {
            return result(PinePaperStatus.WAIT, signal.reason, signal)
        }

        val trade = paperTrading.open(direction, bar.close, bar.time, strategyId)
        if (trade != null) {
            return PinePaperResult(
                PinePaperStatus.OPENED,
                "سُجلت صفقة محاكاة محلية بسعر إغلاق الشمعة؛ لم يُرسل أي أمر إلى المنصة.",
                signal,
                trade
            )
        }

        val account = paperTrading.snapshot()
        val status = when {
            account.halted -> PinePaperStatus.RISK_HALTED
            account.trades.isNotEmpty() -> PinePaperStatus.OPEN_TRADE_EXISTS
            else -> PinePaperStatus.PAPER_REJECTED
        }
        val reason = when (status) {
            PinePaperStatus.RISK_HALTED -> account.haltReasonArabic ?: "محاكي Paper متوقف وفق حدود المخاطر."
            PinePaperStatus.OPEN_TRADE_EXISTS -> "توجد صفقة محاكاة مفتوحة؛ لا يسمح بفتح صفقة ثانية."
            else -> "رفض محاكي Paper الصفقة بسبب حدود الرصيد أو مبلغ الرهان."
        }
        return result(status, reason, signal)
    }

    private fun validBars(bars: List<PineOhlcBar>): Boolean {
        if (bars.isEmpty()) return false
        if (bars.any { bar ->
                !bar.open.isFinite() || !bar.high.isFinite() ||
                    !bar.low.isFinite() || !bar.close.isFinite() ||
                    !bar.volume.isFinite() || bar.open <= 0.0 ||
                    bar.high <= 0.0 || bar.low <= 0.0 || bar.close <= 0.0 ||
                    bar.volume < 0.0 ||
                    bar.high < maxOf(bar.open, bar.close) ||
                    bar.low > minOf(bar.open, bar.close) ||
                    bar.low > bar.high
            }) return false
        return bars.zipWithNext().all { (previous, current) -> current.time > previous.time }
    }

    private fun result(
        status: PinePaperStatus,
        reason: String,
        signal: PineSignalResult? = null
    ) = PinePaperResult(status, reason, signal)
}
