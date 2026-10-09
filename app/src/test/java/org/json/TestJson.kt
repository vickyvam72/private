package org.json

import java.lang.StringBuilder
import java.util.LinkedHashMap

/** Small JVM test double for Android's org.json API used by local unit tests. */
class JSONObject {
    private val values = LinkedHashMap<String, Any?>()

    constructor()
    constructor(source: String) {
        val parsed = Parser(source).value()
        require(parsed is JSONObject) { "JSON root is not an object" }
        values.putAll(parsed.values)
    }

    fun put(key: String, value: Any?): JSONObject = apply {
        if (value == null) values.remove(key) else values[key] = value
    }
    fun put(key: String, value: Boolean): JSONObject = put(key, value as Any)
    fun put(key: String, value: Double): JSONObject = put(key, value as Any)
    fun put(key: String, value: Int): JSONObject = put(key, value as Any)
    fun put(key: String, value: Long): JSONObject = put(key, value as Any)
    fun opt(key: String): Any? = values[key]
    fun has(key: String): Boolean = values.containsKey(key)
    fun isNull(key: String): Boolean = !has(key) || values[key] === NULL
    fun keys(): MutableIterator<String> = values.keys.iterator()
    fun getString(key: String): String = required(key).toString()
    fun optString(key: String): String = if (isNull(key)) "" else values[key].toString()
    fun optString(key: String, fallback: String): String = if (isNull(key)) fallback else values[key].toString()
    fun getDouble(key: String): Double = number(required(key)).toDouble()
    fun optDouble(key: String): Double = if (isNull(key)) Double.NaN else number(values[key]).toDouble()
    fun optDouble(key: String, fallback: Double): Double = if (isNull(key)) fallback else number(values[key]).toDouble()
    fun getInt(key: String): Int = number(required(key)).toInt()
    fun optInt(key: String): Int = if (isNull(key)) 0 else number(values[key]).toInt()
    fun optInt(key: String, fallback: Int): Int = if (isNull(key)) fallback else number(values[key]).toInt()
    fun getLong(key: String): Long = number(required(key)).toLong()
    fun optLong(key: String): Long = if (isNull(key)) 0L else number(values[key]).toLong()
    fun optLong(key: String, fallback: Long): Long = if (isNull(key)) fallback else number(values[key]).toLong()
    fun getBoolean(key: String): Boolean = boolean(required(key))
    fun optBoolean(key: String): Boolean = if (isNull(key)) false else boolean(values[key])
    fun optBoolean(key: String, fallback: Boolean): Boolean = if (isNull(key)) fallback else boolean(values[key])
    fun getJSONArray(key: String): JSONArray = required(key) as JSONArray
    fun optJSONArray(key: String): JSONArray? = values[key] as? JSONArray
    fun optJSONObject(key: String): JSONObject? = values[key] as? JSONObject
    private fun required(key: String): Any = values[key]?.takeUnless { it === NULL }
        ?: throw IllegalArgumentException("Missing JSON key: $key")
    override fun toString(): String = values.entries.joinToString(",", "{", "}") {
        quote(it.key) + ":" + render(it.value)
    }

    companion object {
        @JvmField val NULL: Any = object {
            override fun equals(other: Any?): Boolean = other == null || other === this
            override fun toString(): String = "null"
        }
        internal fun number(value: Any?): Number = when (value) {
            is Number -> value
            is String -> value.toDouble()
            else -> throw IllegalArgumentException("Not a number: $value")
        }
        internal fun boolean(value: Any?): Boolean = when (value) {
            is Boolean -> value
            is String -> value.equals("true", true)
            else -> throw IllegalArgumentException("Not a boolean: $value")
        }
        internal fun quote(value: String): String = buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"', '\\' -> append('\\').append(character)
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }
        internal fun render(value: Any?): String = when (value) {
            null, NULL -> "null"
            is String -> quote(value)
            is JSONObject, is JSONArray, is Number, is Boolean -> value.toString()
            else -> quote(value.toString())
        }
    }
}

