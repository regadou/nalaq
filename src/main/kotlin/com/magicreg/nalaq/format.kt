package com.magicreg.nalaq

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import ezvcard.io.text.VCardReader
import org.apache.commons.csv.CSVFormat
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.w3c.dom.Node
import java.awt.image.RenderedImage
import java.io.*
import java.lang.reflect.Member
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

fun getFormat(txt: String): Format? {
    val lower = txt.lowercase()
    return MIMETYPES[lower] ?: EXTENSIONS[lower]
}

fun getExtensionMimetype(txt: String): String? {
    return EXTENSIONS[txt.lowercase()]?.mimetype
}

fun getMimetypeExtensions(txt: String): List<String> {
    val format = MIMETYPES[txt.lowercase()]
    return format?.extensions ?: listOf()
}

fun detectFileType(path: String?): String? {
    if (path == null)
        return null
    var parts = path.trim().split("/")
    val filename = parts[parts.size-1]
    if (filename == "" || filename.indexOf(".") < 0)
        return null
    if (filename[0] == '.')
        return "text/plain"
    parts = filename.split(".")
    return getExtensionMimetype(parts[parts.size-1])
}

class GenericFormat(
    override val mimetype: String,
    override val extensions: List<String>,
    private val decoder: (InputStream, String) -> Any?,
    private val encoder: (Any?, OutputStream, String) -> Unit,
    override val scripting: Boolean,
    override val supported: Boolean
): Format {
    override fun decode(input: InputStream, charset: String): Any? {
        return decoder(input, charset)
    }

    override fun encode(value: Any?, output: OutputStream, charset: String) {
        encoder(value, output, charset)
    }

    override fun toString(): String {
        return "Format<$mimetype>(${extensions.joinToString(",")})"
    }
}

private const val CSV_MINIMUM_CONSECUTIVE_FIELDS_SIZE = 5
private const val CSV_BUFFER_SIZE = 1024
private const val JSON_EXPRESSION_START = "{[\""
private val CSV_SEPARATORS = ",;|:\t".toCharArray()
private val JSON_SERIALIZER_CLASSES = listOf(Type::class, KClass::class, KCallable::class, Class::class, Member::class, KProperty::class, Property::class, Exception::class,
                                             InputStream::class, OutputStream::class, Reader::class, Writer::class, ByteArray::class, CharArray::class,
                                             Reference::class, Resource::class, Namespace::class, Format::class, Element::class, View::class)
private val CSV_FORMAT = configureCsvFormat()
private val JSON_MAPPER = configureJsonMapper(ObjectMapper())
private val YAML_MAPPER = configureJsonMapper(ObjectMapper(YAMLFactory()))
private val SCRIPTING_FORMATS = unsupportedScriptingFormats()
private val EXTENSIONS = mutableMapOf<String,Format>()
private val MIMETYPES = loadMimeTypesFile()

private fun configureJsonMapper(mapper: ObjectMapper): ObjectMapper {
    mapper.configure(SerializationFeature.INDENT_OUTPUT, true)
        .configure(SerializationFeature.WRITE_CHAR_ARRAYS_AS_JSON_ARRAYS, false)
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        .configure(SerializationFeature.FAIL_ON_SELF_REFERENCES, false)
        .configure(SerializationFeature.FAIL_ON_UNWRAPPED_TYPE_IDENTIFIERS, false)
        .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, true)
        .configure(SerializationFeature.WRITE_DATE_KEYS_AS_TIMESTAMPS, false)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .configure(SerializationFeature.WRITE_DATES_WITH_ZONE_ID, false)
        .configure(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS, false)
        .configure(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS, true)
        .configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, true)
        .configure(SerializationFeature.WRITE_ENUMS_USING_INDEX, false)
        .configure(SerializationFeature.WRITE_NULL_MAP_VALUES, true)
        .configure(SerializationFeature.WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED, false)
        .setDateFormat(SimpleDateFormat("yyyy-MM-dd hh:mm:ss"))
    val module = SimpleModule()
    for (klass in JSON_SERIALIZER_CLASSES)
        addSerializer(module, klass.java)
    return mapper.registerModule(module).registerModule(JavaTimeModule())
}

