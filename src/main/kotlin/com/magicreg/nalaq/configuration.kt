package com.magicreg.nalaq

import org.apache.commons.beanutils.BeanMap
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.util.*
import kotlin.reflect.KFunction

fun runConfiguration(config: Configuration) {
    getContext(config)
    setOutputFormat(config.outputFormat ?: "application/json")
    if (config.printConfig)
        println("configuration = "+outputFormat!!.encodeText(config))
    if (!config.executeExpression.isNullOrBlank())
        executeExpression(config)
    when (val startMethod = config.startMethod ?: "console") {
        "server" -> startServer(config)
        "console" -> startConsole(config)
        "expression", "noop" -> {}
        else -> throw RuntimeException("Invalid start method: $startMethod\nValid values are server, console, expression or noop")
    }
}

fun loadConfiguration(args: Array<String>): Configuration? {
    var offset = 0
    var param = if (args.isEmpty() || !configWords.contains(args[0]))
        configWords.map { System.getenv("NALAQ_"+it.uppercase()) }.filterNotNull().firstOrNull() ?: return null
    else if (args.size == 1 || args[1] == "help")
        return printHelp()
    else if (args[1] == "debug") {
        val exp = if (args.size > 2) args.toList().subList(2, args.size).joinToString(" ") else null
        return defaultConfiguration(exp, true)
    }
    else {
        offset = 2
        args[1]
    }

    val uri = param.toUri() ?: param.detectFormat(true)?.toUri() ?: return printHelp("Invalid configuration uri: ${args[1]}")
    val value = uri.get().resolve()
    if (value is Exception)
        throw value

    val configData = toMap(value)
    var start: String? = null
    var exp: String? = null
    if (args.size > offset) {
        start = "expression"
        exp = args.toList().subList(offset, args.size).joinToString(" ")
    }

    return Configuration(
        consolePrompt = if (configData.containsKey("consolePrompt")) configData["consolePrompt"]?.toString() else defaultConsolePrompt,
        resultPrompt = if (configData.containsKey("resultPrompt")) configData["resultPrompt"]?.toString() else defaultResultPrompt,
        expressionPrompt = if (configData.containsKey("expressionPrompt")) configData["expressionPrompt"]?.toString() else defaultExpressionPrompt,
        printConfig = toBoolean(configData["printConfig"]),
        outputFormat = configData["outputFormat"]?.toString(),
        exitWords = getListValues(configData["exitWords"], defaultExitWords),
        language = configData["language"]?.toString() ?: Locale.getDefault().language,
        targetLanguage = configData["targetLanguage"]?.toString(),
        translateEndpoint = configData["translateEndpoint"]?.toUri(),
        textParser = selectedEnum(TextParser::values, configData["textParser"]) ?: TextParser.entries[0],
        nlpModelFolder = configData["nlpModelFolder"]?.toString(),
        speechEngine = selectedEnum(SpeechEngine::values, configData["speechEngine"]),
        voskSpeechModel = configData["voskSpeechModel"]?.toUri(),
        picoAccessKey = configData["picoAccessKey"]?.toString(),
        serverPort = configData["serverPort"]?.toString()?.toIntOrNull(),
        webContextName = if (configData.containsKey("webContextName")) configData["webContextName"]?.toString() else defaultWebContextName,
        namespaces = getMapUris(configData["namespaces"], ::loadNamespace),
        staticFolder = configData["staticFolder"]?.toString() ?: defaultStaticFolder,
        startMethod =  configData["startMethod"]?.toString() ?: start,
        executeExpression = exp ?: configData["executeExpression"]?.toString()
    )
}

fun defaultConfiguration(expression: String? = null, debug: Boolean = false): Configuration {
    val port = expression?.trim()?.toIntOrNull() ?: 0
    val exp = if (port > 0 || expression.isNullOrBlank()) null else expression
    val lineCount = exp?.split("\n")?.filter(::notEmptyNorComment)?.size ?: 0
    return Configuration(
        consolePrompt = if (exp == null || debug) defaultConsolePrompt else null,
        resultPrompt = if (exp == null || debug) defaultResultPrompt else if (lineCount == 1) "" else null,
        expressionPrompt = if (debug) defaultExpressionPrompt else null,
        printConfig = debug,
        outputFormat = null,
        exitWords = defaultExitWords,
        language = Locale.getDefault().language,
        targetLanguage = null,
        translateEndpoint = null,
        textParser = TextParser.entries[0],
        nlpModelFolder = null,
        speechEngine = null,
        voskSpeechModel = null,
        picoAccessKey = null,
        serverPort = port,
        webContextName = defaultWebContextName,
        namespaces = emptyMap(),
        staticFolder = defaultStaticFolder,
        startMethod = if (exp != null) "expression" else if (port > 0) "server" else null,
        executeExpression = exp
    )
}

fun defaultCharset(charset: String? = null): String {
    if (charset != null)
        defaultCharset = charset
    return defaultCharset
}

private val configWords = "conf,config,configuration".split(",")
private val defaultExitWords = "exit,quit".split(",")
private val defaultStaticFolder = System.getProperty("user.dir")
private const val startAudioWord = "audio"
private const val stopAudioWord = "stop"
private const val cancelAudioWord = "cancel"
private const val defaultConsolePrompt = "\n? "
private const val defaultResultPrompt = "= "
private const val defaultExpressionPrompt = "-> "
private const val defaultWebContextName = "all"
private var outputFormat: Format? = null
private var defaultCharset = "utf8"

