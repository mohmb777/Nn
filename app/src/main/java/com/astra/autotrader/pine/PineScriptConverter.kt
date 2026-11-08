package com.astra.autotrader.pine

/**
 * محلل ومحوّل آمن لمجموعة محدودة من Pine Script v5/v6.
 * لا ينفذ كود المستخدم؛ يحوّل التعابير المعروفة إلى AST ثابتة.
 */
object PineScriptConverter {
    enum class ScriptKind { INDICATOR, STRATEGY }

    data class SignalRule(
        val name: String,
        val expression: String,
        val sourceLine: Int,
        val direction: PineSignalDirection,
        val compiledExpression: PineExpr?
    )

    enum class StrategyDirection { LONG, SHORT }

    data class EntryRule(
        val id: String,
        val direction: StrategyDirection,
        val conditionText: String,
        val sourceLine: Int,
        val compiledCondition: PineExpr,
        val quantityText: String?,
        val compiledQuantity: PineExpr?
    )

    data class ExitRule(
        val id: String,
        val fromEntry: String?,
        val sourceLine: Int,
        val stopText: String?,
        val compiledStop: PineExpr?,
        val limitText: String?,
        val compiledLimit: PineExpr?,
        val lossTicksText: String?,
        val compiledLossTicks: PineExpr?,
        val profitTicksText: String?,
        val compiledProfitTicks: PineExpr?
    )

    data class Conversion(
        val kind: ScriptKind?,
        val title: String?,
        val version: Int?,
        val declarations: List<String>,
        val signals: List<SignalRule>,
        val entries: List<EntryRule>,
        val exits: List<ExitRule>,
        val warnings: List<String>,
        val errors: List<String>,
        val supported: Boolean,
        val compiledVariables: Map<String, PineExpr>
    )

    private val allowedCalls = setOf(
        "input", "input.int", "input.float", "input.bool", "input.string", "input.source",
        "ta.ema", "ta.sma", "ta.rsi", "ta.macd", "ta.crossover", "ta.crossunder",
        "alertcondition", "plotshape", "plotchar", "indicator", "strategy",
        "strategy.entry", "strategy.exit", "strategy.close", "strategy.order",
        "math.abs", "math.min", "math.max"
    )

    private val callPattern = Regex("""([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*\(""")
    private val declarationPattern = Regex("""^\s*(?:var\s+)?(?:(?:float|int|bool|string)\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+)$""")
    private val macdTuplePattern = Regex("""^\s*\[\s*([A-Za-z_]\w*)\s*,\s*([A-Za-z_]\w*)\s*,\s*([A-Za-z_]\w*)\s*]\s*=\s*ta\.macd\s*\((.*)\)\s*$""")


