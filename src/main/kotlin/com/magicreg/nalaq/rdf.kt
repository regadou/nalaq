package com.magicreg.nalaq

import org.eclipse.rdf4j.model.*
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.XSD
import org.eclipse.rdf4j.rio.*
import org.eclipse.rdf4j.rio.helpers.StatementCollector
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.*

fun initRdfFormats(mimetypes: Collection<String>): Collection<RdfFormat> {
    if (FORMAT_NAMES.isNotEmpty())
        throw RuntimeException("The function initRdfFormats must be called only once and has already been called previously")
    for (field in RDFFormat::class.java.fields) {
        val mod = field.modifiers
        if (Modifier.isStatic(mod) && Modifier.isPublic(mod) && RDFFormat::class.java.isAssignableFrom(field.type)) {
            val syntax = field[null] as RDFFormat
            if (!EXCLUDE_FORMATS.contains(syntax)) {
                var formatFound: Boolean = false
                for (type in syntax.mimeTypes) {
                    if (!mimetypes.contains(type)) {
                        FORMAT_NAMES[syntax.name] = RdfFormat(syntax, type)
                        formatFound = true
                        break
                    }
                }
                if (!formatFound)
                    throw RuntimeException("Cannot find unused mimetype for format ${syntax.name}: ${syntax.mimeTypes}")
            }
        }
    }
    loadDefaultNamespaces()
    return FORMAT_NAMES.values
}

class RdfResource(override val id: String, override val namespace: Namespace): Resource {
    override val type: Type get() = (values["rdf:type"] ?: "rdf:Resource").type()
    override val properties: Set<Property> get() = values.keys.map{RdfProperty(this, it)}.toSet()
    private val values = mutableMapOf<String,Any?>()

    override fun getPropertyValue(name: String): Any? {
        return values[normalizePropertyName(name)]
    }

    override fun setPropertyValue(name: String, value: Any?): Boolean {
        values[normalizePropertyName(name)] = value
        return true
    }

    override fun addProperty(name: String, value: Any?): Boolean {
        val key = normalizePropertyName(name)
        if (values.contains(key))
            return false
        values[normalizePropertyName(name)] = value
        return true
    }

    private class RdfProperty(val resource: RdfResource, override val name: String): Property {
        override val type: Type get() = getTypeByName("any")!!
        override val options: PropertyOptions = PropertyOptions()

        override fun getValue(instance: Any?): Any? {
            return resource.getPropertyValue(name)
        }

        override fun setValue(instance: Any?, value: Any?): Boolean {
            return resource.setPropertyValue(name, value)
        }
    }
}

class RdfFormat(val syntax: RDFFormat, override val mimetype: String) : Format {
    override val extensions: List<String> = syntax.fileExtensions
    override val scripting: Boolean = false
    override val supported: Boolean = true
    val readerFactory: RDFParserFactory = getFactory(syntax, RDFParserFactory::class.java)
    val writerFactory: RDFWriterFactory = getFactory(syntax, RDFWriterFactory::class.java)

    override fun decode(input: InputStream, charset: String): Any? {
        return decodeRdf(syntax.name,input)
    }

    override fun encode(value: Any?, output: OutputStream, charset: String) {
        encodeRdf(syntax.name,value,output)
    }

    override fun toString(): String {
        return "Format<$mimetype>(${extensions.joinToString(",")})"
    }
}

