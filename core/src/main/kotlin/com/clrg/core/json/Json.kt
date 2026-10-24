package com.clrg.core.json

/**
 * Minimal JSON reader/writer for settings, rosters and tournament persistence (no external dependency).
 * Values map to: null, Boolean, Double, String, List<Any?>, Map<String, Any?>.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw IllegalArgumentException("Trailing characters at ${p.pos}")
        return v
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> writeDouble(sb, v.toDouble())
            is Double -> writeDouble(sb, v)
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, e)
                }
                sb.append(']')
            }
            is IntArray -> write(sb, v.toList())
            is FloatArray -> write(sb, v.toList())
            is Enum<*> -> writeString(sb, v.name)
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong().toString()) else sb.append(d.toString())
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        fun readValue(): Any? {
            skipWs()
            if (atEnd()) throw IllegalArgumentException("Unexpected end of JSON")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw IllegalArgumentException("Unexpected '$c' at $pos")
            }
        }
        fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw IllegalArgumentException("Bad literal at $pos")
            pos += word.length
            return v
        }
        fun readNumber(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return s.substring(start, pos).toDouble()
        }
        fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[pos++]
                        when (e) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                            else -> throw IllegalArgumentException("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun readArray(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWs()
            if (s[pos] == ']') { pos++; return out }
            while (true) {
                out.add(readValue())
                skipWs()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("Expected , or ] at ${pos - 1}")
                }
            }
        }
        fun readObject(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                if (s[pos++] != ':') throw IllegalArgumentException("Expected : at ${pos - 1}")
                out[key] = readValue()
                skipWs()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalArgumentException("Expected , or } at ${pos - 1}")
                }
            }
        }
    }
}

/** Convenience accessors for parsed JSON maps. */
@Suppress("UNCHECKED_CAST")
class JObj(val map: Map<String, Any?>) {
    fun str(k: String, def: String = ""): String = map[k] as? String ?: def
    fun int(k: String, def: Int = 0): Int = (map[k] as? Number)?.toInt() ?: def
    fun long(k: String, def: Long = 0): Long = (map[k] as? Number)?.toLong() ?: def
    fun float(k: String, def: Float = 0f): Float = (map[k] as? Number)?.toFloat() ?: def
    fun bool(k: String, def: Boolean = false): Boolean = map[k] as? Boolean ?: def
    fun obj(k: String): JObj? = (map[k] as? Map<String, Any?>)?.let { JObj(it) }
    fun list(k: String): List<Any?> = map[k] as? List<Any?> ?: emptyList()
    fun objList(k: String): List<JObj> = list(k).mapNotNull { (it as? Map<String, Any?>)?.let(::JObj) }
    fun has(k: String) = map.containsKey(k)

    companion object {
        fun parse(text: String?): JObj? = try {
            if (text.isNullOrBlank()) null else (Json.parse(text) as? Map<String, Any?>)?.let(::JObj)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }
}
