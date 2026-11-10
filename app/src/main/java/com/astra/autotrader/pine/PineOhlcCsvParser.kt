package com.astra.autotrader.pine

import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

data class PineOhlcDataset(
    val bars: List<PineOhlcBar>,
    val expectedSignals: List<PineSignalAction>? = null,
    val sourceName: String? = null
)

data class PineOhlcCsvParseResult(
    val dataset: PineOhlcDataset?,
    val errorArabic: String?
) {
    val success: Boolean get() = dataset != null && errorArabic == null
}

/**
 * قارئ CSV محلي لبيانات OHLC التي يختارها المستخدم بنفسه.
 * لا يجلب أسعاراً ولا يتصل بـ Pocket Option أو بأي مزود بيانات.
 */
object PineOhlcCsvParser {
    const val MAX_CSV_CHARS = 5_000_000
    const val MAX_ROWS = 10_000
    private const val MIN_EPOCH_MILLIS = 100_000_000_000L

    fun parse(csv: String, sourceName: String? = null): PineOhlcCsvParseResult {
        if (csv.isBlank()) return failure("ملف CSV فارغ.")
        if (csv.length > MAX_CSV_CHARS) {
            return failure("حجم ملف CSV يتجاوز الحد الآمن البالغ 5 ميغابايت.")
        }

        val lines = csv.removePrefix("\uFEFF").split(Regex("\\r\\n|\\n|\\r"))
        val headerLineIndex = lines.indexOfFirst { it.isNotBlank() }
        if (headerLineIndex < 0) return failure("لم يتم العثور على صف العناوين.")

        val delimiter = detectDelimiter(lines[headerLineIndex])
        val headers = splitCsvLine(lines[headerLineIndex], delimiter)
            ?: return failure("صف العناوين يحتوي على علامات اقتباس غير مكتملة.")
        val normalizedHeaders = headers.map { it.trim().lowercase(Locale.ROOT) }

        if (normalizedHeaders.any { it.isBlank() }) {
            return failure("يوجد اسم عمود فارغ في صف العناوين.")
        }
        if (normalizedHeaders.size != normalizedHeaders.toSet().size) {
            return failure("توجد أسماء أعمدة مكررة في ملف CSV.")
        }

        fun locate(aliases: Set<String>, label: String, required: Boolean): Int? {
            val matches = normalizedHeaders.indices.filter { normalizedHeaders[it] in aliases }
            if (matches.size > 1) {
                throw CsvHeaderException("يوجد أكثر من عمود يمكن اعتباره $label؛ اترك عموداً واحداً فقط.")
            }
            if (required && matches.isEmpty()) {
                throw CsvHeaderException("العمود المطلوب $label غير موجود.")
            }
            return matches.firstOrNull()
        }

        val timeIndex: Int
        val openIndex: Int
        val highIndex: Int
        val lowIndex: Int
        val closeIndex: Int
        val volumeIndex: Int?
        val expectedIndex: Int?
        try {
            timeIndex = locate(setOf("time", "timestamp", "datetime", "date_time"), "time/timestamp", true)!!
            openIndex = locate(setOf("open"), "open", true)!!
            highIndex = locate(setOf("high"), "high", true)!!
            lowIndex = locate(setOf("low"), "low", true)!!
            closeIndex = locate(setOf("close"), "close", true)!!
            volumeIndex = locate(setOf("volume", "vol"), "volume", false)
            expectedIndex = locate(setOf("expected_signal", "astra_expected_signal"), "expected_signal", false)
        } catch (error: CsvHeaderException) {
            return failure(error.message ?: "عناوين CSV غير صالحة.")
        }

        val requiredLastIndex = listOfNotNull(
            timeIndex, openIndex, highIndex, lowIndex, closeIndex, volumeIndex, expectedIndex
        ).maxOrNull() ?: return failure("تعذر تحديد أعمدة OHLC.")

        val bars = mutableListOf<PineOhlcBar>()
        val expectedSignals = if (expectedIndex != null) mutableListOf<PineSignalAction>() else null
        var previousTime: Long? = null

        for (lineIndex in headerLineIndex + 1 until lines.size) {
            val line = lines[lineIndex]
            if (line.isBlank()) continue
            if (bars.size >= MAX_ROWS) {
                return failure("عدد الشموع يتجاوز الحد الآمن البالغ $MAX_ROWS شمعة.")
            }

            val fields = splitCsvLine(line, delimiter)
                ?: return failure("صيغة CSV غير صالحة في السطر ${lineIndex + 1}: علامات اقتباس غير مكتملة.")
            if (fields.size <= requiredLastIndex) {
                return failure("عدد الأعمدة غير كافٍ في السطر ${lineIndex + 1}.")
            }

            val rawTime = fields[timeIndex].trim()
            val numericTime = rawTime.toLongOrNull()
            val timestamp = when {
                numericTime != null && numericTime >= MIN_EPOCH_MILLIS -> numericTime
                numericTime != null -> return failure(
                    "الوقت في السطر ${lineIndex + 1} يبدو Unix seconds. استخدم Unix milliseconds أو ISO-8601 مع منطقة زمنية؛ لن نخمن التحويل."
                )
                else -> parseIsoTimestamp(rawTime) ?: return failure(
                    "الوقت في السطر ${lineIndex + 1} غير صالح. استخدم ISO-8601 مع Z/offset أو Unix milliseconds."
                )
            }

            if (timestamp <= 0L) {
                return failure("توقيت الشمعة يجب أن يكون موجباً في السطر ${lineIndex + 1}.")
            }
            if (previousTime != null && timestamp <= previousTime!!) {
                return failure("أوقات الشموع ليست متزايدة بصرامة عند السطر ${lineIndex + 1}؛ تحقق من التكرار والترتيب.")
            }

            fun numberAt(index: Int): Double? =
                fields[index].trim().toDoubleOrNull()?.takeIf { it.isFinite() }

            val open = numberAt(openIndex)
                ?: return failure("قيمة open غير رقمية أو غير منتهية في السطر ${lineIndex + 1}.")
            val high = numberAt(highIndex)
                ?: return failure("قيمة high غير رقمية أو غير منتهية في السطر ${lineIndex + 1}.")
            val low = numberAt(lowIndex)
                ?: return failure("قيمة low غير رقمية أو غير منتهية في السطر ${lineIndex + 1}.")
            val close = numberAt(closeIndex)
                ?: return failure("قيمة close غير رقمية أو غير منتهية في السطر ${lineIndex + 1}.")
            val volume = when {
                volumeIndex == null -> 0.0
                fields[volumeIndex].isBlank() -> 0.0
                else -> numberAt(volumeIndex)
                    ?: return failure("قيمة volume غير رقمية أو غير منتهية في السطر ${lineIndex + 1}.")
            }

            if (!volume.isFinite() || volume < 0.0) {
                return failure("قيمة volume سالبة أو غير صالحة في السطر ${lineIndex + 1}.")
            }
            if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0 ||
                high < maxOf(open, close) || low > minOf(open, close) || low > high
            ) {
                return failure("تركيبة OHLC غير منطقية في السطر ${lineIndex + 1}.")
            }

            if (expectedIndex != null) {
                val expected = parseExpectedSignal(fields[expectedIndex])
                    ?: return failure(
                        "قيمة expected_signal غير معروفة في السطر ${lineIndex + 1}. القيم المقبولة: CALL/1 أو PUT/-1 أو WAIT/0."
                    )
                expectedSignals?.add(expected)
            }

            bars += PineOhlcBar(timestamp, open, high, low, close, volume)
            previousTime = timestamp
        }

