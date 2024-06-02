package com.magicreg.nalaq

import java.time.LocalDateTime
import javax.script.Bindings
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

class ScriptEngineParser(private val id: String): Parser {
    private var inited = false
    private var lastEngine: ScriptEngine = getScriptEngine(id)
    private val memoryLeak: Boolean = lastEngine.factory.engineName == "kotlin"
    private var renewCountdown = INITIAL_COUNTDOWN
    val engine: ScriptEngine get() {
        if (memoryLeak) {
            renewCountdown--
            if (renewCountdown <= 0) {
                println("renewing script engine for $id at ${LocalDateTime.now()}")
                lastEngine = getScriptEngine(id)
                System.gc()
                val rt = Runtime.getRuntime()
                println("memory: ${gb(rt.freeMemory())}/${gb(rt.totalMemory())}/${gb(rt.maxMemory())}")
                renewCountdown = INITIAL_COUNTDOWN
            }
        }
        return lastEngine
    }

    fun checkInit(bindings: Bindings) {
        if (inited)
            return
        inited = true
        val text = initCodeMap[engine.factory.engineName]!!
        println("init: $text")
        engine.eval(text, bindings)
    }

    override fun parse(text: String): Expression {
        return Expression(::scriptExecutor, listOf(this, text))
    }

    override fun toString(): String {
        return "ScriptEngineParser(${engine.factory.engineName})"
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
        for (entry in from.entries)
            put(entry.key, entry.value)
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
    val bindings = ContextBindings(getContext())
    parser.checkInit(bindings)
    println("eval: $text")
    return parser.engine.eval(text, bindings)
}

private const val INITIAL_COUNTDOWN = 1
private val MANAGER = ScriptEngineManager()
private val initCodeMap = mapOf(
    "kotlin" to "import com.magicreg.nalaq.*; import java.io.*; import java.net.*;"
)

private fun getScriptEngine(name: String): ScriptEngine {
    return MANAGER.getEngineByName(name) ?: MANAGER.getEngineByExtension(name) ?: MANAGER.getEngineByMimeType(name)
                                         ?: throw RuntimeException("Unknown script engine: $name")
}

private fun gb(n: Long): String {
    return "${Math.round(n/1e6)}GB"
}