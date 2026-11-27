package com.astra.autotrader.pine

import org.junit.Assert.*
import org.junit.Test

class PineStrategyReplayEngineTest {
    private fun script(
        direction: String = "strategy.long",
        entryCondition: String = "close > open",
        exitArguments: String? = "stop=99, limit=102",
        quantity: String = ""
    ): PineScriptConverter.Conversion {
        val exitLine = exitArguments?.let {
            "strategy.exit(\"Exit\", from_entry=\"Entry\", " + it + ")"
        } ?: ""
        return PineScriptConverter.convert(
            """
                //@version=5
                strategy("Replay strategy")
                enter = $entryCondition
                strategy.entry("Entry", $direction, $quantity when=enter)
                $exitLine
            """.trimIndent()
        )
    }

    private fun bar(time: Long, open: Double, high: Double, low: Double, close: Double) =
        PineOhlcBar(time, open, high, low, close)

    private fun dataset(vararg bars: PineOhlcBar) = PineOhlcDataset(bars.toList())

    @Test fun fillsMarketEntryAtNextBarOpenAndStopsLongPosition() {
        val converted = script(exitArguments = "stop=99")
        assertTrue(converted.errors.joinToString(), converted.supported)
        val report = PineStrategyReplayEngine.run(converted, dataset(
            bar(1_000, 100.0, 102.0, 99.5, 101.0),
            bar(2_000, 100.0, 101.0, 98.0, 99.0)
        ))
        assertTrue(report.errorArabic ?: "simulation failed", report.success)
        assertEquals(1, report.entrySignals)
        assertEquals(1, report.trades.size)
        val trade = report.trades.single()
        assertEquals(1, trade.entryIndex)
        assertEquals(1, trade.exitIndex)
        assertEquals(100.0, trade.entryPrice, 1e-9)
        assertEquals(99.0, trade.exitPrice, 1e-9)
        assertEquals(PineStrategyExitType.STOP, trade.exitType)
        assertEquals(-1.0, trade.profitPoints, 1e-9)
        assertNull(report.openPosition)
    }

    @Test fun takesLongLimitAtConfiguredPrice() {
        val converted = script(exitArguments = "limit=102")
        val report = PineStrategyReplayEngine.run(converted, dataset(
            bar(1_000, 100.0, 102.0, 99.5, 101.0),
            bar(2_000, 100.0, 103.0, 99.5, 102.5)
        ))
        assertTrue(report.errorArabic ?: "simulation failed", report.success)
        assertEquals(PineStrategyExitType.LIMIT, report.trades.single().exitType)
        assertEquals(102.0, report.trades.single().exitPrice, 1e-9)
        assertEquals(2.0, report.netProfitPoints, 1e-9)
    }

    @Test fun choosesStopFirstAndMarksSameBarStopTargetAmbiguity() {
        val converted = script(exitArguments = "stop=99, limit=102")
        val report = PineStrategyReplayEngine.run(converted, dataset(
            bar(1_000, 100.0, 102.0, 99.5, 101.0),
            bar(2_000, 100.0, 103.0, 98.0, 101.0)
        ))
        assertTrue(report.success)
        assertEquals(1, report.ambiguousExitBars)
        assertEquals(PineStrategyExitType.STOP, report.trades.single().exitType)
        assertTrue(report.trades.single().ambiguousBar)
        assertEquals(-1.0, report.netProfitPoints, 1e-9)
    }

    @Test fun stopGapUsesBarOpenInsteadOfBetterStopPrice() {
        val converted = script(exitArguments = "stop=99")
        val report = PineStrategyReplayEngine.run(converted, dataset(
            bar(1_000, 100.0, 102.0, 99.5, 101.0),
            bar(2_000, 98.0, 99.0, 97.0, 98.5)
        ))
        assertTrue(report.success)
        assertEquals(98.0, report.trades.single().exitPrice, 1e-9)
    }

