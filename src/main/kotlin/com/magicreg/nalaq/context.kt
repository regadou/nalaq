package com.magicreg.nalaq

import java.io.File
import java.net.URI
import java.util.*

fun contextInitialized(): Boolean {
    return CURRENT_CONTEXT.get() != null
}

fun findContext(id: String?, makeCurrent: Boolean): Context? {
    if (id.isNullOrBlank())
        return null
    val cx = CONTEXT_MAP[id]
    if (cx != null && makeCurrent)
        CURRENT_CONTEXT.set(cx)
    return cx;
}

fun getContext(vararg values: Any): Context {
    val existingContext = CURRENT_CONTEXT.get()
    if (existingContext != null) {
        if (values.isEmpty())
            return existingContext
        // TODO: if only 1 string arg then search for it in active context map and return it if not null
        throw RuntimeException("Context already initialized on this thread")
    }

    var name: String? = null
    var parent: Context? = null
    var parser: Parser? = null
    var constants: Namespace? = null
    var variables: Namespace? = null
    var config: Configuration? = null
    var uri: URI? = null
    for (value in values) {
        if (value is Context)
            parent = value
        else if (value is Namespace) {
            if (value.readOnly) {
                constants = value
            }
            else if (variables == null)
                variables = value
            else if (constants == null)
                constants = value
            else
                variables = value // TODO: we should create a multimap or add to it if already multimap
        }
        else if (value is Configuration)
            config = value
        else if (value is Parser)
            parser = value
        else if (value is MutableMap<*,*>)
            variables = GenericNamespace(readOnly = false).populate(value as MutableMap<String,Any?>)
        else if (value is Map<*,*>)
            constants = GenericNamespace(readOnly = true).populate((value as Map<String,Any?>).toMutableMap())
        else if (value is String)
            name = value
        else if (value is URI)
            uri = value
    }

    if (constants == null && variables == null)
        constants = getNamespace("nalaq")
    if (name == null)
        name = randomName()
    if (uri == null)
        uri = File(System.getProperty("user.dir")).toURI()
    val cx = Context(name!!, uri!!.toString(), parent, parser, constants ?: GenericNamespace(readOnly=true), variables ?: GenericNamespace(), config)
    CURRENT_CONTEXT.set(cx)
    return cx
}

class Context(
    val name: String,
    override val uri: String,
    private val parent: Context?,
    private var parser: Parser?,
    private val constants: Namespace,
    private val variables: Namespace,
    private var localConfiguration: Configuration?
): Namespace {
    override val prefix: String = ""
    override val readOnly: Boolean = false
    var requestUri: String? = null

    val configuration: Configuration get() {
        val conf = this.localConfiguration ?: parent?.configuration
        if (conf != null)
            return conf
        localConfiguration = defaultConfiguration()
        return localConfiguration!!
    }
    init { CONTEXT_MAP[name] = this }

    fun childContext(childName: String?, constants: Namespace, uri: String = this.uri): Context {
        if (CURRENT_CONTEXT.get() != this)
            throw RuntimeException("This context is not the current thread context")
        val cx = Context(childName ?: name+"/"+randomName(), uri, this, null, constants, GenericNamespace(), null)
        CURRENT_CONTEXT.set(parent)
        return cx
    }

    fun close(destroy: Boolean) {
        // TODO: close any context by removing it from the active context map
        if (CURRENT_CONTEXT.get() != this)
            throw RuntimeException("This context is not the current thread context")
        if (destroy) {
            CONTEXT_MAP.remove(name)
            CURRENT_CONTEXT.set(parent)
        }
        else
            CURRENT_CONTEXT.set(null)
    }

    fun isConstant(key: String): Boolean {
        return constants.hasName(key) || parent?.isConstant(key) ?: false
    }

    fun isVariable(key: String): Boolean {
        return variables.hasName(key) || parent?.isVariable(key) ?: false
    }

    override fun hasName(key: String): Boolean {
        return constants.hasName(key) || variables.hasName(key) || parent?.hasName(key) ?: false
    }

    override val names: List<String> get() {
        val keys = mutableSetOf<String>()
        keys.addAll(constants.names)
        keys.addAll(variables.names)
        if (parent != null)
            keys.addAll(parent.names)
        return keys.sorted()
    }

    override fun value(key: String): Any? {
        if (name.isBlank())
            return this
        if (constants.hasName(key))
            return constants.value(key)
        if (variables.hasName(key))
            return variables.value(key)
        return parent?.value(key)
    }

    override fun setValue(key: String, value: Any?): Boolean {
        if (constants.hasName(key))
            return false
        if (parent != null && parent.isConstant(key))
            return false
        return variables.setValue(key, value)
    }

    fun setConstant(key: String, value: Any?): Boolean {
        if (constants.hasName(key))
            return false
        constants.setValue(key, value)
        return true
    }

    fun valueKeys(value: Any?): List<String> {
        val result = mutableSetOf<String>()
        for (name in constants.names) {
            if (value == constants.value(name))
                result.add(name)
        }
        for (name in variables.names) {
            if (value == variables.value(name))
                result.add(name)
        }
        if (parent != null)
            result.addAll(parent.valueKeys(value))
        return result.sorted()
    }

    fun getParser(): Parser {
        if (parser != null)
            return parser!!
        var p = parent
        while (p != null) {
            if (p.parser != null)
                return p.parser!!
            p = p.parent
        }
        parser = when (configuration.textParser) {
            TextParser.NALAQ -> GenericParser()
            TextParser.NLP -> NlpParser(configuration.nlpModelsFolder ?: throw RuntimeException("NLP model folder was not specified"))
            TextParser.TRANSLATE -> TranslateParser()
        }
        return parser!!
    }

    fun parentFolder(): String {
        if (requestUri == null)
            return parent?.parentFolder() ?: "/"
        var path = URI(requestUri!!).path
        if (!path.endsWith("/")) {
            val index = path.lastIndexOf("/")
            if (index > 0)
                path = path.substring(0, index + 1)
            else
                path = "/"
        }
        return path
    }

    fun fileUri(file: File): URI {
        if (uri.startsWith("file:"))
            return file.toURI()
        var path = file.canonicalFile.toString()
        val basePath = File(configuration.staticFolder).canonicalFile.toString()
        if (path.startsWith(basePath))
            path = path.substring(basePath.length)
        if (!path.startsWith("/"))
            path = "/$path"
        return File(path).toURI()
    }

    fun realFile(path: String): File {
        if (uri.startsWith("file"))
            return if (path.startsWith("/")) File(path) else File(URI(uri).path, path)
        if (path.startsWith("/"))
            return File(configuration.staticFolder, path.substring(1))
        if (requestUri == null)
            return File(configuration.staticFolder, path)
        return File(configuration.staticFolder, parentFolder()+path)
    }

    override fun toString(): String {
        return "Context($name@$uri)"
    }
}

private val CURRENT_CONTEXT = ThreadLocal<Context>()
private val CONTEXT_MAP = mutableMapOf<String,Context>()
private val FUNCTIONS_FILE = "com.magicreg.nalaq.FunctionsKt"

private fun randomName(): String {
    return UUID.randomUUID().toString()
}
