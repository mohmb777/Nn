package com.astra.autotrader.pine

import com.astra.autotrader.engine.PaperTradingEngine
import com.astra.autotrader.engine.RiskConfig
import com.astra.autotrader.engine.TradeDirection
import org.junit.Assert.*
import org.junit.Test

class PinePaperTradingBridgeTest {
    private fun engine() = PaperTradingEngine(
        RiskConfig(startingBalance = 100.0, stake = 2.0, maxStake = 5.0)
    )

    private fun callScript() = PineScriptConverter.convert(
        """
            //@version=5
            indicator("Paper bridge")
            alertcondition(close > open, title="CALL")
        """.trimIndent()
    )

    private fun bars(vararg items: PineOhlcBar) = items.toList()

    @Test fun opensLocalPaperTradeOnlyAfterClosedCallBar() {
        val paper = engine()
        val bridge = PinePaperTradingBridge(paper)
        val data = bars(PineOhlcBar(1_000, 1.10, 1.12, 1.09, 1.11))

        val forming = bridge.processClosedBar("pine-1", callScript(), data, 0, isClosed = false)
        assertEquals(PinePaperStatus.BAR_NOT_CLOSED, forming.status)
        assertTrue(paper.snapshot().trades.isEmpty())

        val closed = bridge.processClosedBar("pine-1", callScript(), data, 0, isClosed = true)
        assertEquals(PinePaperStatus.OPENED, closed.status)
        assertEquals(TradeDirection.CALL, closed.trade?.direction)
        assertEquals(1.11, closed.trade?.entryPrice ?: 0.0, 1e-9)
        assertEquals(1, paper.snapshot().trades.size)
    }

    @Test fun conditionalStrategyEntryCanOpenLocalPaperTrade() {
        val paper = engine()
        val bridge = PinePaperTradingBridge(paper)
        val script = PineScriptConverter.convert(
            """
                //@version=5
                strategy("One entry")
                strategy.entry("Long", strategy.long, when=close > open)
            """.trimIndent()
        )
        val data = bars(PineOhlcBar(1_250, 1.10, 1.12, 1.09, 1.11))

        assertTrue(script.errors.joinToString("\n"), script.supported)
        val result = bridge.processClosedBar("single-entry", script, data, 0, true)
        assertEquals(PinePaperStatus.OPENED, result.status)
        assertEquals(TradeDirection.CALL, result.trade?.direction)
    }

    @Test fun putSignalMapsToPutPaperTrade() {
        val paper = engine()
        val bridge = PinePaperTradingBridge(paper)
        val script = PineScriptConverter.convert(
            """
                //@version=5
                indicator("PUT")
                alertcondition(close < open, title="PUT")
            """.trimIndent()
        )
        val data = bars(PineOhlcBar(1_500, 1.11, 1.12, 1.08, 1.09))

        val result = bridge.processClosedBar("put-script", script, data, 0, true)
        assertEquals(PinePaperStatus.OPENED, result.status)
        assertEquals(TradeDirection.PUT, result.trade?.direction)
    }

    @Test fun sameClosedCandleCannotOpenTwice() {
        val paper = engine()
        val bridge = PinePaperTradingBridge(paper)
        val data = bars(PineOhlcBar(2_000, 1.10, 1.12, 1.09, 1.11))

        assertEquals(
            PinePaperStatus.OPENED,
            bridge.processClosedBar("pine-1", callScript(), data, 0, true).status
        )
        assertEquals(
            PinePaperStatus.DUPLICATE_BAR,
            bridge.processClosedBar("pine-1", callScript(), data, 0, true).status
        )
        assertEquals(1, paper.snapshot().trades.size)
    }

    @Test fun waitSignalIsAlsoConsumedOncePerCandle() {
        val paper = engine()
        val bridge = PinePaperTradingBridge(paper)
        val script = PineScriptConverter.convert(
            """
                //@version=5
                indicator("Wait")
                alertcondition(close > open, title="CALL")
            """.trimIndent()
        )
        val data = bars(PineOhlcBar(3_000, 1.12, 1.13, 1.09, 1.10))

        assertEquals(PinePaperStatus.WAIT, bridge.processClosedBar("wait", script, data, 0, true).status)
        assertEquals(PinePaperStatus.DUPLICATE_BAR, bridge.processClosedBar("wait", script, data, 0, true).status)
        assertTrue(paper.snapshot().trades.isEmpty())
    }

