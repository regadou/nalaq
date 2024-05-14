package com.magicreg.nalaq

import java.net.URI

fun getNamespace(prefix: String): Namespace? {
    return NS_PREFIX_MAP[prefix] ?: NS_URI_MAP[prefix]
}

fun addNamespace(ns: Namespace): Boolean {
    if (NS_PREFIX_MAP.containsKey(ns.prefix) || NS_URI_MAP.containsKey(ns.uri))
        return false
    NS_PREFIX_MAP[ns.prefix] = ns
    NS_URI_MAP[ns.toString()] = ns
    return true
}

fun loadNamespace(prefix: String, uri: URI, readOnly: Boolean): Namespace {
    val ns = toNamespace(listOf(prefix, uri, readOnly))
    if (!addNamespace(ns))
        throw RuntimeException("Prefix $prefix or uri $uri namespace is already defined")
    return ns
}

interface Namespace: Filterable {
    val prefix: String
    val uri: String
    val readOnly: Boolean
    val names: List<String>
    fun hasName(name: String): Boolean
    fun value(name: String): Any?
    fun setValue(name: String, value: Any?): Boolean

    override fun filter(filter: Filter): List<Any?> {
        return filter.filter(names.map { value(it) })
    }
}

class NaLaQNamespace (
    override val prefix: String = "_",
    override val uri: String = "http://localhost/",
    override val readOnly: Boolean = false
): Namespace {
    private val mapping = mutableMapOf<String,Any?>()

    override fun toString(): String {
        return "Namespace($prefix->$uri)"
    }

    override val names get() = mapping.keys.sorted()

    override fun hasName(name: String): Boolean {
        return mapping.containsKey(name)
    }

    override fun value(name: String): Any? {
        return if (name.isBlank()) this else mapping[name]
    }

    override fun setValue(name: String, value: Any?): Boolean {
        if (!readOnly && name.isNotBlank()) {
            mapping[name] = value
            return true
        }
        return false
    }

    fun populate(map: Map<String,Any?>): Namespace {
        if (!readOnly || mapping.isEmpty())
            mapping.putAll(map)
        return this
    }

    // TODO: functions value(path: List<String>) and setValue(path: List<String>)
}

// TODO: ArchiveNamespace class that can interface zip, tgz and file folder

private val NS_PREFIX_MAP = mutableMapOf<String,Namespace>()
private val NS_URI_MAP = mutableMapOf<String,Namespace>()
