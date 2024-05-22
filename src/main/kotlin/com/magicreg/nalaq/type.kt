package com.magicreg.nalaq

import ezvcard.VCard
import org.apache.commons.beanutils.BeanMap
import java.io.*
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.net.URL
import java.sql.Date
import java.sql.Time
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.full.memberProperties
import org.jsoup.nodes.Element
import java.lang.reflect.Field
import java.util.*
import kotlin.reflect.KType

fun getTypeByClass(klass: KClass<*>): Type {
    var type = CLASS_TYPE_MAP[klass]
    if (type != null)
        return type
    for (superclass in ClassIterator(klass.java)) {
        if (superclass == Any::class.java)
            continue
        type = CLASS_TYPE_MAP[superclass.kotlin]
        if (type != null) {
            CLASS_TYPE_MAP[klass] = type
            return type
        }
    }
    CLASS_TYPE_MAP[klass] = ANY_TYPE
    return ANY_TYPE
}

fun getTypeByName(name: String): Type? {
    return NAME_TYPE_MAP[name]
}

fun getTypeFromData(data: Collection<*>, name: String? = null): Type {
    if (data.isEmpty())
        throw RuntimeException("data is empty")

    val first = data.iterator().next()
    val klass = if (first is Map<*,*>)
        Map::class
    else if (first is Property)
        Property::class
    else if (first is Map.Entry<*,*>)
        Map.Entry::class
    else if (first is CharSequence)
        CharSequence::class
    else if (first == null)
        throw RuntimeException("Null item in data")
    else
        throw RuntimeException("Invalid item type: ${first::class.qualifiedName}")

    val properties = mutableMapOf<String,Property>()
    val keys = if (first is Map<*,*>) first.keys.map{it.toString()}.sorted().joinToString(",") else ""
    val iterator = data.iterator()
    while (iterator.hasNext()) {
        val value = iterator.next() ?: throw RuntimeException("Null item in data")
        if (!klass.isInstance(value))
            throw RuntimeException("Conflicting item type ${value::class.qualifiedName} with first item ${klass.qualifiedName}")
        else if (value is Map<*,*>) {
            val newKeys = value.keys.map{it.toString()}.sorted().joinToString(",")
            if (newKeys != keys)
                throw RuntimeException("Item properties are not the same: $newKeys versus $keys")
            val newProps = mapProperties(value)
            if (properties.isEmpty())
                properties.putAll(newProps)
            else {
                // TODO: check if each property has the same type and replace ANY_TYPE types if changed (i.e. first was null and others are not
            }
        }
        else {
            val property = toProperty(value)
            if (properties.keys.firstOrNull { it == property.name } != null)
                throw RuntimeException("Duplicate property name: ${property.name}")
            properties[property.name] = property
        }
    }

    val constructor: KFunction<Any?>? = null // TODO: construct a map with properties from any value
    val validator: ((Any?) -> Boolean)? = null // TODO: validate if map or bean map has required properties with proper value type
    val type = GenericType(name ?: UUID.randomUUID().toString(), getTypeByClass(Map::class), emptyList(), properties, constructor, validator)
    if (name != null) {
        if (NAME_TYPE_MAP.containsKey(name))
            throw RuntimeException("Type name already registered: $name")
        NAME_TYPE_MAP[name] = type
    }

    return type
}

fun anyType(): Type { return ANY_TYPE }
fun nullProperty(): Property { return NULL_PROPERTY }

