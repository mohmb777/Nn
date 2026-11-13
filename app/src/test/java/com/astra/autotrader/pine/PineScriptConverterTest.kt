package com.astra.autotrader.pine

import org.junit.Assert.*
import org.junit.Test

class PineScriptConverterTest {
    @Test fun parsesIndicatorAndCompilesCallSignal() {
        val result = PineScriptConverter.convert("""
            //@version=5
            indicator("EMA Signals", overlay=true)
            fast = ta.ema(close, 9)
            slow = ta.ema(close, 21)
            alertcondition(ta.crossover(fast, slow), title="CALL", message="up")
        """.trimIndent())
        assertTrue(result.supported)
        assertEquals(PineScriptConverter.ScriptKind.INDICATOR, result.kind)
        assertEquals("EMA Signals", result.title)
        assertEquals(1, result.signals.size)
        assertEquals(PineSignalDirection.CALL, result.signals[0].direction)
        assertNotNull(result.signals[0].compiledExpression)
        assertEquals(2, result.compiledVariables.size)
    }

    @Test fun identifiesStrategyAndPlotShapeCondition() {
        val result = PineScriptConverter.convert("""
            //@version=5
            strategy("Simple", overlay=true)
            longCondition = ta.crossover(ta.ema(close, 9), ta.ema(close, 21))
            plotshape(longCondition, style=shape.triangleup)
        """.trimIndent())
        assertTrue(result.supported)
        assertEquals(PineScriptConverter.ScriptKind.STRATEGY, result.kind)
        assertEquals(1, result.signals.size)
        assertEquals(PineSignalDirection.UNKNOWN, result.signals.single().direction)
        assertTrue(result.warnings.any { it.contains("WAIT") || it.contains("CALL أو PUT") })
    }

    @Test fun rejectsUnsupportedCallsAndMissingDeclaration() {
        val result = PineScriptConverter.convert("""
            //@version=5
            x = request.security(syminfo.tickerid, "5", close)
        """.trimIndent())
        assertFalse(result.supported)
        assertTrue(result.errors.any { it.contains("indicator()") })
        assertTrue(result.errors.any { it.contains("request.security") })
    }

    @Test fun rejectsUnsupportedVersionAndOversizedInput() {
        assertFalse(PineScriptConverter.convert("//@version=4\nindicator(\"x\")").supported)
        assertFalse(PineScriptConverter.convert("x".repeat(100_001)).supported)
    }

    @Test fun emptySourceReturnsArabicError() {
        val result = PineScriptConverter.convert(" ")
        assertFalse(result.supported)
        assertTrue(result.errors.first().contains("أدخل"))
    }

    @Test fun inputDefaultsAndSmaAreEvaluated() {
        val result = PineScriptConverter.convert("""
            //@version=5
            indicator("Inputs")
            period = input.int(2, "Period", minval=1)
            average = ta.sma(close, period)
            alertcondition(average > open, title="CALL")
        """.trimIndent())
        assertTrue(result.supported)
        val bars = listOf(
            PineOhlcBar(1, 9.0, 10.0, 8.0, 10.0),
            PineOhlcBar(2, 10.0, 12.0, 9.0, 12.0)
        )
        val signal = PineScriptEvaluator(bars, result.compiledVariables).signalAt(result, 1)
        assertEquals(PineSignalAction.CALL, signal.action)
    }

    @Test fun genericInputDefaultIsEvaluated() {
        val result = PineScriptConverter.convert("""
            //@version=5
            indicator("Generic input")
            period = input(2, "Period")
            average = ta.sma(close, period)
            alertcondition(average > open, title="CALL")
        """.trimIndent())
        assertTrue(result.errors.joinToString("\n"), result.supported)
        val bars = listOf(
            PineOhlcBar(1, 9.0, 10.0, 8.0, 10.0),
            PineOhlcBar(2, 10.0, 12.0, 9.0, 12.0)
        )
        assertEquals(PineSignalAction.CALL, PineScriptEvaluator(bars, result.compiledVariables).signalAt(result, 1).action)
    }

    @Test fun parserHonorsArithmeticAndBooleanPrecedence() {
        val expression = PineExpressionParser.parse("close > open and (close - open) / open > 0.01")
        val bars = listOf(PineOhlcBar(1, 100.0, 103.0, 99.0, 102.0))
        val value = PineScriptEvaluator(bars, emptyMap()).evaluate(expression, 0)
        assertEquals(PineValue.Boolean(true), value)
    }

