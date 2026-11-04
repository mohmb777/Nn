package com.astra.autotrader.engine

import org.junit.Assert.*
import org.junit.Test

class AstraStrategyEngineTest {
    private fun candles(count: Int = 40): List<Candle> {
        var price = 1.1000
        return (0 until count).map { index ->
            val open = price
            price += if (index % 2 == 0) 0.00012 else -0.00010
            Candle(index * 60_000L, open, maxOf(open, price) + 0.00002,
                minOf(open, price) - 0.00002, price, true)
        }
    }

    @Test fun requiresEnoughClosedCandles() {
        assertNull(AstraStrategyEngine.evaluate(candles(20)))
    }

    @Test fun ignoresUnclosedCandles() {
        val input = candles() + candles(1).single().copy(timestamp = 99_000_000L, isClosed = false)
        val result = AstraStrategyEngine.evaluate(input)
        assertNotNull(result)
        assertEquals(39 * 60_000L, result!!.candleTimestamp)
    }

    @Test fun rejectsInvalidOrOutOfOrderCandles() {
        val valid = candles()
        val malformed = valid.toMutableList().apply {
            this[20] = this[20].copy(high = 0.5)
        }
        assertNull(AstraStrategyEngine.evaluate(malformed))

        val unordered = valid.toMutableList().apply {
            this[20] = this[20].copy(timestamp = this[19].timestamp)
        }
        assertNull(AstraStrategyEngine.evaluate(unordered))
    }

    @Test fun emitsOnlyDefinedDirectionsAndArabicReason() {
        val result = AstraStrategyEngine.evaluate(candles())
        assertNotNull(result)
        assertTrue(result!!.direction in TradeDirection.values())
        assertTrue(result.reasonArabic.isNotBlank())
        assertTrue(result.emaFast.isFinite())
        assertTrue(result.emaSlow.isFinite())
        assertTrue(result.rsi in 0.0..100.0)
    }
}