private val EXCLUDE_FORMATS = listOf(RDFFormat.RDFA, RDFFormat.HDT)
private val CLASSES_TO_TYPES = mapOf<Class<*>,IRI>(
    Boolean::class.java to XSD.BOOLEAN,
    Byte::class.java to XSD.BYTE,
    Short::class.java to XSD.SHORT,
    Int::class.java to XSD.INT,
    Long::class.java to XSD.LONG,
    Float::class.java to XSD.FLOAT,
    Double::class.java to XSD.DOUBLE,
    BigInteger::class.java to XSD.INTEGER,
    BigDecimal::class.java to XSD.DECIMAL,
    String::class.java to XSD.STRING,
    URI::class.java to XSD.ANYURI,
    Duration::class.java to XSD.DURATION,
    LocalDateTime::class.java to XSD.DATETIME,
    LocalDate::class.java to XSD.DATE,
    LocalTime::class.java to XSD.TIME,
    List::class.java to RDF.LIST
)
private val TYPES_TO_CLASSES = initTypesToClasses()
private val FORMAT_NAMES = mutableMapOf<String,RdfFormat>()
private val DEFAULT_PREFIXES = mapOf(
    "dcam" to "http://purl.org/dc/dcam/",
    "dc" to "http://purl.org/dc/elements/1.1/",
    "dcterms" to "http://purl.org/dc/terms/",
    "grddl" to "http://www.w3.org/2003/g/data-view#",
    "owl" to "http://www.w3.org/2002/07/owl#",
    "rdf" to "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "rdfs" to "http://www.w3.org/2000/01/rdf-schema#",
    "skos" to "http://www.w3.org/2004/02/skos/core#"
)
private val DEFAULT_NAMESPACES = mutableMapOf<String, GenericNamespace>()

private class WritingContext {
    var namespaces = mutableSetOf<Namespace>()
    var statements = mutableSetOf<Statement>()
    var factory = SimpleValueFactory.getInstance()!!
}

private fun initTypesToClasses(): Map<IRI,Class<*>> {
    val map = mutableMapOf<IRI,Class<*>>()
    for (klass in CLASSES_TO_TYPES.keys) {
        val type = CLASSES_TO_TYPES[klass]!!
        if (!map.containsKey(type))
            map[type] = klass;
    }
    //TODO: RDFS Resource Class Container
    //      RDF Property HTML XMLLiteral
    return map
}

private fun loadDefaultNamespaces() {
    addNamespace(GenericNamespace("_", "http://localhost/", false))
    for (prefix in DEFAULT_PREFIXES.keys) {
        val uri = DEFAULT_PREFIXES[prefix]!!
        val ns = GenericNamespace(prefix, uri, true)
        addNamespace(ns)
        DEFAULT_NAMESPACES[prefix] = ns
    }
    for (prefix in DEFAULT_PREFIXES.keys) {
        val ns = DEFAULT_NAMESPACES[prefix]!!
        val input = RdfFormat::class.java.getResource("/rdf/$prefix.ttl").openStream()
        val map = mutableMapOf<String,Any>()
        for (resource in decodeRdf(RDFFormat.TURTLE.name, input)) {
            if (resource.namespace == ns)
                map[prefix+":"+resource.id] = resource
        }
        ns.populate(map)
    }
}

private fun normalizePropertyName(name: String): String {
    // TODO: check for compacting full uri to prefix:localName format
    return name
}

private fun decodeRdf(formatName: String, input: InputStream?): Set<Resource> {
    val parser = FORMAT_NAMES[formatName]!!.readerFactory.parser
    val collector = StatementCollector()
    parser.setRDFHandler(collector)
    parser.parse(input, "")
    val resources = mutableSetOf<Resource>()
    for ((prefix, uri) in collector.namespaces) {
        var ns: Namespace? = getNamespace(prefix) ?: getNamespace(uri)
        if (ns == null)
            addNamespace(GenericNamespace(prefix, uri))
        else if (ns.uri != uri)
            throw RuntimeException("Conflict of prefix $prefix: where old uri is ${ns.uri} but new uri is $uri")
        else if (ns.prefix != prefix)
            throw RuntimeException("Conflict of uri $uri where old prefix is ${ns.prefix} but new prefix is $prefix")
    }
    for (st in collector.statements) {
        val resource = convertValue(st.subject)
        val predicate = convertValue(st.predicate)
        val value = convertValue(st.`object`)
        if (resource != null) {
            resources.add(resource as Resource)
            if (predicate != null)
                resource.type().property(toString(predicate), resource).setValue(resource, value)
        }
    }
    return resources
}