    @Test fun crossoverUsesPreviousAndCurrentBars() {
        val result = PineScriptConverter.convert("""
            //@version=5
            indicator("Cross", overlay=true)
            fast = ta.ema(close, 1)
            slow = ta.sma(close, 2)
            alertcondition(ta.crossover(fast, slow), title="CALL")
        """.trimIndent())
        assertTrue(result.supported)
        val bars = listOf(
            PineOhlcBar(1, 10.0, 10.0, 10.0, 10.0),
            PineOhlcBar(2, 9.0, 9.0, 9.0, 9.0),
            PineOhlcBar(3, 12.0, 12.0, 12.0, 12.0)
        )
        val signal = PineScriptEvaluator(bars, result.compiledVariables).signalAt(result, 2)
        assertEquals(PineSignalAction.CALL, signal.action)
    }

    @Test fun unknownDirectionAndConflictingRulesNeverGuess() {
        val result = PineScriptConverter.convert("""
            //@version=5
            indicator("Ambiguous", overlay=true)
            alertcondition(close > open, title="UP SETUP")
            alertcondition(close < open, title="PUT")
        """.trimIndent())
        assertTrue(result.supported)
        val bars = listOf(PineOhlcBar(1, 10.0, 12.0, 9.0, 11.0))
        val signal = PineScriptEvaluator(bars, result.compiledVariables).signalAt(result, 0)
        assertEquals(PineSignalAction.WAIT, signal.action)
    }

    @Test fun supportsMacdTupleAssignments() {
        val result = PineScriptConverter.convert("""
            //@version=5
            indicator("MACD", overlay=false)
            [macdLine, signalLine, histLine] = ta.macd(close, 12, 26, 9)
            alertcondition(ta.crossover(macdLine, signalLine), title="CALL")
        """.trimIndent())
        assertTrue(result.supported)
        assertEquals(3, result.compiledVariables.size)
        assertNotNull(result.signals.single().compiledExpression)
    }

    @Test fun crossunderProducesPutWhenTitleIsPut() {
        val result = PineScriptConverter.convert("""
            //@version=5
            indicator("Crossunder")
            fast = ta.ema(close, 1)
            slow = ta.sma(close, 2)
            alertcondition(ta.crossunder(fast, slow), title="PUT")
        """.trimIndent())
        assertTrue(result.supported)
        val bars = listOf(
            PineOhlcBar(1, 12.0, 12.0, 12.0, 12.0),
            PineOhlcBar(2, 13.0, 13.0, 13.0, 13.0),
            PineOhlcBar(3, 10.0, 10.0, 10.0, 10.0)
        )
        assertEquals(PineSignalAction.PUT, PineScriptEvaluator(bars, result.compiledVariables).signalAt(result, 2).action)
    }

    @Test fun macdTupleIsEvaluatedAgainstTheSameOhlcBars() {
        val result = PineScriptConverter.convert("""
            //@version=5
            indicator("MACD")
            [macdLine, signalLine, histLine] = ta.macd(close, 2, 3, 2)
            alertcondition(macdLine > signalLine, title="CALL")
        """.trimIndent())
        assertTrue(result.supported)
        val bars = (1L..5L).map { n ->
            val close = n.toDouble()
            PineOhlcBar(n, close, close, close, close)
        }
        assertEquals(PineSignalAction.CALL, PineScriptEvaluator(bars, result.compiledVariables).signalAt(result, 4).action)
    }

    @Test fun plotshapeTitleCanDeclareDirection() {
        val result = PineScriptConverter.convert("""
            //@version=5
            indicator("Shape")
            plotshape(ta.crossover(ta.ema(close, 2), ta.sma(close, 3)), title="CALL")
        """.trimIndent())
        assertTrue(result.supported)
        assertEquals(PineSignalDirection.CALL, result.signals.single().direction)
    }

    @Test fun strategyEntryIsCompiledAsAConditionNotExecuted() {
        val result = PineScriptConverter.convert("""
            //@version=5
            strategy("Order")
            enterLong = close > open
            strategy.entry("Long", strategy.long, when=enterLong)
        """.trimIndent())
        assertTrue(result.errors.joinToString("\n"), result.supported)
        assertEquals(1, result.entries.size)
        assertEquals("enterLong", result.entries.single().conditionText)
    }

