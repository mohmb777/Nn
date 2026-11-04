package com.astra.autotrader.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TradingPlanTest {
    private val signal = TradingSignal(
        TradeDirection.CALL, "macd_12_26_9", "اختبار", 1000L
    )

    @Test fun expiryMatchesSelectedChartTimeframe() {
        val plan = TradingPlan("macd_12_26_9", "EURUSD", ChartTimeframe.M5, 1.0)
        assertEquals(300, plan.expirySeconds)
    }

    @Test fun rejectsMissingDemoAccountProof() = runBlocking {
        val coordinator = DemoOrderCoordinator(DisabledDemoExecutionPort()) { 10_000L }
        val plan = TradingPlan("macd_12_26_9", "EURUSD", ChartTimeframe.M1, 1.0)
        val result = coordinator.execute(plan, signal, null)
        assertEquals(DemoOrderStatus.REJECTED, result.status)
    }

    @Test fun rejectsRealAccountProof() = runBlocking {
        val coordinator = DemoOrderCoordinator(DisabledDemoExecutionPort()) { 10_000L }
        val plan = TradingPlan("macd_12_26_9", "EURUSD", ChartTimeframe.M1, 1.0)
        val proof = DemoAccountProof(AccountMode.REAL, 10_000L, "test")
        assertEquals(DemoOrderStatus.REJECTED, coordinator.execute(plan, signal, proof).status)
    }

    @Test fun disabledAdapterNeverClaimsOrderWasPlaced() = runBlocking {
        val adapter = DisabledDemoExecutionPort()
        val result = adapter.submit(DemoOrderRequest(TradeDirection.CALL, 1.0, 60, "EURUSD"))
        assertEquals(DemoOrderStatus.REJECTED, result.status)
        assertNull(result.externalOrderId)
    }

    @Test fun rejectsWaitSignalAndInvalidPlan() = runBlocking {
        val coordinator = DemoOrderCoordinator(DisabledDemoExecutionPort()) { 10_000L }
        val proof = DemoAccountProof(AccountMode.DEMO, 10_000L, "manual test")
        val invalidPlan = TradingPlan("", "EURUSD", ChartTimeframe.M1, 1.0)
        assertEquals(
            DemoOrderStatus.REJECTED,
            coordinator.execute(invalidPlan, signal, proof).status
        )
        val validPlan = TradingPlan("macd_12_26_9", "EURUSD", ChartTimeframe.M1, 1.0)
        val wait = signal.copy(direction = TradeDirection.WAIT)
        assertEquals(DemoOrderStatus.REJECTED, coordinator.execute(validPlan, wait, proof).status)
    }
}