    @Test fun onlyOneOpenPaperTradeIsAllowed() {
        val paper = engine()
        val bridge = PinePaperTradingBridge(paper)
        val first = bars(PineOhlcBar(4_000, 1.10, 1.12, 1.09, 1.11))
        val second = bars(
            PineOhlcBar(4_000, 1.10, 1.12, 1.09, 1.11),
            PineOhlcBar(5_000, 1.10, 1.12, 1.09, 1.11)
        )

        assertEquals(PinePaperStatus.OPENED, bridge.processClosedBar("one", callScript(), first, 0, true).status)
        assertEquals(PinePaperStatus.OPEN_TRADE_EXISTS, bridge.processClosedBar("two", callScript(), second, 1, true).status)
        assertEquals(1, paper.snapshot().trades.size)
    }

    @Test fun haltedPaperAccountRejectsNewSignal() {
        val paper = engine()
        repeat(2) { index ->
            val trade = paper.open(TradeDirection.CALL, 1.10, index.toLong() + 1L, "manual")!!
            assertTrue(paper.settle(trade.id, won = false, payoutRate = 0.8))
        }
        assertTrue(paper.snapshot().halted)

        val bridge = PinePaperTradingBridge(paper)
        val data = bars(PineOhlcBar(6_000, 1.10, 1.12, 1.09, 1.11))
        assertEquals(PinePaperStatus.RISK_HALTED, bridge.processClosedBar("halted", callScript(), data, 0, true).status)
        assertTrue(paper.snapshot().trades.isEmpty())
    }

    @Test fun invalidAndOutOfOrderOhlcAreRejected() {
        val bridge = PinePaperTradingBridge(engine())
        val invalid = bars(PineOhlcBar(7_000, 1.10, 1.05, 1.09, 1.11))
        assertEquals(
            PinePaperStatus.INVALID_INPUT,
            bridge.processClosedBar("bad", callScript(), invalid, 0, true).status
        )

        val unordered = bars(
            PineOhlcBar(8_000, 1.10, 1.12, 1.09, 1.11),
            PineOhlcBar(7_500, 1.10, 1.12, 1.09, 1.11)
        )
        assertEquals(
            PinePaperStatus.INVALID_INPUT,
            bridge.processClosedBar("unordered", callScript(), unordered, 1, true).status
        )
    }

    @Test fun unsupportedExitAndQuantityRulesAreNotSilentlyIgnored() {
        val paper = engine()
        val bridge = PinePaperTradingBridge(paper)
        val exitScript = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Exit")
                enter = close > open
                strategy.entry("Long", strategy.long, when=enter)
                strategy.exit("Close", from_entry="Long", stop=close - 0.01)
            """.trimIndent()
        )
        val quantityScript = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Quantity")
                strategy.entry("Long", strategy.long, qty=2, when=close > open)
            """.trimIndent()
        )
        val data = bars(PineOhlcBar(9_000, 1.10, 1.12, 1.09, 1.11))

        assertEquals(
            PinePaperStatus.UNSUPPORTED_EXIT_RULES,
            bridge.processClosedBar("exit", exitScript, data, 0, true).status
        )
        assertEquals(
            PinePaperStatus.UNSUPPORTED_QUANTITY,
            bridge.processClosedBar("quantity", quantityScript, data, 0, true).status
        )
        assertTrue(paper.snapshot().trades.isEmpty())
    }

    @Test fun unconditionalAndMultipleEntryOrdersAreRejected() {
        val bridge = PinePaperTradingBridge(engine())
        val unconditional = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Always on")
                strategy.entry("Long", strategy.long)
            """.trimIndent()
        )
        val multiple = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Two entries")
                longCondition = close > open
                shortCondition = close < open
                strategy.entry("Long", strategy.long, when=longCondition)
                strategy.entry("Short", strategy.short, when=shortCondition)
            """.trimIndent()
        )
        val data = bars(PineOhlcBar(9_500, 1.10, 1.12, 1.09, 1.11))

        assertEquals(
            PinePaperStatus.UNSUPPORTED_ENTRY_SEMANTICS,
            bridge.processClosedBar("always", unconditional, data, 0, true).status
        )
        assertEquals(
            PinePaperStatus.UNSUPPORTED_ENTRY_SEMANTICS,
            bridge.processClosedBar("multiple", multiple, data, 0, true).status
        )
    }

    @Test fun olderCandleCannotBeProcessedAfterNewerCandle() {
        val paper = engine()
        val bridge = PinePaperTradingBridge(paper)
        val first = bars(
            PineOhlcBar(10_000, 1.10, 1.12, 1.09, 1.11),
            PineOhlcBar(11_000, 1.10, 1.12, 1.09, 1.11)
        )
        assertEquals(PinePaperStatus.OPENED, bridge.processClosedBar("ordered", callScript(), first, 1, true).status)

        val old = bars(PineOhlcBar(10_000, 1.10, 1.12, 1.09, 1.11))
        assertEquals(PinePaperStatus.STALE_BAR, bridge.processClosedBar("ordered", callScript(), old, 0, true).status)
    }
}