private fun loadMimeTypesFile(): MutableMap<String,Format> {
    val mimetypes = initManagedMimetypes()
    val reader = BufferedReader(InputStreamReader(GenericFormat::class.java.getResource("/mime.types").openStream()))
    reader.lines().forEach { line ->
        val escape = line.indexOf('#')
        val txt = if (escape >= 0) line.substring(0, escape) else line
        var mimetype: String? = null
        var extensions = mutableListOf<String>()
        val e = StringTokenizer(txt)
        while (e.hasMoreElements()) {
            val token = e.nextElement().toString()
            if (mimetype == null)
                mimetype = token
            else
                extensions.add(token)
        }
        if (mimetype != null) {
            val scripting = SCRIPTING_FORMATS[mimetype] ?: false
            if (mimetypes[mimetype] != null)
                ;
            else if (mimetype.split("/")[0] == "text")
                addMimetype(mimetypes, ::decodeString, ::encodeString, mimetype, extensions, scripting, false)
            else
                addMimetype(mimetypes, ::decodeBytes, ::encodeBytes, mimetype, extensions, scripting, false)
        }
    }
    reader.close()
    return mimetypes
}

private fun addMimetype(mimetypes: MutableMap<String,Format>,
                        decoder: (InputStream, String) -> Any?,
                        encoder: (Any?, OutputStream, String) -> Unit,
                        mimetype: String, extensions: List<String>,
                        scripting: Boolean = false,
                        supported: Boolean = true) {
    val format = GenericFormat(mimetype, extensions, decoder, encoder, scripting, supported)
    mimetypes[mimetype] = format
    addExtensions(format)
}

private fun addExtensions(format: Format) {
    for (extension in format.extensions) {
        if (!EXTENSIONS.containsKey(extension))
            EXTENSIONS[extension] = format
    }
}

private fun initManagedMimetypes(): MutableMap<String,Format> {
    val formats = mutableMapOf<String,Format>()

    addMimetype(formats, ::decodeBytes, ::encodeBytes, "application/octet-stream", listOf("bin"))
    addMimetype(formats, ::decodeString, ::encodeTextPlain, "text/plain", listOf("txt"))
    addMimetype(formats, ::decodeProperties, ::encodeProperties, "text/x-java-properties", listOf("properties"))
    addMimetype(formats, ::decodeCsv, ::encodeCsv, "text/csv", listOf("csv"))
    addMimetype(formats, ::decodeUris, ::encodeUris, "text/uri-list", listOf("uris", "uri"))
    addMimetype(formats, ::decodeNalaq, ::encodeNalaq, "text/x-nalaq", listOf("nalaq", "nlq"), true)
    addMimetype(formats, ::decodeKotlin, ::encodeKotlin, "text/x-kotlin", listOf("kts", "kt"), true)
    addMimetype(formats, ::decodeJson, ::encodeJson, "application/json", listOf("json"))
    addMimetype(formats, ::decodeYaml, ::encodeYaml, "application/yaml", listOf("yaml"))
    addMimetype(formats, ::decodeHtml, ::encodeHtml, "text/html", listOf("html", "htm"))
    addMimetype(formats, ::decodeXml, ::encodeXml, "application/xml", listOf("xml"))
    addMimetype(formats, ::decodeForm, ::encodeForm, "application/x-www-form-urlencoded", listOf("form", "urlencoded"))
    addMimetype(formats, ::decodeVcard, ::encodeVcard, "text/vcard", listOf("vcf", "vcard"))
    addMimetype(formats, ::decodeCalendar, ::encodeCalendar, "text/calendar", listOf("ics", "ifb"))

    for (mimetype in ImageIO.getReaderMIMETypes()) {
        if (!mimetype.startsWith("x-")) {
            addMimetype(formats,
                { input, charset -> ImageIO.read(input) },
                { value, output, charset -> run { ImageIO.write(value as RenderedImage, mimetype, output) }},
                mimetype, getImageExtensions(mimetype)
            )
        }
    }

    for (type in AudioSystem.getAudioFileTypes()) {
        val format = AudioType(type)
        formats[format.mimetype] = format
        addExtensions(format)
    }

    for (format in initRdfFormats(formats.keys)) {
        for (type in format.syntax.mimeTypes) {
            if (!formats.containsKey(type))
                formats[type] = format
        }
        addExtensions(format)
    }

    return formats
}