    @Test fun simulatesShortStopAndUsesShortProfitSign() {
        val converted = script("strategy.short", "close < open", "stop=101, limit=98")
        assertTrue(converted.errors.joinToString(), converted.supported)
        val report = PineStrategyReplayEngine.run(converted, dataset(
            bar(1_000, 100.0, 101.0, 98.0, 99.0),
            bar(2_000, 100.0, 102.0, 99.0, 100.5)
        ))
        assertTrue(report.errorArabic ?: "simulation failed", report.success)
        assertEquals(PineStrategyExitType.STOP, report.trades.single().exitType)
        assertEquals(101.0, report.trades.single().exitPrice, 1e-9)
        assertEquals(-1.0, report.netProfitPoints, 1e-9)
    }

    @Test fun convertsLossAndProfitTicksOnlyWhenTickSizeIsProvided() {
        val converted = script(exitArguments = "loss=10, profit=20")
        assertTrue(converted.errors.joinToString(), converted.supported)
        val bars = dataset(
            bar(1_000, 100.0, 102.0, 99.5, 101.0),
            bar(2_000, 100.0, 101.0, 99.5, 100.5),
            bar(3_000, 100.5, 103.0, 98.0, 101.0)
        )
        val missingTick = PineStrategyReplayEngine.run(converted, bars)
        assertFalse(missingTick.success)
        assertTrue(missingTick.errorArabic!!.contains("tick size"))
        val report = PineStrategyReplayEngine.run(converted, bars, tickSize = 0.1)
        assertTrue(report.errorArabic ?: "simulation failed", report.success)
        assertEquals(1, report.trades.size)
        assertEquals(99.0, report.trades.single().exitPrice, 1e-9)
        assertEquals(PineStrategyExitType.STOP, report.trades.single().exitType)
    }

    @Test fun leavesPositionOpenWhenNoExitOrderIsDefined() {
        val converted = script(exitArguments = null)
        val report = PineStrategyReplayEngine.run(converted, dataset(
            bar(1_000, 100.0, 102.0, 99.5, 101.0),
            bar(2_000, 100.0, 101.0, 99.5, 100.5)
        ))
        assertTrue(report.success)
        assertTrue(report.trades.isEmpty())
        assertNotNull(report.openPosition)
        assertEquals(1, report.openPosition!!.entryIndex)
    }

    @Test fun reportsEntrySignalThatHasNoFollowingBarToFill() {
        val converted = script(exitArguments = null)
        val report = PineStrategyReplayEngine.run(converted, dataset(
            bar(1_000, 100.0, 102.0, 99.5, 101.0)
        ))
        assertTrue(report.success)
        assertEquals(1, report.entrySignals)
        assertEquals(0, report.trades.size)
        assertEquals(0, report.pendingEntrySignalIndex)
        assertNull(report.openPosition)
    }

