package app.iptvplayer.protocols.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/**
 * Tolerant field access for provider JSON (docs/IPTV_PROTOCOLS.md §4.3): numbers as strings, booleans as 0/1, `null`,
 * `""` and missing all mean absent, `[]` where an object was expected. Values that cannot be coerced are reported
 * through [onMismatch] (field name only, never the value) and treated as absent.
 */
internal class LenientObject(private val obj: JsonObject, private val onMismatch: (field: String) -> Unit) {
    fun has(field: String): Boolean = present(obj[field]) != null

    fun string(field: String): String? {
        val element = present(obj[field]) ?: return null
        return when (element) {
            is JsonPrimitive -> element.content.trim().ifEmpty { null }
            else -> mismatch(field)
        }
    }

    fun long(field: String): Long? {
        val element = present(obj[field]) ?: return null
        val primitive = element as? JsonPrimitive ?: return mismatch(field)
        val text = primitive.content.trim()
        return text.toLongOrNull() ?: text.toDoubleOrNull()?.takeIf { it == kotlin.math.floor(it) && it in -9.0e15..9.0e15 }?.toLong()
            ?: mismatch(field)
    }

    fun int(field: String): Int? = long(field)?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else mismatch(field) }

    fun double(field: String): Double? {
        val element = present(obj[field]) ?: return null
        val primitive = element as? JsonPrimitive ?: return mismatch(field)
        return primitive.content.trim().toDoubleOrNull() ?: mismatch(field)
    }

    /** `1`, `"1"`, `true`, `"true"` → true; `0`, `"0"`, `false`, `"false"` → false. */
    fun bool(field: String): Boolean? {
        val element = present(obj[field]) ?: return null
        val primitive = element as? JsonPrimitive ?: return mismatch(field)
        return when (primitive.content.trim().lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> mismatch(field)
        }
    }

    fun epochSeconds(field: String): Instant? = long(field)?.let { Instant.fromEpochSeconds(it) }

    /** A scalar or an array of scalars, as used by `category_id` / `category_ids`. */
    fun stringList(field: String): List<String> {
        val element = present(obj[field]) ?: return emptyList()
        return when (element) {
            is JsonArray -> element.mapNotNull { item -> scalarText(item) }
            is JsonPrimitive -> listOfNotNull(element.content.trim().ifEmpty { null })
            else -> mismatch(field) ?: emptyList()
        }
    }

    private fun scalarText(item: JsonElement): String? {
        val primitive = item as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content.trim().ifEmpty { null }
    }

    /** An object, or null when absent, `[]` (panel quirk for "empty") or another type. */
    fun obj(field: String): LenientObject? {
        val element = present(obj[field]) ?: return null
        return when {
            element is JsonObject -> LenientObject(element, onMismatch)
            element is JsonArray && element.isEmpty() -> null
            else -> mismatch(field)
        }
    }

    fun array(field: String): JsonArray? = present(obj[field]) as? JsonArray

    fun raw(field: String): JsonElement? = present(obj[field])

    private fun present(element: JsonElement?): JsonElement? = when {
        element == null || element is JsonNull -> null
        element is JsonPrimitive && element.isString && element.content.isEmpty() -> null
        else -> element
    }

    private fun <T> mismatch(field: String): T? {
        onMismatch(field)
        return null
    }

    companion object {
        /** Wraps [element] when it is an object; `[]` and other types yield null. */
        fun of(element: JsonElement?, onMismatch: (String) -> Unit): LenientObject? =
            (element as? JsonObject)?.let { LenientObject(it, onMismatch) }
    }
}

/** Decodes the HTML entities panels leak into names (`&amp;`, `&#39;`, `&#x27;`). Unknown entities are kept. */
internal object HtmlEntities {
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    fun decode(text: String): String {
        if ('&' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val semicolon = if (c == '&') text.indexOf(';', i + 1) else -1
            if (semicolon in (i + 2)..(i + 10)) {
                val name = text.substring(i + 1, semicolon)
                val decoded = when {
                    name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
                    name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
                    else -> NAMED[name.lowercase()]
                }
                if (decoded != null) {
                    out.append(decoded)
                    i = semicolon + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun codePoint(value: Int): String? = when {
        value in 0x20..0xD7FF || value in 0xE000..0xFFFD -> value.toChar().toString()
        value in 0x10000..0x10FFFF -> {
            val v = value - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
        else -> null
    }
}
