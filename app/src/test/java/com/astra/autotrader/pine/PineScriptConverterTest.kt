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

    @Test fun strategyOrdersAreNotClaimedAsConverted() {
        val result = PineScriptConverter.convert("""
            //@version=5
            strategy("Order")
            enterLong = close > open
            strategy.entry("Long", strategy.long, when=enterLong)
        """.trimIndent())
        assertFalse(result.supported)
        assertTrue(result.errors.any { it.contains("لم تُترجم") })
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
}
