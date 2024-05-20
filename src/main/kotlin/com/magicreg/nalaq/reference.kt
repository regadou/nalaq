package com.magicreg.nalaq

import java.lang.reflect.Field
import java.net.URI
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

class PropertyReference(
    override val key: String,
    override val parent: Any?,
    override val readOnly: Boolean = false
): Reference {
    override val value: Any? get() {
        val resolved = parent.resolve()
        return if (key.isBlank())
            resolved
        else
            resolved.type().property(key, resolved).getValue(resolved)
    }

    override fun setValue(value: Any?): Any? {
        val old = this.value
        if (key.isNotBlank() && !readOnly) {
            val resolved = parent.resolve()
            resolved.type().property(key, resolved).setValue(resolved, value)
        }
        return old
    }

    override fun toString(): String {
        return "$key@$parent"
    }
}

class PropertiesReference(
    val keys: Collection<String>,
    override val parent: Any?,
    override val readOnly: Boolean = false
): Reference {
    override val key = keys.joinToString(",")

    override val value: Any? get() {
        val resolved = parent.resolve()
        val type = resolved.type()
        return keys.map {type.property(it, resolved).getValue(resolved).resolve()}
    }

    override fun setValue(value: Any?): Any? {
        val old = this.value
        if (keys.isNotEmpty() && !readOnly && value is Collection<*> && value.size == keys.size) {
            val resolved = parent.resolve()
            val type = resolved.type()
            val values = value.toList()
            for ((i,k) in keys.toList().withIndex())
                type.property(k, resolved).setValue(resolved, values[i])
        }
        return old
    }

    override fun toString(): String {
        return "$key@$parent"
    }
}

class NameReference(
    override val key: String,
    override val readOnly: Boolean = false,
): Reference {
    override val parent: Any? = null

    override val value: Any? get() {
        val cx = getContext()
        return if (cx.hasName(key)) cx.value(key) else key
    }

    override fun setValue(newValue: Any?): Any? {
        val oldValue = value
        if (!readOnly)
            getContext().setValue(key, newValue)
        return oldValue
    }

    override fun toString(): String {
        return key
    }
}

class KeyValueReference(
    override val key: String,
    private var originalValue: Any? = null,
    override val readOnly: Boolean = false,
    override val parent: Any? = null
): Reference {
    override val value: Any? get() { return originalValue }

    override fun setValue(newValue: Any?): Any? {
        val oldValue = originalValue
        if (!readOnly)
            originalValue = newValue
        return oldValue
    }

    override fun toString(): String {
        return if (parent == null) "$key=$originalValue" else "$key@$parent"
    }
}

class ValueReference(
    private var originalValue: Any? = null,
    override val readOnly: Boolean = false,
): Reference {
    override val key: String = ""
    override val parent: Any? = null
    override val value: Any? get() { return originalValue }

    override fun setValue(newValue: Any?): Any? {
        val oldValue = originalValue
        if (!readOnly)
            originalValue = newValue
        return oldValue
    }

    override fun toString(): String {
        return toString(originalValue)
    }
}

class UriReference(val uri: URI): Reference {
    override val key = uri.toString()
    override val parent: Any? = null
    override val readOnly = uri.readOnly()
    override val value: Any? get() { return cache ?: uri.get() }

    private var cache: Any? = null

    override fun setValue(newValue: Any?): Any? {
        val old = value
        if (!readOnly) {
            uri.put(newValue)
            cache = null
        }
        return old
    }

    override fun toString(): String {
        return key
    }
}

class KotlinPropertyReference(val property: KProperty<Any?>, override val parent: Any? = null): Reference {
    override val key = property.name
    override val value: Any? get() {
        return if (parent == null)
            property.getter.call()
        else if (parent == Context::class)
            property.getter.call(getContext())
        else
            property.getter.call(parent)
    }
    override val readOnly = false

    override fun setValue(newValue: Any?): Any? {
        val old = value
        property.call(newValue)
        return old
    }

    override fun toString(): String {
        return if (parent == null) {
            val java = property.javaField ?: property.javaGetter
            val prefix = if (java == null) "" else "${java.declaringClass.name}."
            "$prefix$key"
        }
        else
            "$key@$parent"
    }
}

class JavaFieldReference(val field: Field): Reference {
    override val key = field.name ?: field.toString()
    override val value: Any? get() { return this.field.get(parent) }
    override val parent = null
    override val readOnly = false

    override fun setValue(newValue: Any?): Any? {
        val old = value
        field.set(parent, newValue)
        return old
    }

    override fun toString(): String {
        return "${field.declaringClass.name}.$key"
    }
}