    @Test fun rsiEvaluatesOnlyAfterItsWarmupWindow() {
        val result = PineScriptConverter.convert("""
            //@version=5
            indicator("RSI")
            strength = ta.rsi(close, 2)
            alertcondition(strength > 70, title="CALL")
        """.trimIndent())
        assertTrue(result.supported)
        val bars = listOf(
            PineOhlcBar(1, 10.0, 10.0, 10.0, 10.0),
            PineOhlcBar(2, 11.0, 11.0, 11.0, 11.0),
            PineOhlcBar(3, 12.0, 12.0, 12.0, 12.0)
        )
        val evaluator = PineScriptEvaluator(bars, result.compiledVariables)
        assertEquals(PineSignalAction.CALL, evaluator.signalAt(result, 2).action)
    }

    @Test fun rsiUsesOneChangePerBarAndWilderSmoothing() {
        val result = PineScriptConverter.convert("""
            //@version=5
            indicator("RSI Wilder")
            strength = ta.rsi(close, 2)
            alertcondition(strength > 70, title="CALL")
        """.trimIndent())
        assertTrue(result.supported)
        val bars = listOf(
            PineOhlcBar(1, 10.0, 10.0, 10.0, 10.0),
            PineOhlcBar(2, 11.0, 11.0, 11.0, 11.0),
            PineOhlcBar(3, 10.0, 10.0, 10.0, 10.0),
            PineOhlcBar(4, 12.0, 12.0, 12.0, 12.0)
        )
        val evaluator = PineScriptEvaluator(bars, result.compiledVariables)
        assertNull(evaluator.evaluate(PineExpr.Identifier("strength"), 1))
        val seeded = evaluator.evaluate(PineExpr.Identifier("strength"), 2) as PineValue.Number
        assertEquals(50.0, seeded.value, 1e-6)
        val smoothed = evaluator.evaluate(PineExpr.Identifier("strength"), 3) as PineValue.Number
        assertEquals(83.3333333333, smoothed.value, 1e-6)
        assertEquals(PineSignalAction.CALL, evaluator.signalAt(result, 3).action)
    }

    @Test fun compilesStrategyEntryAndExitLevelsAsRulesOnly() {
        val result = PineScriptConverter.convert("""
            //@version=5
            strategy("Bracket", overlay=true)
            longCondition = close > open
            strategy.entry("Long", strategy.long, qty=2, when=longCondition)
            strategy.exit("Long Exit", from_entry="Long", stop=close - 2, limit=close + 3)
        """.trimIndent())

        assertTrue(result.errors.joinToString("\n"), result.supported)
        assertEquals(PineScriptConverter.ScriptKind.STRATEGY, result.kind)
        assertEquals(1, result.entries.size)
        assertEquals(1, result.exits.size)
        assertEquals(PineScriptConverter.StrategyDirection.LONG, result.entries.single().direction)
        assertEquals("Long", result.entries.single().id)
        assertEquals("Long", result.exits.single().fromEntry)

        val bars = listOf(PineOhlcBar(1, 100.0, 104.0, 98.0, 101.0))
        val evaluator = PineScriptEvaluator(bars, result.compiledVariables)
        assertEquals(true, evaluator.entryConditionAt(result.entries.single(), 0))
        assertEquals(PineSignalAction.CALL, evaluator.signalAt(result, 0).action)
        assertEquals(2.0, evaluator.entryQuantityAt(result.entries.single(), 0)!!, 1e-9)

        val levels = evaluator.exitLevelsAt(result.exits.single(), 0)!!
        assertEquals(99.0, levels.stopPrice!!, 1e-9)
        assertEquals(104.0, levels.limitPrice!!, 1e-9)
        assertNull(levels.lossTicks)
        assertNull(levels.profitTicks)
    }

    @Test fun supportsShortEntryAndNamedExitFromEntry() {
        val result = PineScriptConverter.convert("""
            //@version=5
            strategy("Short")
            strategy.entry("Short", strategy.short, when=close < open)
            strategy.exit("Short Exit", from_entry="Short", stop=close + 1)
        """.trimIndent())
        assertTrue(result.errors.joinToString("\n"), result.supported)
        assertEquals(PineScriptConverter.StrategyDirection.SHORT, result.entries.single().direction)
        assertEquals("Short", result.exits.single().fromEntry)
        val bars = listOf(PineOhlcBar(1, 10.0, 11.0, 8.0, 9.0))
        val evaluator = PineScriptEvaluator(bars, result.compiledVariables)
        assertEquals(true, evaluator.entryConditionAt(result.entries.single(), 0))
        assertEquals(PineSignalAction.PUT, evaluator.signalAt(result, 0).action)
    }

