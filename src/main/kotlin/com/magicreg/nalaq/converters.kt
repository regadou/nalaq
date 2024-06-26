package com.magicreg.nalaq

import ezvcard.VCard
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import org.apache.commons.beanutils.BeanMap
import org.jsoup.nodes.Element
import java.io.*
import java.lang.reflect.*
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.Temporal
import java.util.*
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.reflect.*
import kotlin.reflect.jvm.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toKotlinDuration

/*************************************
 The difference between all the converters below and their counterparts in extensions.kt file is of the following:
 - converters should never fail and tries the conversion process, possibly returning inadequate data, but of the proper requested type
 - the only time the convert() function should fail is if no converter was found for the requested target type
 - the extensions functions try to convert to the requested type but will return null if conversion is not possible
 - you should always check for nullity after an extension conversion function call
 - an extension conversion function toX() should have a companion boolean function isX() to validate if conversion is possible
****************************************/

fun getConverter(type: KClass<*>): KFunction<Any?>? {
    return converters[type]
}

fun toByteArray(value: Any?): ByteArray {
    if (value is ByteArray)
        return value
    if (value == null)
        return byteArrayOf()
    if (value.isText())
        return value.toText().toByteArray(Charset.forName(defaultCharset()))
    if (value is Number)
        return value.toBytes()
    if (value is Boolean)
        return (if (value) 1 else 0).toByte().toBytes()
    if (value is Collection<*>) {
        val bytes = ByteArrayOutputStream()
        for (item in value)
            bytes.write(toByteArray(item))
        return bytes.toByteArray()
    }
    if (value::class.java.isArray)
        return toByteArray(ListArrayAdapter(value))
    return toString(value).toByteArray(Charset.forName(defaultCharset()))
}

fun toCharSequence(value: Any?): CharSequence {
    if (value is CharSequence)
        return value
    return toString(value)
}

fun toString(value: Any?): String {
    if (value.isText())
        return value.toText()
    if (value is Date)
        return printDate(value)
    if (value is KClass<*>)
        return value.qualifiedName ?: value.java.name
    if (value is KFunction<*>) {
        val exec = value.javaMethod ?: value.javaConstructor
        val prefix = if (exec == null) "" else "${toString(exec.declaringClass)}."
        return "$prefix${value.name}"
    }
    if (value is KProperty<*>) {
        val java = value.javaField ?: value.javaGetter
        val prefix = if (java == null) "" else "${toString(java.declaringClass)}."
        return "$prefix${value.name}"
    }
    if (value is Class<*>)
        return value.name
    if (value is Member)
        return "${toString(value.declaringClass)}.${value.name}"
    if (value is Type)
        return value.name
    if (value is Property)
        return value.name
    if (value is Context)
        return "all"
    if (value is Namespace)
        return "${value.prefix}:"
    if (value is Reference) {
        val owner = if (value.parent is Context) "" else toString(value.parent)
        if (owner == "") {
            if (value.key == "")
                return "null"
            else
                return value.key+"="+toString(value.value)
        }
        else if (value.key == "")
            return owner
        else
            return value.key + "@" + owner
    }
    if (value is Map.Entry<*,*>)
        return toString(value.key)+"="+toString(value.value)
    if (value is Element)
        return value.outerHtml()
    if (value == null)
        return ""
    val iterator = value.toIterator()
    if (iterator != null)
        return "(" + toCollection(iterator).joinToString(" ") { toString(it) } + ")"
    // TODO: check if there is a name or label property which is a String
    return value.toString()
}

fun toChar(value: Any?): Char {
    if (value is Char)
        return value
    if (value is CharSequence)
        return if (value.isEmpty()) ' ' else value[0]
    if (value is Number)
        return Char(value.toInt())
    if (value == null)
        return ' '
    if (value.isText())
        return toChar(value.toText())
    return toChar(toString(value))
}

fun toConcatString(value: Any?): String {
    if (value.isText())
        return value.toText()
    return value.toCollection().joinToString("") { toString(it) }
}

