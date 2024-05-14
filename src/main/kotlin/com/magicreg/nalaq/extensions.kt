package com.magicreg.nalaq

import ezvcard.VCard
import org.apache.commons.beanutils.BeanMap
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.Temporal
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.kotlinFunction
import kotlin.reflect.jvm.reflect

fun Any?.type(): Type {
    if (this is Type)
        return this
    if (this is SqlRow)
        return this.table
    if (this is Resource)
        return this.type
    if (this is Map<*,*>) {
        val value = this["class"] ?: this["type"]
        if (value != null) {
            val name = toString(value)
            var type = getTypeByName(name)
            if (type != null)
                return type
            val klass = name.toClass()
            if (klass != null) {
                type = getTypeByClass(klass)
                if (type != null)
                    return type
            }
        }
    }
    if (this is URI)
        this.resolve().type()
    if (this is CharSequence) {
        val uri = this.toUri()
        if (uri != null)
            return uri.resolve().type()
    }
    return getTypeByClass(if (this == null) Void::class else this::class)
}

fun Any?.resolve(deepResolving: Boolean = false): Any? {
    if (this is Expression)
        return this.value().resolve()
    if (this is Map.Entry<*,*>)
        return this.value.resolve()
    if (this is URI)
        return this.get().resolve()
    if (this is URL)
        return this.toURI().get().resolve()
    if (this is File)
        return this.toURI().get().resolve()
    if (deepResolving) {
        if (this.isText()) {
            val txt = this.toText()
            val uri = txt.toUri()
            if (uri != null)
                return uri.get().resolve()
            val cx = getContext()
            if (cx.hasName(txt))
                return cx.value(txt).resolve()
            return txt
        }
        if (this is Collection<*>)
            return this.map { it.resolve(false) }
        if (this != null && this::class.java.isArray)
            return ListArrayAdapter(this).map { it.resolve(false) }
    }
    return this
}

fun Any?.path(path: List<String>): Any? {
    var node: Any? = this
    for (part in path)
        node = node?.type()?.property(part, node)?.getValue(node).resolve() ?: return null
    return node
}

fun Any?.pathValue(path: List<String>, value: Any?): Boolean {
    if (path.isEmpty())
        return false;
    var node: Any? = this
    var last = path[path.size-1]
    for (part in path.subList(0, path.size-1)) {
        node = node?.type()?.property(part, node).resolve() ?: return false
        // TODO: try to set a new map when result value is null or if (property == nullProperty())
    }
    return node?.type()?.property(last, node)?.setValue(node, value) ?: false
}

fun Any?.isPrimitive(): Boolean {
    return this == null || this is Number || this is Boolean || this.isText()
}

fun Any?.isReference(): Boolean {
    return this is Reference || this is URI || this is URL || this is File
        || this is KProperty<*> || this is Field || this is Property || this is Map.Entry<*,*>
}

fun Any?.toReference(): Reference? {
    if (this is Reference)
        return this
    if (this is URI)
        return UriReference(this)
    if (this is URL)
        return UriReference(this.toURI())
    if (this is File)
        return UriReference(this.canonicalFile.toURI())
    if (this is KProperty<*>)
        return KotlinPropertyReference(this)
    if (this is Field)
        return JavaFieldReference(this)
    if (this is Property)
        return KeyValueReference(this.name, this.type)
    if (this is Map.Entry<*,*>)
        return KeyValueReference(this.toText(), this.value)
    return null
}

fun Any?.isText(): Boolean {
    return this is CharSequence || this is Char || this is File || this is URI || this is URL || this is CharArray || this is ByteArray
            || (this is Array<*> && (this.isArrayOf<Char>() || this.isArrayOf<Byte>()))
}