    private data class SourceStatement(
        val lineNumber: Int,
        val text: String,
        val guardText: String? = null
    )
    fun convert(source: String): Conversion {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val declarations = mutableListOf<String>()
        val signals = mutableListOf<SignalRule>()
        val entries = mutableListOf<EntryRule>()
        val exits = mutableListOf<ExitRule>()
        val variables = linkedMapOf<String, PineExpr>()

        fun result(
            kind: ScriptKind? = null,
            title: String? = null,
            version: Int? = null
        ) = Conversion(kind, title, version, declarations.toList(), signals.toList(),
            entries.toList(), exits.toList(), warnings.distinct(), errors.distinct(),
            errors.isEmpty(), variables.toMap())

        if (source.isBlank()) {
            errors += "أدخل كود Pine Script أولاً."
            return result()
        }
        if (source.length > 100_000) {
            errors += "حجم الملف يتجاوز الحد الآمن (100 كيلوبايت)."
            return result()
        }

        val lines = source.lines()
        val version = Regex("""(?m)^\s*//\s*@version=(\d+)\s*$""").find(source)?.groupValues?.get(1)?.toIntOrNull()
        if (version != 5 && version != 6) errors += "الإصدار المدعوم حالياً هو Pine Script v5/v6 فقط."

        val declaration = lines.firstOrNull {
            val line = it.trimStart()
            line.startsWith("indicator(") || line.startsWith("strategy(")
        }
        val kind = when {
            declaration?.trimStart()?.startsWith("indicator(") == true -> ScriptKind.INDICATOR
            declaration?.trimStart()?.startsWith("strategy(") == true -> ScriptKind.STRATEGY
            else -> null
        }
        if (kind == null) errors += "لم يتم العثور على indicator() أو strategy() في بداية سطر."
        val title = declaration?.let { Regex("""\(\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }

        if (Regex("""\b(request\.security|request\.seed|barmerge\.|lookahead\s*=\s*barmerge\.lookahead_on)""").containsMatchIn(source)) {
            errors += "تم اكتشاف request.security أو lookahead؛ يجب رفضه لأن توقيت البيانات قد يسبب إعادة الرسم."
        }
        if (Regex("""\b(for|while|switch|import|export)\b""").containsMatchIn(source)) {
            errors += "الحلقات وswitch والاستيراد/التصدير خارج نطاق المحلل الآمن الحالي."
        }
        if (Regex("""\b(strategy\.order|strategy\.close)\s*\(""").containsMatchIn(source)) {
            warnings += "تم العثور على أمر استراتيجية خارج النطاق المدعوم؛ لم يتم تحويله أو تنفيذه."
            errors += "strategy.order وstrategy.close غير مدعومين حالياً؛ استخدم فقط strategy.entry وstrategy.exit بالوسائط المعلنة في هذا المحوّل."
        }
        if (Regex("""\b(barstate\.isrealtime|timenow|calc_on_every_tick\s*=\s*true)""").containsMatchIn(source)) {
            warnings += "تم اكتشاف اعتماد على التحديث اللحظي؛ قد تختلف الإشارة قبل إغلاق الشمعة. الاختبار الحالي يجب أن يستخدم شموعاً مغلقة."
        }
        if (Regex("""\[[^]]*]""").containsMatchIn(source) && !macdTuplePattern.containsMatchIn(source)) {
            warnings += "فهرسة السلاسل [ ] غير مدعومة؛ لا يمكن ضمان تطابق توقيت الإشارة."
        }

        val statements = flattenSimpleIfBlocks(lines, errors)
        statements.forEach { statement ->
            val lineNumber = statement.lineNumber
            val line = statement.text
            val guardText = statement.guardText
            if (line.isBlank() || line.startsWith("@version")) return@forEach
            if (line.startsWith("indicator(") || line.startsWith("strategy(")) return@forEach

            val calls = callPattern.findAll(line).map { it.groupValues[1] }.toList()
            calls.forEach { call ->
                if (call !in allowedCalls) errors += "السطر " + lineNumber + ": الدالة غير المدعومة: " + call
            }

            val tuple = macdTuplePattern.matchEntire(line)
            if (tuple != null) {
                val names = listOf(tuple.groupValues[1], tuple.groupValues[2], tuple.groupValues[3])
                val args = splitTopLevel(tuple.groupValues[4])
                if (args.size != 4) {
                    errors += "السطر " + lineNumber + ": ta.macd يتطلب المصدر وطولي EMA السريع والبطيء وطول خط الإشارة."
                } else {
                    val parsedArgs = args.mapNotNull { parseExpression(it, lineNumber, errors) }
                    if (parsedArgs.size == 4) {
                        val components = listOf("ta.macd.line", "ta.macd.signal", "ta.macd.histogram")
                        names.forEachIndexed { componentIndex, name ->
                            variables[name] = PineExpr.Call(components[componentIndex], parsedArgs.map { PineExpr.Argument(null, it) })
                            declarations += name + " = " + components[componentIndex] + "(...)"
                        }
                    }
                }
                return@forEach
            }

            if (Regex("""^strategy\.entry\s*\(""").containsMatchIn(line)) {
                parseStrategyEntry(callArguments(line, "strategy.entry"), lineNumber, kind, version, guardText, entries, errors)
                return@forEach
            }

            if (Regex("""^strategy\.exit\s*\(""").containsMatchIn(line)) {
                if (guardText != null) {
                    errors += "السطر " + lineNumber + ": strategy.exit داخل if/else غير مدعوم بعد؛ لم يتم إسقاط شرط الخروج أو تجاهله."
                } else {
                    parseStrategyExit(callArguments(line, "strategy.exit"), lineNumber, kind, exits, errors)
                }
                return@forEach
            }

            if (Regex("""^alertcondition\s*\(""").containsMatchIn(line)) {
                val args = callArguments(line, "alertcondition")
                val baseExpression = args.firstOrNull()
                val expressionText = if (guardText == null || baseExpression == null) baseExpression
                    else "(" + guardText + ") and (" + baseExpression + ")"
                val name = Regex("""\btitle\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1) ?: "تنبيه السطر " + lineNumber
                if (expressionText.isNullOrBlank()) {
                    errors += "السطر " + lineNumber + ": alertcondition يحتاج شرطاً في الوسيط الأول."
                } else {
                    val compiled = parseExpression(expressionText, lineNumber, errors)
                    signals += SignalRule(name, expressionText, lineNumber, directionFor(name), compiled)
                }
                return@forEach
            }

            if (Regex("""^(plotshape|plotchar)\s*\(""").containsMatchIn(line)) {
                val functionName = if (Regex("""^plotshape\s*\(""").containsMatchIn(line)) "plotshape" else "plotchar"
                val baseExpression = callArguments(line, functionName).firstOrNull()
                val expressionText = if (guardText == null || baseExpression == null) baseExpression
                    else "(" + guardText + ") and (" + baseExpression + ")"
                if (expressionText.isNullOrBlank()) {
                    errors += "السطر " + lineNumber + ": " + functionName + " يحتاج شرطاً في الوسيط الأول."
                } else {
                    val compiled = parseExpression(expressionText, lineNumber, errors)
                    val shapeTitle = Regex("""\btitle\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1) ?: functionName + " السطر " + lineNumber
                    signals += SignalRule(shapeTitle, expressionText, lineNumber, directionFor(shapeTitle), compiled)
                }
                return@forEach
            }

            val assignment = declarationPattern.matchEntire(line)
            if (assignment != null) {
                val name = assignment.groupValues[1]
                val expressionText = assignment.groupValues[2].trim()
                val compiled = parseExpression(expressionText, lineNumber, errors)
                if (compiled != null) {
                    variables[name] = compiled
                    declarations += name + " = " + expressionText
                }
            }
        }

        val knownIdentifiers = variables.keys + setOf("open", "high", "low", "close", "volume", "true", "false", "na")
        val compiledExpressions = buildList {
            addAll(variables.values)
            signals.mapNotNullTo(this) { it.compiledExpression }
            entries.mapTo(this) { it.compiledCondition }
            entries.mapNotNullTo(this) { it.compiledQuantity }
            exits.mapNotNullTo(this) { it.compiledStop }
            exits.mapNotNullTo(this) { it.compiledLimit }
            exits.mapNotNullTo(this) { it.compiledLossTicks }
            exits.mapNotNullTo(this) { it.compiledProfitTicks }
        }
        val unknownIdentifiers = compiledExpressions
            .flatMap { collectIdentifiers(it) }
            .filter { it !in knownIdentifiers }
            .distinct()
        unknownIdentifiers.forEach { identifier ->
            errors += "المتغير أو الرمز غير المدعوم: " + identifier + "؛ لا يمكن تقييمه من OHLC أو تعريفات السكربت الحالية."
        }

        if (signals.isEmpty() && entries.isEmpty()) warnings += "لم يتم العثور على alertcondition أو plotshape/plotchar أو strategy.entry قابل للتحويل إلى إشارة."
        if (entries.any { it.conditionText == "true" }) {
            warnings += "يوجد strategy.entry بلا شرط when؛ اعتُبر شرطه true دائماً. هذا لا يحاكي حالة المركز أو pyramiding أو تكرار أوامر المنصة."
        }
        if (entries.isNotEmpty() || exits.isNotEmpty()) {
            warnings += "تم تحويل أوامر الاستراتيجية إلى قواعد داخلية فقط؛ لا يوجد إرسال أوامر ولا ربط تلقائي بـ Paper Trading."
            if (exits.any { it.compiledLossTicks != null || it.compiledProfitTicks != null }) {
                warnings += "قيم loss/profit في strategy.exit محفوظة بوحدة ticks كما في Pine؛ يلزم tick size موثوق قبل تحويلها إلى أسعار."
            }
        }
        if (signals.any { it.direction == PineSignalDirection.UNKNOWN }) {
            warnings += "بعض الشروط لا تحمل اسماً صريحاً CALL أو PUT؛ ستنتج WAIT بدلاً من تخمين الاتجاه."
        }
        return result(kind, title, version)
    }

    /**
     * يحوّل كتل if/else ذات عبارة واحدة إلى عبارات محروسة.
     * لا يسمح بالتداخل أو تعدد العبارات؛ لا تُنفذ الكتل بل تُترجم شروطها إلى AST لاحقاً.
     */
    private fun flattenSimpleIfBlocks(
        lines: List<String>,
        errors: MutableList<String>
    ): List<SourceStatement> {
        val output = mutableListOf<SourceStatement>()

        fun clean(index: Int): String = lines[index].substringBefore("//").trim()
        fun indentation(index: Int): Int =
            lines[index].takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4 else 1 }

        fun nextMeaningful(start: Int): Int {
            var index = start
            while (index < lines.size && clean(index).isBlank()) index++
            return index
        }

        fun readBranch(start: Int, parentIndent: Int, ownerLine: Int, label: String): Pair<SourceStatement?, Int> {
            val first = nextMeaningful(start)
            if (first >= lines.size || indentation(first) <= parentIndent) {
                errors += "السطر " + ownerLine + ": كتلة " + label + " فارغة؛ يجب أن تحتوي عبارة واحدة مزاحة للداخل."
                return null to first
            }

            val bodyLines = mutableListOf<Int>()
            var cursor = first
            while (cursor < lines.size) {
                if (clean(cursor).isBlank()) {
                    cursor++
                    continue
                }
                if (indentation(cursor) <= parentIndent) break
                bodyLines += cursor
                cursor++
            }
            if (bodyLines.size != 1) {
                errors += "السطر " + ownerLine + ": يدعم المحوّل عبارة واحدة فقط داخل " + label + "؛ الكتل المتعددة أو المتداخلة مرفوضة."
                return null to cursor
            }

            val bodyIndex = bodyLines.single()
            val body = clean(bodyIndex)
            if (!Regex("""^(strategy\.entry|alertcondition|plotshape|plotchar)\s*\(""").containsMatchIn(body)) {
                errors += "السطر " + (bodyIndex + 1) + ": داخل if/else يُسمح حالياً بـ strategy.entry أو alertcondition أو plotshape أو plotchar فقط؛ لم تُحوّل العبارة."
                return null to cursor
            }
            return SourceStatement(bodyIndex + 1, body) to cursor
        }

        var index = 0
        while (index < lines.size) {
            val current = clean(index)
            if (current.isBlank() || current.startsWith("@version")) {
                index++
                continue
            }
            if (current == "else" || current.startsWith("else ")) {
                errors += "السطر " + (index + 1) + ": else بلا if مطابق أو else if غير مدعوم؛ استخدم كتلة if/else بسيطة."
                if (current.startsWith("else if")) {
                    val parentIndent = indentation(index)
                    index++
                    while (index < lines.size && (clean(index).isBlank() || indentation(index) > parentIndent)) index++
                } else {
                    index++
                }
                continue
            }

            val ifMatch = Regex("""^if\s+(.+)$""").matchEntire(current)
            if (ifMatch == null) {
                output += SourceStatement(index + 1, current)
                index++
                continue
            }

            val condition = ifMatch.groupValues[1].trim()
            if (condition.isBlank()) {
                errors += "السطر " + (index + 1) + ": شرط if فارغ."
                index++
                continue
            }
            val ownerLine = index + 1
            val parentIndent = indentation(index)
            val (trueBranch, afterTrue) = readBranch(index + 1, parentIndent, ownerLine, "if")
            trueBranch?.let { output += it.copy(guardText = condition) }

            val possibleElse = nextMeaningful(afterTrue)
            if (possibleElse < lines.size &&
                indentation(possibleElse) == parentIndent &&
                clean(possibleElse).startsWith("else")) {
                val elseText = clean(possibleElse)
                if (elseText != "else") {
                    errors += "السطر " + (possibleElse + 1) + ": else if غير مدعوم؛ استخدم if/else منفصلة أو شرطاً مركباً صريحاً."
                    var cursor = possibleElse + 1
                    while (cursor < lines.size && (clean(cursor).isBlank() || indentation(cursor) > parentIndent)) cursor++
                    index = cursor
                } else {
                    val (falseBranch, afterFalse) = readBranch(possibleElse + 1, parentIndent, possibleElse + 1, "else")
                    falseBranch?.let { output += it.copy(guardText = "not (" + condition + ")") }
                    index = afterFalse
                }
            } else {
                index = afterTrue
            }
        }
        return output
    }

    private fun splitNamedArgument(raw: String): Pair<String, String>? {
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in raw.indices) {
            val char = raw[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else {
                when (char) {
                    '"' -> quoted = true
                    '(' -> depth++
                    ')' -> depth--
                    '=' -> if (depth == 0) {
                        val previousIsEquals = index > 0 && raw[index - 1] == '='
                        val nextIsEquals = index + 1 < raw.length && raw[index + 1] == '='
                        if (!previousIsEquals && !nextIsEquals) {
                            val key = raw.substring(0, index).trim()
                            val value = raw.substring(index + 1).trim()
                            if (key.matches(Regex("""[A-Za-z_][A-Za-z0-9_]*""")) && value.isNotEmpty()) {
                                return key to value
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun duplicateNamedKeys(arguments: List<String>): Set<String> =
        arguments.mapNotNull { splitNamedArgument(it)?.first }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys

    private fun splitArguments(arguments: List<String>): Pair<List<String>, Map<String, String>> {
        val positional = mutableListOf<String>()
        val named = linkedMapOf<String, String>()
        arguments.forEach { raw ->
            val pair = splitNamedArgument(raw)
            if (pair == null) positional += raw
            else named[pair.first] = pair.second
        }
        return positional to named
    }

    private fun stringLiteral(source: String, line: Int, label: String, errors: MutableList<String>): String? {
        val parsed = try {
            PineExpressionParser.parse(source)
        } catch (error: PineParseException) {
            errors += "السطر " + line + ": " + label + " يجب أن يكون نصاً ثابتاً بين علامتي اقتباس."
            return null
        }
        return (parsed as? PineExpr.StringLiteral)?.value ?: run {
            errors += "السطر " + line + ": " + label + " يجب أن يكون نصاً ثابتاً بين علامتي اقتباس."
            null
        }
    }

    private fun parseStrategyEntry(
        rawArguments: List<String>,
        line: Int,
        kind: ScriptKind?,
        version: Int?,
        guardText: String?,
        entries: MutableList<EntryRule>,
        errors: MutableList<String>
    ) {
        if (kind != ScriptKind.STRATEGY) {
            errors += "السطر " + line + ": strategy.entry لا يُسمح به داخل indicator؛ يجب أن يكون التصريح strategy()."
            return
        }
        val duplicates = duplicateNamedKeys(rawArguments)
        if (duplicates.isNotEmpty()) {
            errors += "السطر " + line + ": وسائط strategy.entry مكررة: " + duplicates.sorted().joinToString(", ")
            return
        }
        val (positional, named) = splitArguments(rawArguments)
        if (positional.size !in 2..3) {
            errors += "السطر " + line + ": strategy.entry يتطلب id وdirection، مع qty اختيارية؛ الوسائط الموضعية الأخرى غير مدعومة."
            return
        }
        if (version == 6 && "when" in named) {
            errors += "السطر " + line + ": وسيط when أزيل من strategy.entry في Pine v6؛ استخدم كتلة if بسيطة مدعومة لتحديد شرط الدخول."
            return
        }
        val unknown = named.keys - setOf("qty", "when")
        if (unknown.isNotEmpty()) {
            errors += "السطر " + line + ": وسائط strategy.entry غير المدعومة: " + unknown.sorted().joinToString(", ")
            return
        }
        if ("qty" in named && positional.size == 3) {
            errors += "السطر " + line + ": لا تكرر qty كوسيط موضعي ومسمّى معاً."
            return
        }
        val id = stringLiteral(positional[0], line, "id", errors) ?: return
        val directionExpr = try {
            PineExpressionParser.parse(positional[1])
        } catch (error: PineParseException) {
            errors += "السطر " + line + ": direction يجب أن يكون strategy.long أو strategy.short."
            return
        }
        val direction = when ((directionExpr as? PineExpr.Identifier)?.name) {
            "strategy.long" -> StrategyDirection.LONG
            "strategy.short" -> StrategyDirection.SHORT
            else -> {
                errors += "السطر " + line + ": direction يجب أن يكون strategy.long أو strategy.short."
                return
            }
        }
        val conditionText = when {
            guardText == null -> named["when"] ?: "true"
            named["when"] == null -> guardText
            else -> "(" + guardText + ") and (" + named["when"] + ")"
        }
        val compiledCondition = parseExpression(conditionText, line, errors) ?: return
        val quantityText = named["qty"] ?: positional.getOrNull(2)
        val compiledQuantity = quantityText?.let { parseExpression(it, line, errors) }
        if (quantityText != null && compiledQuantity == null) return
        entries += EntryRule(id, direction, conditionText, line, compiledCondition, quantityText, compiledQuantity)
    }

    private fun parseStrategyExit(
        rawArguments: List<String>,
        line: Int,
        kind: ScriptKind?,
        exits: MutableList<ExitRule>,
        errors: MutableList<String>
    ) {
        if (kind != ScriptKind.STRATEGY) {
            errors += "السطر " + line + ": strategy.exit لا يُسمح به داخل indicator؛ يجب أن يكون التصريح strategy()."
            return
        }
        val duplicates = duplicateNamedKeys(rawArguments)
        if (duplicates.isNotEmpty()) {
            errors += "السطر " + line + ": وسائط strategy.exit مكررة: " + duplicates.sorted().joinToString(", ")
            return
        }
        val (positional, named) = splitArguments(rawArguments)
        if (positional.isEmpty() || positional.size > 2) {
            errors += "السطر " + line + ": strategy.exit يتطلب id ونص from_entry اختيارياً؛ استخدم الوسائط المسمّاة لمستويات الخروج."
            return
        }
        val unknown = named.keys - setOf("from_entry", "stop", "limit", "loss", "profit")
        if (unknown.isNotEmpty()) {
            errors += "السطر " + line + ": وسائط strategy.exit غير المدعومة: " + unknown.sorted().joinToString(", ")
            return
        }
        if ("from_entry" in named && positional.size == 2) {
            errors += "السطر " + line + ": لا تكرر from_entry موضعياً ومسمّى معاً."
            return
        }
        val id = stringLiteral(positional[0], line, "id", errors) ?: return
        val fromEntryText = named["from_entry"] ?: positional.getOrNull(1)
        val fromEntry = fromEntryText?.let { stringLiteral(it, line, "from_entry", errors) }
        if (fromEntryText != null && fromEntry == null) return

        val stopText = named["stop"]
        val limitText = named["limit"]
        val lossText = named["loss"]
        val profitText = named["profit"]
        if (stopText == null && limitText == null && lossText == null && profitText == null) {
            errors += "السطر " + line + ": strategy.exit يحتاج واحداً على الأقل من stop أو limit أو loss أو profit؛ أوامر trailing غير مدعومة."
            return
        }
        if ((stopText != null && lossText != null) || (limitText != null && profitText != null)) {
            errors += "السطر " + line + ": الجمع بين stop وloss أو بين limit وprofit غير مدعوم بعد؛ رُفض لتجنب تغيير أولوية الخروج الأصلية."
            return
        }
        val stop = stopText?.let { parseExpression(it, line, errors) }
        val limit = limitText?.let { parseExpression(it, line, errors) }
        val loss = lossText?.let { parseExpression(it, line, errors) }
        val profit = profitText?.let { parseExpression(it, line, errors) }
        if ((stopText != null && stop == null) || (limitText != null && limit == null) ||
            (lossText != null && loss == null) || (profitText != null && profit == null)) return
        exits += ExitRule(id, fromEntry, line, stopText, stop, limitText, limit, lossText, loss, profitText, profit)
    }

    private fun parseExpression(source: String, line: Int, errors: MutableList<String>): PineExpr? {
        return try {
            val expression = PineExpressionParser.parse(source)
            val unsupported = collectCalls(expression).firstOrNull { call ->
                call !in allowedCalls && call !in setOf("ta.macd.line", "ta.macd.signal", "ta.macd.histogram")
            }
            if (unsupported != null) {
                errors += "السطر " + line + ": الدالة غير المدعومة داخل التعبير: " + unsupported
                null
            } else expression
        } catch (error: PineParseException) {
            errors += "السطر " + line + ": تعذر تحليل التعبير: " + error.message
            null
        }
    }

    private fun collectCalls(expression: PineExpr): List<String> = when (expression) {
        is PineExpr.Call -> listOf(expression.name) + expression.arguments.flatMap { collectCalls(it.value) }
        is PineExpr.Unary -> collectCalls(expression.operand)
        is PineExpr.Binary -> collectCalls(expression.left) + collectCalls(expression.right)
        else -> emptyList()
    }

    private fun collectIdentifiers(expression: PineExpr): List<String> = when (expression) {
        is PineExpr.Identifier -> listOf(expression.name)
        is PineExpr.Call -> expression.arguments.flatMap { collectIdentifiers(it.value) }
        is PineExpr.Unary -> collectIdentifiers(expression.operand)
        is PineExpr.Binary -> collectIdentifiers(expression.left) + collectIdentifiers(expression.right)
        else -> emptyList()
    }

    private fun directionFor(name: String): PineSignalDirection = when (name.trim().uppercase()) {
        "CALL", "BUY", "LONG" -> PineSignalDirection.CALL
        "PUT", "SELL", "SHORT" -> PineSignalDirection.PUT
        else -> PineSignalDirection.UNKNOWN
    }

    private fun callArguments(line: String, functionName: String): List<String> {
        val start = line.indexOf(functionName)
        if (start < 0) return emptyList()
        val open = line.indexOf('(', start)
        var depth = 0
        var quoted = false
        var escaped = false
        var end = -1
        for (i in open until line.length) {
            val char = line[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else {
                if (char == '"') quoted = true
                else if (char == '(') depth++
                else if (char == ')') {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
        }
        if (end < 0) return emptyList()
        return splitTopLevel(line.substring(open + 1, end))
    }

    private fun splitTopLevel(source: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var quoted = false
        var escaped = false
        var start = 0
        source.forEachIndexed { index, char ->
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else {
                when (char) {
                    '"' -> quoted = true
                    '(' -> depth++
                    ')' -> depth--
                    ',' -> if (depth == 0) {
                        result += source.substring(start, index).trim()
                        start = index + 1
                    }
                }
            }
        }
        val last = source.substring(start).trim()
        if (last.isNotEmpty()) result += last
        return result
    }
}
