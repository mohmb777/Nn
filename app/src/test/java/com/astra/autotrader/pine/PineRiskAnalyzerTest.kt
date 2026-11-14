package com.astra.autotrader.pine

import org.junit.Assert.*
import org.junit.Test

class PineRiskAnalyzerTest {
    @Test fun rejectsRequestSecurityWithExactSourceLine() {
        val source = """
            //@version=5
            indicator("MTF")
            value = request.security("EURUSD", "5", close)
        """.trimIndent()

        val finding = PineRiskAnalyzer.analyze(source).single()
        assertEquals(3, finding.line)
        assertEquals("MTF_SECURITY_UNSUPPORTED", finding.code)
        assertEquals(PineRiskSeverity.ERROR, finding.severity)
        assertTrue(finding.message.contains("request.security"))
        assertTrue(finding.message.contains("المحاذاة"))
    }

    @Test fun rejectsLookaheadOnAsFutureLeak() {
        val source = """
            //@version=5
            indicator("Future")
            value = barmerge.lookahead_on
        """.trimIndent()

        val finding = PineRiskAnalyzer.analyze(source).single()
        assertEquals("LOOKAHEAD_ON", finding.code)
        assertEquals(PineRiskSeverity.ERROR, finding.severity)
        assertTrue(finding.message.contains("بيانات مستقبلية"))
    }

    @Test fun rejectsPivotFunctionsBecauseTheyNeedRightBarsAndCanBackpaint() {
        val source = """
            //@version=5
            indicator("Pivots")
            ph = ta.pivothigh(high, 3, 3)
            pl = ta.pivotlow(low, 3, 3)
        """.trimIndent()

        val findings = PineRiskAnalyzer.analyze(source)
            .filter { it.code == "PIVOT_BACKPAINT" }
        assertEquals(2, findings.size)
        assertTrue(findings.all { it.severity == PineRiskSeverity.ERROR })
        assertTrue(findings[0].message.contains("شموعاً يمينية"))
        assertTrue(findings[1].message.contains("ta.pivotlow"))
    }

    @Test fun warnsForRealtimeAndIntrabarDependencies() {
        val source = """
            //@version=5
            indicator("Realtime")
            live = barstate.isrealtime
            tick = timenow
            strategy("not parsed here", calc_on_every_tick=true)
        """.trimIndent()

        val findings = PineRiskAnalyzer.analyze(source)
            .filter { it.code == "INTRABAR_DEPENDENCY" }
        assertEquals(3, findings.size)
        assertTrue(findings.all { it.severity == PineRiskSeverity.WARNING })
        assertTrue(findings.all { it.message.contains("شموع مكتملة") })
    }

    @Test fun rejectsUnmodeledBarstateConfirmed() {
        val finding = PineRiskAnalyzer.analyze(
            """
                //@version=5
                indicator("Confirmed")
                ok = barstate.isconfirmed
            """.trimIndent()
        ).single()

        assertEquals("BARSTATE_CONFIRMED_UNMODELED", finding.code)
        assertEquals(PineRiskSeverity.ERROR, finding.severity)
    }

    @Test fun rejectsNegativeHistoryAndNegativePlotOffsets() {
        val source = """
            //@version=5
            indicator("Future references")
            future = close[-1]
            plotshape(close > open, offset=-2)
        """.trimIndent()

        val findings = PineRiskAnalyzer.analyze(source)
        assertTrue(findings.any { it.code == "NEGATIVE_HISTORY_OFFSET" && it.line == 3 })
        assertTrue(findings.any { it.code == "NEGATIVE_PLOT_OFFSET" && it.line == 4 })
        assertTrue(findings.filter { it.code.startsWith("NEGATIVE_") }
            .all { it.severity == PineRiskSeverity.ERROR })
    }

    @Test fun warnsForPositiveHistoryIndexAndLookaheadOffContext() {
        val source = """
            //@version=5
            indicator("History")
            previous = close[1]
            mode = barmerge.lookahead_off
        """.trimIndent()

        val findings = PineRiskAnalyzer.analyze(source)
        assertTrue(findings.any { it.code == "HISTORY_INDEX_UNSUPPORTED" && it.line == 3 })
        assertTrue(findings.any { it.code == "LOOKAHEAD_OFF_CONTEXT" && it.line == 4 })
        assertTrue(findings.all { it.severity == PineRiskSeverity.WARNING })
    }

    @Test fun ignoresKeywordsInsideCommentsAndStringLiterals() {
        val source = """
            //@version=5
            indicator("request.security lookahead_on ta.pivothigh")
            // request.security("EURUSD", "5", close)
            // barmerge.lookahead_on and ta.pivotlow(low, 2, 2)
            alertcondition(close > open, title="CALL")
        """.trimIndent()

        assertTrue(PineRiskAnalyzer.analyze(source).isEmpty())
    }

    @Test fun converterSurfacesRiskErrorsAndWarningsInItsExistingDiagnostics() {
        val source = """
            //@version=5
            indicator("Risk integration")
            realtime = barstate.isrealtime
            value = request.security("EURUSD", "5", close)
            alertcondition(close > open, title="CALL")
        """.trimIndent()

        val conversion = PineScriptConverter.convert(source)
        assertFalse(conversion.supported)
        assertTrue(conversion.errors.any { it.contains("السطر 4") && it.contains("request.security") })
        assertTrue(conversion.warnings.any { it.contains("السطر 3") && it.contains("تحديث داخل الشمعة") })
    }
}
