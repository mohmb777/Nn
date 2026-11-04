package com.astra.autotrader.engine

import org.junit.Assert.*
import org.junit.Test

class PaperTradingEngineTest {
    @Test fun blocksSecondConcurrentPaperTrade() {
        val engine = PaperTradingEngine(RiskConfig(100.0, 2.0, 5.0))
        assertNotNull(engine.open(TradeDirection.CALL, 1.25, 1000L, "test"))
        assertNull(engine.open(TradeDirection.PUT, 1.24, 2000L, "test"))
    }

    @Test fun stopsAfterConfiguredConsecutiveLosses() {
        val engine = PaperTradingEngine(RiskConfig(100.0, 2.0, 5.0, maxConsecutiveLosses = 2))
        repeat(2) {
            val trade = engine.open(TradeDirection.CALL, 1.2, it.toLong(), "test")!!
            assertTrue(engine.settle(trade.id, false, 0.8))
        }
        assertTrue(engine.snapshot().halted)
        assertNull(engine.open(TradeDirection.CALL, 1.2, 3L, "test"))
    }

    @Test fun rejectsInvalidPayoutAndUnknownTrade() {
        val engine = PaperTradingEngine(RiskConfig(100.0, 2.0, 5.0))
        val trade = engine.open(TradeDirection.PUT, 1.2, 1L, "test")!!
        assertFalse(engine.settle(trade.id, true, -0.1))
        assertFalse(engine.settle(999L, true, 0.8))
        assertEquals(100.0, engine.snapshot().balance, 0.0001)
    }

    @Test fun signalGateRejectsDisconnectedOrStaleData() {
        val candle = Candle(1000L, 1.0, 1.2, 0.9, 1.1, true)
        val disconnected = DataSourceStatus(ConnectionState.DISCONNECTED, "off", 1000L)
        assertFalse(SignalGate.validate(listOf(candle), disconnected, 1100L).allowed)
        val connectedButStale = DataSourceStatus(ConnectionState.CONNECTED, "on", 1L)
        assertFalse(SignalGate.validate(listOf(candle), connectedButStale, 100_000L).allowed)
    }
}