    @Test fun rejectsUnsupportedStrategyArgumentsAndTrailingExits() {
        val entry = PineScriptConverter.convert("""
            //@version=5
            strategy("Bad entry")
            strategy.entry("Long", strategy.long, limit=close)
        """.trimIndent())
        assertFalse(entry.supported)
        assertTrue(entry.errors.any { it.contains("وسائط strategy.entry غير المدعومة") })

        val exit = PineScriptConverter.convert("""
            //@version=5
            strategy("Trailing")
            strategy.exit("Exit", from_entry="Long", trail_points=10, trail_offset=5)
        """.trimIndent())
        assertFalse(exit.supported)
        assertTrue(exit.errors.any { it.contains("وسائط strategy.exit غير المدعومة") })
    }

    @Test fun rejectsStrategyOrdersInsideIndicatorsAndUntranslatedCloseOrders() {
        val indicator = PineScriptConverter.convert("""
            //@version=5
            indicator("Wrong kind")
            strategy.entry("Long", strategy.long, when=close > open)
        """.trimIndent())
        assertFalse(indicator.supported)
        assertTrue(indicator.errors.any { it.contains("داخل indicator") })

        val closeOrder = PineScriptConverter.convert("""
            //@version=5
            strategy("Close")
            strategy.close("Long")
        """.trimIndent())
        assertFalse(closeOrder.supported)
        assertTrue(closeOrder.errors.any { it.contains("strategy.order وstrategy.close") })
    }

    @Test fun evaluatesRelativeExitTicksWithoutConvertingThemToPrices() {
        val result = PineScriptConverter.convert("""
            //@version=5
            strategy("Ticks")
            strategy.exit("Tick Exit", from_entry="Long", loss=4, profit=8)
        """.trimIndent())
        assertTrue(result.errors.joinToString("\n"), result.supported)
        val bars = listOf(PineOhlcBar(1, 100.0, 101.0, 99.0, 100.0))
        val levels = PineScriptEvaluator(bars, result.compiledVariables).exitLevelsAt(result.exits.single(), 0)!!
        assertEquals(4.0, levels.lossTicks!!, 1e-9)
        assertEquals(8.0, levels.profitTicks!!, 1e-9)
        assertNull(levels.stopPrice)
        assertNull(levels.limitPrice)
    }

    @Test fun rejectsConflictingAbsoluteAndRelativeExitPairsAndDuplicateArguments() {
        val mixed = PineScriptConverter.convert("""
            //@version=5
            strategy("Mixed")
            strategy.exit("Exit", stop=close - 1, loss=5)
        """.trimIndent())
        assertFalse(mixed.supported)
        assertTrue(mixed.errors.any { it.contains("الجمع بين stop وloss") })

        val duplicate = PineScriptConverter.convert("""
            //@version=5
            strategy("Duplicate")
            strategy.entry("Long", strategy.long, qty=1, qty=2)
        """.trimIndent())
        assertFalse(duplicate.supported)
        assertTrue(duplicate.errors.any { it.contains("وسائط strategy.entry مكررة") })
    }

    @Test fun rejectsV6StrategyWhenAndWarnsForUnconditionalEntry() {
        val v6 = PineScriptConverter.convert("""
            //@version=6
            strategy("V6")
            strategy.entry("Long", strategy.long, when=close > open)
        """.trimIndent())
        assertFalse(v6.supported)
        assertTrue(v6.errors.any { it.contains("أزيل من strategy.entry في Pine v6") })

        val unconditional = PineScriptConverter.convert("""
            //@version=5
            strategy("Unconditional")
            strategy.entry("Long", strategy.long)
        """.trimIndent())
        assertTrue(unconditional.errors.joinToString("\n"), unconditional.supported)
        assertTrue(unconditional.warnings.any { it.contains("بلا شرط when") })
    }

    @Test fun rejectsUnknownSymbolsInsteadOfReturningNullAtRuntime() {
        val result = PineScriptConverter.convert("""
            //@version=5
            indicator("Unknown symbol")
            tick = syminfo.mintick
            alertcondition(close > tick, title="CALL")
        """.trimIndent())
        assertFalse(result.supported)
        assertTrue(result.errors.any { it.contains("syminfo.mintick") })
    }