class GenericType(
    override val name: String,
    override val parentType: Type,
    override val classes: List<KClass<*>> = listOf(),
    private val storedProperties: Map<String,Property>? = null,
    private val constructor: KFunction<Any?>? = null,
    private val validator: ((Any?) -> Boolean)? = null
): Type {
    override val rootType: Type get() {
        var type: Type = this
        while (type.parentType != ANY_TYPE)
            type = type.parentType
        return type
    }
    override val childrenTypes: List<Type> get() = subTypes.toList()
    private val subTypes = mutableSetOf<GenericType>()
    init {
        if (parentType is GenericType)
            parentType.subTypes.add(this)
        else if (parentType is AnyType)
            parentType.subTypes.add(this)
    }

    override fun toString(): String {
        return "Type($name)"
    }

    override fun properties(instance: Any?): List<String> {
        return if (storedProperties != null)
            addToGenericNames(storedProperties.keys)
        else if (classes.isEmpty() || Collection::class.isSuperclassOf(classes[0]) || classes[0]::class.java.isArray)
            addToGenericNames()
        else if (Map::class.isSuperclassOf(classes[0]) || Namespace::class.isSuperclassOf(classes[0]))
            addToGenericNames(toMap(instance).keys.map { toString(it) })
        else
            addToGenericNames(classes[0].memberProperties.map { it.name })
    }

    override fun property(name: String, instance: Any?): Property {
        return if (name.isBlank())
            SelfProperty()
        else if (storedProperties != null)
            storedProperties[name] ?: GENERIC_PROPERTIES[name] ?: NULL_PROPERTY
        else if (classes.isEmpty())
            GENERIC_PROPERTIES[name] ?: NULL_PROPERTY
        else if (Collection::class.isSuperclassOf(classes[0]) || classes[0]::class.java.isArray)
            indexProperty(name) ?: GENERIC_PROPERTIES[name] ?: NULL_PROPERTY
        else if (Map::class.isSuperclassOf(classes[0]) || Namespace::class.isSuperclassOf(classes[0]))
            mapProperty(name, toMap(instance)) ?: GENERIC_PROPERTIES[name] ?: NULL_PROPERTY
        else
            getKotlinProperty(name, instance) ?: GENERIC_PROPERTIES[name] ?: NULL_PROPERTY
    }

    override fun newInstance(args: List<Any?>): Any? {
        if (constructor != null)
            return constructor.call(args.simplify(true))
        for (c in classes) {
            val converter = getConverter(c)
            if (converter != null)
                return converter.call(args.simplify(true))
            for (cons in c.constructors) {
                try { return cons.call(*args.toTypedArray()) }
                catch (e: Exception) {}
            }
        }
        throw RuntimeException("Cannot construct a new instance of type $name")
    }

    override fun isInstance(value: Any?): Boolean {
        if (validator != null)
            return validator?.let { it(value) } ?: false
        for (c in classes) {
            if (c.isInstance(value))
                return true
        }
        for (subtype in subTypes) {
            if (subtype.isInstance(value))
                return true
        }
        return false
    }

    private fun getKotlinProperty(name: String, instance: Any?): Property? {
        for (klass in classes) {
            if (klass.isInstance(instance)) {
                val property = klass.memberProperties.filter { it.name == name }.map {KotlinProperty(it)}.firstOrNull()
                if (property != null)
                    return property
            }
        }
        return null
    }
}

class GenericProperty(
    override val name: String,
    override val type: Type,
    private val reader: ((Any?) -> Any?)? = null,
    private val writer: ((Any?,Any?) -> Boolean)? = null,
    override val options: PropertyOptions = PropertyOptions()
): Property {

    override fun toString(): String {
        return "Property($name)"
    }

    override fun getValue(instance: Any?): Any? {
        return if (reader != null)
            reader.invoke(instance)
        else
            toMap(instance)[name]
    }

    override fun setValue(instance: Any?, value: Any?): Boolean {
        return if (writer != null)
            writer.invoke(instance, value)
        else if (instance is Map<*,*>) {
            if (instance is MutableMap<*,*>) {
                (instance as MutableMap<Any?,Any?>)[name] = value
                true
            }
            else
                false
        }
        else {
            try {
                BeanMap(value)[name] = value
                true
            }
            catch (e: Exception) { false }
        }
    }
}

class KotlinProperty(
    private val kotlin: KProperty<*>
): Property {
    override val name: String = kotlin.name
    override val type: Type = kotlin.returnType.type()
    override val options: PropertyOptions = PropertyOptions(nullable = kotlin.returnType.isMarkedNullable)

    override fun toString(): String {
        return "Property($name)"
    }

    override fun getValue(instance: Any?): Any? {
        return kotlin?.getter?.call(instance)
    }

    override fun setValue(instance: Any?, value: Any?): Boolean {
        try {
            kotlin.call(instance, value)
            return true
        }
        catch (e: Exception) { return false }
    }
}

