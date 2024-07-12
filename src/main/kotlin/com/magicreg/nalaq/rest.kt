package com.magicreg.nalaq

import jakarta.json.*
import org.apache.commons.beanutils.BeanMap
import java.io.StringReader
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import kotlin.reflect.KFunction

class RestParser(): Parser {

    override fun parse(txt: String): Expression {
        return parseText(txt)
    }

    override fun toString(): String {
        return "RestParser"
    }
}

fun restGet(path: JsonPointer, value: JsonValue): JsonValue {
    val ns = if (path is UriPointer) path.namespace else getContext()
    try { return path.getValue(NamespaceWrapper(ns)) }
    catch (e: Exception) { return value }
}

fun restPost(path: JsonPointer, value: JsonValue): JsonValue {
    val ns = if (path is UriPointer) path.namespace else getContext()
    val map = NamespaceWrapper(ns)
    try { path.add(map, value) }
    catch (e: Exception) {}
    return path.getValue(map)
}

fun restPut(path: JsonPointer, value: JsonValue): JsonValue {
    val ns = if (path is UriPointer) path.namespace else getContext()
    val map = NamespaceWrapper(ns)
    try { path.replace(map, value) }
    catch (e: Exception) {}
    return path.getValue(map)
}

fun restPatch(path: JsonPointer, value: JsonValue): JsonValue {
    if (path is UriPointer)
        throw RuntimeException("PATCH operation is not supported for URI")
    if (value.valueType != JsonValue.ValueType.ARRAY)
        throw RuntimeException("PATCH operation needs an array value but got "+value.valueType)
    val patch = Json.createPatch(value.asJsonArray())
    try {
        patch.apply(NamespaceWrapper(getContext()))
        return JsonValue.TRUE
    }
    catch (e: Exception) { return JsonValue.FALSE }
}

fun restDelete(path: JsonPointer, value: JsonValue): JsonValue {
    val ns = if (path is UriPointer) path.namespace else getContext()
    try {
        path.remove(NamespaceWrapper(ns))
        return JsonValue.TRUE
    }
    catch (e: Exception) { return JsonValue.FALSE }
}

fun JsonValue.unwrap(): Any? {
    return when (this.valueType) {
        JsonValue.ValueType.NULL -> null
        JsonValue.ValueType.FALSE -> false
        JsonValue.ValueType.TRUE -> true
        JsonValue.ValueType.STRING -> (this as JsonString).string
        JsonValue.ValueType.NUMBER -> (this as JsonNumber).numberValue()
        JsonValue.ValueType.OBJECT -> this.asJsonObject().toMutableMap()
        JsonValue.ValueType.ARRAY -> this.asJsonArray().toMutableList()
    }
}


private val BLANK_NON_ASCII = '\u007F'..'\u00AD'
private val FUNCTIONS_MAP = mapOf(
    "get" to ::restGet,
    "post" to ::restPost,
    "put" to ::restPut,
    "patch" to ::restPatch,
    "delete" to ::restDelete
)

private class ExpressionBuilder() {
    var function: KFunction<JsonValue>? = null
    var path: JsonPointer? = null
    var value: JsonValue? = null

    fun add(txt: String) {
        if (function == null)
            function = FUNCTIONS_MAP[txt.lowercase()] ?: throw RuntimeException("Invalid REST function: $txt")
        else if (path == null)
            path = parsePath(txt)
        else
            value = parseValue(txt)
    }

    fun build(): Expression {
        if (function == null)
            add("get")
        if (path == null)
            add("/")
        if (value == null)
            add("null")
        return Expression(function, listOf(path, value))
    }

    override fun toString(): String {
        return "ExpressionBuilder"
    }
}

private class JsonReference(private val path: JsonPointer): JsonValue {
    private var value: JsonValue? = null

    fun getValue(): JsonValue {
        value = restGet(path, JsonValue.NULL)
        return value ?: JsonValue.NULL
    }

    override fun getValueType(): JsonValue.ValueType {
        return if (value == null)
            getValue().valueType
        else
            value!!.valueType
    }

    override fun toString(): String {
        return "JsonReference($path)"
    }
}

private class UriPointer(private val uri: URI): JsonPointer {
    val namespace: Namespace get() {
        return getNamespace(uri.scheme) ?: getContext()
    }

    override fun <T : JsonStructure?> add(target: T, value: JsonValue?): T {
        uri.post(if (value == null) null else value.unwrap())
        return NamespaceWrapper(namespace) as T
    }

    override fun <T : JsonStructure?> remove(target: T): T {
        uri.delete()
        return NamespaceWrapper(namespace) as T
    }

    override fun <T : JsonStructure?> replace(target: T, value: JsonValue?): T {
        uri.put(if (value == null) null else value.unwrap())
        return NamespaceWrapper(namespace) as T
    }

    override fun containsValue(target: JsonStructure?): Boolean {
        return uri.get() != null
    }

    override fun getValue(target: JsonStructure?): JsonValue {
        return jsonValue(uri.get())
    }

    override fun toString(): String {
        return "$uri"
    }
}

private class NamespaceWrapper(private val ns: Namespace): AbstractMutableMap<String,JsonValue>(), JsonObject {

    override val entries: MutableSet<MutableMap.MutableEntry<String, JsonValue>> get() {
        return ns.names.map { MapEntryWrapper(ns, it) }.toMutableSet()
    }

    override fun get(key: String): JsonValue? {
        return jsonValue(ns.value(key))
    }

    override fun put(key: String, value: JsonValue): JsonValue? {
        val old = ns.value(key)
        val newValue = if (old is JsonValue)
            jsonValue(value)
        else if (value is JsonValue)
            value.unwrap()
        else
            value
        ns.setValue(key, value)
        return if (old is JsonValue) old else jsonValue(old)
    }

