package com.magicreg.nalaq

import org.apache.commons.beanutils.BeanMap
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.Charset
import java.util.*
import kotlin.reflect.KFunction

fun main(args: Array<String>) {
    val config = loadConfiguration(args)
    if (config != null)
        runConfiguration(config)
    else if (args.isNotEmpty())
        runConfiguration(defaultConfiguration(args))
    else
        runConfiguration(defaultConfiguration())
}

fun runConfiguration(config: Configuration) {
    validateLanguages(config)
    if (!addNamespace(nalaqNamespace(config)))
        throw RuntimeException("Nalaq namespace was registered before runtime")
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
        val subArgs = if (args.size > 2) args.toList().subList(2, args.size).toTypedArray() else emptyArray()
        return defaultConfiguration(subArgs, true)
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
    val arguments = mutableListOf<String>()
    var start: String? = null
    var exp: String? = null
    var parser: TextParser? = null
    if (args.size > offset) {
        start = "expression"
        val subArgs = args.toList().subList(offset, args.size)
        val pair = loadScriptFile(subArgs)
        parser = pair.first
        val script = pair.second
        exp = script ?: subArgs.joinToString(" ")
        if (script != null)
            arguments.addAll(subArgs.subList(1, subArgs.size))
    }

    return Configuration(
        consolePrompt = if (configData.containsKey("consolePrompt")) configData["consolePrompt"]?.toString() else defaultConsolePrompt,
        resultPrompt = if (configData.containsKey("resultPrompt")) configData["resultPrompt"]?.toString() else defaultResultPrompt,
        expressionPrompt = if (configData.containsKey("expressionPrompt")) configData["expressionPrompt"]?.toString() else defaultExpressionPrompt,
        printConfig = toBoolean(configData["printConfig"]),
        remoteScripting = toBoolean(configData["remoteScripting"]),
        outputFormat = configData["outputFormat"]?.toString(),
        language = configData["language"]?.toString() ?: Locale.getDefault().language,
        targetLanguage = configData["targetLanguage"]?.toString(),
        translateEndpoint = configData["translateEndpoint"]?.toUri(),
        voiceCommand = configData["voiceCommand"]?.toString(),
        textParser = parser ?: selectedEnum(TextParser::values, configData["textParser"]) ?: TextParser.entries[0],
        nlpModelsFolder = configData["nlpModelsFolder"]?.toString(),
        speechModelsFolder = configData["speechModelsFolder"]?.toString(),
        serverPort = configData["serverPort"]?.toString()?.toIntOrNull(),
        namespaces = getMapUris(configData["namespaces"], ::loadNamespace),
        webFolder = configData["webFolder"]?.toString() ?: defaultWebFolder,
        webApi = configData["webApi"]?.toUri(),
        startMethod =  configData["startMethod"]?.toString() ?: start,
        executeExpression = exp ?: configData["executeExpression"]?.toString(),
        arguments = arguments
    )
}

fun defaultConfiguration(args: Array<String> = emptyArray(), debug: Boolean = false): Configuration {
    val port = if (args.size == 1) args[0].toIntOrNull()?:0 else 0
    val (parser, script) = loadScriptFile(args.toList())
    val arguments = if (script != null) args.toList().subList(1, args.size) else emptyList()
    val exp = script ?: if (port > 0 || args.isEmpty()) null else args.joinToString(" ")
    val lineCount = exp?.split("\n")?.filter(::notEmptyNorComment)?.size ?: 0
    return Configuration(
        consolePrompt = if (exp == null || debug) defaultConsolePrompt else null,
        resultPrompt = if (exp == null || debug) defaultResultPrompt else if (lineCount == 1) "" else null,
        expressionPrompt = if (debug) defaultExpressionPrompt else null,
        printConfig = debug,
        remoteScripting = false,
        outputFormat = null,
        language = Locale.getDefault().language,
        targetLanguage = null,
        translateEndpoint = null,
        voiceCommand = null,
        textParser = parser ?: TextParser.entries[0],
        nlpModelsFolder = null,
        speechModelsFolder = null,
        serverPort = port,
        namespaces = emptyMap(),
        webFolder = defaultWebFolder,
        webApi = null,
        startMethod = if (exp != null) "expression" else if (port > 0) "server" else null,
        executeExpression = exp,
        arguments = arguments
    )
}

