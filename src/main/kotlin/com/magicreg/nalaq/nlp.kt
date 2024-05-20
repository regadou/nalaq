package com.magicreg.nalaq

import opennlp.tools.parser.*
import opennlp.tools.parser.Parser
import opennlp.tools.sentdetect.SentenceDetector
import opennlp.tools.sentdetect.SentenceDetectorME
import opennlp.tools.sentdetect.SentenceModel
import opennlp.tools.tokenize.Tokenizer
import opennlp.tools.tokenize.TokenizerME
import opennlp.tools.tokenize.TokenizerModel
import opennlp.tools.util.Span
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.util.*
import kotlin.reflect.KFunction

class TranslateParser(): com.magicreg.nalaq.Parser {
    private var translateEndpointUrl: URI? = null

    override fun parse(txt: String): Expression {
        val config = getContext().configuration
        val result = if (config.targetLanguage.isNullOrBlank()) txt else translate(config.language, config.targetLanguage, txt)
        return Expression(null, listOf(result))
    }

    fun translate(sourceLang: String, targetLang: String, text: String ): String {
        val data = mapOf(
            "source" to sourceLang,
            "target" to targetLang,
            "format" to "text",
            "q" to text
        )
        val result = translateEndpoint().post(data, mapOf("content-type" to "application/json"))
        return toMap(result)["translatedText"]?.toString() ?: result.toString()
    }

    private fun translateEndpoint(): URI {
        if (translateEndpointUrl != null)
            return translateEndpointUrl!!
        val endpoint = getContext().configuration.translateEndpoint
        if (endpoint.isNullOrBlank())
            throw RuntimeException("translateEndpoint configuration is empty")
        val port = endpoint.toIntOrNull()
        if (port != null) {
            val host = "localhost"
            Runtime.getRuntime().exec(arrayOf("libretranslate", "--host", host, "--port", "$port"))
            Thread.sleep(3000L)
            translateEndpointUrl = URI("http://$host:$port/translate")
        }
        else
            translateEndpointUrl = URI(endpoint)
        return translateEndpointUrl!!
    }
}

class NlpParser(
    private val modelFolder: String,
    private val dictionary: Map<*, *> = TreeMap<Any?, Any?>(),
    lazyLoading: Boolean = false,
    private val loadingMessage: Boolean = false
): com.magicreg.nalaq.Parser {
    private var parser: Parser? = null
    private var tokenizer: Tokenizer? = null
    private var sentenceDetector: SentenceDetector? = null

    init {
        if (!lazyLoading) {
            checkDependencies()
        }
    }

    override fun parse(txt: String): Expression {
        val sentences = getSentences(txt)
        return when (sentences.size) {
            0 -> Expression()
            1 -> Expression(sentences[0].function, sentences[0].parameters)
            else -> {
                val params = mutableListOf<Any?>()
                for (sentence in sentences)
                    params.add(Expression(sentence.function, sentence.parameters))
                Expression(null, params)
            }
        }
    }

    private fun getSentences(text: String): List<Sentence> {
        val sentences: MutableList<Sentence> = ArrayList()
        for (sentence in getSentenceDetector().sentDetect(text)) {
            sentences.add(Sentence(sentence, getTokenizer(), getParser(), dictionary))
        }
        return sentences
    }

    private fun checkDependencies() {
        if (sentenceDetector == null || tokenizer == null || parser == null) {
            if (loadingMessage)
                println("Loading parser data for OpenNLP ...")
            getSentenceDetector()
            getTokenizer()
            getParser()
        }
    }

    private fun getSentenceDetector(): SentenceDetector {
        if (sentenceDetector == null)
            inputModelFile(DEFAULT_SENTENCE_MODEL).use { input -> sentenceDetector = SentenceDetectorME(SentenceModel(input)) }
        return sentenceDetector!!
    }

    private fun getTokenizer(): Tokenizer {
        if (tokenizer == null)
            inputModelFile(DEFAULT_TOKENIZER_MODEL).use { input -> tokenizer = TokenizerME(TokenizerModel(input)) }
        return tokenizer!!
    }

    private fun getParser(): Parser {
        if (parser == null)
            inputModelFile(DEFAULT_PARSER_MODEL).use { input -> parser = ParserFactory.create(ParserModel(input)) }
        return parser!!
    }

    private fun inputModelFile(filename: String): InputStream {
        val modelFile = File("$modelFolder/$filename")
        if (!modelFile.exists())
            throw RuntimeException("Model file $modelFile was not found")
        return FileInputStream(modelFile)
    }
}