    @Test fun rejectsUnknownFunctionInsideExpression() {
        val result = PineScriptConverter.convert("""
            //@version=5
            indicator("Unsupported")
            value = ta.supertrend(3, 10)
            alertcondition(value > close, title="CALL")
        """.trimIndent())
        assertFalse(result.supported)
        assertTrue(result.errors.any { it.contains("ta.supertrend") })
    }

    @Test fun rejectsHistoryIndexingAndLookahead() {
        val history = PineScriptConverter.convert("""
            //@version=5
            indicator("History")
            previous = close[1]
            alertcondition(close > previous, title="CALL")
        """.trimIndent())
        assertFalse(history.supported)
        assertTrue(history.errors.any { it.contains("فهرسة الشموع") })
        val lookahead = PineScriptConverter.convert("""
            //@version=5
            indicator("Future")
            value = barmerge.lookahead_on
        """.trimIndent())
        assertFalse(lookahead.supported)
        assertTrue(lookahead.errors.any { it.contains("lookahead") })
    }

    @Test fun supportsSingleStatementIfElseEntriesInPineV6() {
        val result = PineScriptConverter.convert("""
            //@version=6
            strategy("If Else")
            isBull = close > open
            if isBull
                strategy.entry("Long", strategy.long)
            else
                strategy.entry("Short", strategy.short)
        """.trimIndent())

        assertTrue(result.errors.joinToString("\n"), result.supported)
        assertEquals(2, result.entries.size)
        assertEquals("isBull", result.entries[0].conditionText)
        assertEquals("not (isBull)", result.entries[1].conditionText)

        val bars = listOf(
            PineOhlcBar(1, 10.0, 12.0, 9.0, 11.0),
            PineOhlcBar(2, 11.0, 12.0, 9.0, 10.0)
        )
        val evaluator = PineScriptEvaluator(bars, result.compiledVariables)
        assertEquals(PineSignalAction.CALL, evaluator.signalAt(result, 0).action)
        assertEquals(PineSignalAction.PUT, evaluator.signalAt(result, 1).action)
    }

    @Test fun supportsGuardedPlotshapeWithoutElse() {
        val result = PineScriptConverter.convert("""
            //@version=5
            indicator("Guarded shape")
            bullish = close > open
            if bullish
                plotshape(true, title="CALL")
        """.trimIndent())

        assertTrue(result.errors.joinToString("\n"), result.supported)
        assertEquals(1, result.signals.size)
        assertTrue(result.signals.single().expression.contains("bullish"))
        val bars = listOf(PineOhlcBar(1, 10.0, 12.0, 9.0, 11.0))
        assertEquals(PineSignalAction.CALL, PineScriptEvaluator(bars, result.compiledVariables).signalAt(result, 0).action)
    }

    @Test fun rejectsMultipleStatementsAndNestedIfBlocks() {
        val multiple = PineScriptConverter.convert("""
            //@version=5
            strategy("Multiple")
            if close > open
                strategy.entry("Long", strategy.long)
                strategy.exit("Exit", stop=close - 1)
        """.trimIndent())
        assertFalse(multiple.supported)
        assertTrue(multiple.errors.any { it.contains("عبارة واحدة فقط") })

        val nested = PineScriptConverter.convert("""
            //@version=5
            strategy("Nested")
            if close > open
                if close > low
                    strategy.entry("Long", strategy.long)
        """.trimIndent())
        assertFalse(nested.supported)
        assertTrue(nested.errors.any { it.contains("عبارة واحدة فقط") })
    }

    @Test fun rejectsElseIfAndConditionalExitRatherThanDroppingTheirGuards() {
        val elseIf = PineScriptConverter.convert("""
            //@version=5
            strategy("Else if")
            if close > open
                strategy.entry("Long", strategy.long)
            else if close < open
                strategy.entry("Short", strategy.short)
        """.trimIndent())
        assertFalse(elseIf.supported)
        assertTrue(elseIf.errors.any { it.contains("else if غير مدعوم") })

        val conditionalExit = PineScriptConverter.convert("""
            //@version=5
            strategy("Conditional exit")
            if close < open
                strategy.exit("Exit", from_entry="Long", stop=close + 1)
        """.trimIndent())
        assertFalse(conditionalExit.supported)
        assertTrue(conditionalExit.errors.any { it.contains("داخل if/else") })
    }

