package io.github.springthief1123.lovelyspace.core.chat

/**
 * `ajax.php` の応答（`a = 1; b = 'x'; c = new Array('..', '..');` のような代入文の並び）を
 * eval せずに読む。値は数値・真偽値・null・文字列・文字列の配列だけを扱う。
 */
internal object JsAssignments {
    sealed interface Value {
        data class Str(val value: String) : Value
        data class Num(val value: Double) : Value
        data class Bool(val value: Boolean) : Value
        data class Arr(val items: List<Value>) : Value
        data object Null : Value
    }

    /** 代入先の名前（`gRoomVars.fromsize` など）→ 値。連鎖代入 `a = b = 1` は両方に入る。 */
    fun parse(source: String): Map<String, Value> {
        val result = linkedMapOf<String, Value>()
        val s = Scanner(source.replace(XML_DECLARATION, ""))
        while (true) {
            s.skipSeparators()
            if (s.atEnd) break
            val names = mutableListOf<String>()
            var value: Value? = null
            while (!s.atEnd) {
                s.skipSpace()
                val start = s.pos
                val ident = s.identifier()
                s.skipSpace()
                if (ident != null && s.peek() == '=' && s.peek(1) != '=') {
                    s.advance()
                    names += ident
                    continue
                }
                s.pos = start
                value = s.value()
                break
            }
            if (value != null) names.forEach { result[it] = value }
            s.skipToStatementEnd()
        }
        return result
    }

    private val XML_DECLARATION = Regex("""^\s*<\?xml[^>]*\?>""")

    private class Scanner(private val src: String) {
        var pos = 0
        val atEnd: Boolean get() = pos >= src.length

        fun peek(offset: Int = 0): Char? = src.getOrNull(pos + offset)
        fun advance() { pos++ }

        fun skipSpace() {
            while (!atEnd && src[pos].isWhitespace()) pos++
        }

        fun skipSeparators() {
            while (!atEnd && (src[pos].isWhitespace() || src[pos] == ';')) pos++
        }

        /** 文の終わり（文字列の外の `;`）まで読み飛ばす。読めない値があっても次の文から続けられるように。 */
        fun skipToStatementEnd() {
            var depth = 0
            while (!atEnd) {
                when (val c = src[pos]) {
                    '\'', '"' -> { string(c); continue }
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    ';' -> if (depth <= 0) { pos++; return }
                }
                pos++
            }
        }

        fun identifier(): String? {
            val start = pos
            while (!atEnd && (src[pos].isLetterOrDigit() || src[pos] == '_' || src[pos] == '$' || src[pos] == '.')) pos++
            return if (pos > start && !src[start].isDigit()) src.substring(start, pos) else { pos = start; null }
        }

        fun value(): Value? {
            skipSpace()
            val c = peek() ?: return null
            return when {
                c == '\'' || c == '"' -> Value.Str(string(c))
                c == '[' -> { pos++; Value.Arr(items(']')) }
                src.startsWith("new Array", pos) -> {
                    pos += "new Array".length
                    skipSpace()
                    if (peek() != '(') return null
                    pos++
                    Value.Arr(items(')'))
                }
                c == '-' || c.isDigit() -> number()
                src.startsWith("true", pos) -> { pos += 4; Value.Bool(true) }
                src.startsWith("false", pos) -> { pos += 5; Value.Bool(false) }
                src.startsWith("null", pos) -> { pos += 4; Value.Null }
                else -> null
            }
        }

        private fun items(close: Char): List<Value> {
            val list = mutableListOf<Value>()
            while (true) {
                skipSpace()
                if (atEnd) return list
                if (peek() == close) { pos++; return list }
                if (peek() == ',') { pos++; continue }
                val v = value() ?: return list
                list += v
            }
        }

        private fun number(): Value? {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd && (src[pos].isDigit() || src[pos] == '.')) pos++
            return src.substring(start, pos).toDoubleOrNull()?.let { Value.Num(it) }
        }

        /** 引用符で囲まれた JS 文字列を読み、エスケープを戻す。 */
        fun string(quote: Char): String {
            pos++ // 開き引用符
            val sb = StringBuilder()
            while (!atEnd) {
                val c = src[pos++]
                if (c == quote) return sb.toString()
                if (c != '\\' || atEnd) { sb.append(c); continue }
                when (val e = src[pos++]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    '0' -> sb.append('\u0000')
                    'x' -> hex(2)?.let { sb.append(it) } ?: sb.append('x')
                    'u' -> hex(4)?.let { sb.append(it) } ?: sb.append('u')
                    '\n' -> Unit // 行継続
                    else -> sb.append(e) // \' \" \\ \/ など
                }
            }
            return sb.toString()
        }

        private fun hex(len: Int): Char? {
            if (pos + len > src.length) return null
            val code = src.substring(pos, pos + len).toIntOrNull(16) ?: return null
            pos += len
            return code.toChar()
        }
    }
}

internal fun JsAssignments.Value?.asString(): String? = (this as? JsAssignments.Value.Str)?.value

internal fun JsAssignments.Value?.asInt(): Int? = when (this) {
    is JsAssignments.Value.Num -> value.toInt()
    is JsAssignments.Value.Str -> value.trim().toIntOrNull()
    else -> null
}

internal fun JsAssignments.Value?.asLong(): Long? = when (this) {
    is JsAssignments.Value.Num -> value.toLong()
    is JsAssignments.Value.Str -> value.trim().toLongOrNull()
    else -> null
}

/** JS の真偽判定に合わせる（1 / "1" / true は true、0 / "" / null は false）。 */
internal fun JsAssignments.Value?.asFlag(): Boolean? = when (this) {
    null -> null
    is JsAssignments.Value.Bool -> value
    is JsAssignments.Value.Num -> value != 0.0
    is JsAssignments.Value.Str -> value.isNotEmpty() && value != "0"
    is JsAssignments.Value.Arr -> true
    JsAssignments.Value.Null -> false
}