class SelfProperty(): Property {
    override val name: String = "self"
    override val type: Type = ANY_TYPE
    override val options: PropertyOptions = PropertyOptions()

    override fun toString(): String {
        return "Property(self)"
    }

    override fun getValue(instance: Any?): Any? {
        return instance
    }

    override fun setValue(instance: Any?, value: Any?): Boolean {
        return false
    }
}

private class AnyType(): Type {
    override val name = "any"
    override val parentType = this
    override val childrenTypes: List<Type> get() = subTypes.toList()
    override val rootType: Type = this
    override val classes = listOf(Any::class)
    override fun properties(instance: Any?): List<String> {
        return addToGenericNames()
    }
    override fun property(name: String, instance: Any?): Property {
        return if (name.isBlank()) SelfProperty() else GENERIC_PROPERTIES[name] ?: NULL_PROPERTY
    }
    override fun newInstance(args: List<Any?>): Any? {
        return simplify(args)
    }
    override fun isInstance(value: Any?): Boolean {
        return true
    }
    val subTypes = mutableSetOf<GenericType>()
}

private val ANY_TYPE = AnyType()
private val NULL_PROPERTY: Property = GenericProperty("null", ANY_TYPE)
private val CLASS_TYPE_MAP = mutableMapOf<KClass<*>, Type>(
    Void::class to GenericType("nothing", ANY_TYPE, listOf(Void::class), null, ::nullFunction) { it == null },
    Number::class to GenericType("number", ANY_TYPE, listOf(Number::class), null, ::toNumber),
    KFunction::class to GenericType("function", ANY_TYPE, listOf(KFunction::class, Method::class, Constructor::class), null, ::toFunction),
    Map::class to GenericType("entity", ANY_TYPE, listOf(Map::class), null, ::toEntity),
    Collection::class to GenericType("collection", ANY_TYPE, listOf(Collection::class), null, ::toList) { it is Collection<*> || (it != null && it::class.java.isArray) },
    CharSequence::class to GenericType("text", ANY_TYPE, listOf(CharSequence::class, CharArray::class, Char::class), null, ::toConcatString),
    View::class to GenericType("view", ANY_TYPE, listOf(View::class), null, ::toView),
    Any::class to ANY_TYPE
)
private val NAME_TYPE_MAP = indexAllBuiltinTypes()
private val GENERIC_PROPERTIES = mapOf(
    "" to GenericProperty("", ANY_TYPE, {it}),
    "type" to GenericProperty("type", CLASS_TYPE_MAP[Type::class]!!, {getTypeByClass(if (it == null) Void::class else it::class)}),
    "class" to GenericProperty("class", CLASS_TYPE_MAP[KClass::class]!!, {if (it == null) Void::class else it::class}),
    "size" to GenericProperty("size", CLASS_TYPE_MAP[Int::class]!!, ::sizePropertyValue),
    "keys" to GenericProperty("keys", CLASS_TYPE_MAP[Int::class]!!, ::keysPropertyValue)
)

