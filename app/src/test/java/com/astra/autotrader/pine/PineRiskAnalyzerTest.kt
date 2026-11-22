package com.astra.autotrader.pine

import org.junit.Assert.*
import org.junit.Test

class PineRiskAnalyzerTest {
    @Test fun rejectsVariableAndSeriesExpressionHistoryOffsetsWithLineNumbers() {
        val source = """
            //@version=5
            indicator("Dynamic history")
            period = input.int(5, "Period")
            byPeriod = close[period]
            byBar = close[bar_index - 1]
        """.trimIndent()

        val findings = PineRiskAnalyzer.analyze(source)
            .filter { it.code == "DYNAMIC_HISTORY_OFFSET" }

        assertEquals(2, findings.size)
        assertEquals(listOf(4, 5), findings.map { it.line })
        assertTrue(findings.all { it.severity == PineRiskSeverity.ERROR })
        assertTrue(findings.all { it.message.contains("فهرسة تاريخية ديناميكية") })
    }

    @Test fun ignoresDynamicHistoryTextInsideCommentsAndStrings() {
        val source = """
            //@version=5
            indicator("Dynamic history text")
            // close[period] is only an example in this comment
            alertcondition(close > open, title="close[period]", message="close[bar_index - 1]")
        """.trimIndent()

        val findings = PineRiskAnalyzer.analyze(source)

        assertFalse(findings.any { it.code == "DYNAMIC_HISTORY_OFFSET" })
    }

    @Test fun keepsFixedHistoryOffsetsOnTheirExistingRules() {
        val source = """
            //@version=5
            indicator("Fixed history")
            previous = close[1]
            future = close[-1]
        """.trimIndent()

        val findings = PineRiskAnalyzer.analyze(source)

        assertFalse(findings.any { it.code == "DYNAMIC_HISTORY_OFFSET" })
        assertTrue(findings.any { it.code == "HISTORY_INDEX_WARMUP" && it.line == 3 })
        assertTrue(findings.any { it.code == "NEGATIVE_HISTORY_OFFSET" && it.line == 4 })
    }
}
