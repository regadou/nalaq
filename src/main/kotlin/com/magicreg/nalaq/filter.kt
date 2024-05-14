package com.magicreg.nalaq

import java.util.*
import org.jsoup.nodes.Element
import kotlin.reflect.KFunction

fun compare(v1: Any?, v2: Any?): Int {
    if (v1 == null || v2 == null) {
        if (v1 != null)
            return 1
        if (v2 != null)
            return -1
        return 0
    }
    if (v1 is Collection<*> || v1::class.java.isArray || v2 is Collection<*> || v2::class.java.isArray) {
        val c1 = v1.toCollection()
        val c2 = v2.toCollection()
        if (c1.size != c2.size)
            return c1.size - c2.size
        for ((index,item) in c1.withIndex()) {
            val dif = compare(item, c2[index])
            if (dif != 0)
                return dif
        }
        return 0
    }
    if (v1.isMappable() || v2.isMappable() ) {
        val m1 = v1.toMap() ?: toMap(v1)
        val m2 = v2.toMap() ?: toMap(v2)
        if (m1.size != m2.size)
            return m1.size - m2.size
        val k1 = m1.keys.map { it.toText() }.sorted()
        val k2 = m2.keys.map { it.toText() }.sorted()
        var dif = k1.joinToString(",").normalize().compareTo(k2.joinToString(",").normalize())
        if (dif != 0)
            return dif
        for (index in 0 until k1.size) {
            dif = compare(m1[k1[index]], m2[k2[index]])
            if (dif != 0)
                return dif
        }
        return 0
    }
    if (v1 is Number || v1 is Boolean || v2 is Number || v2 is Boolean)
        return toDouble(v1).compareTo(toDouble(v2))
    return toString(v1).normalize().compareTo(toString(v2).normalize())
}

enum class LogicOperator {
    OR, AND
}

enum class CompareOperator(val symbol: String, val function: KFunction<Any?>) {
    LESS("<", ::less_func), NOT_LESS(">=", ::not_less_func),
    EQUAL("=", ::equal_func), NOT_EQUAL("!=", ::not_equal_func),
    MORE(">", ::more_func), NOT_MORE("<=", ::not_more_func),
    IN("@", ::in_func), NOT_IN("!@", ::not_in_func),
    BETWEEN("><", ::between_func), NOT_BETWEEN("!><", ::not_between_func),
    MATCH("~", ::match_func), NOT_MATCH("!~", ::not_match_func)
}

interface Filterable {
    fun filter(filter: Filter): List<Any?>
}

class Filter(key: String? = null, compare: Any? = null, value: Any? = null): Iterable<Map<String,Any?>> {
    private val conditions: MutableList<MutableMap<String,Any?>> = mutableListOf()
    init {
        if (key != null && compare != null) {
            addCondition(LogicOperator.AND, key, compare.toCompareOperator(true)!!, value)
        }
    }

    override fun toString(): String {
        return "Filter(${toString(conditions)})"
    }

    override fun iterator(): Iterator<Map<String, Any?>> {
        return conditions.iterator()
    }

    fun filter(container: Any?): List<Any?> {
        if (container is Filterable)
            return container.filter(this)
        if (container is Map<*,*>)
            return filterItems(listOf(container).iterator(), this)
        if (container.isText())
            return filterItems(container.toText().split("\n").iterator(), this)
        if (container is Element) {
            if (conditions.size == 1 && conditions[0].size == 1 && conditions[0].keys.iterator().next() == "css")
                return container.select(toString(conditions[0].values.iterator().next()))
            return filterItems(container.children().iterator(), this)
        }
        val iterator = container.toIterator()
        if (iterator != null)
            return filterItems(iterator, this)
        if (container == null)
            return emptyList()
        if (container is Expression)
            return filter(container.resolve())
        if (container.isReference())
            return filter(container.toReference().resolve())
        return filterItems(listOf(container).iterator(), this)
    }

    fun and(key: String, compare: String, value: Any?): Filter {
        return addCondition(LogicOperator.AND, key, compare.toCompareOperator(true)!!, value)
    }

    fun and(key: String, compare: CompareOperator, value: Any?): Filter {
        return addCondition(LogicOperator.AND, key, compare, value)
    }

    fun or(key: String, compare: String, value: Any?): Filter {
        return addCondition(LogicOperator.OR, key, compare.toCompareOperator(true)!!, value)
    }

    fun or(key: String, compare: CompareOperator, value: Any?): Filter {
        return addCondition(LogicOperator.OR, key, compare, value)
    }

    fun mapCondition(condition: Map<String,Any?>): Filter {
        val newMap = mutableMapOf<String,Any?>()
        newMap.putAll(condition)
        conditions.add(newMap)
        return this
    }

    fun expressionCondition(exp: Expression): Filter {
        addExpression(this, exp)
        return this
    }

    fun textCondition(txt: String): Filter {
        val tokens = mutableListOf<String>()
        val parser = StringTokenizer(txt)
        while (parser.hasMoreTokens())
            tokens.add(parser.nextToken())
        return listCondition(tokens)
    }

