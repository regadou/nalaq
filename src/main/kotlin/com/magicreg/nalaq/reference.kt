package com.magicreg.nalaq

import java.lang.reflect.Field
import java.net.URI
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

class Expression(
    val function: KFunction<Any?>? = null,
    val parameters: List<Any?> = emptyList()
) {
    fun value(): Any? {
        return if (function == null)
            parameters.simplify(true)
        else if (parameters.isEmpty())
            function
        else
            function.call(*getFunctionParameters())
    }

    override fun toString(): String {
        val name = function?.name ?: ""
        val space = if (function == null) "" else " "
        return "("+name+space+parameters.joinToString(" ")+")"
    }

    private fun getFunctionParameters(): Array<Any?> {
        val funcParams = function!!.parameters
        if (funcParams.isEmpty())
            return emptyArray()

        if (funcParams.size == 1) {
            val param = funcParams[0]
            val type = param.type.classifier!! as KClass<*>
            if (param.isVararg)
                return parameters.map{convert(it,type)}.toTypedArray()
            if (type.isInstance(parameters))
                return arrayOf(parameters)
            val value = if (parameters.isEmpty()) null else parameters[0]
            return arrayOf(convert(value, type))
        }

        val targetParams = mutableListOf<Any?>()
        for ((index,param) in funcParams.withIndex()) {
            val value = if (index >= parameters.size) null else parameters[index]
            targetParams.add(convert(value, param.type.classifier!! as KClass<*>))
        }
        val lastParam = funcParams[funcParams.size-1]
        if (lastParam.isVararg) {
            val type = lastParam.type.classifier!! as KClass<*>
            var left = parameters.size - funcParams.size
            while (left > 0) {
                targetParams.add(convert(parameters[parameters.size-left], type))
                left--
            }
        }

        return targetParams.toTypedArray()
    }
}

interface Reference: MutableMap.MutableEntry<String,Any?> {
    override val key: String
    override val value: Any?
    val parent: Any?
    val readOnly: Boolean
}

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