private fun encodeRdf(formatName: String, value: Any?, output: OutputStream?) {
    val writer = FORMAT_NAMES[formatName]!!.writerFactory.getWriter(output)
    writer.startRDF()
    val wcx = WritingContext()
    addResources(wcx, value)
    for (ns in wcx.namespaces)
        writer.handleNamespace(ns.prefix, ns.uri)
    for (st in wcx.statements)
        writer.handleStatement(st)
    writer.endRDF()
}

private fun <T> getFactory(syntax: RDFFormat, type: Class<T>): T {
    val format = syntax.name.replace("/", "").replace("-", "").replace("star", "Star")
    val folder = if (format == "BinaryRDF") "binary" else format.lowercase(Locale.getDefault())
    val action = type.simpleName.substring(3)
    val name = "org.eclipse.rdf4j.rio.$folder.$format$action"
    return Class.forName(name).getConstructor().newInstance() as T
}

private fun getValue(wcx: WritingContext, value: Any?): Value? {
    if (value == null)
        return RDF.NIL
    val javaClass = if (value is java.sql.Date) toTemporal(value)::class.java else value.javaClass
    for (klass in ClassIterator(javaClass)) {
        val type = CLASSES_TO_TYPES[klass]
        if (type != null)
            return wcx.factory.createLiteral(value.toString(), type)
    }
    if (value is URI)
        return wcx.factory.createIRI(value.toString())
    if (value is Resource) {
        return if (value.namespace.prefix == "_")
            wcx.factory.createBNode(value.id)
        else
            wcx.factory.createIRI(value.namespace.uri, value.id)
    }
    if (value is Namespace)
        return wcx.factory.createIRI(value.uri)
    if (value is SqlTable)
        return wcx.factory.createIRI(value.database.uri+"/"+value.name)
    if (value is SqlColumn)
        return wcx.factory.createIRI(value.table.database.uri+"/"+value.table.name+"/"+value.name)
    if (value is SqlRow)
        return wcx.factory.createIRI(value.table.database.uri+"/"+value.table.name+"#"+value.id)
    if (value is View)
        return wcx.factory.createIRI(value.uri.toString())
    // TODO: check for Type, Expression, Person, Property, Function
    return null
}

fun convertValue(value: Value): Any? {
    if (value is IRI) {
        var ns = getNamespace(value.namespace)
        if (ns != null) {
            val data = ns.value(value.localName) ?: ns.value(ns.prefix + ":" + value.localName)
            if (data != null) return data
        }
        ns = GenericNamespace()
        return RdfResource(value.localName, ns)
    }
    if (value is BNode) {
        val ns = getNamespace("_")!!
        val data = ns.value(value.id) ?: ns.value(ns.prefix + ":" + value.id)
        if (data != null)
            return data
        return RdfResource(value.id, ns)
    }
    if (value is Literal) {
        if (value.language.isPresent)
            return value.language.get()+":"+value.label
        val type = TYPES_TO_CLASSES[value.datatype]
        try { return convert(value.label, type?.kotlin ?: String::class) }
        catch (e: Exception) { throw RuntimeException("Error trying to convert $value: ${e.message}")}
    }
    throw java.lang.RuntimeException("Unknown RDF value type: " + value::class.qualifiedName)
}


private fun addResources(wcx: WritingContext, value: Any?, ns: Namespace? = null) {
    if (value is Iterator<*>) {
        while (value.hasNext())
            addResources(wcx, value.next())
    }
    else if (value is Iterable<*>)
        addResources(wcx, value.iterator())
    else if (value is Array<*>)
        addResources(wcx, ArrayIterator(value))
    else if (value is Resource) {
        val iri = wcx.factory.createIRI(value.namespace.uri, value.id)
        for (property in value.properties) {
            val data = value.properties.firstOrNull { it.name == property.name }?.getValue(value)
            wcx.statements.add(wcx.factory.createStatement(iri, getValue(wcx, property) as IRI, getValue(wcx, data)))
        }
    }
    else if (value is Namespace) {
        for (name in value.names)
            addResources(wcx, value.value(name), value)
    }
    else if (value is Map<*,*>) {
        // TODO: create an id for this map and loop through its keys to create a statement for each (map,key,value) triplet
    }
    else {
        // TODO: check for Type, Expression, Person, Property, Function
    }
}