private fun indexAllBuiltinTypes(): MutableMap<String, Type> {
    addType("decimal", Number::class, listOf(Double::class, Float::class, BigDecimal::class), ::toDouble)
    addType("integer", Number::class, listOf(Int::class, Long::class, Short::class, Byte::class, BigInteger::class), ::toInteger)
    addType("boolean", Number::class, listOf(Boolean::class), ::toBoolean)
    addType("type", Map::class, listOf(Type::class, KClass::class, Class::class, KType::class), ::toType)
    addType("property", Map::class, listOf(Property::class, PropertyReference::class, Map.Entry::class, KProperty::class, Field::class), ::toProperty)
    addType("namespace", Map::class, listOf(Namespace::class), ::toNamespace)
    addType("person", Map::class, listOf(Person::class), ::toPerson)
    addType("contact", Map::class, listOf(VCard::class))
    addType("chemical", Map::class, listOf(Chemical::class))
    addType("timestamp", Map::class, listOf(LocalDateTime::class, Timestamp::class, java.util.Date::class), ::toDateTime)
    addType("date", LocalDateTime::class, listOf(LocalDate::class, Date::class), ::toDate)
    addType("time", LocalDateTime::class, listOf(LocalTime::class, Time::class), ::toTime)
    addType("duration", Map::class, listOf(kotlin.time.Duration::class, java.time.Duration::class, javax.xml.datatype.Duration::class), ::toDuration)
    addType("location", Map::class, listOf(GeoLocation::class))
    // TODO: path (1D shape), area (2D shapes) and volume (3D shapes)
    addType("array", Collection::class, arrayTypes(), ::toArray)
    addType("set", Collection::class, listOf(Set::class, MutableSet::class), ::toSet)
    addType("list", Collection::class, listOf(List::class, MutableList::class), ::toList)
    addType("uri", CharSequence::class, listOf(URI::class, URL::class, File::class), ::toURI)
    addType("binary", CharSequence::class, listOf(ByteArray::class), ::toByteArray)
    addType("input", CharSequence::class, listOf(InputStream::class, Reader::class), ::toInputStream)
    addType("output", CharSequence::class, listOf(OutputStream::class, Writer::class), ::toOutputStream)
    addType("speech", Reader::class, listOf(SpeechReader::class), ::toSpeechReader)
    addType("expression", CharSequence::class, listOf(Expression::class, Filter::class), ::toExpression)
    addType("document", View::class, listOf(Element::class))
    addType("image", View::class, listOf(Image::class))
    addType("audio", View::class, listOf(Audio::class))
    addType("video", View::class, listOf(Video::class))
    addType("model", View::class, listOf(Model::class))

    val missingPairs = mutableListOf<Pair<KClass<*>,Type>>()
    for (type in CLASS_TYPE_MAP.values) {
        for (klass in type.classes) {
            if (CLASS_TYPE_MAP[klass] == null)
                missingPairs.add(Pair(klass, type))
        }
    }
    for (pair in missingPairs)
        CLASS_TYPE_MAP[pair.first] = pair.second

    val map = mutableMapOf<String, Type>()
    var duplicates = mutableListOf<String>()
    for (type in CLASS_TYPE_MAP.values.toSet()) {
        if (map.containsKey(type.name))
            duplicates.add(type.name)
        else
            map[type.name] = type
    }
    if (duplicates.isNotEmpty())
        throw RuntimeException("Duplicate names found: $duplicates")
    return map
}

private fun addType(name: String, parent: KClass<*>, classes: List<KClass<*>>, constructor: KFunction<Any?>? = null) {
    if (classes.isEmpty())
        throw RuntimeException("No classes given for new type $name")
    for (klass in classes) {
        if (CLASS_TYPE_MAP.containsKey(klass))
            throw RuntimeException("Duplicate $klass for new type $name")
    }
    CLASS_TYPE_MAP[classes[0]] = GenericType(name, getTypeByClass(parent), classes, null, constructor)
}

private fun simplify(args: List<Any?>): Any? {
    if (args.isEmpty())
        return null
    if (args.size == 1) {
        var value = args[0] ?: return null
        if (value is Collection<*>)
            return simplify(value.toList())
        if (value is Array<*>)
            return simplify(value.toList())
        if (value is Map<*,*>)
            return value.entries.filter { it.key != null && it.value != null }.map { it.key to it.value }.toMap()
        return value
    }
    val items = mutableListOf<Any?>()
    for (arg in args) {
        val simplified = simplify(listOf(arg))
        if (simplified != null)
            items.add(simplified)
    }
    return items.simplify()
}

private fun addToGenericNames(names: Collection<String>? = null): List<String> {
    val propset = mutableSetOf(*GENERIC_PROPERTIES.keys.toTypedArray())
    if (names != null)
        propset.addAll(names)
    return propset.sorted()
}

private fun nullFunction(vararg params: Any?): Any? {
    return null
}

