package com.astra.autotrader.pine

/**
 * تعبيرات Pine المدعومة. هذه شجرة بيانات فقط؛ لا تنفذ نصوصاً ولا تستعمل eval.
 */
sealed interface PineExpr {
    data class NumberLiteral(val value: Double) : PineExpr
    data class StringLiteral(val value: String) : PineExpr
    data class Identifier(val name: String) : PineExpr
    data class Unary(val operator: String, val operand: PineExpr) : PineExpr
    data class Binary(val operator: String, val left: PineExpr, val right: PineExpr) : PineExpr
    data class Call(val name: String, val arguments: List<Argument>) : PineExpr
    data class Argument(val name: String?, val value: PineExpr)
}

class PineParseException(message: String) : IllegalArgumentException(message)

/**
 * محلل Pratt صغير لمجموعة تعبيرات محددة من Pine v5.
 * أي رمز أو تركيب غير معروف يُرفض بدلاً من تخمين معناه.
 */
object PineExpressionParser {
    private enum class Kind { NUMBER, STRING, IDENTIFIER, OPERATOR, LEFT_PAREN, RIGHT_PAREN, COMMA, END }
    private data class Token(val kind: Kind, val text: String)

    fun parse(source: String): PineExpr {
        val parser = Parser(tokenize(source))
        val expression = parser.expression()
        if (!parser.atEnd()) throw PineParseException("رمز غير متوقع بعد نهاية التعبير: " + parser.current().text)
        return expression
    }

    private fun tokenize(source: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c.isWhitespace()) { i++; continue }
            if (c.isDigit() || (c == '.' && i + 1 < source.length && source[i + 1].isDigit())) {
                val start = i
                var dotSeen = false
                if (source[i] == '.') { dotSeen = true; i++ }
                while (i < source.length) {
                    val n = source[i]
                    if (n.isDigit()) i++
                    else if (n == '.' && !dotSeen) { dotSeen = true; i++ }
                    else break
                }
                val number = source.substring(start, i).toDoubleOrNull()
                    ?: throw PineParseException("قيمة رقمية غير صالحة.")
                tokens += Token(Kind.NUMBER, number.toString())
                continue
            }
            if (c == '"') {
                i++
                val value = StringBuilder()
                var closed = false
                while (i < source.length) {
                    val ch = source[i++]
                    if (ch == '"') { closed = true; break }
                    if (ch == '\\' && i < source.length) {
                        val escaped = source[i++]
                        value.append(when (escaped) { 'n' -> '\n'; 't' -> '\t'; else -> escaped })
                    } else value.append(ch)
                }
                if (!closed) throw PineParseException("نص غير مغلق بعلامة اقتباس.")
                tokens += Token(Kind.STRING, value.toString())
                continue
            }
            if (c.isLetter() || c == '_') {
                val start = i++
                while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '.')) i++
                tokens += Token(Kind.IDENTIFIER, source.substring(start, i))
                continue
            }
            if (c == '(') { tokens += Token(Kind.LEFT_PAREN, "("); i++; continue }
            if (c == ')') { tokens += Token(Kind.RIGHT_PAREN, ")"); i++; continue }
            if (c == ',') { tokens += Token(Kind.COMMA, ","); i++; continue }
            val two = if (i + 1 < source.length) source.substring(i, i + 2) else ""
            if (two in setOf(">=", "<=", "==", "!=")) {
                tokens += Token(Kind.OPERATOR, two); i += 2; continue
            }
            if (c in setOf('+', '-', '*', '/', '%', '>', '<', '=')) {
                tokens += Token(Kind.OPERATOR, c.toString()); i++; continue
            }
            if (c == '[' || c == ']') {
                throw PineParseException("فهرسة الشموع [ ] غير مدعومة بعد؛ لا يمكن تحويل هذا التعبير بأمان.")
            }
            throw PineParseException("رمز غير مدعوم في التعبير: " + c)
        }
        tokens += Token(Kind.END, "<نهاية>")
        return tokens
    }

    private class Parser(private val tokens: List<Token>) {
        private var position = 0
        fun current(): Token = tokens[position]
        fun atEnd(): Boolean = current().kind == Kind.END
        private fun advance(): Token = tokens[position++]
        private fun match(kind: Kind, text: String? = null): Boolean {
            if (current().kind != kind || (text != null && current().text != text)) return false
            advance()
            return true
        }

        fun expression(minPrecedence: Int = 1): PineExpr {
            var left = prefix()
            while (current().kind == Kind.OPERATOR || (current().kind == Kind.IDENTIFIER && current().text in setOf("and", "or"))) {
                val op = current().text
                val precedence = precedence(op)
                if (precedence < minPrecedence) break
                advance()
                val right = expression(precedence + 1)
                left = PineExpr.Binary(op, left, right)
            }
            return left
        }

        private fun prefix(): PineExpr {
            val token = advance()
            return when (token.kind) {
                Kind.NUMBER -> PineExpr.NumberLiteral(token.text.toDouble())
                Kind.STRING -> PineExpr.StringLiteral(token.text)
                Kind.IDENTIFIER -> when (token.text) {
                    "not", "!" -> PineExpr.Unary("not", expression(6))
                    "true" -> PineExpr.Identifier("true")
                    "false" -> PineExpr.Identifier("false")
                    else -> if (match(Kind.LEFT_PAREN)) call(token.text) else PineExpr.Identifier(token.text)
                }
                Kind.OPERATOR -> if (token.text == "-" || token.text == "+") {
                    PineExpr.Unary(token.text, expression(6))
                } else throw PineParseException("عامل غير متوقع: " + token.text)
                Kind.LEFT_PAREN -> {
                    val nested = expression()
                    if (!match(Kind.RIGHT_PAREN)) throw PineParseException("قوس إغلاق مفقود.")
                    nested
                }
                else -> throw PineParseException("بداية تعبير غير صالحة: " + token.text)
            }
        }

        private fun call(name: String): PineExpr {
            val args = mutableListOf<PineExpr.Argument>()
            if (match(Kind.RIGHT_PAREN)) return PineExpr.Call(name, args)
            while (true) {
                val argumentName = if (
                    current().kind == Kind.IDENTIFIER &&
                    position + 1 < tokens.size &&
                    tokens[position + 1].kind == Kind.OPERATOR &&
                    tokens[position + 1].text == "="
                ) {
                    val key = advance().text
                    advance()
                    key
                } else null
                args += PineExpr.Argument(argumentName, expression())
                if (match(Kind.RIGHT_PAREN)) break
                if (!match(Kind.COMMA)) throw PineParseException("كان متوقعاً فاصلة أو قوس إغلاق داخل استدعاء " + name)
            }
            return PineExpr.Call(name, args)
        }

        private fun precedence(operator: String): Int = when (operator) {
            "or" -> 1
            "and" -> 2
            "==", "!=", ">", ">=", "<", "<=" -> 3
            "+", "-" -> 4
            "*", "/", "%" -> 5
            else -> -1
        }
    }
}
