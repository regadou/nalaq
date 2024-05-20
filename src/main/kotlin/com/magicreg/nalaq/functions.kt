package com.magicreg.nalaq

import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.Writer
import kotlin.reflect.*
import kotlin.reflect.full.createType

fun builtinOperators(): Collection<KFunction<Any?>> {
    return FUNCTION_PRECEDENCE_MAP.keys
}

fun functionPrecedence(function: KFunction<*>): Int {
    return FUNCTION_PRECEDENCE_MAP[function] ?: DEFAULT_PRECEDENCE
}

fun get_func(args: List<Any?>): Any? {
    val values = mutableListOf<Any?>()
    for (arg in args)
        values.add((arg.toUri() ?: arg).resolve())
    return values.simplify()
}

fun is_func(args: List<Any?>): Any? {
    if (args.isEmpty())
        return false
    val first = args[0]
    val value = args.subList(1, args.size).map { it.resolve() }.simplify()
    val item = if (first.isText()) getContext().value(first.toText()).resolve() else first.resolve()
    if (compare(item, value) == 0)
        return true
    if (value is Type)
        return value.isInstance(item)
    // TODO: check if (type of value) of item == value
    return false
}

fun of_func(args: List<Any?>): Any? {
    var value: Any? = if (args.isEmpty()) null else args[args.size-1]
    for (i in args.size-2 downTo  0) {
        val iterator = args[i].toIterator()
        if (iterator == null) {
            val arg = args[i]
            val key = if (arg.isReference()) {
                val ref = arg.toReference()!!
                ref.value?.toText() ?: ref.key
            }
            else if (arg is Expression)
                toString(arg.resolve())
            else if (arg.isText())
                arg.toText()
            else
                toString(arg)
            value = PropertyReference(key, value)
            continue
        }
        val keys = toCollection(args[i]).map { toString(it) }
        value = PropertiesReference(keys, value)
    }
    return value
}

fun as_func(args: List<Any?>): Any? {
    if (args.isEmpty())
        return null
    val value = if (args.size == 1) null else if (args.size == 2) args[1].resolve() else args.subList(2, args.size).resolve()
    val id = args[0]
    val uri = id.toUri()
    return if (uri != null)
        uri.put(value)
    else if (id.isReference()) {
        val ref = id.toReference()!!
        ref.setValue(value)
        ref.value
    }
    else if (id.isText()) {
        val cx = getContext()
        val key = id.toText()
        cx.setValue(key, value)
        cx.value(key)
    }
    else
        value
}

fun to_func(args: List<Any?>): Any? {
    if (args.isEmpty())
        return null
    val params = if (args.size == 1) arrayOf(args[0], null) else args
    var last = args[0]
    for (index in 1 until args.size) {
        var target = args[index]
        if (target == null) {
            val uri = last.toUri()
            if (uri != null)
                uri.delete()
            else {
                last = last.resolve()
                if (last is CharSequence)
                    getContext().setValue(last.toString(), null)
                else if (target is MutableCollection<*>) {
                    (target as MutableCollection<Any?>).remove(last)
                    last = target
                    continue
                }
            }
            last = null
        }
        else {
            last = last.resolve()
            val uri = target.toUri()
            if (uri != null)
                last = uri.post(last)
            else {
                target = target.resolve()
                if (target is CharSequence) {
                    val cx = getContext()
                    val key = toString(target.resolve())
                    cx.setValue(key, last)
                    last = cx.value(key)
                } else if (target is MutableCollection<*>) {
                    (target as MutableCollection<Any?>).add(last)
                    last = target
                } else if (target is OutputStream) {
                    target.write(toByteArray(toString(last)+"\n"))
                    target.flush()
                } else if (target is Writer) {
                    target.write(toString(last)+"\n")
                    target.flush()
                } else if (target is Type)
                    last = target.newInstance(listOf(last))
                else if (target is KClass<*>)
                    last = getTypeByClass(target).newInstance(listOf(last))
                else if (target is Class<*>)
                    last = getTypeByClass(target.kotlin).newInstance(listOf(last))
                else if (target is PropertyReference) {
                    val key = target.toString()
                    target.setValue(last)
                    last = target.value
                }
// TODO:          else if (target is Number) createRange
                else
                    last = target
                // TODO: if (target is Map or entity type) either add a key/value pair of find a collection/stream to add to
            }
        }
    }
    return last
}