private fun decodeString(input: InputStream, charset: String): Any? {
    return input.readAllBytes().toString(Charset.forName(charset))
}

private fun encodeString(value: Any?, output: OutputStream, charset: String) {
    if (value != null)
        output.write(toString(value).toByteArray(Charset.forName(charset)))
}

private fun encodeTextPlain(value: Any?, output: OutputStream, charset: String) {
    if (value != null)
        output.write(value.toPlainText().toByteArray(Charset.forName(charset)))
}

private fun decodeBytes(input: InputStream, charset: String): Any? {
    return input.readAllBytes()
}

private fun encodeBytes(value: Any?, output: OutputStream, charset: String) {
    if (value is ByteArray)
        output.write(value)
    else if (value != null)
        output.write(toString(value).toByteArray(Charset.forName(charset)))
}

private fun decodeUris(input: InputStream, charset: String): Any? {
    return input.readAllBytes().toString(Charset.forName(charset)).split("\n").filter { it.isNotBlank() && it.trim()[0] != '#' }.map { URI(it) }
}

private fun encodeUris(value: Any?, output: OutputStream, charset: String) {
    val uris = toCollection(value).map { toURI(it) }
    output.write((uris.joinToString("\n")+"\n").toByteArray(Charset.forName(charset)))
}

private fun decodeJson(input: InputStream, charset: String): Any? {
    return JSON_MAPPER.readValue(InputStreamReader(input, charset), Any::class.java)
}

private fun encodeJson(value: Any?, output: OutputStream, charset: String) {
    encodeString(JSON_MAPPER.writeValueAsString(value)+"\n", output, charset)
}

private fun decodeYaml(input: InputStream, charset: String): Any? {
    return YAML_MAPPER.readValue(InputStreamReader(input, charset), Any::class.java)
}

private fun encodeYaml(value: Any?, output: OutputStream, charset: String) {
    encodeString(YAML_MAPPER.writeValueAsString(value)+"\n", output, charset)
}

private fun decodeVcard(input: InputStream, charset: String): Any? {
    val reader = VCardReader(input);
    return reader.readAll()
}

private fun encodeVcard(value: Any?, output: OutputStream, charset: String) {
    throw RuntimeException("VCard encoding not implemented yet")
}

private fun decodeCalendar(input: InputStream, charset: String): Any? {
    throw RuntimeException("Calendar decoding not implemented yet")
}

private fun encodeCalendar(value: Any?, output: OutputStream, charset: String) {
    throw RuntimeException("Calendar encoding not implemented yet")
}

private fun decodeProperties(input: InputStream, charset: String?): Any? {
    val props = Properties()
    props.load(InputStreamReader(input, charset))
    return props
}

private fun encodeProperties(value: Any?, output: OutputStream, charset: String?) {
    var properties: Properties?
    if (value is Properties)
        properties = value
    else if (value == null)
        properties = Properties()
    else {
        val map = toMap(value)
        properties = Properties()
        for (key in map.keys)
            properties.setProperty(toString(key), toString(map[key]))
    }
    properties.store(OutputStreamWriter(output, charset), "")
}

private fun decodeNalaq(input: InputStream, charset: String): Any? {
    return input.readAllBytes().toText().toExpression()
}

private fun encodeNalaq(value: Any?, output: OutputStream, charset: String) {
    output.write((value.toText()+"\n").toByteArray(Charset.forName(charset)))
}

private fun decodeKotlin(input: InputStream, charset: String): Any? {
    return ScriptEngineParser("kts", getContext(), emptyList()).parse(input.readAllBytes().toString(Charset.forName(charset)))
}

private fun encodeKotlin(value: Any?, output: OutputStream, charset: String) {
    output.write((value.toString()+"\n").toByteArray(Charset.forName(charset)))
}

private fun decodeCsv(input: InputStream, charset: String): Any {
    val dst = mutableListOf<Map<String,Any>>()
    val bufferedInput = BufferedInputStream(input, CSV_BUFFER_SIZE)
    val records = CSV_FORMAT.builder().setDelimiter(detectSeparator(bufferedInput)).build().parse(InputStreamReader(bufferedInput, charset))
    val fields = mutableListOf<String>()
    for (record in records) {
        if (fields.isEmpty()) {
            val it = record.iterator()
            while (it.hasNext())
                fields.add(it.next())
        }
        else {
            val map = mutableMapOf<String,Any>()
            val n = Math.min(fields.size, record.size())
            for (f in 0 until n)
                map[fields[f]] = record.get(f)
            dst.add(map)
        }
    }
    return dst
}