fun defaultCharset(charset: String? = null): String {
    if (charset != null)
        defaultCharset = charset
    return defaultCharset
}

fun getLanguages(): Collection<Language> {
    return languages.values
}

fun getLanguage(code: String): Language? {
    return languages[code]
}

fun getSpeechWriter(): SpeechWriter? {
    return speaker
}

private val configWords = "conf,config,configuration".split(",")
private val defaultWebFolder = System.getProperty("user.dir")
private val scriptPrimaryTypes = "text,application".split(",")
private const val helpWord = "help"
private const val dictioWord = "dictionary"
private const val exitWord = "exit"
private const val startAudioWord = "audio"
private const val stopAudioWord = "stop"
private const val cancelAudioWord = "cancel"
private const val defaultConsolePrompt = "\n? "
private const val defaultResultPrompt = "= "
private const val defaultExpressionPrompt = "-> "
private const val NALAQ_PREFIX = "nalaq"
private const val NALAQ_URI = "https://magicreg.com/nalaq/"
private var outputFormat: Format? = null
private var defaultCharset = "utf8"
private var speaker: SpeechWriter? = null
private val languages = loadLanguages()

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

private fun loadLanguages(): Map<String,Language> {
    val langs = mutableMapOf<String,Language>()
    val lines = InputStreamReader(Configuration::class.java.getResource("/languages.csv").openStream(), defaultCharset).readLines().iterator()
    val fields = lines.next().split(",")
    while (lines.hasNext()) {
        val parts = lines.next().split(",")
        if (parts.size < fields.size)
            continue
        val map = mutableMapOf<String,String>()
        for (index in fields.indices)
            map[fields[index]] = parts[index]
        val code = map["code"]!!
        langs[code] = Language(
            name = map["name"] ?: "language#${langs.size+1}",
            code = code,
            voice = map["voice"] ?: "",
            model = map["model"] ?: ""
        )
    }
    return langs
}

private fun validateLanguages(config: Configuration) {
    val errors = mutableListOf<String>()
    val srclang = getLanguage(config.language)
    val dstlang = if (config.targetLanguage == null) null else getLanguage(config.targetLanguage)

    if (config.textParser == TextParser.TRANSLATE) {
        if (config.translateEndpoint == null)
            errors.add("Text parser is translate but endpoint is not specified")
        if (srclang == null)
            errors.add("Invalid language: ${config.language}")
        if (dstlang == null && config.targetLanguage != null)
            errors.add("Invalid target language: ${config.targetLanguage}")
    }

    if (config.speechModelsFolder != null && config.startMethod != "server") {
        if (srclang == null)
            errors.add("Invalid language: ${config.language}")
        else if (srclang.model.isNullOrBlank())
            errors.add("No existing model for this language: ${config.language}")
    }

    if (config.voiceCommand != null && config.startMethod != "server") {
        val lang = if (config.textParser == TextParser.TRANSLATE) dstlang ?: srclang else srclang
        if (lang == null) {
            if (errors.isEmpty())
                errors.add("Invalid language to speak: $lang")
        }
        else if (lang.voice.isNullOrBlank())
            errors.add("No existing voice for this language: ${config.language}")
        else if (errors.isEmpty())
            speaker = SpeechWriter(lang.code, config.voiceCommand)
    }

    if (errors.isNotEmpty()) {
        val plural = if (errors.size > 1) "s" else ""
        val separator = if (errors.size > 1) "\n- " else " "
        throw RuntimeException("Configuration error$plural for language features:$separator${errors.joinToString(separator)}")
    }
}

