package com.astra.autotrader.engine

enum class ChartTimeframe(val seconds: Int, val labelArabic: String) {
    M1(60, "دقيقة واحدة"),
    M5(300, "5 دقائق"),
    M15(900, "15 دقيقة"),
    M30(1800, "30 دقيقة"),
    H1(3600, "ساعة")
}

enum class AccountMode { UNKNOWN, DEMO, REAL }

data class TradingPlan(
    val strategyId: String,
    val symbol: String,
    val timeframe: ChartTimeframe,
    val stake: Double,
    val payoutFloorPercent: Int = 70,
    val maxOpenTrades: Int = 1
) {
    fun validate(): String? {
        if (strategyId.isBlank()) return "اختر استراتيجية أولاً"
        if (symbol.isBlank()) return "حدد زوج التداول من المنصة"
        if (!stake.isFinite() || stake <= 0.0) return "مبلغ الصفقة غير صالح"
        if (payoutFloorPercent !in 0..100) return "نسبة العائد المحددة غير صالحة"
        if (maxOpenTrades != 1) return "يسمح النظام بصفقة تجريبية واحدة فقط في الوقت الحالي"
        return null
    }

    /** مدة الصفقة المقترحة تساوي الإطار الزمني المحدد. */
    val expirySeconds: Int get() = timeframe.seconds
}

data class DemoAccountProof(
    val mode: AccountMode,
    val verifiedAtMillis: Long,
    val evidence: String
)

enum class DemoOrderStatus { REJECTED, PREPARED, SUBMITTED, CONFIRMED, UNKNOWN }

data class DemoOrderRequest(
    val direction: TradeDirection,
    val amount: Double,
    val expirySeconds: Int,
    val symbol: String
)

data class DemoOrderResult(
    val status: DemoOrderStatus,
    val messageArabic: String,
    val externalOrderId: String? = null
)

/**
 * واجهة تنفيذ معزولة. لا توجد وصلة تنفيذ افتراضية إلى الموقع.
 * لا يمكن إضافة موصل إلا بعد التحقق من وضع DEMO ومن واجهة الموقع الفعلية.
 */
interface DemoExecutionPort {
    suspend fun submit(request: DemoOrderRequest): DemoOrderResult
}

class DisabledDemoExecutionPort : DemoExecutionPort {
    override suspend fun submit(request: DemoOrderRequest) = DemoOrderResult(
        DemoOrderStatus.REJECTED,
        "التنفيذ التجريبي غير موصول بعد؛ لا يمكن تأكيد وضع الحساب أو نتيجة الأمر."
    )
}

class DemoOrderCoordinator(
    private val port: DemoExecutionPort,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun execute(
        plan: TradingPlan,
        signal: TradingSignal,
        accountProof: DemoAccountProof?
    ): DemoOrderResult {
        plan.validate()?.let {
            return DemoOrderResult(DemoOrderStatus.REJECTED, it)
        }
        if (signal.direction == TradeDirection.WAIT) {
            return DemoOrderResult(DemoOrderStatus.REJECTED, "لا توجد إشارة دخول")
        }
        if (accountProof == null || accountProof.mode != AccountMode.DEMO) {
            return DemoOrderResult(DemoOrderStatus.REJECTED, "لم يتم التحقق من أن حساب المنصة تجريبي")
        }
        if (nowMillis() - accountProof.verifiedAtMillis !in 0..30_000L) {
            return DemoOrderResult(DemoOrderStatus.REJECTED, "تأكيد وضع الحساب التجريبي قديم")
        }
        return port.submit(
            DemoOrderRequest(
                direction = signal.direction,
                amount = plan.stake,
                expirySeconds = plan.expirySeconds,
                symbol = plan.symbol
            )
        )
    }
}