class Sentence(
    val text: String,
    private val tokenizer: Tokenizer,
    private val parser: Parser,
    private val dictionary: Map<*, *>
) {
    var function: KFunction<*>? = null
    val debugInfo = StringBuilder()
    val parameters = getTokens()

    override fun toString(): String {
        return text
    }

    private fun getTokens(): List<Word> {
        if (text.isNullOrBlank())
            return emptyList()
        val spans = tokenizer.tokenizePos(text)
        val parse = getParse(spans)
        populateDebugInfo(parse, "")
        return parseTokens(parse, dictionary)
    }

    private fun getParse(spans: Array<Span>): Parse {
        val parse = Parse(text, Span(0, text.length), AbstractBottomUpParser.INC_NODE, 1.0, 0)
        for (s in spans.indices) {
            val span = spans[s]
            parse.insert(Parse(text, span, AbstractBottomUpParser.TOK_NODE, 0.0, s))
        }
        return parser.parse(parse)
    }

    private fun populateDebugInfo(parse: Parse, baseTabs: String) {
        var tabs = baseTabs
        val type = parse.type
        val label: String = if (type == "TK")
            parse.coveredText
        else {
            val pos = POS_INSTANCES[type]
            if (pos == null)
                "{$type}"
            else
                "<${pos.wordtype.name}:${pos.name}->${pos.wordtype.datatype}>"
        }
        debugInfo.append(tabs).append(label).append("\n")
        if (parse.childCount > 0) {
            tabs += " "
            for (child in parse.children)
                populateDebugInfo(child, tabs)
        }
    }

    private fun parseTokens(parse: Parse, dictionary: Map<*, *>): List<Word> {
        val type = parse.type
        val pos = POS_INSTANCES[type]
        if (pos != null)
            return listOf(getWord(parse.coveredText, pos.wordtype, dictionary))
        else if (type.length == 1 && isPunctuation(type[0]))
            return listOf(getWord(type, getWordType("punctuation"), dictionary))
        val nc = parse.childCount
        return when (nc) {
            0 -> emptyList()
            1 -> parseTokens(parse.children[0], dictionary)
            else -> {
                val tokens = mutableListOf<Word>()
                for (child in parse.children) {
                    val subtokens = parseTokens(child, dictionary)
                    when (subtokens.size) {
                        0 -> {}
                        1 -> tokens.add(subtokens[0])
                        else -> tokens.add(parseExpression(subtokens))
                    }
                }
                tokens
            }
        }
    }

    private fun isPunctuation(c: Char): Boolean {
        return ",;:.?!".indexOf(c) >= 0
    }

    private fun getWord(txt: String, type: WordType?, dictionary: Map<*, *>): Word {
        return Word(txt, type ?: WordType.WORD, dictionary[txt.normalize()].resolve() ?: text, DEFAULT_LANGUAGE)
    }

    private fun parseExpression(tokens: List<Word>): Word {
        val exp = Expression(null, tokens)
        return Word(exp.toString(), WordType.WORD, exp, DEFAULT_LANGUAGE)
    }
}

class Word(
    override val key: String,
    val wordType: WordType,
    override val value: Any?,
    val language: String
): Reference {
    override val parent: Any? = language
    override val readOnly: Boolean = true

    override fun setValue(newValue: Any?): Any? {
        return value
    }

    override fun toString(): String {
        return "$language:$key"
    }

    override fun equals(that: Any?): Boolean {
        return that != null && key.normalize() == that.toString().normalize()
    }

    override fun hashCode(): Int {
        return toString().normalize().hashCode()
    }
}