        if (bars.isEmpty()) return failure("لم يحتوِ الملف على شموع OHLC.")
        return PineOhlcCsvParseResult(
            PineOhlcDataset(bars, expectedSignals?.toList(), sourceName),
            null
        )
    }

    private fun parseIsoTimestamp(value: String): Long? {
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun parseExpectedSignal(raw: String): PineSignalAction? =
        when (raw.trim().uppercase(Locale.ROOT)) {
            "CALL", "BUY", "LONG", "1", "1.0" -> PineSignalAction.CALL
            "PUT", "SELL", "SHORT", "-1", "-1.0" -> PineSignalAction.PUT
            "WAIT", "NONE", "0", "0.0" -> PineSignalAction.WAIT
            else -> null
        }

    private fun detectDelimiter(header: String): Char {
        var quoted = false
        var commas = 0
        var semicolons = 0
        var index = 0
        while (index < header.length) {
            val char = header[index]
            if (char == '"') {
                if (quoted && index + 1 < header.length && header[index + 1] == '"') {
                    index++
                } else {
                    quoted = !quoted
                }
            } else if (!quoted) {
                if (char == ',') commas++
                if (char == ';') semicolons++
            }
            index++
        }
        return if (semicolons > commas) ';' else ','
    }

    private fun splitCsvLine(line: String, delimiter: Char): List<String>? {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> {
                    fields += field.toString().trim()
                    field.setLength(0)
                }
                else -> field.append(char)
            }
            index++
        }
        if (quoted) return null
        fields += field.toString().trim()
        return fields
    }

    private fun failure(message: String) = PineOhlcCsvParseResult(null, message)

    private class CsvHeaderException(message: String) : IllegalArgumentException(message)
}
