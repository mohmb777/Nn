package com.astra.autotrader.pine

/**
 * محلل بنيوي آمن لمجموعة محدودة من Pine Script v5.
 * لا ينفذ الكود ولا يستخدم eval؛ يحتفظ بشروط الإشارة كنص موثق للمراجعة.
 */
object PineScriptConverter {
    enum class ScriptKind { INDICATOR, STRATEGY }
    data class SignalRule(val name: String, val expression: String, val sourceLine: Int)
    data class Conversion(
        val kind: ScriptKind?,
        val title: String?,
        val version: Int?,
        val declarations: List<String>,
        val signals: List<SignalRule>,
        val warnings: List<String>,
        val errors: List<String>,
        val supported: Boolean
    )

    private val allowedCalls = setOf(
        "input.int", "input.float", "input.bool", "input.string", "input.source",
        "ta.ema", "ta.sma", "ta.rsi", "ta.macd", "ta.crossover", "ta.crossunder",
        "alertcondition", "plotshape", "plotchar", "indicator", "strategy"
    )
    private val callPattern = Regex("""([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\s*\\(""")
    private val declarationPattern = Regex("""^\\s*(?:var\\s+)?(?:float|int|bool|string)?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$""")
    private val alertPattern = Regex("""alertcondition\\s*\\(\\s*(.+?)\\s*,\\s*title\\s*=\\s*"([^"]*)" """.trimEnd())

    fun convert(source: String): Conversion {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val declarations = mutableListOf<String>()
        val signals = mutableListOf<SignalRule>()
        if (source.isBlank()) return Conversion(null, null, null, emptyList(), emptyList(), emptyList(),
            listOf("أدخل كود Pine Script أولاً."), false)
        if (source.length > 100_000) return Conversion(null, null, null, emptyList(), emptyList(), emptyList(),
            listOf("حجم الملف يتجاوز الحد الآمن (100 كيلوبايت)."), false)

        val lines = source.lines()
        val version = Regex("""^\\s*//\\s*@version=(\\d+)""").find(source)?.groupValues?.get(1)?.toIntOrNull()
        if (version != 5 && version != 6) errors += "الإصدار المدعوم حالياً هو Pine Script v5/v6 فقط."
        val declaration = lines.firstOrNull { it.trimStart().startsWith("indicator(") || it.trimStart().startsWith("strategy(") }
        val kind = when {
            declaration?.trimStart()?.startsWith("indicator(") == true -> ScriptKind.INDICATOR
            declaration?.trimStart()?.startsWith("strategy(") == true -> ScriptKind.STRATEGY
            else -> null
        }
        if (kind == null) errors += "لم يتم العثور على indicator() أو strategy() في بداية سطر."
        val title = declaration?.let { Regex("""\(\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }

        if (Regex("""\\b(request\\.security|request\\.seed|barmerge\\.|lookahead\\s*=\\s*barmerge\\.lookahead_on)""").containsMatchIn(source)) {
            errors += "تم اكتشاف request.security أو lookahead؛ لا يمكن ضمان تطابق التوقيت ومنع إعادة الرسم."
        }
        if (Regex("""\\b(for|while|switch|import|export)\\b""").containsMatchIn(source)) {
            errors += "الحلقات وswitch والاستيراد/التصدير خارج نطاق المحلل الآمن الحالي."
        }
        if (Regex("""\\[[^]]*\\]""").containsMatchIn(source)) {
            warnings += "استخدام فهرسة السلاسل [ ] يحتاج مراجعة يدوية وقد يغيّر توقيت الإشارة."
        }
        if (Regex("""\\b(strategy\\.(entry|exit|order|close))\\s*\\(""").containsMatchIn(source)) {
            warnings += "أوامر strategy.entry/exit محفوظة للمراجعة فقط؛ لن يتم تنفيذها."
        }

        lines.forEachIndexed { index, raw ->
            val line = raw.substringBefore("//").trim()
            if (line.isBlank() || line.startsWith("//") || line.startsWith("@version")) return@forEachIndexed
            val calls = callPattern.findAll(line).map { it.groupValues[1] }.toList()
            calls.forEach { call ->
                if (call.startsWith("strategy.") && call in setOf("strategy.entry", "strategy.exit", "strategy.close", "strategy.order")) return@forEach
                if (call !in allowedCalls && !call.startsWith("math.") && !call.startsWith("color.")) {
                    errors += "السطر ${index + 1}: الدالة غير المدعومة: $call"
                }
            }
            val alert = Regex("""alertcondition\\s*\\(\\s*(.+?)\\s*,\\s*title\\s*=\\s*"([^"]+)"""").find(line)
            if (alert != null) signals += SignalRule(alert.groupValues[2], alert.groupValues[1].trim(), index + 1)
            val assignment = declarationPattern.find(line)
            if (assignment != null) declarations += "${assignment.groupValues[1]} = ${assignment.groupValues[2]}"
            if (line.startsWith("plotshape(") || line.startsWith("plotchar(")) {
                val condition = Regex("""(?:plotshape|plotchar)\\s*\\(\\s*([^,]+)""").find(line)?.groupValues?.get(1)
                if (!condition.isNullOrBlank()) signals += SignalRule("إشارة السطر ${index + 1}", condition.trim(), index + 1)
            }
        }
        if (signals.isEmpty()) warnings += "لم يتم العثور على alertcondition أو plotshape/plotchar قابل للتحويل إلى إشارة."
        if (Regex("""\\b(timeframe\\.period|barstate\\.isrealtime|timenow)\\b""").containsMatchIn(source)) {
            warnings += "الكود يعتمد على حالة زمنية/لحظية؛ اختبره على شموع مغلقة متطابقة قبل اعتماد الإشارات."
        }
        return Conversion(kind, title, version, declarations, signals, warnings.distinct(), errors.distinct(), errors.isEmpty())
    }
}