    @Test fun rejectsMismatchedFromEntryAndUnsupportedQuantity() {
        val mismatched = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Mismatch")
                enter = close > open
                strategy.entry("Entry", strategy.long, when=enter)
                strategy.exit("Exit", from_entry="Other", stop=99)
            """.trimIndent()
        )
        val quantity = script(exitArguments = "stop=99", quantity = "qty=2,")
        val bars = dataset(bar(1_000, 100.0, 102.0, 99.5, 101.0))
        assertTrue(mismatched.supported)
        assertFalse(PineStrategyReplayEngine.run(mismatched, bars).success)
        assertFalse(PineStrategyReplayEngine.run(quantity, bars).success)
    }

    @Test fun rejectsMultipleEntriesAndMultipleExitRules() {
        val multipleEntries = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Multiple")
                longCondition = close > open
                shortCondition = close < open
                strategy.entry("Long", strategy.long, when=longCondition)
                strategy.entry("Short", strategy.short, when=shortCondition)
            """.trimIndent()
        )
        val multipleExits = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Multiple exits")
                enter = close > open
                strategy.entry("Entry", strategy.long, when=enter)
                strategy.exit("Stop", from_entry="Entry", stop=99)
                strategy.exit("Target", from_entry="Entry", limit=102)
            """.trimIndent()
        )
        val bars = dataset(bar(1_000, 100.0, 102.0, 99.5, 101.0))
        assertTrue(multipleEntries.supported)
        assertTrue(multipleExits.supported)
        assertFalse(PineStrategyReplayEngine.run(multipleEntries, bars).success)
        assertFalse(PineStrategyReplayEngine.run(multipleExits, bars).success)
    }

    @Test fun rejectsInvalidOhlcAndWrongSideAbsoluteLevels() {
        val invalidBars = dataset(bar(1_000, 100.0, 99.0, 99.5, 101.0))
        val wrongSide = script(exitArguments = "stop=102")
        val wrongSideBars = dataset(bar(1_000, 100.0, 103.0, 99.5, 101.0))
        assertFalse(PineStrategyReplayEngine.run(script(), invalidBars).success)
        val report = PineStrategyReplayEngine.run(wrongSide, wrongSideBars)
        assertFalse(report.success)
        assertTrue(report.errorArabic!!.contains("stop"))
    }

    @Test fun rejectsIndicatorAndUnconditionalEntry() {
        val indicator = PineScriptConverter.convert(
            """
                //@version=5
                indicator("Indicator")
                alertcondition(close > open, title="CALL")
            """.trimIndent()
        )
        val unconditional = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Always")
                strategy.entry("Entry", strategy.long)
            """.trimIndent()
        )
        val bars = dataset(bar(1_000, 100.0, 102.0, 99.5, 101.0))
        assertFalse(PineStrategyReplayEngine.run(indicator, bars).success)
        val report = PineStrategyReplayEngine.run(unconditional, bars)
        assertFalse(report.success)
        assertTrue(report.errorArabic!!.contains("غير المشروط"))
    }

    @Test fun comparesRawEntryConditionsAgainstReferenceSignalsNotTradeFills() {
        val converted = script(exitArguments = null)
        val bars = listOf(
            bar(20_000, 100.0, 102.0, 99.5, 101.0),
            bar(21_000, 101.0, 102.0, 100.0, 100.5),
            bar(22_000, 100.5, 102.0, 100.0, 101.5)
        )
        val references = listOf(
            PineSignalAction.CALL,
            PineSignalAction.WAIT,
            PineSignalAction.PUT
        )

        val report = PineStrategyReplayEngine.run(
            converted,
            PineOhlcDataset(bars, expectedSignals = references)
        )

        assertTrue(report.errorArabic ?: "simulation failed", report.success)
        assertEquals(1, report.entrySignals)
        assertEquals(3, report.comparedBars)
        assertEquals(2, report.matchedBars)
        assertEquals(1, report.mismatchCount)
        assertEquals(0, report.unavailableComparisonBars)
        assertEquals(66.666666, report.matchRatePercent!!, 0.001)
        assertEquals(2, report.mismatchSamples.single().index)
        assertEquals(PineSignalAction.PUT, report.mismatchSamples.single().expected)
        assertEquals(PineSignalAction.CALL, report.mismatchSamples.single().actual)
    }

    @Test fun excludesUnavailableWarmupConditionsFromReferenceComparison() {
        val converted = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Warmup parity")
                enter = ta.sma(close, 3) > close
                strategy.entry("Entry", strategy.long, when=enter)
            """.trimIndent()
        )
        assertTrue(converted.errors.joinToString(), converted.supported)
        val bars = listOf(
            bar(30_000, 1.0, 1.1, 0.9, 1.0),
            bar(31_000, 1.0, 3.1, 0.9, 3.0),
            bar(32_000, 3.0, 3.1, 0.9, 1.0)
        )
        val references = listOf(
            PineSignalAction.CALL,
            PineSignalAction.PUT,
            PineSignalAction.CALL
        )

        val report = PineStrategyReplayEngine.run(
            converted,
            PineOhlcDataset(bars, expectedSignals = references)
        )

        assertTrue(report.errorArabic ?: "simulation failed", report.success)
        assertEquals(2, report.unavailableComparisonBars)
        assertEquals(1, report.comparedBars)
        assertEquals(1, report.matchedBars)
        assertEquals(0, report.mismatchCount)
        assertEquals(100.0, report.matchRatePercent!!, 0.0)
    }

    @Test fun parsesConditionalStrategyCloseAndFillsAtNextBarOpen() {
        val converted = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Conditional close")
                enter = close > open
                leave = close < open
                strategy.entry("Entry", strategy.long, when=enter)
                if leave
                    strategy.close("Entry", comment="Exit condition")
            """.trimIndent()
        )
        assertTrue(converted.errors.joinToString(), converted.supported)
        assertEquals(1, converted.closes.size)
        assertEquals("Entry", converted.closes.single().id)
        assertEquals("leave", converted.closes.single().conditionText)

        val report = PineStrategyReplayEngine.run(converted, dataset(
            bar(1_000, 100.0, 102.0, 99.5, 101.0),
            bar(2_000, 102.0, 103.0, 101.0, 101.5),
            bar(3_000, 101.0, 102.0, 100.0, 101.25)
        ))
        assertTrue(report.errorArabic ?: "simulation failed", report.success)
        assertEquals(1, report.entrySignals)
        assertEquals(1, report.trades.size)
        val trade = report.trades.single()
        assertEquals(1, trade.entryIndex)
        assertEquals(2, trade.exitIndex)
        assertEquals(102.0, trade.entryPrice, 1e-9)
        assertEquals(101.0, trade.exitPrice, 1e-9)
        assertEquals(PineStrategyExitType.MARKET_CLOSE, trade.exitType)
        assertEquals(-1.0, trade.profitPoints, 1e-9)
        assertNull(report.openPosition)
        assertNull(report.pendingCloseSignalIndex)
    }

    @Test fun keepsConditionalClosePendingWhenNoFollowingBarExists() {
        val converted = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Pending close")
                enter = close > open
                leave = close < open
                strategy.entry("Entry", strategy.long, when=enter)
                if leave
                    strategy.close("Entry")
            """.trimIndent()
        )
        val report = PineStrategyReplayEngine.run(converted, dataset(
            bar(1_000, 100.0, 102.0, 99.5, 101.0),
            bar(2_000, 102.0, 103.0, 101.0, 101.5)
        ))
        assertTrue(report.errorArabic ?: "simulation failed", report.success)
        assertNotNull(report.openPosition)
        assertEquals(1, report.pendingCloseSignalIndex)
        assertTrue(report.trades.isEmpty())
    }

    @Test fun rejectsCloseWithMismatchedEntryIdOrCombinedExitOrder() {
        val mismatched = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Mismatched close")
                enter = close > open
                strategy.entry("Entry", strategy.long, when=enter)
                strategy.close("Other")
            """.trimIndent()
        )
        val combined = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Combined exits")
                enter = close > open
                strategy.entry("Entry", strategy.long, when=enter)
                strategy.exit("Risk", from_entry="Entry", stop=99)
                strategy.close("Entry")
            """.trimIndent()
        )
        assertTrue(mismatched.errors.joinToString(), mismatched.supported)
        assertTrue(combined.errors.joinToString(), combined.supported)
        val bars = dataset(bar(1_000, 100.0, 102.0, 99.5, 101.0))
        assertTrue(PineStrategyReplayEngine.run(mismatched, bars).errorArabic!!.contains("لا يطابق"))
        assertTrue(PineStrategyReplayEngine.run(combined, bars).errorArabic!!.contains("الجمع"))
    }

    @Test fun rejectsUnsupportedStrategyCloseSizingAndImmediateExecution() {
        val quantity = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Close quantity")
                enter = close > open
                strategy.entry("Entry", strategy.long, when=enter)
                strategy.close("Entry", qty=1)
            """.trimIndent()
        )
        val immediate = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Immediate close")
                enter = close > open
                strategy.entry("Entry", strategy.long, when=enter)
                strategy.close("Entry", immediately=true)
            """.trimIndent()
        )
        assertFalse(quantity.supported)
        assertTrue(quantity.errors.joinToString().contains("qty"))
        assertFalse(immediate.supported)
        assertTrue(immediate.errors.joinToString().contains("immediately=false"))
    }

    @Test fun rejectsStrategyCloseInsideIndicator() {
        val converted = PineScriptConverter.convert(
            """
                //@version=5
                indicator("Invalid close")
                strategy.close("Entry")
            """.trimIndent()
        )
        assertFalse(converted.supported)
        assertTrue(converted.errors.joinToString().contains("داخل indicator"))
    }


    @Test fun combinesMultipleConditionalCloseRulesForSameEntryWithOrSemantics() {
        val converted = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Multiple conditional closes")
                enter = close > open
                firstExit = close < open and close < 100
                secondExit = close < open
                strategy.entry("Entry", strategy.long, when=enter)
                if firstExit
                    strategy.close("Entry", comment="First condition")
                if secondExit
                    strategy.close("Entry", comment="Second condition")
            """.trimIndent()
        )
        assertTrue(converted.errors.joinToString(), converted.supported)
        assertEquals(2, converted.closes.size)
        assertTrue(converted.closes.all { it.id == "Entry" })

        val report = PineStrategyReplayEngine.run(converted, dataset(
            bar(1_000, 100.0, 102.0, 99.5, 101.0),
            bar(2_000, 102.0, 103.0, 100.0, 101.5),
            bar(3_000, 101.0, 102.0, 100.0, 101.25)
        ))

        assertTrue(report.errorArabic ?: "simulation failed", report.success)
        assertEquals(1, report.entrySignals)
        assertEquals(1, report.trades.size)
        val trade = report.trades.single()
        assertEquals(1, trade.entryIndex)
        assertEquals(2, trade.exitIndex)
        assertEquals(101.0, trade.exitPrice, 1e-9)
        assertEquals(PineStrategyExitType.MARKET_CLOSE, trade.exitType)
        assertTrue(report.warnings.any { it.contains("منطق OR") })
        assertNull(report.openPosition)
        assertNull(report.pendingCloseSignalIndex)
    }

    @Test fun rejectsMultipleConditionalCloseRulesWithDifferentEntryIds() {
        val converted = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Different close ids")
                enter = close > open
                leave = close < open
                strategy.entry("Entry", strategy.long, when=enter)
                if leave
                    strategy.close("Entry")
                if leave
                    strategy.close("Other")
            """.trimIndent()
        )
        assertFalse(converted.supported)
        assertTrue(converted.errors.any { it.contains("اختلاف معرّفات الإغلاق") })
    }


    @Test fun conditionalExitUpdatesOnlyWhenGuardIsTrueAndKeepsLastOrderWhenFalse() {
        val converted = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Conditional exit persistence")
                enter = close > open
                armExit = close > open
                strategy.entry("Entry", strategy.long, when=enter)
                if armExit
                    strategy.exit("Exit", from_entry="Entry", stop=close - 1)
            """.trimIndent()
        )
        assertTrue(converted.errors.joinToString(), converted.supported)
        assertEquals("close > open", converted.exits.single().guardText)

        val report = PineStrategyReplayEngine.run(converted, dataset(
            bar(1_000, 100.0, 102.0, 99.5, 101.0),
            bar(2_000, 100.5, 102.0, 100.2, 101.5),
            bar(3_000, 101.0, 102.0, 100.0, 100.5)
        ))

        assertTrue(report.errorArabic ?: "simulation failed", report.success)
        assertEquals(1, report.entrySignals)
        assertEquals(1, report.trades.size)
        val trade = report.trades.single()
        assertEquals(1, trade.entryIndex)
        assertEquals(2, trade.exitIndex)
        assertEquals(PineStrategyExitType.STOP, trade.exitType)
        assertEquals(100.5, trade.exitPrice, 1e-9)
        assertTrue(report.warnings.any { it.contains("يبقى آخر أمر خروج") })
    }

    @Test fun conditionalExitCanBePlacedAfterTheEntryBarWhenGuardWasInitiallyFalse() {
        val converted = PineScriptConverter.convert(
            """
                //@version=5
                strategy("Delayed conditional exit")
                enter = close > open
                armExit = close < open
                strategy.entry("Entry", strategy.long, when=enter)
                if armExit
                    strategy.exit("Exit", from_entry="Entry", stop=close - 1)
            """.trimIndent()
        )
        assertTrue(converted.errors.joinToString(), converted.supported)

        val report = PineStrategyReplayEngine.run(converted, dataset(
            bar(1_000, 100.0, 102.0, 99.5, 101.0),
            bar(2_000, 100.5, 101.0, 100.2, 100.0),
            bar(3_000, 100.0, 100.5, 98.5, 99.5)
        ))

        assertTrue(report.errorArabic ?: "simulation failed", report.success)
        assertEquals(1, report.trades.size)
        val trade = report.trades.single()
        assertEquals(1, trade.entryIndex)
        assertEquals(2, trade.exitIndex)
        assertEquals(PineStrategyExitType.STOP, trade.exitType)
        assertEquals(99.0, trade.exitPrice, 1e-9)
    }

}
