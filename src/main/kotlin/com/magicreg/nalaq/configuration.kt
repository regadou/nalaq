package com.magicreg.nalaq

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import kotlin.reflect.KFunction

fun runConfiguration(config: Configuration) {
    val cx = getContext(config)
    setOutputFormat(config.outputFormat)
    if (config.printConfig)
        println("configuration = "+getFormat(config.outputFormat)!!.encodeText(config))
    when (config.startMethod) {
        "server" -> startServer(config)
        "console" -> startConsole(config)
        "expression" -> executeExpression(config)
        else -> throw RuntimeException("Invalid start method: ${config.startMethod}\nValid values are server, console or expression")
    }
}

fun loadConfiguration(args: Array<String>): Configuration? {
    if (args.size < 2 || !configWords.contains(args[0]))
        return null
    if (args[1] == "debug") {
        val exp = if (args.size > 2) args.toList().subList(2, args.size).joinToString(" ") else null
        return defaultConfiguration(exp, true)
    }
    val uri = args[1].toUri() ?: args[1].detectFormat(true)?.toUri() ?: throw RuntimeException("Invalid configuration uri: ${args[1]}")
    val value = uri.get().resolve()
    if (value is Exception)
        throw value
    val configData = toMap(value)
    var start: String? = null
    var exp: String? = null
    if (args.size > 2) {
        start = "expression"
        exp = args.toList().subList(2, args.size).joinToString(" ")
    }
    return Configuration(
        consolePrompt = if (configData.containsKey("consolePrompt")) configData["consolePrompt"]?.toString() else defaultConsolePrompt,
        resultPrompt = if (configData.containsKey("resultPrompt")) configData["resultPrompt"]?.toString() else defaultResultPrompt,
        expressionPrompt = if (configData.containsKey("expressionPrompt")) configData["expressionPrompt"]?.toString() else defaultExpressionPrompt,
        printConfig = toBoolean(configData["printConfig"]),
        outputFormat = configData["outputFormat"]?.toString() ?: defaultOutputFormat,
        exitWords = getListValues(configData["exitWords"], defaultExitWords),
        textParser = selectedEnum(TextParser::values, configData["textParser"]) ?: TextParser.entries[0],
        nlpModelFolder = configData["nlpModelFolder"]?.toString(),
        speechEngine = selectedEnum(SpeechEngine::values, configData["speechEngine"]),
        voskSpeechModel = configData["voskSpeechModel"]?.toUri(),
        picoAccessKey = configData["picoAccessKey"]?.toString(),
        serverPort = configData["serverPort"]?.toString()?.toIntOrNull(),
        webContextName = if (configData.containsKey("webContextName")) configData["webContextName"]?.toString() else defaultWebContextName,
        namespaces = getMapUris(configData["namespaces"], ::loadNamespace),
        staticFolder = configData["staticFolder"]?.toString() ?: defaultStaticFolder,
        startMethod = start ?: configData["startMethod"]?.toString() ?: defaultStartMethod,
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
        outputFormat = defaultOutputFormat,
        exitWords = defaultExitWords,
        textParser = TextParser.entries[0],
        nlpModelFolder = null,
        speechEngine = null,
        voskSpeechModel = null,
        picoAccessKey = null,
        serverPort = port,
        webContextName = defaultWebContextName,
        namespaces = emptyMap(),
        staticFolder = defaultStaticFolder,
        startMethod = if (exp != null) "expression" else if (port > 0) "server" else defaultStartMethod,
        executeExpression = exp
    )
}

fun defaultCharset(charset: String? = null): String {
    if (charset != null)
        defaultCharset = charset
    return defaultCharset
}

data class Configuration(
    val consolePrompt: String?,
    val resultPrompt: String?,
    val expressionPrompt: String?,
    val printConfig: Boolean,
    val outputFormat: String,
    val exitWords: List<String>,
    val textParser: TextParser,
    val nlpModelFolder: String?,
    val speechEngine: SpeechEngine?,
    val voskSpeechModel: URI?,
    val picoAccessKey: String?,
    val serverPort: Int?,
    val webContextName: String?,
    val namespaces: Map<String,URI>,
    val staticFolder: String,
    val startMethod: String,
    val executeExpression: String?,
)

private val configWords = "conf,config,configuration".split(",")
private val defaultExitWords = "exit,quit".split(",")
private val startAudioWord = "audio"
private val stopAudioWord = "stop"
private val cancelAudioWord = "cancel"
private const val defaultConsolePrompt = "\n? "
private const val defaultResultPrompt = "= "
private const val defaultExpressionPrompt = "-> "
private const val defaultOutputFormat = "application/json"
private const val defaultStaticFolder = "./"
private const val defaultStartMethod = "console"
private const val defaultWebContextName = "all"
private var outputFormat: Format? = null
private var defaultCharset = "utf8"

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
            val inputText = speechInput.readLine().trim()
            println(inputText)
            inputText
        } else textInput.readLine().trim()
        if (txt.isBlank())
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
    if (outputFormat == null)
        outputFormat = getFormat(defaultOutputFormat)
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