enum class WordType(val datatype: Type) {
    DETERMINER(getTypeByName("entity")!!),
    ADJECTIVE(getTypeByName("property")!!),
    ADVERB(getTypeByName("property")!!),
    NOUN(getTypeByName("type")!!),
    PRONOUN(getTypeByName("entity")!!),
    NAME(getTypeByName("entity")!!),
    VERB(getTypeByName("function")!!),
    PREPOSITION(getTypeByName("function")!!),
    CONJUNCTION(getTypeByName("function")!!),
    PUNCTUATION(getTypeByName("function")!!),
    INTERJECTION(getTypeByName("view")!!),
    ONOMATOPOEIA(getTypeByName("view")!!),
    WORD(getTypeByName("text")!!)
}

class PartOfSpeech(
    val code: String,
    val name: String,
    val description: String,
    val wordtype: WordType
) {
    override fun toString(): String {
        return "PartOfSpeech($name)"
    }
}

private const val DEFAULT_SENTENCE_MODEL = "en-sent.bin"
private const val DEFAULT_TOKENIZER_MODEL = "en-token.bin"
private const val DEFAULT_PARSER_MODEL = "en-parser-chunking.bin"
private const val DEFAULT_LANGUAGE = "en"
private val POS_INSTANCES = initPOS()

private fun getWordType(txt: String): WordType? {
    try { return WordType.valueOf(txt.uppercase()) }
    catch (e: Exception) { return null }
}

private fun initPOS(): Map<String,PartOfSpeech> {
    val map = mutableMapOf<String, PartOfSpeech>()
    val data = arrayOf(
        arrayOf("CC", "Coordinating conjunction"),
        arrayOf("CD", "adjective", "Cardinal number"),
        arrayOf("DT", "Determiner"),
        arrayOf("EX", "pronoun", "Existential there"),
        arrayOf("FW", "Foreign word"),
        arrayOf("IN", "Preposition or subordinating conjunction"),
        arrayOf("JJ", "Adjective"),
        arrayOf("JJR", "Adjective", "comparative"),
        arrayOf("JJS", "Adjective", "superlative"),
        arrayOf("LS", "punctuation", "List item marker"),
        arrayOf("MD", "adverb", "Modal"),
        arrayOf("NN", "Noun", "singular or mass"),
        arrayOf("NNS", "Noun", "plural"),
        arrayOf("NNP", "name", "Proper noun singular"),
        arrayOf("NNPS", "name", "Proper noun plural"),
        arrayOf("PDT", "determiner", "Predeterminer"),
        arrayOf("POS", "determiner", "Possessive ending"),
        arrayOf("PRP", "Personal pronoun"),
        arrayOf("PRP$", "Possessive pronoun"),
        arrayOf("RB", "Adverb"),
        arrayOf("RBR", "Adverb", "comparative"),
        arrayOf("RBS", "Adverb", "superlative"),
        arrayOf("RP", "punctuation", "Particle"),
        arrayOf("SYM", "punctuation", "Symbol"),
        arrayOf("TO", "Preposition"),
        arrayOf("UH", "Interjection"),
        arrayOf("VB", "Verb", "base form"),
        arrayOf("VBD", "Verb", "past tense"),
        arrayOf("VBG", "Verb", "gerund or present participle"),
        arrayOf("VBN", "Verb", "past participle"),
        arrayOf("VBP", "Verb", "non-3rd person singular present"),
        arrayOf("VBZ", "Verb", "3rd person singular present"),
        arrayOf("WDT", "Whatever determiner"),
        arrayOf("WP", "Whatever pronoun"),
        arrayOf("WP$", "Possessive whatever pronoun"),
        arrayOf("WRB", "Whatever adverb")
    )

    for (row in data) {
        val code = row[0]
        val name = row[1]
        val description = if (row.size > 2) row[2] else row[1]
        var type: WordType? = null
        for (part in row) {
            for (txt in part.split(" ")) {
                type = getWordType(txt)
                if (type != null)
                    break
            }
            if (type != null)
                break
        }
        map[code] = PartOfSpeech(code, name, description, type ?: WordType.WORD)
    }
    return map
}