    @Test fun smaIgnoresNaAndUsesTheLatestLengthNonNaValues() {
        val expression = PineExpressionParser.parse("ta.sma(close / (close - open), 2)")
        val bars = listOf(
            PineOhlcBar(1, 1.0, 2.0, 0.5, 2.0), // 2.0
            PineOhlcBar(2, 3.0, 3.5, 2.5, 3.0), // division by zero -> na
            PineOhlcBar(3, 2.0, 4.0, 1.0, 4.0), // 2.0
            PineOhlcBar(4, 1.0, 3.0, 0.5, 3.0)  // 1.5
        )
        val evaluator = PineScriptEvaluator(bars, emptyMap())

        assertNull(evaluator.evaluate(expression, 1))
        assertEquals(2.0, (evaluator.evaluate(expression, 2) as PineValue.Number).value, 1e-9)
        assertEquals(1.75, (evaluator.evaluate(expression, 3) as PineValue.Number).value, 1e-9)
    }

    @Test fun smaWarmupCountsNonNaSamplesRatherThanCalendarBars() {
        val expression = PineExpressionParser.parse("ta.sma(close / (close - open), 3)")
        val bars = listOf(
            PineOhlcBar(1, 1.0, 2.0, 0.5, 2.0),
            PineOhlcBar(2, 3.0, 3.5, 2.5, 3.0),
            PineOhlcBar(3, 2.0, 4.0, 1.0, 4.0)
        )
        val evaluator = PineScriptEvaluator(bars, emptyMap())

        assertNull(evaluator.evaluate(expression, 2))
    }

    @Test fun emaKeepsItsRecursiveStateAcrossNaSourceSamples() {
        val expression = PineExpressionParser.parse("ta.ema(close / (close - open), 2)")
        val bars = listOf(
            PineOhlcBar(1, 1.0, 2.0, 0.5, 2.0), // 2.0
            PineOhlcBar(2, 3.0, 3.5, 2.5, 3.0), // na
            PineOhlcBar(3, 3.0, 5.0, 2.5, 5.0)  // 2.5
        )
        val value = PineScriptEvaluator(bars, emptyMap()).evaluate(expression, 2) as PineValue.Number

        assertEquals(7.0 / 3.0, value.value, 1e-9)
    }

    @Test fun rsiUsesWilderSeedAndSmoothingReferenceValues() {
        val expression = PineExpressionParser.parse("ta.rsi(close, 2)")
        val closes = listOf(10.0, 11.0, 10.0, 12.0)
        val bars = closes.mapIndexed { index, close ->
            PineOhlcBar(index + 1L, close, close + 1.0, close - 1.0, close)
        }
        val evaluator = PineScriptEvaluator(bars, emptyMap())

        assertNull(evaluator.evaluate(expression, 1))
        assertEquals(50.0, (evaluator.evaluate(expression, 2) as PineValue.Number).value, 1e-9)
        assertEquals(75.0, (evaluator.evaluate(expression, 3) as PineValue.Number).value, 1e-9)
    }

    @Test fun macdTupleComponentsMatchTheirReferenceRecurrence() {
        val conversion = PineScriptConverter.convert("""
            //@version=5
            indicator("MACD reference")
            [macdLine, signalLine, histogram] = ta.macd(close, 2, 3, 2)
            alertcondition(histogram > 0, title="CALL")
        """.trimIndent())
        assertTrue(conversion.errors.joinToString("\n"), conversion.supported)

        val closes = listOf(1.0, 2.0, 3.0, 2.0, 4.0, 5.0)
        val bars = closes.mapIndexed { index, close ->
            PineOhlcBar(index + 1L, close, close + 1.0, close - 1.0, close)
        }
        val evaluator = PineScriptEvaluator(bars, conversion.compiledVariables)
        fun number(name: String) =
            (evaluator.evaluate(PineExpr.Identifier(name), 5) as PineValue.Number).value

        assertEquals(4.465020576131687, number("macdLine"), 1e-9)
        assertEquals(4.03125, number("signalLine"), 1e-9)
        assertEquals(0.05731310013717428, number("histogram"), 1e-9)
        assertEquals(number("macdLine") - number("signalLine"), number("histogram"), 1e-9)
    }

}