fun from_func(args: List<Any?>): Any? {
    if (args.size == 2) {
        val source = args[1].resolve()
        if (source is Collection<*>) {
            val property = resolveProperty(args[0])
            if (property != null)
                return source.map {of_func(listOf(property, it))}.simplify()
        }
        else if (source is InputStream)
            return source.readAllBytes()
        else if (source is Reader)
            return source.readText()
    }
    return to_func(args.reversed())
}

fun with_func(args: List<Any?>): Any? {
    if (args.isEmpty())
        return null
    if (args.size == 1)
        return args[0].resolve().toCollection().simplify()
    var container: Any? = args[0]
    for (i in 1 until args.size)
        container = toFilter(args[i]).filter(container.resolve()).simplify()
    return container
}

fun not_func(args: List<Any?>): Any? {
    return when(args.size) {
        0 -> !toBoolean(null)
        1 -> !toBoolean(args[0].resolve())
        else -> !toBoolean(and_func(args))
    }
}

fun and_func(args: List<Any?>): Any? {
    if (args.isEmpty())
        return false
    for (arg in args) {
        if (!toBoolean(arg.resolve()))
            return false
    }
    return true
}

fun or_func(args: List<Any?>): Any? {
    if (args.isEmpty())
        return true
    for (arg in args) {
        if (toBoolean(arg.resolve()))
            return true
    }
    return false
}

fun equal_func(args: List<Any?>): Any? {
    var minArgs = minimumResolvedArgs(args)
    for (i in 1 until minArgs.size) {
        if (compare(minArgs[i-1], minArgs[i]) != 0)
            return false
    }
    return true
}

fun not_equal_func(args: List<Any?>): Any? {
    var minArgs = minimumResolvedArgs(args)
    for (i in 1 until minArgs.size) {
        if (compare(minArgs[i-1], minArgs[i]) == 0)
            return false
    }
    return true
}

fun less_func(args: List<Any?>): Any? {
    var minArgs = minimumResolvedArgs(args)
    for (i in 1 until minArgs.size) {
        if (compare(minArgs[i-1], minArgs[i]) >= 0)
            return false
    }
    return true
}

fun not_more_func(args: List<Any?>): Any? {
    var minArgs = minimumResolvedArgs(args)
    for (i in 1 until minArgs.size) {
        if (compare(minArgs[i-1], minArgs[i]) > 0)
            return false
    }
    return true
}

fun more_func(args: List<Any?>): Any? {
    var minArgs = minimumResolvedArgs(args)
    for (i in 1 until minArgs.size) {
        if (compare(minArgs[i-1], minArgs[i]) <= 0)
            return false
    }
    return true
}

fun not_less_func(args: List<Any?>): Any? {
    var minArgs = minimumResolvedArgs(args)
    for (i in 1 until minArgs.size) {
        if (compare(minArgs[i-1], minArgs[i]) < 0)
            return false
    }
    return true
}

fun in_func(args: List<Any?>): Any? { return null }
fun not_in_func(args: List<Any?>): Any? { return null }
fun between_func(args: List<Any?>): Any? { return null }
fun not_between_func(args: List<Any?>): Any? { return null }
fun match_func(args: List<Any?>): Any? { return null }
fun not_match_func(args: List<Any?>): Any? { return null }
fun by_func(args: List<Any?>): Any? { return null }
fun if_func(args: List<Any?>): Any? { return null }

fun while_func(args: List<Any?>): Any? {
    if (args.size != 2)
        throw RuntimeException("Function while needs 2 arguments but got ${args.size}: $args")
    val condition = args[0]
    val exp = toExpression(args[1])
    var value: Any? = null
    while (toBoolean(condition.resolve()))
        value = exp.value().resolve()
    return value
}

fun each_func(args: List<Any?>): Any? {
    val (iterator, key, expression) = findTokenTypes(args)
    if (expression == null || iterator == null || key == null) {
        val missing = mutableListOf<String>()
        if (expression == null)
            missing.add("expression")
        if (iterator == null)
            missing.add("collection")
        if (key == null)
            missing.add("key")
        throw RuntimeException("Function each has some missing arguments: ${missing.joinToString(" ")}")
    }
    var value: Any? = null
    val cx = getContext()
    while (iterator.hasNext()) {
        cx.setValue(key, iterator.next())
        value = expression.value().resolve()
    }
    return value
}