private fun arrayTypes(): List<KClass<*>> {
    return listOf(
        Array::class,
        BooleanArray::class,
        ShortArray::class,
        IntArray::class,
        LongArray::class,
        FloatArray::class,
        DoubleArray::class
    )
}

private fun indexProperty(name: String): Property? {
    val index = name.toIntOrNull() ?: return null
    return GenericProperty(name, ANY_TYPE, {indexPropertyValue(index, it)}, { instance, value -> indexPropertySetValue(index, instance, value)})
}

private fun indexPropertyValue(index: Int, instance: Any?): Any? {
    if (instance is List<*>) {
        return if (index < -instance.size || index >= instance.size)
            null
        else if (index >= 0)
            instance[index]
        else
            instance[instance.size+index]
    }
    else if (instance is Collection<*>) {
        return if (index < -instance.size || index >= instance.size)
            null
        else if (index >= 0)
            instance.toTypedArray()[index]
        else
            instance.toTypedArray()[instance.size+index]
    }
    else if (instance != null && instance::class.java.isArray) {
        val size = java.lang.reflect.Array.getLength(instance)
        return if (index < -size || index >= size)
            null
        else if (index >= 0)
            java.lang.reflect.Array.get(instance, index)
        else
            java.lang.reflect.Array.get(instance, size+index)
    }
    return null
}

private fun indexPropertySetValue(index: Int, instance: Any?, value: Any?): Boolean {
    try {
        if (instance is MutableCollection<*> && index >= instance.size)
            return (instance as MutableCollection<Any?>).add(value)
        if (instance is MutableList<*>) {
            val list = instance as MutableList<Any?>
            if (index >= list.size)
                list.add(value)
            else if (index >= 0)
                list[index] = value
            else if (index >= -list.size)
                list[list.size + index] = value
            else
                return false
            return true
        }
        if (instance != null && instance::class.java.isArray) {
            val size = java.lang.reflect.Array.getLength(instance)
            if (index >= size)
                false
            else if (index >= 0)
                java.lang.reflect.Array.set(instance, index, value)
            else if (index >= -size)
                java.lang.reflect.Array.set(instance, size + index, value)
            else
                return false
            return true
        }
    } catch (e: Exception) {}
    return false
}

private fun mapProperty(name: String, instance: Map<*,*>): Property? {
    return if (instance.contains(name)) GenericProperty(name, ANY_TYPE, {mapPropertyValue(name, it)}, { instance, value -> mapPropertySetValue(name, instance, value)}) else null
}

private fun mapPropertyValue(name: String, instance: Any?): Any? {
    return toMap(instance)[name]
}

private fun mapPropertySetValue(name: String, instance: Any?, value: Any?): Boolean {
    val map = toMap(instance)
    if (map is MutableMap<*,*>) {
        (map as MutableMap<Any?,Any?>)[name] = value
        return true
    }
    return false
}

private fun sizePropertyValue(owner: Any?): Number {
    if (owner == null)
        return 0
    if (owner is Map<*,*>)
        return owner.size
    if (owner is Collection<*>)
        return owner.size
    if (owner::class.java.isArray)
        return java.lang.reflect.Array.getLength(owner)
    if (owner is Namespace)
        return owner.names.size
    if (owner is CharSequence)
        return owner.length
    if (owner is Number)
        return owner
    if (owner is Boolean)
        return if (owner) 1 else 0
    return 1
}

private fun keysPropertyValue(owner: Any?): List<String> {
    // TODO: merge with Type.properties() function
    return (if (owner is Map<*,*>)
        owner.keys.map { toString(it) }
    else if (owner is Namespace)
        owner.names
    else if (owner == null || owner is Collection<*> || owner::class.java.isArray)
        emptyList()
    else
        owner::class.memberProperties.map { it.name }).sorted()
}

private fun mapProperties(map: Map<*,*>): Map<String,Property> {
    val properties = mutableMapOf<String,Property>()
    for (key in map.keys) {
        val name = key.toString()
        val value = map[key]
        val type = if (value == null) ANY_TYPE else getTypeByClass(value::class)
        properties[name] = GenericProperty(name, type)
    }
    return properties
}