fun Any?.toText(): String {
    if (this is CharSequence || this is Char)
        return this.toString()
    if (this is File)
        return this.toURI().toString()
    if (this is URI)
        return this.toString()
    if (this is URL)
        return this.toURI().toString()
    if (this is CharArray)
        return this.joinToString("")
    if (this is ByteArray)
        return this.toString(Charset.forName("utf8"))
    if (this is Array<*>) {
        if (this.isArrayOf<Char>())
            return this.joinToString("")
        if (this.isArrayOf<Byte>()) {
            val array = this as Array<Byte>
            return ByteArray(this.size){array[it]}.toString(Charset.forName("utf8"))
        }
    }
    if (this.isIterable())
        return "("+this.toIterator()!!.asSequence().joinToString(","){it.toText()}+")"
    if (this.isMappable())
        return "("+this.toMap()!!.entries.joinToString(","){it.key.toText()+"="+it.value.toText()}+")"
    if (this == null)
        return ""
    return this.toString()
}

fun Any?.isFunction(): Boolean {
    return this is KFunction<*> || this is Method || this is Constructor<*> || this is Function<*>
}

fun Any?.toFunction(): KFunction<Any?>? {
    if (this is KFunction<*>)
        return this
    if (this is Method)
        return this.kotlinFunction
    if (this is Constructor<*>)
        return this.kotlinFunction
    if (this is Function<*>) {
        val fn = this.reflect()
        if (fn != null)
            return fn
        // TODO: we might want to convert it to a java method if the call() function does not work
    }
    // TODO: can we detect functional interface and convert it to a KFunction ?
    // TODO: if (value is Type or KClass or Class) return the constructor of this type
    // TODO: if (value is Collection or Array) use as parameters to construct a NaLaQFunction
    return null
}

fun Any?.toCompareOperator(throwOnFail: Boolean = false): CompareOperator? {
    if (this is CompareOperator)
        return this
    if (this is String) {
        for (op in CompareOperator.entries) {
            if (op.symbol == this)
                return op
        }
    }
    if (this is KFunction<Any?>) {
        if (this == ::is_func)
            return CompareOperator.EQUAL
        for (op in CompareOperator.entries) {
            if (op.function == this)
                return op
        }
    }
    if (throwOnFail)
        throw RuntimeException("Invalid compare operator: $this")
    else
        return null
}

fun Any?.toLogicOperator(throwOnFail: Boolean = false): LogicOperator? {
    if (this is KFunction<Any?>) {
        when(this) {
            ::and_func -> return LogicOperator.AND
            ::or_func -> return LogicOperator.OR
        }
    }
    if (this is String) {
        when(this.uppercase()) {
            "AND", "&", "&&" -> return LogicOperator.AND
            "OR",  "|", "||" -> return LogicOperator.OR
        }
    }
    if (throwOnFail)
        throw RuntimeException("Invalid logic operator: $this")
    else
        return null
}

fun Any?.isIterable(): Boolean {
    if (this == null)
        return false
    return this is Iterator<*> || this is Enumeration<*> || this is Iterable<*> || this::class.java.isArray
}

fun Any?.toIterator(): Iterator<Any?>? {
    if (this is Iterator<*>)
        return this
    if (this is Enumeration<*>)
        return this.iterator()
    if (this is Iterable<*>)
        return this.iterator()
    if (this != null && this::class.java.isArray)
        return ArrayIterator(this)
    if (this is Map.Entry<*,*>)
        return null
    if (this.isMappable())
        return this.toMap()?.entries?.iterator()
    return null
}

fun Any?.toCollection(): List<Any?> {
    return if (this == null)
        emptyList()
    else if (this is List<*>)
        this
    else if (this is Collection<*>)
        this.toList()
    else if (this is Array<*>)
        this.toList()
    else
        this.toIterator()?.asSequence()?.toList() ?: listOf(this)
}

fun Any?.isMappable(): Boolean {
    return this is Map<*,*> || this is Namespace || this is Resource || this is Element || this is VCard || this is Person || (this != null && this::class.isData)
}