fun do_func(args: List<Any?>): Any? {
    val cx = getContext()
    val format = getFormat(cx.configuration.outputFormat ?: "text/plain")!!
    var value: Any? = null
    for (arg in args) {
        if (cx.configuration.expressionPrompt != null)
            println(cx.configuration.expressionPrompt+format.encodeText(arg))
        if (arg !is Expression)
            throw RuntimeException("Function do is supposed to be called with a list of expressions")
        value = arg.value().resolve()
        cx.setValue("it", value)
        if (cx.configuration.resultPrompt != null)
            println(cx.configuration.resultPrompt+format.encodeText(value))
    }
    return value
}

fun done_func(args: List<Any?>): Any? {
    throw RuntimeException("Function done is not supposed to be called")
}

class NaLaQFunction(
    override val name: String,
    val parameterNames: List<String>,
    val expressions: List<Expression>
): KFunction<Any?> {
    private val parameterType = Any::class.createType(nullable = true)

    override val annotations: List<Annotation>
        get() = emptyList()
    override val isAbstract: Boolean
        get() = false
    override val isExternal: Boolean
        get() = false
    override val isFinal: Boolean
        get() = false
    override val isInfix: Boolean
        get() = false
    override val isInline: Boolean
        get() = false
    override val isOpen: Boolean
        get() = false
    override val isOperator: Boolean
        get() = false
    override val isSuspend: Boolean
        get() = false
    override val parameters: List<KParameter>
        get() = emptyList() // TODO: build from parameterNames
    override val returnType: KType
        get() = parameterType
    override val typeParameters: List<KTypeParameter>
        get() = emptyList() // TODO: build from parameterNames
    override val visibility: KVisibility?
        get() = null

    override fun call(vararg args: Any?): Any? {
        val params = NaLaQNamespace(readOnly=true).populate(args.indices.associate { Pair(parameterNames[it], args[it]) })
        return executeExpressions(params, expressions)
    }

    override fun callBy(args: Map<KParameter, Any?>): Any? {
        val params = NaLaQNamespace(readOnly=true).populate(args.mapKeys{it.key.name!!})
        return executeExpressions(params, expressions)
    }

    private fun executeExpressions(params: Namespace, expressions: List<Expression>): Any? {
        val cx = getContext().childContext(null, params)
        var value: Any? = null
        for (exp in expressions)
            value = exp.value()
        cx.close(true)
        return value
    }
}

data class NaLaQParameter(
    override val annotations: List<Annotation>,
    override val index: Int,
    override val isOptional: Boolean,
    override val isVararg: Boolean,
    override val kind: KParameter.Kind,
    override val name: String?,
    override val type: KType
): KParameter {
    override fun toString(): String {
        return "Parameter#name"
    }
}

private val DEFAULT_PRECEDENCE = 2
private val FUNCTIONS_PRECEDENCE = listOf<List<KFunction<Any?>>>(
    listOf(::as_func, ::do_func, ::done_func),
    listOf(::if_func, ::while_func, ::each_func),
    listOf(::get_func), // default precedence level for functions
    listOf(::from_func, ::by_func),
    listOf(::with_func),
    listOf(::to_func),
    listOf(::and_func, ::or_func),
    listOf(::is_func, ::more_func, ::less_func, ::equal_func, ::not_more_func, ::not_less_func, ::not_equal_func,
           ::in_func, ::not_in_func, ::between_func, ::not_between_func, ::match_func, ::not_match_func),
    listOf(::of_func),
    listOf(::not_func)
)
private val FUNCTION_PRECEDENCE_MAP = mapPrecedences()
private enum class TokenType { ITERABLE, REFERENCE, EXPRESSION, MAP, TEXT}

private fun mapPrecedences(): Map<KFunction<Any?>, Int> {
    val map = mutableMapOf<KFunction<Any?>, Int>()
    for (index in FUNCTIONS_PRECEDENCE.indices) {
        for (f in FUNCTIONS_PRECEDENCE[index]) {
            if (map.containsKey(f))
                throw RuntimeException("Duplicate function precedence entry: $f")
            map[f] = index
        }
    }
    return map
}