private fun printHelp(message: String? = null): Configuration {
    val params = BeanMap(defaultConfiguration()).entries.filter{it.key!="class"}.map{ "  - ${it.key}: ${getFormat("json")!!.encodeText(it.value).trim()}" }.sorted()
    if (message != null)
        println(message)
    println("Usage: nalaq [config <keyword|uri|data>] [<expression> ...]")
    println("- supported configuration keywords: debug, help")
    println("- supported configuration uri schemes: http, https, file, data")
    println("- configuration can also auto-detect some data file formats: json, yaml, csv, urlencoded form")
    println("- you can also pass configuration data with the NALAG_CONFIG environment variable")
    println("- list of configuration parameters with their default values:")
    println(params.joinToString("\n"))
    return Configuration(startMethod = "noop")
}

private fun getListValues(value: Any?, defaultValue: List<String>): List<String> {
    return (
        if (value == null)
            defaultValue
        else if (value is Collection<*>)
            value.map { toString(it) }
        else if (value is Array<*>)
            value.map { toString(it) }
        else if (value is Map<*,*>)
            value.keys.map { toString(it) }
        else
            value.toString().trim().split(",")
    ).map { it.trim() }.filter { it.isNotBlank() }
}

private fun getMapUris(value: Any?, process: KFunction<Any?>? = null): Map<String,URI> {
    val src: Map<Any?,Any?> = (
        if (value == null)
            emptyMap()
        else if (value is Map<*,*>)
            value as Map<Any?,Any?>
        else
            throw RuntimeException("Invalid map value: $value")
    )
    val dst = mutableMapOf<String,URI>()
    for (key in src.keys) {
        val uri = src[key].toUri() ?: throw RuntimeException("Invalid uri value: ${src[value]}")
        val prefix = toString(key)
        process?.call(prefix, uri, true)
        dst[prefix] = uri
    }
    return dst
}

private fun <T: Enum<T>> selectedEnum(values:()->Array<T>, value: Any?): T? {
    val txt = value?.toString()?.trim()?.uppercase()
    if (txt.isNullOrBlank())
        return null
    for (instance in values()) {
        if (instance.name == txt)
            return instance
    }
    val engines = SpeechEngine.entries.joinToString(" ")
    throw RuntimeException("Unknown speech engine: $value\nAvailable engines: $engines")
}

private fun startConsole(config: Configuration) {
    val textInput = BufferedReader(InputStreamReader(System.`in`))
    if (!config.executeExpression.isNullOrBlank())
        executeExpression(config)
    var speechInput: BufferedReader? = if (config.speechEngine != null) speechInputStream(null) else null
    var running = true
    while (running) {
        if (config.consolePrompt != null) {
            print(config.consolePrompt)
            System.out.flush()
        }
        val txt = if (speechInput != null) {
            val inputText = speechInput.readLine()?.trim()
            if (!inputText.isNullOrBlank())
                println(inputText)
            inputText
        }
        else
            textInput.readLine()?.trim()
        if (txt.isNullOrBlank())
            continue
        if (config.exitWords.contains(txt))
            running = false
        else if (txt == startAudioWord) {
            if (speechInput != null)
                println("Audio listening is already active")
            else if (config.speechEngine == null)
                println("Audio listening is not configured")
            else {
                speechInput = speechInputStream(null)
                println("Audio listening is now active")
            }
        }
        else if (txt == stopAudioWord) {
            if (speechInput == null)
                println("Audio listening is not active")
            else {
                speechInput.close()
                speechInput = null
                println("Audio listening has stopped")
            }
        }
        else if (txt.endsWith(" $cancelAudioWord")) {
            if (speechInput == null)
                println("Audio is not active")
            else
                println("Last audio sentence cancelled")
        }
        else if (setOutputFormat(txt))
            println("output format set to ${outputFormat?.mimetype}")
        else if (txt == "help")
            printHelp()
        else {
            try {
                val value = execute(txt, config.expressionPrompt)
                if (config.resultPrompt != null)
                    printValue(value, config.resultPrompt)
            }
            catch (e: Exception) { e.printStackTrace() }
        }
    }
    stopSpeechInput(null)
}

private fun executeExpression(config: Configuration) {
    val txt = config.executeExpression ?: ""
    if (config.consolePrompt != null)
        println(config.consolePrompt+txt)
    val value = execute(txt, config.expressionPrompt)
    if (config.resultPrompt != null)
        printValue(value, config.resultPrompt)
}

private fun printValue(value: Any?, prompt: String) {
    println(prompt+outputFormat!!.encodeText(value))
}

private fun execute(txt: String, expressionPrompt: String?): Any? {
    val exp = txt.toExpression()
    if (expressionPrompt != null)
        printValue(exp, expressionPrompt)
    return exp.value().resolve()
}

private fun setOutputFormat(txt: String): Boolean {
    val format = getFormat(txt)
    if (format != null && format.supported) {
        outputFormat = format
        return true
    }
    return false
}

private fun notEmptyNorComment(txt: String): Boolean {
    val trimmed = txt.trim()
    if (trimmed.isEmpty() || trimmed[0] == '#')
        return false
    if (trimmed[0] == '/') {
        if (trimmed.length > 1 && trimmed[1] == '/')
            return false
    }
    return true
}