fun Any?.toMap(): Map<Any?,Any?>? {
    if (this is Map<*,*>)
        return this as Map<Any?,Any?>
    if (this is Namespace)
        return MapAdapter({ this.names.toSet() },
            { this.value(toString(it)) },
            if (this.readOnly) { k, _ -> this.value(toString(k))} else { k, v -> this.setValue(toString(k), v) })
    if (this is Resource)
        return MapAdapter({ this.properties.map{ it.name }.toSet() },
            { k -> this.properties.firstOrNull{it.name == k}?.getValue(this) },
            { k, v -> this.properties.firstOrNull{it.name == k}?.setValue(this, v) })
    if (this is Element) {
        val map = mutableMapOf<Any?,Any?>("tag" to this.tagName(), "parent" to this.parent(), "children" to this.children())
        for (entry in this.attributes())
            map[entry.key] = if (entry.key == "style") {
                val css = mutableMapOf<String,String>()
                val parts = entry.value.split(";")
                for (part in parts) {
                    val index = part.indexOf(":")
                    if (index > 0)
                        css[part.substring(0, index).trim()] = part.substring(index+1).trim()
                }
                css
            }
            else
                entry.value.split(",").filter { it.isNotBlank() }.map { it.trim() }.simplify() ?: ""
        return map
    }
    if (this is VCard || this is Person)
        return BeanMap(this)
    if (this is Collection<*>) {
        val map = mutableMapOf<Any?,Any?>()
        for (item in this) {
            if (item !is Map.Entry<*,*>)
                return null
            map[item.key] = item.value
        }
        return map
    }
    if (this is Array<*>)
        return this.toList().toMap()
    if (this is Map.Entry<*,*>)
        return mapOf(this.key to this.value)
    if (this != null && this::class.isData)
        return BeanMap(this)
    return null
}

fun Any?.toUri(): URI? {
    if (this is URI)
        return this
    if (this is URL)
        return this.toURI()
    if (this is File)
        return getContext().fileUri(this)
    if (this is CharSequence) {
        val txt = this.toString().trim()
        try {
            if (txt.startsWith("/") || txt.startsWith("./") || txt.startsWith("../"))
                return File(txt).toURI()
            val scheme = if (txt.contains(":")) txt.substring(0, txt.indexOf(":")) else null
            if (scheme != null && (isBuiltinUriScheme(scheme) || getNamespace(scheme) != null))
                return URI(txt)
            // TODO: test word against all context configured default namespaces prefixes
        } catch (e: Exception) {}
    }
    return null
}

fun String?.fileType(): String? {
    if (this == null)
        return null
    var parts = this.split("/")
    val filename = parts[parts.size-1]
    if (filename == "" || filename.indexOf(".") < 0)
        return null
    if (filename[0] == '.')
        return "text/plain"
    parts = filename.split(".")
    return getExtensionMimetype(parts[parts.size-1])
}

fun String.detectFormat(createDataUri: Boolean = false): String? {
    val txt = this.trim()
    if (txt.isBlank())
        return null
    val type = if (txt.startsWith("---\n"))
        "application/yaml"
    else if ((txt[0] == '{' && txt[txt.length-1] == '}') || (txt[0] == '[' && txt[txt.length-1] == ']'))
        "application/json"
    else if (txt.startsWith("<html>") && txt.endsWith("</html>")) // TODO: fix bug where there might be a document header <!--
        "text/html"
    else if (txt[0] == '<' && txt[txt.length-1] == '>')
        "application/xml"
    else if (txt.toCharArray().none { it <= ' ' } && txt.toCharArray().any { it == '=' })
        "application/x-www-form-urlencoded"
    else if (txt.toCharArray().any { it == '\n' }) {
        val lines = txt.split("\n")
        if (lines[0].toCharArray().none { it <= ' ' } && lines[0].toCharArray().any { it == ',' })
            "text/csv"
        else
            null
    }
    else
        null
    return if (type == null)
        null
    else if (!createDataUri)
        type
    else {
        try { "data:$type,${URLEncoder.encode(txt, defaultCharset())}" }
        catch (e: Exception) { null }
    }
}