fun toBoolean(value: Any?): Boolean {
    if (value is Boolean)
        return value
    if (value is Number)
        return value.toDouble() != 0.0
    if (value is CharSequence || value is Char) {
        val txt = value.toString().trim().lowercase()
        return txt.isNotBlank() && !FALSE_WORDS.contains(txt)
    }
    if (value == null)
        return false
    val iterator = value.toIterator()
    if (iterator != null) {
        if (!iterator.hasNext())
            return false
        val first = iterator.next()
        if (iterator.hasNext())
            return true
        return toBoolean(first)
    }
    return toBoolean(toString(value))
}

fun toByte(value: Any?): Byte {
    return toNumber(value).toByte()
}

fun toShort(value: Any?): Short {
    return toNumber(value).toShort()
}

fun toInt(value: Any?): Int {
    return toNumber(value).toInt()
}

fun toLong(value: Any?): Long {
    return toNumber(value).toLong()
}

fun toFloat(value: Any?): Float {
    return toNumber(value).toFloat()
}

fun toDouble(value: Any?): Double {
    return toNumber(value).toDouble()
}

fun toInteger(value: Any?): Number {
    return if (value is Int || value is Long || value is Short || value is Byte || value is BigDecimal)
        value as Number
    else if (value is Number)
        value.toLong()
    else
        toLong(value)
}

fun toNumber(value: Any?): Number {
    val n = numericValue(value, null)
    if (n != null)
        return n
    val iterator = value.toIterator()
    if (iterator != null) {
        var count = 0
        while (iterator.hasNext())
            count++
        return count
    }
    return 1
}