    override fun getValueType(): JsonValue.ValueType {
        return JsonValue.ValueType.OBJECT
    }

    override fun getJsonArray(name: String?): JsonArray {
        return Json.createArrayBuilder(toCollection(name)).build()
    }

    override fun getJsonObject(name: String?): JsonObject {
        return Json.createObjectBuilder(toMap(name).mapKeys { it.toText() }).build()
    }

    override fun getJsonNumber(name: String?): JsonNumber {
        return jsonNumber(toNumber(name))
    }

    override fun getJsonString(name: String?): JsonString {
        return Json.createValue(getString(name))
    }

    override fun getString(name: String?): String {
        return ns.value(name!!).toString()
    }

    override fun getString(name: String?, defaultValue: String?): String {
        if (name != null && ns.hasName(name))
            return ns.value(name).toString()
        return defaultValue ?: "null"
    }

    override fun getInt(name: String?): Int {
        return toInt(ns.value(name!!))
    }

    override fun getInt(name: String?, defaultValue: Int): Int {
        if (name != null && ns.hasName(name))
            return toInt(ns.value(name))
        return defaultValue
    }

    override fun getBoolean(name: String?): Boolean {
        return toBoolean(ns.value(name!!))
    }

    override fun getBoolean(name: String?, defaultValue: Boolean): Boolean {
        if (name != null && ns.hasName(name))
            return toBoolean(ns.value(name))
        return defaultValue
    }

    override fun isNull(name: String?): Boolean {
        val value = ns.value(name!!)
        return value != null && value != JsonValue.NULL
    }

    override fun toString(): String {
        return "$ns"
    }
}

private class MapEntryWrapper(val ns: Namespace, override val key: String): MutableMap.MutableEntry<String,JsonValue> {
    override val value: JsonValue get() {
        val value = ns.value(key)
        return if (value is JsonValue) value else jsonValue(value)
    }

    override fun setValue(newValue: JsonValue): JsonValue {
        val old = ns.value(key)
        val value = if (old is JsonValue) newValue else newValue.unwrap()
        ns.setValue(key, value)
        return if (old is JsonValue) old else jsonValue(old)
    }

    override fun toString(): String {
        return "$key@$ns"
    }
}

private fun parseText(txt: String): Expression {
    val builder = ExpressionBuilder()
    var word: String? = null
    for ((i,c) in txt.withIndex()) {
        if (c <= ' ' || c in BLANK_NON_ASCII) {
            if (word != null) {
                builder.add(word)
                word = null
            }
        }
        else if (word == null) {
            if (builder.function != null && builder.path != null) {
                builder.add(txt.substring(i).trim())
                break
            }
            word = c.toString()
        }
        else
            word += c
    }
    if (word != null)
        builder.add(word)
    return builder.build()
}

private fun parseValue(txt: String): JsonValue {
    return when (txt) {
        "",
        "null" -> JsonValue.NULL
        "false" -> JsonValue.FALSE
        "true" -> JsonValue.TRUE
        else -> {
            when (txt[0]) {
                '{' -> readJson(txt)
                '[' -> readJson(txt)
                // TODO: can we have '(' to support complex expressions ?
                '"' -> readJson(txt)
                '/' -> JsonReference(parsePath(txt))
                '-', '+',
                in '0'..'9' -> jsonNumber(txt)
                in 'a'..'z' -> JsonReference(parsePath(txt))
                else -> throw RuntimeException("Invalid token: $txt")
            }
        }
    }
}

private fun readJson(txt: String): JsonValue {
    return Json.createReader(StringReader(txt)).readValue()
}

private fun parsePath(txt: String): JsonPointer {
    val path = if (txt == "/" || txt.isBlank())
        ""
    else if (txt.startsWith("/"))
        txt
    else {
        val colon = txt.indexOf(":")
        if (colon >= 0) {
            val scheme = txt.substring(0, colon)
            if (isBuiltinUriScheme(scheme) || getNamespace(scheme) != null)
                return UriPointer(URI(txt))
        }
        "/$txt"
    }
    return Json.createPointer(path)
}

private fun jsonNumber(value: Any?): JsonNumber {
    val n: Number = if (value.isText()) {
        val txt = value.toText().trim()
        txt.toIntOrNull() ?: txt.toDoubleOrNull() ?: throw RuntimeException("Invalid number syntax: $txt")
    }
    else if (value is Number)
        value
    else if (value is Boolean)
        if (value) 1 else 0
    else if (value == null)
        0
    else if (value is Collection<*>)
        value.size
    else if (value is Array<*>)
        value.size
    else if (value is Map<*,*>)
        value.size
    else
        throw RuntimeException("Invalid number value: $value")
    return if (n is Double)
        Json.createValue(n)
    else if (n is BigDecimal)
        Json.createValue(n)
    else if (n is BigInteger)
        Json.createValue(n)
    else if (n is Long)
        Json.createValue(n)
    else
        Json.createValue(n.toInt())
}

private fun jsonValue(value: Any?): JsonValue {
    if (value is JsonValue)
        return value
    if (value is Number)
        return jsonNumber(value)
    if (value is Boolean)
        return if (value) JsonValue.TRUE else JsonValue.FALSE
    if (value.isText())
        return Json.createValue(value.toText())
    if (value is Collection<*>)
        return Json.createArrayBuilder(value).build()
    if (value is Array<*>)
        return Json.createArrayBuilder(value.toList()).build()
    if (value.isMappable())
        return Json.createObjectBuilder(value.toMap()!!.mapKeys { it.toText() }).build()
    if (value == null)
        return JsonValue.NULL
    return Json.createObjectBuilder(BeanMap(value).mapKeys { it.toText() }).build()
}
