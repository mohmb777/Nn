package com.astra.autotrader.pine

import org.junit.Assert.*
import org.junit.Test

class PineReplayEngineTest {
    private fun conversion() = PineScriptConverter.convert(
        """
            //@version=5
            indicator("Replay parity")
            alertcondition(close > open, title="CALL")
            alertcondition(close < open, title="PUT")
        """.trimIndent()
    )

    private fun dataset(expected: List<PineSignalAction>? = null) = PineOhlcDataset(
        bars = listOf(
            PineOhlcBar(1_794_219_000_000L, 1.10, 1.13, 1.09, 1.12),
            PineOhlcBar(1_794_219_060_000L, 1.12, 1.14, 1.08, 1.09),
            PineOhlcBar(1_794_219_120_000L, 1.09, 1.11, 1.08, 1.10)
        ),
        expectedSignals = expected
    )

    @Test fun comparesReplayAgainstExpectedSignalsOnIdenticalBars() {
        val script = conversion()
        assertTrue(script.errors.joinToString(), script.supported)

        val report = PineReplayEngine.run(
            script,
            dataset(listOf(PineSignalAction.CALL, PineSignalAction.PUT, PineSignalAction.WAIT))
        )

        assertTrue(report.errorArabic ?: "replay failed", report.success)
        assertEquals(3, report.totalBars)
        assertEquals(1, report.callCount)
        assertEquals(1, report.putCount)
        assertEquals(1, report.waitCount)
        assertEquals(3, report.comparedBars)
        assertEquals(3, report.matchedBars)
        assertEquals(0, report.mismatchCount)
        assertEquals(100.0, report.matchRatePercent!!, 0.0)
    }

    @Test fun reportsMismatchedExpectedSignalWithoutChangingOriginalRules() {
        val report = PineReplayEngine.run(
            conversion(),
            dataset(listOf(PineSignalAction.WAIT, PineSignalAction.PUT, PineSignalAction.WAIT))
        )

        assertTrue(report.success)
        assertEquals(1, report.mismatchCount)
        assertEquals(1, report.mismatchSamples.size)
        assertEquals(PineSignalAction.WAIT, report.mismatchSamples.single().expected)
        assertEquals(PineSignalAction.CALL, report.mismatchSamples.single().actual)
    }

    @Test fun doesNotClaimParityWhenExpectedSignalColumnIsAbsent() {
        val report = PineReplayEngine.run(conversion(), dataset())

        assertTrue(report.success)
        assertEquals(0, report.comparedBars)
        assertNull(report.matchRatePercent)
    }

    @Test fun rejectsUnsupportedConversionAndTooManyBars() {
        val unsupported = PineScriptConverter.convert("not pine code")
        val unsupportedReport = PineReplayEngine.run(unsupported, dataset())
        assertFalse(unsupportedReport.success)

        val manyBars = PineOhlcDataset(
            bars = (0..PineReplayEngine.MAX_REPLAY_BARS).map { index ->
                PineOhlcBar(index + 1L, 1.10, 1.12, 1.09, 1.11)
            }
        )
        val oversizedReport = PineReplayEngine.run(conversion(), manyBars)
        assertFalse(oversizedReport.success)
        assertTrue(oversizedReport.errorArabic!!.contains("حد إعادة الاختبار"))
    }

    @Test fun rejectsDatasetWithInvalidOrUnorderedBars() {
        val invalid = PineOhlcDataset(
            listOf(PineOhlcBar(1L, 1.10, 1.05, 1.09, 1.11))
        )
        val unordered = PineOhlcDataset(
            listOf(
                PineOhlcBar(2L, 1.10, 1.12, 1.09, 1.11),
                PineOhlcBar(1L, 1.10, 1.12, 1.09, 1.11)
            )
        )

        assertFalse(PineReplayEngine.run(conversion(), invalid).success)
        assertFalse(PineReplayEngine.run(conversion(), unordered).success)
    }
}