    fun listCondition(tokens: List<Any?>): Filter {
        if (tokens.isEmpty())
            return this
        val first = tokens[0]
        // TODO: if (tokens.size == 1 && first is String) it is either a css query or a urlencoded query (?x=y&a=b...) or a uri fragment (#x)
        // css can be distinguished from fragment if it contains space or does not start with #
        // but #x could still be a css query or fragment so we leave it to the execution to decide
        // urlencoded query can be break down to a map with logic and compare operators (already done in server.kt that passes a map to a filter)
        var op = if (first is String)
            first.toLogicOperator()
        else if (first is LogicOperator)
            first
        else
            null
        var i = if (op != null) 1 else 0
        while (i < tokens.size) {
            if (i+3 > tokens.size)
                throw RuntimeException("Invalid expression part: ${tokens.subList(i, tokens.size)}")
            addCondition(op ?: LogicOperator.AND, tokens[i].toString(), tokens[i+1].toCompareOperator(true)!!, parse(tokens[i+2]))
            i += 3
            if (i+1 < tokens.size) {
                val next = tokens[i]
                op = if (next is LogicOperator)
                    next
                else if (next is String)
                    next.toLogicOperator(true)
                else
                    null
                i++
            }
        }
        return this
    }

    fun addCondition(logic: LogicOperator, key: String, compare: CompareOperator, value: Any?): Filter {
        if (conditions.isEmpty())
            conditions.add(mutableMapOf())
        else {
            when (logic) {
                LogicOperator.OR -> conditions.add(mutableMapOf())
                LogicOperator.AND -> {}
            }
        }
        conditions[conditions.size - 1][key] = if (compare == CompareOperator.EQUAL) value else mapOf(compare.symbol to value)
        return this
    }

    fun toExpression(): Expression {
        return when (conditions.size) {
            0 -> Expression(null, emptyList())
            1 -> mapExpression(conditions[0])
            else -> {
                val params = mutableListOf<Any?>()
                for (condition in conditions)
                    params.add(mapExpression(condition))
                Expression(::or_func, params)
            }
        }
    }
}

private fun parse(token: Any?): Any? {
    if (token !is String)
        return token
    return token.toLongOrNull()
        ?: token.toDoubleOrNull()
        ?: token.toTemporal()
        ?: when (token) {
            "true" -> true
            "false" -> false
            "null" -> null
            "" -> ""
            else -> {
                if (token[0] == '(' && token[token.length-1] == ')')
                    token.substring(1, token.length-1).split(",")
                else
                    token
            }
        }
}

private fun select(list: List<Any?>, index: Int): Any? {
    return if (index < list.size) list[index] else null
}

private fun addExpression(filter: Filter, exp: Expression) {
    when (exp.function) {
        ::or_func -> {
            for (param in exp.parameters) {
                filter.mapCondition(mutableMapOf())
                addExpression(filter, toExpression(param))
            }
        }
        ::and_func -> {
            for (param in exp.parameters)
                addExpression(filter, toExpression(param))
        }
        else -> {
            filter.addCondition(
                LogicOperator.AND,
                expressionKey(select(exp.parameters, 0)),
                exp.function.toCompareOperator(true)!!,
                select(exp.parameters, 1))
        }
    }
}

private fun filterItems(items: Iterator<Any?>, filter: Filter): List<Any?> {
    val matches = mutableListOf<Any?>()
    while (items.hasNext()) {
        val item = items.next()
        if (satisfyConditions(item, filter.iterator()))
            matches.add(item)
    }
    return matches
}

private fun satisfyConditions(item: Any?, conditions: Iterator<Any?>): Boolean {
    val map = toMap(item)
    val type = item.type()
    while (conditions.hasNext()) {
        if (satisfyCondition(map, type, toMap(conditions.next()) as Map<String, Any?>))
            return true
    }
    return false
}

private fun satisfyCondition(item: Any?, type: Type, condition: Map<String, Any?>): Boolean {
    for (entry in condition.entries) {
        val exp = entryExpression(entry)
        val key = exp.parameters[0].resolve()?.toText() ?: ""
        val value = exp.parameters[1]
        val params = listOf(type.property(key, item).getValue(item), value)
        if (!toBoolean(exp.function!!.call(params)))
            return false
    }
    return true
}

private fun  mapExpression(map: Map<String,Any?>): Expression {
    return when (map.size) {
        0 -> Expression(null, listOf(true))
        1 -> entryExpression(map.entries.iterator().next())
        else -> {
            val params = mutableListOf<Any?>()
            for (entry in map.entries)
                params.add(entryExpression(entry))
            Expression(::and_func, params)
        }
    }
}

private fun entryExpression(entry: Map.Entry<String,Any?>): Expression {
    var operator = CompareOperator.EQUAL
    var value = entry.value
    if (value is Map<*,*> && value.size == 1) {
        val valueEntry = value.entries.iterator().next()
        val op = valueEntry.key.toCompareOperator(true)
        if (op != null) {
            operator = op
            value = valueEntry.value
        }
    }
    return Expression(operator.function, listOf(entry.key, value))
}

private fun expressionKey(value: Any?): String {
    if (value.isReference()) {
        val ref = value.toReference()!!
        return ref.value?.resolve()?.toText() ?: ref.key
    }
    if (value.isText())
        return value.toText()
    if (value is Expression)
        return expressionKey(value.value())
    if (value.isIterable()) {
        val result = value.toCollection().simplify()
        if (value !is Collection<*>)
            return expressionKey(result)
    }
    return toString(value)
}