private fun minimumResolvedArgs(args: List<Any?>): List<Any?> {
    return  when (args.size) {
        0 -> listOf(null, null)
        1 -> listOf(args[0].resolve(), null)
        else -> args.map { it.resolve() }
    }

}

private fun resolveProperty(value: Any?): Any? {
    if (value is Reference)
        return value.key
    val resolved = value.resolve()
    if (resolved.isReference())
        return resolved.toReference()!!.key
    else if (resolved.isText())
        return resolved.toText()
    else if (resolved is Collection<*>) {
        val properties = resolved.map { resolveProperty(it) }.filterNotNull()
        if (properties.size == resolved.size)
            return properties
    }
    else if (resolved == null)
        return null
    else if (resolved::class.java.isArray)
        return resolveProperty(ListArrayAdapter(resolved))
    return null
}

private fun findTokenTypes(args: List<Any?>): Triple<Iterator<*>?,String?,Expression?> {
    if (args.size != 3)
        throw RuntimeException("Function each needs 3 arguments but got ${args.size}: $args")
    val types = arrayOf(mutableListOf(), mutableListOf<TokenType>(), mutableListOf<TokenType>())
    for ((index, token) in args.withIndex()) {
        if (token.isIterable())
            types[index].add(TokenType.ITERABLE)
        if (token is Expression)
            types[index].add(TokenType.EXPRESSION)
        if (token.isReference())
            types[index].add(TokenType.REFERENCE)
        if (token.isText())
            types[index].add(TokenType.TEXT)
        if (token.isMappable())
            types[index].add(TokenType.MAP)
    }

    val collectionCount = types.filter{it.contains(TokenType.ITERABLE)}.size
    val keyCount = types.filter{it.contains(TokenType.REFERENCE)||it.contains(TokenType.TEXT)}.size
    var expressionCount = types.filter{it.contains(TokenType.EXPRESSION)}.size
    var mapCount = types.filter{it.contains(TokenType.MAP)}.size

    val collection: Iterator<Any?>? = if (collectionCount > 0)
        pickFirst(args, types, TokenType.ITERABLE).toIterator()
    else if (expressionCount > 1) {
        val iterator = pickFirst(args, types, TokenType.EXPRESSION).resolve()?.toIterator()
        if (iterator != null) {
            expressionCount--
            iterator
        }
        else
            findIterator(args, types, keyCount)
    }
    else
        findIterator(args, types, keyCount)

    val key: String? = if (keyCount > 0) {
        val txt = pickFirst(args, types, TokenType.TEXT)?.toText()
        if (txt == null) {
            val ref = pickFirst(args, types, TokenType.REFERENCE)?.toReference()
            ref?.resolve()?.toText() ?: ref?.key
        }
        else
            txt
    }
    else if (expressionCount > 1) {
        expressionCount--
        val value = pickFirst(args, types, TokenType.EXPRESSION).resolve()
        if (value.isReference()) {
            val ref = value.toReference()
            ref?.resolve()?.toText() ?: ref?.key
        }
        else
            value.toText()
    }
    else if (mapCount > 0) {
        val map = pickFirst(args, types, TokenType.MAP).toMap()
        if (map != null && map.size == 1) {
            mapCount--
            val entry = map.entries.iterator().next()
            entry.value.resolve()?.toText() ?: entry.key.toText()
        }
        else
            null
    }
    else
        null

    val expression: Expression? = if (expressionCount > 0)
        toExpression(pickFirst(args, types, TokenType.EXPRESSION))
    else if (mapCount > 0)
        toExpression(pickFirst(args, types, TokenType.MAP).toMap())
    else
        null

    return Triple(collection, key, expression)
}

private fun pickFirst(args: List<Any?>, types: Array<MutableList<TokenType>>, type: TokenType): Any? {
    val index = types.indexOfFirst{it.contains(type)}
    if (index < 0)
        return null
    types[index].clear()
    return args[index]
}

private fun findIterator(args: List<Any?>, types: Array<MutableList<TokenType>>, keyCount: Int): Iterator<Any?>? {
    if (keyCount > 1) {
        val ref = pickFirst(args, types, TokenType.REFERENCE)
        if (ref.isReference()) {
            val value = ref.toReference().resolve()
            if (value.isIterable())
                return value.toIterator()
        }
    }
    val map = pickFirst(args, types, TokenType.MAP)
    if (map.isMappable())
        return map.toMap()?.entries?.iterator()
    return null
}
