package com.magicreg.nalaq

import ezvcard.VCard
import java.net.URI
import org.apache.commons.beanutils.BeanMap
import java.util.*

class Person(
    var id: String? = null,
    var name: String? = null,
    var firstName: String? = null,
    var lastName: String? = null,
    var address: String? = null,
    var city: String? = null,
    var email: String? = null,
    val phones: MutableMap<String,String> = mutableMapOf(),
    var phone: String? = null,
    var website: String? = null,
    var image: URI? = null,
    var notes: String? = null,
    var organization: String? = null,
    var role: String? = null,
) {
    val extraProperties = mutableMapOf<String, Any?>()

    override fun toString(): String {
        val txt = name ?: listOf(firstName ?: "", lastName ?: "").joinToString(" ").trim()
        return "Person(${txt})"
    }

    fun importData(data: Map<String, Any?>): Person {
        val bean = BeanMap(this)
        for (entry in data.entries)
            setProperty(this, entry.key, entry.value)
        return this
    }

    fun importCard(card: VCard): Person {
        val map = filterValue(card.toMap())
        val contact = if (map == null)
            mapOf<String,Any?>()
        else if (map is Map<*,*>)
            map as Map<String,Any?>
        else
            mapOf<String,Any?>(map.javaClass.simpleName.lowercase() to map)
        this.id = generateId()
        copyProperties(contact, this)
        this.image = createImageUrl(card)
        this.city = extractCity(this.address)
        this.phone = extractPrefPhone(this)
        return this
    }
}

private val VCF_DISCARD_KEYS = listOf("version", "supportedVersions", "parameters", "properties")
private val fieldsMapping = mapOf<String,Any?>(
    "phones" to listOf("telephoneNumbers", "text"),
    "name" to listOf("formattedName", "value"),
    "firstName" to listOf("structuredName", "given"),
    "lastName" to listOf("structuredName", "family"),
    "address" to listOf("addresses", "streetAddress"),
    "email" to listOf("emails", "value"),
    "notes" to listOf("notes", "value"),
    "organization" to listOf("organization", "values"),
    "website" to listOf("urls"),
    "role" to listOf("titles")
)


private fun generateId(): String {
    return UUID.randomUUID().toString()
}

private fun filterValue(value: Any?): Any? {
    return if (value is Iterable<*>)
        filterIterable(value.iterator())
    else if (value is Iterator<*>)
        filterIterable(value)
    else if (value is Array<*>)
        filterIterable(ArrayIterator(value))
    else if (value is Map<*,*>)
        filterMap(value as Map<String,Any?>)
    else if (value.isPrimitive())
        value
    else
        BeanMap(value)
}

private fun filterMap(src: Map<String,Any?>): Any? {
    val dst = mutableMapOf<String,Any?>()
    for (entry in src.entries) {
        if (VCF_DISCARD_KEYS.contains(entry.key))
            continue
        val value = filterValue(entry.value)
        if (value != null)
            dst[entry.key] = value
    }
    return if (dst.isEmpty()) null else if (dst.size == 1) dst.values.toList()[0] else dst
}

private fun filterIterable(src: Iterator<Any?>): Any? {
    val dst = mutableListOf<Any?>()
    while (src.hasNext()) {
        val value = filterValue(src.next())
        if (value != null)
            dst.add(value)
    }
    return dst.simplify()
}

private fun copyProperties(src: Map<String,Any?>, person: Person) {
    for ((field, path) in fieldsMapping) {
        if (path is String)
            setProperty(person, field, src[path])
        else if (path.isIterable()) {
            val list = path.toCollection()
            var value: Any = src[list[0]] ?: continue
            val iterator = if (value.isIterable()) value.toIterator()!! else listOf(value).iterator()
            val map = mutableMapOf<String,Any?>()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item is Map<*,*>)
                    setMapProperty(map, item, list)
                else if (item is Collection<*>)
                    map["item#"+(map.size+1)] = item.joinToString(" ")
                else if (item is Array<*>)
                    map["item#"+(map.size+1)] = item.joinToString(" ")
                else if (item.isPrimitive())
                    map["item#"+(map.size+1)] = item
                else
                    setMapProperty(map, BeanMap(item), list)
            }
            if (field == "address")
                filterAddress(person, map)
            else if (map.isNotEmpty())
                setProperty(person, field, if (map.size == 1) map.values.iterator().next() else map)
        }
    }
}

private fun setMapProperty(parent: MutableMap<String, Any?>, map: Map<*,*>, path: List<Any?>) {
    val key = if (path.size > 1) path[1] else map.keys.toList()[0]
    parent[map["types"]?.toString()?.replace("x-","") ?: "item#"+(parent.size+1)] = map[key]
}

private fun setProperty(person: Person, property: String, value: Any?) {
    if (value == null)
        return
    when (property) {
        "phones" -> person.phones.putAll(toMap(value) as Map<String, String>)
        "name" -> person.name = toString(value)
        "firstName" -> person.firstName = toString(value)
        "lastName" -> person.lastName = toString(value)
        "address" -> person.address = toString(value)
        "email" -> person.email = toString(value)
        "notes" -> person.notes = toString(value)
        "organization" -> person.organization = toString(value)
        "website" -> person.website = toString(value)
        "role" -> person.role = toString(value)
        else -> person.extraProperties[property] = value
    }
}

private fun createImageUrl(card: VCard): URI? {
    if (card.photos == null || card.photos.isEmpty())
        return null
    val photo = card.photos[0]
    val type = photo.contentType.mediaType
    return URI("data:$type;base64,${Base64.getEncoder().encodeToString(photo.data)}")
}

private fun filterAddress(person: Person, address: Map<String,Any?>) {
    if (address.isEmpty())
        return
    val keys = address.keys
    val key = if (keys.contains("home")) "home" else keys.toList()[0]
    person.address = address[key]?.toString()
    val notes = person.notes
    val lines = mutableListOf<String>()
    if (notes != null)
        lines.add(notes)
    for (k in keys) {
        if (k != key)
            lines.add("$k: ${address[k]}")
    }
    if (lines.isNotEmpty())
        person.notes = lines.joinToString("\n")
}

private fun extractCity(address: String?): String? {
    if (address == null || address.trim().isEmpty())
        return null
    return findCity(address.split(","))
}

private fun findCity(parts: List<String>): String? {
    return if (parts.size < 2) null else parts[1].trim()
}

private fun extractPrefPhone(person: Person): String? {
    if (person.phones.isEmpty())
        return null
    for (key in person.phones.keys) {
        if (key.contains("pref"))
            return person.phones[key].toString()
    }
    return person.phones.values.toList()[0]
}