class JSONArray {
    private val values = mutableListOf<Any?>()
    constructor()
    constructor(collection: Collection<*>) { values.addAll(collection) }
    fun put(value: Any?): JSONArray = apply { values.add(value ?: JSONObject.NULL) }
    fun put(value: Boolean): JSONArray = put(value as Any)
    fun put(value: Double): JSONArray = put(value as Any)
    fun put(value: Int): JSONArray = put(value as Any)
    fun put(value: Long): JSONArray = put(value as Any)
    fun length(): Int = values.size
    fun opt(index: Int): Any? = values.getOrNull(index)
    fun isNull(index: Int): Boolean = values.getOrNull(index) == null || values.getOrNull(index) === JSONObject.NULL
    fun getString(index: Int): String = values[index].toString()
    fun optString(index: Int): String = values.getOrNull(index)?.takeUnless { it === JSONObject.NULL }?.toString().orEmpty()
    fun optString(index: Int, fallback: String): String = values.getOrNull(index)?.takeUnless { it === JSONObject.NULL }?.toString() ?: fallback
    fun optDouble(index: Int): Double = values.getOrNull(index)?.takeUnless { it === JSONObject.NULL }?.let(JSONObject::number)?.toDouble() ?: Double.NaN
    fun optDouble(index: Int, fallback: Double): Double = values.getOrNull(index)?.takeUnless { it === JSONObject.NULL }?.let(JSONObject::number)?.toDouble() ?: fallback
    fun optLong(index: Int): Long = values.getOrNull(index)?.takeUnless { it === JSONObject.NULL }?.let(JSONObject::number)?.toLong() ?: 0L
    fun getJSONObject(index: Int): JSONObject = values[index] as JSONObject
    fun optJSONObject(index: Int): JSONObject? = values.getOrNull(index) as? JSONObject
    fun optJSONArray(index: Int): JSONArray? = values.getOrNull(index) as? JSONArray
    override fun toString(): String = values.joinToString(",", "[", "]") { JSONObject.render(it) }
}

private class Parser(private val text: String) {
    private var index = 0
    fun value(): Any? {
        whitespace()
        return when (text.getOrNull(index)) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> stringValue()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", JSONObject.NULL)
            else -> numberValue()
        }
    }
    private fun objectValue(): JSONObject {
        expect('{'); val result = JSONObject(); whitespace()
        if (peek('}')) { index++; return result }
        while (true) {
            whitespace(); val key = stringValue(); whitespace(); expect(':')
            result.put(key, value()); whitespace()
            if (peek('}')) { index++; return result }
            expect(',')
        }
    }
    private fun arrayValue(): JSONArray {
        expect('['); val result = JSONArray(); whitespace()
        if (peek(']')) { index++; return result }
        while (true) {
            result.put(value()); whitespace()
            if (peek(']')) { index++; return result }
            expect(',')
        }
    }
    private fun stringValue(): String {
        expect('"'); val result = StringBuilder()
        while (index < text.length) {
            val character = text[index++]
            if (character == '"') return result.toString()
            if (character != '\\') result.append(character) else {
                val escaped = text[index++]
                result.append(when (escaped) {
                    '"', '\\', '/' -> escaped
                    'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    'u' -> text.substring(index, index + 4).toInt(16).toChar().also { index += 4 }
                    else -> error("Invalid escape")
                })
            }
        }
        error("Unterminated string")
    }
    private fun numberValue(): Number {
        whitespace(); val start = index
        while (index < text.length && text[index] in "-+0123456789.eE") index++
        val token = text.substring(start, index)
        return if (token.contains('.') || token.contains('e', true)) token.toDouble() else token.toLong()
    }
    private fun <T> literal(token: String, result: T): T {
        require(text.startsWith(token, index)); index += token.length; return result
    }
    private fun expect(character: Char) { whitespace(); require(text.getOrNull(index) == character); index++ }
    private fun peek(character: Char): Boolean = text.getOrNull(index) == character
    private fun whitespace() { while (text.getOrNull(index)?.isWhitespace() == true) index++ }
}