fun toDateTime(value: Any?): LocalDateTime {
    if (value is LocalDateTime)
        return value
    if (value is Temporal)
        return LocalDateTime.from(value)
    if (value == null)
        return LocalDateTime.now()
    if (value is Date)
        return LocalDateTime.ofEpochSecond(value.time / 1000, (value.time % 1000).toInt() * 1000, toZoneOffset())
    if (value is Number)
        return LocalDateTime.ofEpochSecond(value.toLong(), 0, toZoneOffset("utc"))
    if (value is CharSequence)
        return value.toString().toTemporal()?.toDateTime() ?: LocalDateTime.now()
    // TODO: collection/array/map of year, month, day, hour, minute, second
    return LocalDateTime.parse(value.toString(), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
}

fun toDate(value: Any?): LocalDate {
    if (value is LocalDate)
        return value
    if (value is Temporal)
        return LocalDate.from(value)
    if (value == null)
        return LocalDate.now()
    if (value is Date)
        return LocalDate.ofEpochDay(value.time / 86400000)
    if (value is Number)
        return LocalDate.ofEpochDay(value.toLong())
    if (value is CharSequence)
        return value.toString().toTemporal()?.toDate() ?: LocalDate.now()
    // TODO: collection/array/map of year, month, day
    return LocalDate.parse(value.toString(), DateTimeFormatter.ofPattern("yyyy-MM-dd"))
}

fun toTime(value: Any?): LocalTime {
    if (value is LocalTime)
        return value
    if (value is Temporal)
        return LocalTime.from(value)
    if (value == null)
        return LocalTime.now()
    if (value is Date)
        return LocalTime.ofNanoOfDay((value.time % 86400000)*1000)
    if (value is Number)
        return LocalTime.ofSecondOfDay(value.toLong())
    if (value is CharSequence)
        return value.toString().toTemporal()?.toTime() ?: LocalTime.now()
    // TODO: collection/array/map of hour, minute, second
    return LocalTime.parse(value.toString(), DateTimeFormatter.ofPattern("HH:mm:ss"))
}

fun toTemporal(value: Any?): Temporal {
    if (value is Temporal)
        return value
    if (value is java.sql.Time)
        return toTime(value)
    if (value is java.sql.Date)
        return toDate(value)
    if (value is java.sql.Timestamp)
        return toDateTime(value)
    return toDateTime(value)
}

fun toUtilDate(value: Any?): Date {
    val temporal = toTemporal(value)
    if (temporal is LocalDateTime)
        return java.sql.Timestamp(temporal.toEpochSecond(toZoneOffset())*1000)
    if (temporal is LocalDate)
        return java.sql.Date(temporal.toEpochDay()*86400000)
    if (temporal is LocalTime)
        return java.sql.Time(temporal.toNanoOfDay()/1000)
    return Date(temporal.toDateTime().toEpochSecond(toZoneOffset())*1000)
}

fun toZoneOffset(value: Any? = null): ZoneOffset {
    if (value == null)
        return ZoneOffset.systemDefault().rules.getOffset(LocalDateTime.now())
    if (value is Number) {
        var n = value.toDouble()
        if (n < -86400 || n > 86400)
            n = (n / 3600) % 24
        else if (n < -24 || n > 24)
            n = (n / 3600) % 24
        return ZoneOffset.ofHoursMinutesSeconds(n.toInt(), (n * 60 % 60).toInt(), (n * 3600 % 60).toInt())
    }
    return ZoneOffset.of(value.toString())
}

fun toDuration(value: Any?): Duration {
    if (value is Duration)
        return value
    if (value is java.time.Duration)
        return value.toKotlinDuration()
    if (value is javax.xml.datatype.Duration)
        return value.getTimeInMillis(Date()).milliseconds
    val n = numericValue(value, null)
    if (n != null)
        return n.toDouble().milliseconds
    // TODO: check if it is a map or map entry collection of different time parts
    if (value == null)
        return (0.0).milliseconds
    return Duration.parseIsoString(value.toString())
}

fun toMap(value: Any?): Map<Any?,Any?> {
    if (value is Map<*,*>)
        return value as Map<Any?,Any?>
    val map = value.toMap()
    if (map != null)
        return map
    if (value is Collection<*>)
        return MapCollectionAdapter(value) as Map<Any?,Any?>
    if (value == null)
        return mutableMapOf()
    if (value::class.java.isArray)
        return MapCollectionAdapter(ListArrayAdapter(value)) as Map<Any?,Any?>
    val iterator = value.toIterator()
    if (iterator != null) {
        val list = toList(iterator)
        return list.toMap() ?: MapCollectionAdapter(list) as Map<Any?,Any?>
    }
    if (value is Type)
        return value.properties(null).map{KeyValueReference(it, value.property(it).type)}.toMap()!!
    if (value is KClass<*>)
        return toMap(getTypeByClass(value))
    if (value is Class<*>)
        return toMap(getTypeByClass(value.kotlin))
    if (value.isText() || value is Number || value is Boolean)
        return mutableMapOf<Any?,Any?>("value" to value)
    return BeanMap(value)
}

fun toEntity(value: Any?): Map<String,Any?> {
    val args = value.toCollection()
    if (args.isEmpty())
        return mutableMapOf()
    if (args.size == 1)
        return toMap(args[0]).mapKeys{it.toText()}
    val map = mutableMapOf<String,Any?>()
    var key: String? = null
    for (arg in args) {
        if (key == null)
            key = toString(arg)
        else {
            map[key] = arg.resolve()
            key = null
        }
    }
    if (key != null)
        map[key] = null
    return map
}

fun toPerson(value: Any?): Person {
    if (value is Person)
        return value
    if (value is VCard)
        return Person().importCard(value)
    if (value is Map<*,*>)
        return Person().importData(value as Map<String, Any?>)
    if (value == null)
        return Person()
    return Person().importData(toMap(value) as Map<String, Any?>)
}

fun toArray(value: Any?): Array<Any?> {
    if (value is Array<*>)
        return value as Array<Any?>
    return toList(value).toTypedArray<Any?>()
}

fun toCollection(value: Any?): Collection<Any?> {
    if (value is Collection<*>)
        return value
    return toList(value)
}

fun toSet(value: Any?): Set<Any?> {
    if (value is Set<*>)
        return value
    if (value is Collection<*>)
        return setOf(*value.toTypedArray())
    return setOf(*toList(value).toTypedArray())
}

fun toList(value: Any?): List<Any?> {
    if (value is List<*>)
        return value
    if (value is Collection<*>)
        return value.toList()
    val map = value.toMap()
    if (map != null)
        return map.entries.toList()
    if (value.isText()) {
        val txt = value.toText().trim()
        if (txt.isEmpty())
            return mutableListOf()
        if (txt.indexOf('\n') > 0)
            return txt.split("\n")
        return txt.split(",") // TODO: we could have other splitters like ; : = & | - / tab and blank
    }
    if (value == null)
        return mutableListOf()
    if (value::class.java.isArray)
        return ListArrayAdapter(value)
    val iterator = value.toIterator()
    if (iterator != null) {
        val list = mutableListOf<Any?>()
        while (iterator.hasNext())
            list.add(iterator.next())
        return list
    }
    return mutableListOf(value)
}

fun toNamespace(value: Any?): Namespace {
    if (value is Namespace)
        return value
    var prefix: String? = null
    var uri: String? = null
    var mapping: MutableMap<String,Any?>? = null
    var readOnly: Boolean? = null
    val args: Collection<Any?> = if (value is Collection<*>)
        value
    else if (value == null)
        emptyList()
    else if (value is Array<*>)
        value.toList()
    else
        listOf(value)
    for (arg in args) {
        if (arg is Boolean)
            readOnly = arg
        else if (arg is URI)
            uri = arg.toString()
        else if (arg is URL || arg is File)
            uri = arg.toUri().toString()
        else if (arg is CharSequence) {
            val goturi = arg.toUri()
            if (goturi == null)
                prefix = arg.toString()
            else
                uri = goturi.toString()
        }
        else if (arg is MutableMap<*,*>)
            mapping = arg as MutableMap<String,Any?>
        else if (arg is Map<*,*>)
            mapping = (arg as Map<String,Any?>).toMutableMap()
    }
    if (args.size == 1 && prefix != null)
        return getNamespace(prefix) ?: throw RuntimeException("Invalide namespace: $prefix")
    if (mapping == null && uri != null) {
        val value = URI(uri).get()
        if (value is Database) {
            if (prefix != null)
                value.prefix = prefix
            return value
        }
        if (value is MutableMap<*,*>)
            mapping = value as MutableMap<String,Any?>
        else if (value is Map<*,*>)
            mapping = (value as Map<String,Any?>).toMutableMap()
    }
    if (mapping != null) {
        if (prefix == null)
            prefix = mapping.remove("prefix")?.toString()
        if (uri == null)
            uri = mapping.remove("uri")?.toString()
        if (readOnly == null)
            readOnly = toBoolean(mapping.remove("readOnly"))
    }
    return GenericNamespace(prefix?:"", uri?:"http://localhost/", readOnly?:false).populate(mapping?:mutableMapOf())
}

fun toURI(value: Any?): URI {
    val uri = value.toUri()
    if (uri != null)
        return uri
    if (value == null)
        return URI("nalaq:null")
    if (value.isText()) {
        val txt = value.toText()
        if (getContext().hasName(txt))
            return URI("nalaq:$txt")
    }
    val json = getFormat("json")!!.encodeText(value)
    return URI("data:application/json,${URLEncoder.encode(json, defaultCharset())}")
}

fun toExpression(value: Any?): Expression {
    if (value is Expression)
        return value
    if (value.isText())
        return value.toText().toExpression()
    if (value is Reader)
        return value.readText().toExpression()
    if (value is Filter)
        return value.toExpression()
    if (value.isMappable())
        return Filter().mapCondition(value.toMap()!!.mapKeys{it.toText()}).toExpression()
    val iterator = value.toIterator()
    if (iterator != null)
        return toList(value).joinToString(" ").toExpression()
    return Expression(null, listOf(value))
}

fun toFilter(value: Any?): Filter {
    if (value is Filter)
        return value
    if (value is Expression)
        return Filter().expressionCondition(value)
    if (value.isReference())
        return toFilter(value.toReference()!!.value)
    if (value is Map<*,*>)
        return Filter().mapCondition(value as Map<String, Any?>)
    if (value is Collection<*>) {
        if (value.isEmpty())
            return Filter()
        val first = value.iterator().next()
        if (first is Map<*,*>) {
            val filter = Filter()
            for (map in value)
                filter.mapCondition(map as Map<String, Any?>)
            return filter
        }
        return Filter().listCondition(value.toList())
    }
    if (value == null)
        return Filter()
    if (value.isText())
        return Filter().textCondition(value.toText())
    if (value::class.java.isArray)
        return toFilter(toList(value))
    return Filter("it", "=", value)
}

fun toInputStream(value: Any?): InputStream {
    if (value is InputStream)
        return value
    if (value is Reader)
        return ByteArrayInputStream(toByteArray(value.readText()))
    if (value is File)
        return FileInputStream(value)
    if (value is URI)
        return value.toURL().openStream()
    if (value is URL)
        return value.openStream()
    if (value == null)
        return System.`in`
    if (value.isText()) {
        val txt = value.toText()
        val uri = txt.toUri()
        if (uri != null)
            return uri.toURL().openConnection().getInputStream()
    }
    return ByteArrayInputStream(toByteArray(value))
}

fun toOutputStream(value: Any?): OutputStream {
    if (value is OutputStream)
        return value
    if (value is Writer)
        return OutputStreamAdapter { bytes, start, length ->
            val buffer = ByteArrayInputStream(bytes, start, length)
            value.write(buffer.readAllBytes().toString(Charset.forName(defaultCharset())))
        }
    if (value is File)
        return FileOutputStream(value)
    if (value is URI)
        return value.toURL().openConnection().getOutputStream()
    if (value is URL)
        return value.openConnection().getOutputStream()
    if (value == null)
        return System.out
    if (value.isText()) {
        val txt = value.toText()
        if (txt == "error")
            return System.err
        val uri = txt.toUri()
        if (uri != null)
            return uri.toURL().openConnection().getOutputStream()
    }
    val output = ByteArrayOutputStream()
    output.write(toByteArray(value))
    return output
}

fun toView(value: Any?): View {
    return if (value is View) value else createView(toURI(value))
}

fun toAudioStream(value: Any?): AudioStream {
    if (value is AudioStream)
        return value
    val audio = AudioStream()
    if (value is ByteArray)
        audio.inputAudio(ByteArrayInputStream(value))
    else if (value is Array<*> && value.isArrayOf<Byte>()) {
        val array = value as Array<Byte>
        val bytes = ByteArray(value.size) {array[it]}
        audio.inputAudio(ByteArrayInputStream(bytes))
    }
    else if (value is InputStream)
        audio.inputAudio(value)
    else if (value == null)
        audio.inputAudio(microphoneInput())
    else if (value.isText()) {
        val speaker = getSpeechWriter()
        if (speaker != null)
            audio.inputAudio(speaker.audioInputStream(value.toText()))
    }
    else if (value is Collection<*> || value is Array<*>) {
        val list = toCollection(value)
        for (item in list)
            audio.inputAudio(toAudioStream(item).audioInputStream())
    }
    // TODO: else if (value is javax.sound.midi.Sequence)
    else {
        val uri = value.toUri()
        if (uri != null && uri.contentType()?.startsWith("audio/") == true)
            audio.inputAudio(uri.toURL().openStream())
    }
    return audio
}

fun toSpeechReader(value: Any?): SpeechReader {
    if (value is SpeechReader)
        return value
    if (value is AudioInputStream)
        return SpeechReader(getContext().configuration.language, value)
    if (value is InputStream)
        return SpeechReader(getContext().configuration.language, AudioSystem.getAudioInputStream(value))
    if (value is AudioStream)
        return SpeechReader(getContext().configuration.language, value.audioInputStream())
    // TODO: a collection or array that specifies both the source(stream|uri and the model)
    return SpeechReader(getContext().configuration.language, toAudioStream(value).audioInputStream())
}

fun toClass(value: Any?): KClass<*> {
    if (value is KClass<*>)
        return value
    if (value is Class<*>)
        return value.kotlin
    if (value is Type)
        return if (value.classes.isEmpty()) Any::class else value.classes[0]
    if (value is KType)
        return value.javaClass.kotlin
    if (value is CharSequence) {
        val txt = value.toString().trim()
        val c = txt.toClass()
        if (c != null)
            return c
        val type = getTypeByName(txt)
        if (type != null)
            return type.classes[0]
    }
    if (value == null)
        return Void::class
    return value::class
}

fun toType(value: Any?): Type {
    if (value is Type)
        return value
    if (value is KClass<*>)
        return getTypeByClass(value)
    if (value is Class<*>)
        return getTypeByClass(value.kotlin)
    if (value is KType)
        return getTypeByClass(value.javaClass.kotlin)
    if (value is CharSequence) {
        val txt = value.toString().trim()
        val type = getTypeByName(txt)
        if (type != null)
            return type
        val c = txt.toClass()
        if (c != null)
            return getTypeByClass(c)
    }
    if (value is Resource)
        return value.type
    if (value == null)
        return getTypeByClass(Void::class)
    try {
        if (value is Collection<*>) {
            if (value.size == 2) {
                val it = value.iterator()
                val first = it.next()
                val last = it.next()
                if (first.isText() && (last is Collection<*>))
                    return getTypeFromData(last, first.toText())
                if (last.isText() && (first is Collection<*>))
                    return getTypeFromData(first, last.toText())
            }
            return getTypeFromData(value)
        }
        else if (value is Map<*,*>) {
            try {
                if (value.size == 1) {
                    val entry = value.entries.iterator().next()
                    val eval = entry.value
                    if (eval is Collection<*>)
                        return getTypeFromData(eval, entry.key.toString())
                }
            }
            catch (e: Exception) {}
            return getTypeFromData(value.entries)
        }
        else if (value is Array<*>)
            return toType(value.toList())
    }
    catch (e: Exception) {}
    return getTypeByClass(value::class)
}

fun toFunction(value: Any?): KFunction<Any?> {
    return value.toFunction() ?: ({ value.resolve() } as KFunction<Any?>)
}

fun toProperty(value: Any?): Property {
    if (value is Property)
        return value
    if (value is PropertyReference) {
        val owner = value.parent.resolve()
        return getTypeByClass(owner!!::class).property(value.key, owner)
    }
    if (value is KProperty<*>)
        return KotlinProperty(value)
    if (value is Field)
        return KotlinProperty(value.kotlinProperty as KProperty<Any?>)
    if (value is Map.Entry<*,*>) {
        val type = getTypeByClass(if (value == null) Void::class else value::class)
        return GenericProperty(value.key.toString(), type)
    }
    if (value is CharSequence) {
        val parser = StringTokenizer(value.toString())
        var name: String? = null
        var type: Type? = null
        var options = PropertyOptions()
        while (parser.hasMoreTokens()) {
            val token = parser.nextToken()
            if (name == null)
                name = token
            else if (type == null) {
                try {}
                catch (e: Exception) {
                    val parts = token.split("(")
                    type = getTypeByName(parts[0]) ?: throw RuntimeException("Invalid SQL type: ${parts[0]}")
                    if (parts.size > 1)
                        options = options.copy(size = token.toIntOrNull())
                }
            }
            else {
                // TODO: check for other possible options in next tokens
            }
        }
        if (name != null && type != null)
            return GenericProperty(name = name, type = type, options = options)
    }
    return getTypeByClass(if (value == null) Void::class else value::class).property("", value)
}

private val FALSE_WORDS = "false,no,0,none,empty".split(",")
private val converters = initConverters()

private fun initConverters(): MutableMap<KClassifier, KFunction<Any?>> {
    val map = mutableMapOf<KClassifier, KFunction<*>>()
    for (function in arrayOf(
        ::toString,
        ::toCharSequence,
        ::toByteArray,
        ::toChar,
        ::toBoolean,
        ::toByte,
        ::toShort,
        ::toInt,
        ::toLong,
        ::toFloat,
        ::toDouble,
        ::toNumber,
        ::toDateTime,
        ::toDate,
        ::toTime,
        ::toTemporal,
        ::toUtilDate,
        ::toZoneOffset,
        ::toMap,
        ::toPerson,
        ::toList,
        ::toSet,
        ::toCollection,
        ::toArray,
        ::toNamespace,
        ::toURI,
        ::toExpression,
        ::toFilter,
        ::toInputStream,
        ::toOutputStream,
        ::toView,
        ::toAudioStream,
        ::toSpeechReader,
        ::toClass,
        ::toType,
        ::toFunction,
        ::toProperty,
    )) {
        map[function.returnType.classifier!!] = function
    }
    return map
}

private fun numericValue(value: Any?, defaultValue: Number?): Number? {
    if (value is Number)
        return value
    if (value is Boolean)
        return (if (value) 1 else 0).toByte()
    if (value == null)
        return 0
    if (value.isText()) {
        val n = value.toText().toDoubleOrNull()
        if (n != null)
            return n
    }
    return defaultValue
}

private fun printDate(date: Date): String {
    if (date is java.sql.Timestamp || date is java.sql.Time || date is java.sql.Date)
        return date.toString()
    return java.sql.Timestamp(date.time).toString()
}
