package com.astra.autotrader.pine

import org.junit.Assert.*
import org.junit.Test

class PineScriptConverterTest {
    @Test fun parsesIndicatorAndAlertCondition() {
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
        assertTrue(result.signals[0].expression.contains("ta.crossover"))
    }

    @Test fun identifiesStrategyAndPlotShapeSignal() {
        val result = PineScriptConverter.convert("""
            //@version=6
            strategy("Simple", overlay=true)
            longCondition = ta.crossover(ta.ema(close, 9), ta.ema(close, 21))
            plotshape(longCondition, style=shape.triangleup)
        """.trimIndent())
        assertTrue(result.supported)
        assertEquals(PineScriptConverter.ScriptKind.STRATEGY, result.kind)
        assertEquals(1, result.signals.size)
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
}
