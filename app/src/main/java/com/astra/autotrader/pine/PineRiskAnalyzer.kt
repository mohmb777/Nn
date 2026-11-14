package com.astra.autotrader.pine

enum class PineRiskSeverity { WARNING, ERROR }

data class PineRiskFinding(
    val line: Int,
    val code: String,
    val severity: PineRiskSeverity,
    val message: String
)

/**
 * فحص ساكن لمخاطر إعادة الرسم وتسريب المستقبل.
 * يفحص الشيفرة فقط بعد إخفاء التعليقات والنصوص؛ لا يقيّم Pine ولا ينفذه.
 */
object PineRiskAnalyzer {
    private data class Rule(
        val code: String,
        val severity: PineRiskSeverity,
        val pattern: Regex,
        val message: (MatchResult) -> String
    )

    private val rules = listOf(
        Rule(
            "MTF_SECURITY_UNSUPPORTED",
            PineRiskSeverity.ERROR,
            Regex("""\brequest\s*\.\s*security(?:_lower_tf)?\s*\("""),
            { "request.security غير مدعومة: اختلاف الإطار الزمني والمحاذاة وgaps وسلوك الشمعة الأعلى غير ممثل في محرك OHLC المحلي؛ رُفض التحويل بدلاً من افتراض تطابق." }
        ),
        Rule(
            "LOOKAHEAD_ON",
            PineRiskSeverity.ERROR,
            Regex("""\bbarmerge\s*\.\s*lookahead_on\b"""),
            { "barmerge.lookahead_on يسمح بظهور بيانات مستقبلية على الشموع التاريخية وقد يسبب تسريباً زمنياً وإعادة رسم؛ رُفض السكربت." }
        ),
        Rule(
            "PIVOT_BACKPAINT",
            PineRiskSeverity.ERROR,
            Regex("""\bta\s*\.\s*pivot(high|low)\s*\("""),
            { match ->
                val function = if (match.groupValues[1] == "high") "ta.pivothigh" else "ta.pivotlow"
                "$function يحتاج شموعاً يمينية للتأكيد، وقد يرسم النتيجة على موضع pivot سابق بعد التأكيد؛ هذا التوقيت غير ممثل في المحوّل، لذلك رُفض."
            }
        ),
        Rule(
            "INTRABAR_DEPENDENCY",
            PineRiskSeverity.WARNING,
            Regex("""\b(?:barstate\s*\.\s*isrealtime|barstate\s*\.\s*isnew|timenow|calc_on_every_tick\s*=\s*true)\b"""),
            { "اعتماد على حالة لحظية أو تحديث داخل الشمعة؛ إعادة الاختبار الحالية تستخدم OHLC لشموع مكتملة فقط، وقد تختلف الإشارة قبل الإغلاق." }
        ),
        Rule(
            "BARSTATE_CONFIRMED_UNMODELED",
            PineRiskSeverity.ERROR,
            Regex("""\bbarstate\s*\.\s*isconfirmed\b"""),
            { "barstate.isconfirmed غير ممثل كمتغير في مقيّم OHLC الحالي؛ لا يمكن تحويله بأمان إلى قيمة ثابتة لكل سياقات التشغيل." }
        ),
        Rule(
            "NEGATIVE_HISTORY_OFFSET",
            PineRiskSeverity.ERROR,
            Regex("""\b[A-Za-z_][A-Za-z0-9_]*\s*\[\s*-\s*\d+\s*]"""),
            { "فهرسة سالبة للسلسلة تطلب قيمة مستقبلية؛ رُفضت لمنع تسريب بيانات من شموع لم تكن متاحة وقت الإشارة." }
        ),
        Rule(
            "NEGATIVE_PLOT_OFFSET",
            PineRiskSeverity.ERROR,
            Regex("""\boffset\s*=\s*-\s*\d+\b"""),
            { "offset السالب يعيد رسم العلامة بصرياً على شمعة سابقة؛ لا يستطيع المحوّل الحفاظ على توقيت العلامة والإشارة معاً، لذلك رُفض." }
        ),
        Rule(
            "HISTORY_INDEX_UNSUPPORTED",
            PineRiskSeverity.WARNING,
            Regex("""\b[A-Za-z_][A-Za-z0-9_]*\s*\[\s*\d+\s*]"""),
            { "تم اكتشاف فهرسة تاريخية [n]؛ المقيّم الحالي لا يدعم مرجع الشمعة السابقة، وسيُرفض التعبير إذا استُخدم في قاعدة مترجمة." }
        ),
        Rule(
            "LOOKAHEAD_OFF_CONTEXT",
            PineRiskSeverity.WARNING,
            Regex("""\bbarmerge\s*\.\s*lookahead_off\b"""),
            { "lookahead_off لا يكفي وحده لضمان تطابق بيانات الأطر الزمنية المتعددة؛ أي request.security ما زالت خارج نطاق هذا المحوّل." }
        )
    )

    fun analyze(source: String): List<PineRiskFinding> {
        if (source.isBlank()) return emptyList()
        val codeOnly = maskCommentsAndStrings(source)
        val findings = linkedMapOf<String, PineRiskFinding>()

        rules.forEach { rule ->
            rule.pattern.findAll(codeOnly).forEach { match ->
                val line = 1 + codeOnly.substring(0, match.range.first).count { it == '\n' }
                val key = rule.code + ":" + line
                findings.putIfAbsent(
                    key,
                    PineRiskFinding(line, rule.code, rule.severity, rule.message(match))
                )
            }
        }

        return findings.values.sortedWith(compareBy<PineRiskFinding> { it.line }.thenBy { it.code })
    }

    private fun maskCommentsAndStrings(source: String): String {
        val chars = source.toCharArray()
        var index = 0
        var inString = false
        var inComment = false
        var escaped = false

        while (index < chars.size) {
            val current = chars[index]
            val next = chars.getOrNull(index + 1)

            if (inComment) {
                if (current == '\n') {
                    inComment = false
                } else {
                    chars[index] = ' '
                }
                index++
                continue
            }

            if (inString) {
                if (current == '\n') {
                    inString = false
                    escaped = false
                } else {
                    if (escaped) {
                        escaped = false
                    } else if (current == '\\') {
                        escaped = true
                    } else if (current == '"') {
                        inString = false
                    }
                    chars[index] = ' '
                }
                index++
                continue
            }

            if (current == '/' && next == '/') {
                chars[index] = ' '
                if (index + 1 < chars.size) chars[index + 1] = ' '
                index += 2
                inComment = true
                continue
            }

            if (current == '"') {
                chars[index] = ' '
                inString = true
            }
            index++
        }

        return String(chars)
    }
}