private fun encodeCsv(value: Any?, output: OutputStream, charset: String) {
    val printer = CSV_FORMAT.print(OutputStreamWriter(output, charset))
    val records = toList(value)
    val fields = mutableListOf<String>()
    var lastSize = 0
    var consecutives = 0
    for (record in records) {
        val map = toMap(record)
        for (key in map.keys) {
            if (fields.indexOf(key) < 0)
                fields.add(key.toString())
        }
        if (lastSize == fields.size)
            consecutives++
        else {
            consecutives = 0
            lastSize = fields.size
        }
        if (consecutives >= CSV_MINIMUM_CONSECUTIVE_FIELDS_SIZE)
            break;
    }
    printer.printRecord(fields)
    for (record in records) {
        val map = toMap(record)
        for (field in fields) {
            val cell = map[field];
            printer.print(toString(cell))
        }
        printer.println();
    }
    printer.flush()
}

private fun decodeForm(input: InputStream, charset: String): Any? {
    val map = mutableMapOf<String,Any?>()
    var entries = InputStreamReader(input, charset).readText().split("&")
    for (entry in entries) {
        val eq = entry.indexOf('=')
        if (eq < 0)
            setValue(map, entry, true)
        else
            setValue(map, entry.substring(0,eq), entry.substring(eq+1))
    }
    return map
}

private fun encodeForm(value: Any?, output: OutputStream, charset: String) {
    val entries = mutableListOf<String>()
    val map = toMap(value)
    for (key in map.keys) {
        entries.add(urlencode(key?.toString() ?: "null")+"="+
                urlencode(map[key]?.toString() ?: "null"))
    }
    encodeString(entries.joinToString("&"), output, charset)
}

private fun decodeXml(input: InputStream, charset: String): Any? {
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
}

private fun encodeXml(value: Any?, output: OutputStream, charset: String) {
    if (value == null || value !is Node)
        throw RuntimeException("XML data to encode must be of type ${Node::class.qualifiedName}")
    TransformerFactory.newInstance().newTransformer().transform(DOMSource(value), StreamResult(output))
}

private fun decodeHtml(input: InputStream, charset: String): Any? {
    // TODO: use HtmlUnit if there is javascript code that interacts with the DOM (any window or document calls)
    return Jsoup.parse(InputStreamReader(input, charset).readText())
}

private fun encodeHtml(value: Any?, output: OutputStream, charset: String) {
    val separator: String? = null
    val fields: MutableList<String>? = null
    val html = if (value == null)
        ""
    else if (value is Element)
        value.outerHtml()
    else if (value is Map<*,*>)
        encodeHtmlMap(value as Map<Any?,Any?>, separator, fields)
    else if (value is Collection<*>)
        encodeHtmlCollection(value, separator, fields)
    else if (value is Array<*>)
        encodeHtmlCollection(listOf(*value), separator, fields)
    else
        value.toString()
    encodeString(html+"\n", output, charset)
}

private fun encodeHtmlMap(map: Map<Any?,Any?>, separator: String?, fields: MutableList<String>?): String {
    val cells = mutableListOf<String>()
    if (fields != null) {
        if (separator == null)
            throw RuntimeException("Bad programming: fields is not null but separator is null")
        for (key in map.keys) {
            val txt = toString(key)
            if (!fields.contains(txt))
                fields.add(txt)
        }
        for (field in fields)
            cells.add(toString(map[field]))
    }
    else if (separator != null) {
        for (key in map.keys)
            cells.add(toString(key)+" = "+toString(map[key]))
    }
    else {
        cells.add("<table border=1 cellspacing=2 cellpadding=2>\n<tr>")
        for (key in map.keys)
            cells.add("<td>"+toString(key)+"</td><td>"+toString(map[key])+"</td>")
        cells.add("</tr>\n</table>")
    }
    return cells.joinToString(separator ?: "</tr>\n<tr>")
}