fun String.normalize(): String {
    val txt = if (Normalizer.isNormalized(this, NORMALIZED_FORM))
        this
    else
        Normalizer.normalize(this, NORMALIZED_FORM).replace("\\p{M}".toRegex(), "")
    return txt.lowercase().trim()
}

fun String.toTemporal(): Temporal? {
    try { return LocalDateTime.parse(this, TIMESTAMP_FORMAT) }
    catch (e: Exception) {
        try { return LocalDate.parse(this, DATE_FORMAT) }
        catch (e2: Exception) {
            try { return LocalTime.parse(this, TIME_FORMAT) }
            catch (e3: Exception) {
                try { return LocalTime.parse(this, HOUR_FORMAT) }
                catch (e4: Exception) {}
            }
        }
    }
    return null
}

fun String.toClass(): KClass<*>? {
    try { return Class.forName(this).kotlin }
    catch (e: Exception) {
        try {
            if (this.startsWith("kotlin.collections."))
                return Class.forName("java.util."+this.substring("kotlin.collections.".length)).kotlin
            if (this.startsWith("kotlin."))
                return Class.forName("java.lang."+this.substring("kotlin.".length)).kotlin
            val type = getTypeByName(this)
            if (type != null)
                return type.classes[0]
        }
        catch (e: Exception) {}
    }
    return null
}

fun String.toJvm(): Member? {
    val value = this.toClass()
    if (value != null)
        return value.java as Member
    val index = this.lastIndexOf(".")
    if (index > 0) {
        val klass = this.substring(0, index).toClass() ?: return null
        val name = this.substring(index + 1)
        return klass.java.methods.firstOrNull { it.name == name }
            ?: klass.java.fields.firstOrNull { it.name == name }
    }
    return null
}

fun String.toExpression(): Expression {
    return getContext().getParser().parse(this)
}

fun Number.toBytes(): ByteArray {
    val bytes = ByteArrayOutputStream()
    var n = this.toLong()
    while (n > 0) {
        bytes.write((n % 256).toInt())
        n /= 256
    }
    return bytes.toByteArray()
}

fun Collection<Any?>.simplify(resolve: Boolean = false): Any? {
    val value = when (this.size) {
        0 -> null
        1 -> this.iterator().next()
        else -> this
    }
    if (!resolve)
        return value
    if (value !is Collection<*>)
        return value.resolve()
    return value.map{it.resolve()}
}

fun Temporal.toDateTime(): LocalDateTime {
    if (this is LocalDateTime)
        return this
    if (this is LocalDate)
        return this.toDateTime()
    if (this is LocalTime)
        return this.toDateTime()
    if (this is Instant)
        return this.toDateTime()
    throw RuntimeException("Temporal format not supported: ${this::class.simpleName}")
// TODO: HijrahDate, JapaneseDate, MinguoDate, OffsetDateTime, OffsetTime, ThaiBuddhistDate, Year, YearMonth, ZonedDateTime
}

fun Temporal.toDate(): LocalDate {
    if (this is LocalDate)
        return this
    if (this is LocalDateTime)
        return this.toDate()
    if (this is LocalTime)
        return this.toDate()
    if (this is Instant)
        return this.toDate()
    throw RuntimeException("Temporal format not supported: ${this::class.simpleName}")
}

fun Temporal.toTime(): LocalTime {
    if (this is LocalTime)
        return this
    if (this is LocalDate)
        return this.toTime()
    if (this is LocalDateTime)
        return this.toTime()
    if (this is Instant)
        return this.toTime()
    throw RuntimeException("Temporal format not supported: ${this::class.simpleName}")
}

private val NORMALIZED_FORM = Normalizer.Form.NFD
private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val HOUR_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
