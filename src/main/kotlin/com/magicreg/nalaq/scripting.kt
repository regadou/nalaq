package com.magicreg.nalaq

import javax.script.Bindings
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

class ScriptEngineParser(id: String, val context: Context, val arguments: List<String>): Parser {
    val engine: ScriptEngine = getScriptEngine(id)
    val bindings = ContextBindings(context)
    init {
        val txtArgs = '"'+arguments.joinToString("\",\"")+'"'
        val initScript = initCodeMap[engine.factory.engineName]?.replace("{arguments}", txtArgs)
        engine.eval(initScript, bindings)
    }

    override fun parse(text: String): Expression {
        return Expression(::scriptExecutor, listOf(this, text))
    }

    override fun toString(): String {
        return engine.factory.engineName
    }
}

class ContextBindings(private val cx: Context): Bindings, AbstractMap<String,Any?>() {
    override fun clear() {
        for (key in cx.names)
            cx.setValue(key, null)
    }

    override fun put(key: String, value: Any?): Any? {
        val old = cx.value(key)
        cx.setValue(key, value)
        return old
    }

    override fun putAll(from: Map<out String, Any?>) {
        TODO("Not yet implemented")
    }

    override fun remove(key: String): Any? {
        val old = cx.value(key)
        cx.setValue(key, null)
        return old
    }

    override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>> get() {
        return cx.names.map { KeyValueReference(it, cx.value(it)) }.toMutableSet()
    }

    override val keys: MutableSet<String> get() {
        return cx.names.toMutableSet()
    }

    override val values: MutableCollection<Any?> get() {
        return cx.names.map { cx.value(it) }.toMutableList()
    }

}

fun scriptExecutor(parser: ScriptEngineParser, text: String): Any? {
    return parser.engine.eval(text, parser.bindings)
}

private val MANAGER = ScriptEngineManager()
private val initCodeMap = mapOf(
    "kotlin" to "import com.magicreg.nalaq.*; import java.io.*; import java.net.*; val args=listOf({arguments})"
)

private fun getScriptEngine(name: String): ScriptEngine {
    return MANAGER.getEngineByName(name) ?: MANAGER.getEngineByExtension(name) ?: MANAGER.getEngineByMimeType(name)
                                         ?: throw RuntimeException("Unknown script engine: $name")
}
