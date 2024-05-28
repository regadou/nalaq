package com.magicreg.nalaq

import java.net.URI
import kotlin.reflect.KClass
import kotlin.reflect.KFunction

fun compileTokens(vararg tokens: Any?): Expression {
    if (tokens.isEmpty())
        return Expression(null, emptyList())
    for (token in tokens) {
        if (token !is List<*>)
            return compileTokens(LineStatus(tokens.toList()), true)
    }
    return compileExpressions(ParserStatus(tokens.toList() as List<List<Any?>>), true)
}

class GenericParser(): Parser {
    override fun parse(txt: String): Expression {
        val tokens = parseText(txt)
        if (tokens.isEmpty())
            return Expression(null, emptyList())
        val first = tokens[0]
        if (tokens.size == 1 && first is ExpressionList)
            return compileExpressions(ParserStatus(first.lines), true)
        return compileTokens(LineStatus(tokens), true)
    }

    override fun toString(): String {
        return "GenericParser"
    }
}

private class ParserStatus(
    val lines: List<List<Any?>>,
    var lineno: Int = 0
)

private class LineStatus(
    val tokens: List<Any?>,
    var index: Int = 0
)

private class ExpressionList(val lines: List<List<Any?>>) {}

private fun parseText(txt: String): List<Any?> {
    val lines = mutableListOf<List<Any?>>()
    val words = mutableListOf<Any?>()
    var word: String? = null
    var quote: Char? = null
    for (c in txt) {
        if (c == '"' || c == '\'' || c == '`') {
            if (c == quote) {
                words.add(word)
                word = null
                quote = null
            }
            else if (quote == null) {
                quote = c
                word = ""
            }
            else
                word += c
        }
        else if (quote != null)
            word += c
        else if (c <= ' ' || c in BLANK_NON_ASCII) {
            if (word != null) {
                words.add(getTokenValue(word))
                word = null
            }
            if (c == '\n' && words.isNotEmpty()) {
                lines.add(words.toList())
                words.clear()
            }
        }
        else if (word == null)
            word = c.toString()
        else
            word += c
    }
    if (word != null)
        words.add(getTokenValue(word))
    if (lines.isEmpty())
        return words
    if (lines.size == 1 && words.isEmpty())
        return lines[0]
    if (words.isNotEmpty())
        lines.add(words)
    return listOf(ExpressionList(lines))
}

private fun getTokenValue(txt: String): Any? {
    val cx = getContext()
    val value = if (cx.isConstant(txt))
        cx.value(txt)
    else {
        txt.toLongOrNull()
        ?: txt.toDoubleOrNull()
        ?: txt.toTemporal()
        ?: txt.toUri()
        ?: txt.toJvmObject()
        ?: NameReference(txt)
    }
    return if (value is URI && value.readOnly()) value.resolve() else value
}

private fun compileExpressions(status: ParserStatus, topLevel: Boolean): Expression {
    val exps = mutableListOf<Expression>()
    while (status.lineno < status.lines.size) {
        val line = status.lines[status.lineno]
        if (line.isEmpty())
            continue
        if (line[0] == ::done_func) {
            if (topLevel)
                throw RuntimeException("Invalid done token position in $line")
            break
        }
        else if (line[line.size-1] == ::do_func) {
            status.lineno++
            val line2 = line.subList(0, line.size-1).toMutableList()
            line2.add(compileExpressions(status, false))
            exps.add(compileTokens(LineStatus(line2), true))
        }
        else
            exps.add(compileTokens(LineStatus(line), true))
        status.lineno++
    }
    return Expression(::do_func, exps)
}

private fun compileTokens(status: LineStatus, topLevel: Boolean): Expression {
    var fn: KFunction<Any?>? = null
    var first: MutableList<Any?> = mutableListOf()
    var last: MutableList<Any?> = mutableListOf()
    var fnSeqCount = 0
    while (status.index < status.tokens.size) {
        val token = status.tokens[status.index]
        if (token.type() != FUNCTION_TYPE)
            addToken(token, fn, first, last, fnSeqCount)
        else {
            val (newfn, skip) = findFunction(status.tokens, status.index)
            if (skip)
                status.index++
            if (newfn == ::done_func) {
                if (topLevel)
                    throw RuntimeException("Invalid done token position in ${status.tokens}")
                break
            }
            else if (newfn == ::do_func) {
                status.index++
                addToken(compileTokens(status, false), fn, first, last, fnSeqCount)
            }
            else if (fn == null)
                fn = newfn
            else if (fn == newfn)
                fnSeqCount++
            else if (newfn.precedence() > fn.precedence()) {
                last.add(newfn)
                last.addAll(status.tokens.subList(status.index+1, status.tokens.size))
                val substatus = LineStatus(last)
                last = mutableListOf(compileTokens(substatus, topLevel))
                status.index += substatus.index
                break
            }
            else {
                val exp = Expression(fn, merge(first, last))
                fn = newfn
                first = mutableListOf(exp)
                last = mutableListOf()
                fnSeqCount = 0
            }
        }
        status.index++
    }
    return Expression(fn, merge(first, last))
}

private fun findFunction(tokens: List<Any?>, index: Int): Pair<KFunction<*>, Boolean> {
    val fn = tokens[index].toFunction() ?: toFunction(tokens[index])
    if (fn != ::not_func || index >= tokens.size)
        return Pair(fn, false)
    val nextfn = tokens[index+1].toFunction() ?: return Pair(fn, false)
    val name = nextfn.name.split("_")[0]
    val finalfn = builtinOperators().filter{it.name == "not_$name"}.firstOrNull() ?: fn
    return Pair(finalfn, finalfn != fn)
}

private fun addToken(token: Any?, fn: KFunction<Any?>?, first: MutableList<Any?>, last: MutableList<Any?>, fnSeqCount: Int) {
    if (fn == null)
        first.add(token)
    else if (fnSeqCount == 0)
        last.add(token)
    else if (last.size > fnSeqCount) {
        val item = last[fnSeqCount]
        if (item is MutableList<*>)
            (item as MutableList<Any?>).add(token)
        else
            last[fnSeqCount] = mutableListOf(item, last)
    }
    else
        last.add(token)
}

private fun merge(first: MutableList<Any?>, last: MutableList<Any?>): List<Any?> {
    val params = mutableListOf<Any?>()

    var exp = findEntity(first)
    if (exp != null)
        params.add(exp)
    else if (first.size > 1)
        params.add(first)
    else if (first.isNotEmpty())
        params.add(first[0])

    exp = findEntity(last)
    if (exp != null)
        params.add(exp)
    else if (params.isEmpty())
        params.addAll(last)
    else if (last.size > 1)
        params.add(last)
    else if (last.isNotEmpty())
        params.add(last[0])

    return params
}

private fun findEntity(items: MutableList<Any?>): Expression? {
    // TODO: it is possible that we encounter a function in the list: what to do then ?
    if (items.size < 2)
        return null
    var type: Type? = null
    for ((index, item) in items.withIndex()) {
        if (item is Type) {
            type = item
            items.removeAt(index)
            break
        }
        if (item is KClass<*>)
            return createJvmExpression(item.java, items, index)
        if (item is Class<*>)
            return createJvmExpression(item, items, index)
    }
    if (type == null)
        return null
    return Expression(type::newInstance, items)
}

private fun createJvmExpression(klass: Class<*>, items: MutableList<Any?>, index: Int): Expression? {
    return null // TODO: call java.beans.Expression
}

val FUNCTION_TYPE = getTypeByClass(KFunction::class)
val BLANK_NON_ASCII = '\u007F'..'\u00AD'