private fun nalaqNamespace(config: Configuration): Namespace {
    val map = mutableMapOf<String,Any?>(
        dictioWord to KotlinPropertyReference(Context::names, Context::class),
        "true" to true,
        "false" to false,
        "null" to ValueReference(null, true)
    )
    for (op in builtinOperators()) {
        val lastIndex = op.name.lastIndexOf("_")
        if (lastIndex < 0)
            continue
        val name = op.name.substring(0, lastIndex)
        if (map.containsKey(name))
            throw RuntimeException("NaLaQ namespace already has a registered name of $name")
        map[name] = op
    }
    registerTypeAndChildren(getTypeByClass(Any::class), map)
    return GenericNamespace(NALAQ_PREFIX, NALAQ_URI, true).populate(map)
}

private fun registerTypeAndChildren(type: Type, map: MutableMap<String,Any?>) {
    if (map.containsKey(type.name))
        throw RuntimeException("NaLaQ namespace already has a registered name of ${type.name}")
    map[type.name] = type
    for (child in type.childrenTypes)
        registerTypeAndChildren(child, map)
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

private fun <T: Enum<T>> selectedEnum(valuesFunction:()->Array<T>, value: Any?): T? {
    val txt = value?.toString()?.trim()?.uppercase()
    if (txt.isNullOrBlank())
        return null
    val values = valuesFunction()
    for (instance in values) {
        if (instance.name == txt)
            return instance
    }
    val klass = values[0]::class.simpleName
    throw RuntimeException("Unknown $klass instance: $value\nAvailable $klass values: ${values.joinToString(" ")}")
}

private fun loadScriptFile(args: List<String>): Pair<TextParser?, String?> {
    if (args.isNotEmpty()) {
        val file = File(args[0])
        val format = getFormat(file.extension)
        if (file.exists() && file.isFile && format != null && format.supported && scriptPrimaryTypes.contains(format.mimetype.split("/")[0]))
            return Pair(getTextParser(format.mimetype), file.readText(Charset.forName(defaultCharset())))
    }
    return Pair(null, null)
}

private fun getTextParser(mimetype: String): TextParser? {
    for (parser in TextParser.entries) {
        if (mimetype.contains(parser.name.lowercase()))
            return parser
    }
    return null
}

private fun startConsole(config: Configuration) {
    val textInput = BufferedReader(InputStreamReader(System.`in`))
    if (!config.executeExpression.isNullOrBlank())
        executeExpression(config)
    var speechInput: BufferedReader? = if (config.speechModelsFolder != null) speechInputStream(config.language) else null
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
        when (txt) {
            exitWord -> running = false
            helpWord -> printHelp()
            startAudioWord -> {
                if (speechInput != null)
                    println("Audio listening is already active")
                else if (config.speechModelsFolder == null)
                    println("Audio listening is not configured")
                else {
                    speechInput = speechInputStream(config.language)
                    println("Audio listening is now active")
                }
            }
            stopAudioWord -> {
                if (speechInput == null)
                    println("Audio listening is not active")
                else {
                    speechInput.close()
                    speechInput = null
                    println("Audio listening has stopped")
                }
            }
            else -> {
                if (txt.endsWith(" $cancelAudioWord"))
                    println("Last sentence cancelled")
                else if (setOutputFormat(txt))
                    println("output format set to ${outputFormat?.mimetype}")
                else {
                    try {
                        val value = execute(txt, config.expressionPrompt)
                        if (config.resultPrompt != null)
                            printValue(value, config.resultPrompt, speaker)
                    }
                    catch (e: Exception) { e.printStackTrace() }
                }
            }
        }
    }
    stopSpeechInput(config.language)
}

private fun executeExpression(config: Configuration) {
    val txt = config.executeExpression ?: ""
    if (config.consolePrompt != null)
        println(config.consolePrompt+txt)
    val value = execute(txt, config.expressionPrompt)
    if (config.resultPrompt != null)
        printValue(value, config.resultPrompt, speaker)
}

private fun printValue(value: Any?, prompt: String, speech: SpeechWriter?) {
    var format = outputFormat!!
    println(prompt+format.encodeText(value))
    if (speech != null) {
        if (format.mimetype != "text/plain")
            format = getFormat("text/plain")!!
        speech.write(format.encodeText(value))
    }
}

private fun execute(txt: String, expressionPrompt: String?): Any? {
    val exp = txt.toExpression()
    if (expressionPrompt != null)
        printValue(exp, expressionPrompt, null)
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