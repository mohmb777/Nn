package com.astra.autotrader.pine

import org.junit.Assert.*
import org.junit.Test

class PineOhlcCsvParserTest {
    @Test fun parsesEpochMillisecondsAndExpectedSignals() {
        val csv = """
            time,open,high,low,close,volume,expected_signal
            1794219000000,1.10,1.13,1.09,1.12,120,CALL
            1794219060000,1.12,1.14,1.08,1.09,95,PUT
            1794219120000,1.09,1.11,1.08,1.10,0,WAIT
        """.trimIndent()

        val result = PineOhlcCsvParser.parse(csv, "chart-export.csv")

        assertTrue(result.errorArabic ?: "parse failed", result.success)
        val dataset = result.dataset!!
        assertEquals(3, dataset.bars.size)
        assertEquals(120.0, dataset.bars[0].volume, 0.0)
        assertEquals(
            listOf(PineSignalAction.CALL, PineSignalAction.PUT, PineSignalAction.WAIT),
            dataset.expectedSignals
        )
        assertEquals("chart-export.csv", dataset.sourceName)
    }

    @Test fun parsesIso8601AndSemicolonSeparatedCsv() {
        val csv = """
            time;open;high;low;close
            2026-11-10T09:00:00Z;1.10;1.12;1.09;1.11
            2026-11-10T09:01:00+00:00;1.11;1.13;1.10;1.12
        """.trimIndent()

        val result = PineOhlcCsvParser.parse(csv)

        assertTrue(result.errorArabic ?: "parse failed", result.success)
        assertEquals(2, result.dataset!!.bars.size)
        assertEquals(0.0, result.dataset!!.bars[0].volume, 0.0)
    }

    @Test fun rejectsUnixSecondsInsteadOfGuessingUnit() {
        val csv = """
            time,open,high,low,close
            1794219000,1.10,1.12,1.09,1.11
        """.trimIndent()

        val result = PineOhlcCsvParser.parse(csv)

        assertFalse(result.success)
        assertTrue(result.errorArabic!!.contains("Unix seconds"))
    }

    @Test fun rejectsInvalidOhlcAndNonIncreasingTime() {
        val invalidOhlc = """
            time,open,high,low,close
            1794219000000,1.10,1.05,1.09,1.11
        """.trimIndent()
        val unordered = """
            time,open,high,low,close
            1794219060000,1.10,1.12,1.09,1.11
            1794219000000,1.10,1.12,1.09,1.11
        """.trimIndent()

        assertTrue(PineOhlcCsvParser.parse(invalidOhlc).errorArabic!!.contains("OHLC"))
        assertTrue(PineOhlcCsvParser.parse(unordered).errorArabic!!.contains("متزايدة"))
    }

    @Test fun rejectsMissingColumnsUnknownExpectedSignalAndDuplicateHeaders() {
        val missing = "time,open,high,low\n1794219000000,1.1,1.2,1.0"
        val unknownSignal = """
            time,open,high,low,close,expected_signal
            1794219000000,1.10,1.12,1.09,1.11,maybe
        """.trimIndent()
        val duplicate = """
            time,open,high,low,close,close
            1794219000000,1.10,1.12,1.09,1.11,1.11
        """.trimIndent()

        assertTrue(PineOhlcCsvParser.parse(missing).errorArabic!!.contains("close"))
        assertTrue(PineOhlcCsvParser.parse(unknownSignal).errorArabic!!.contains("expected_signal"))
        assertTrue(PineOhlcCsvParser.parse(duplicate).errorArabic!!.contains("مكررة"))
    }

    @Test fun rejectsNonNumericVolumeInsteadOfReplacingItWithZero() {
        val csv = """
            time,open,high,low,close,volume
            1794219000000,1.10,1.12,1.09,1.11,unknown
        """.trimIndent()

        val result = PineOhlcCsvParser.parse(csv)

        assertFalse(result.success)
        assertTrue(result.errorArabic!!.contains("volume"))
    }

    @Test fun treatsNaReferenceValuesAsUncomparedWarmupBars() {
        val csv = """
            time,open,high,low,close,expected_signal
            1794219000000,1.10,1.12,1.09,1.11,CALL
            1794219060000,1.11,1.13,1.10,1.12,na
            1794219120000,1.12,1.14,1.11,1.13,PUT
        """.trimIndent()

        val result = PineOhlcCsvParser.parse(csv)

        assertTrue(result.errorArabic ?: "parse failed", result.success)
        assertEquals(
            listOf(PineSignalAction.CALL, null, PineSignalAction.PUT),
            result.dataset!!.expectedSignals
        )
    }

    @Test fun rejectsUnclosedQuotesAndOversizedInput() {
        val malformed = "time,open,high,low,close\n1794219000000,1.1,1.2,1.0,\"1.1"
        val oversized = "x".repeat(PineOhlcCsvParser.MAX_CSV_CHARS + 1)

        assertTrue(PineOhlcCsvParser.parse(malformed).errorArabic!!.contains("اقتباس"))
        assertTrue(PineOhlcCsvParser.parse(oversized).errorArabic!!.contains("5 ميغابايت"))
    }
}