private fun encodeHtmlCollection(col: Collection<Any?>, separator: String?, fields: MutableList<String>?): String {
    if (col.isEmpty())
        return ""
    if (separator != null)
        return col.joinToString(separator)
    if (fields == null) {
        val first = col.iterator().next()
        if (first is Map<*,*>) {
            val headers = mutableListOf<String>()
            val rows = mutableListOf("<table border=1 cellspacing=2 cellpadding=2>\n<tr>", "")
            for (item in col)
                rows.add("<td>"+encodeHtmlMap(toMap(item), "</td><td>", headers)+"</td>")
            rows.add("</tr>\n</table>")
            rows[1] = "<th>"+headers.joinToString("</th><th>")+"</th>"
            return rows.joinToString("</tr>\n<tr>")
        }
        if (first is Collection<*>) {
            // TODO: print with <UL><LI> structure
        }
        if (first is Array<*>) {
            // TODO: print with <UL><LI> structure
        }
        return encodeHtmlCollection(col, "<br>\n", fields)
    }
    return col.joinToString("<br>\n")
}

private fun configureCsvFormat(): CSVFormat {
    return CSVFormat.DEFAULT.builder().setDelimiter(CSV_SEPARATORS[0]).build()
}

private fun <T> addSerializer(module: SimpleModule, klass: Class<T>) {
    val serializer = object : JsonSerializer<T>() {
        override fun serialize(value:T, jgen: JsonGenerator, provider: SerializerProvider) {
            jgen.writeString(toString(value))
        }
    }
    module.addSerializer(klass, serializer)
}

private fun getImageExtensions(mimetype: String): List<String> {
    val subtype = mimetype.split("/")[1]
    return when(subtype) {
        "jpeg" -> listOf("jpg", "jpeg", "jpe")
        "tiff" -> listOf("tif", "tiff")
        "vnd.wap.wbmp" -> listOf("wbmp")
        else -> listOf(subtype)
    }
}

private fun parseValue(src: Any?): Any? {
    if (src is CharSequence) {
        return when (val value = URLDecoder.decode(src.toString(), defaultCharset()).trim()) {
            "null" -> null
            "true" -> true
            "false" -> false
            else -> value.toIntOrNull() ?: value.toDoubleOrNull() ?: getExpression(value.trim())
        }
    }
    return src
}

private fun getExpression(value: String): Any? {
    if (value.isEmpty())
        return ""
    if (JSON_EXPRESSION_START.indexOf(value[0]) < 0)
        return value
    val first = value[0]
    val last = value[value.length-1]
    if ((first == '[' && last == ']') || (first == '{' && last == '}') || (first == '"' && last == '"')) {
        try { return JSON_MAPPER.readValue(ByteArrayInputStream(value.toByteArray()), Any::class.java) }
        catch(e: Exception) {}
    }
    return value
}

private fun setValue(map: MutableMap<String,Any?>, name: String, value: Any?) {
    val key = URLDecoder.decode(name, defaultCharset())
    val old = map[key]
    if (old == null)
        map[key] = parseValue(value)
    else if (old is MutableCollection<*>)
        (old as MutableCollection<Any?>).add(parseValue(value))
    else
        map[key] = mutableListOf<Any?>(old, parseValue(value))
}

private fun urlencode(txt: String): String {
    return URLEncoder.encode(txt, defaultCharset()).replace("+", "%20")
}

private fun detectSeparator(input: InputStream): Char {
    val buffer = ByteArray(CSV_BUFFER_SIZE)
    input.mark(CSV_BUFFER_SIZE)
    input.read(buffer)
    input.reset()
    val txt = buffer.toString(Charset.forName(defaultCharset()))
    for (c in txt) {
        if (CSV_SEPARATORS.contains(c))
            return c
        if (c == '\n' || c == '\r')
            break
    }
    return CSV_SEPARATORS[0]
}

private fun unsupportedScriptingFormats(): Map<String,Boolean> {
    return """
        text/x-csh
        text/x-perl
        text/x-python
        text/x-sh
        text/x-tcl
        application/x-csh
        application/x-ruby
        application/x-sh
        application/x-shar
        application/x-tcl
        application/x-trash
        application/x-xpinstall
        application/x-xz
        text/x-cgi
        text/x-php
        text/x-asp
        text/x-jsp
        text/x-jss 
        text/x-ssjs
        text/x-groovy
        text/x-lua
        text/x-clojure
        text/x-nodejs
    """.trimIndent().trim().split("\n").associateWith { true }
}