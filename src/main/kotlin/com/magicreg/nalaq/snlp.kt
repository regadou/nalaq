package com.magicreg.nalaq

import edu.stanford.nlp.simple.Document
import edu.stanford.nlp.simple.Sentence
import java.util.StringTokenizer
import java.util.concurrent.atomic.AtomicInteger

class SnlpParser(): Parser {

    override fun toString(): String {
        return "SnlpParser"
    }

    override fun parse(txt: String): Expression {
        val sentences = getSentences(txt)
        return when (sentences.size) {
            0 -> Expression()
            1 -> sentences[0].toExpression()
            else -> {
                val params = mutableListOf<Any?>()
                for (sentence in sentences)
                    params.add(sentence.toExpression())
                Expression(null, params)
            }
        }
    }

    fun getSentences(text: String): List<Sentence> {
        return Document(text).sentences()
    }
}

fun Sentence.toExpression(): Expression {
    val tokenizer = StringTokenizer(this.parse().pennString(), "() \n\r\t", true)
    val tokens = mutableListOf<String>()
    var last: String = " "
    while (tokenizer.hasMoreTokens()) {
        val token = tokenizer.nextToken().trim()
        if (token.isNotEmpty()) {
            val txt = jsonize(token)
            if (last.isNotBlank() && last != "[" && txt != "]")
                tokens.add(",")
            tokens.add(txt)
            last = txt
        }
    }
    val json = tokens.joinToString(" ")
    val data = getFormat("json")!!.decodeText(json).toCollection()
    return Expression(null, listOf(compileList(data)))
//    return Expression(null, listOf(compileTokens(tokens)))
}

fun jsonize(src: String): String {
    if (src == "(")
        return "["
    if (src == ")")
        return "]"
    return '"' + src + '"'
}

fun compileList(data: List<Any?>): Any? {
    if (data.isEmpty())
        return null
    val first = data[0]
    if (first !is String)
        return data
    val values = data.subList(1, data.size).map { if (it is List<*>) compileList(it) else it }
    val value = when (values.size) {
        0 -> null
        1 -> values[0]
        else -> values
    }
    return if (first == "ROOT") value else mapOf(first to value)
}

interface LevelType {
    val id: String
    val label: String
}

data class ClauseType(
    override val id: String,
    override val label: String,
    val description: String
): LevelType

data class PhraseType(
    override val id: String,
    override val label: String,
    val description: String = ""
): LevelType

data class TokenType(
    override val id: String,
    val wordType: String,
    val dataType: String,
    override val label: String
): LevelType

class PunctuationType(symbol: Char): LevelType {
    override val id: String = "$symbol"
    override val label: String = id
}

private val clauseTypesMap = createIdTypeMap(
    ClauseType("S", "direct declarative", "Clause that is not introduced by a (possible empty) subordinating conjunction or a wh-word and that does not exhibit subject-verb inversion."),
    ClauseType("SBAR", "subordinate declarative", "Clause introduced by a (possibly empty) subordinating conjunction."),
    ClauseType("SBARQ", "direct question", "Direct question introduced by a wh-word or a wh-phrase. Indirect questions and relative clauses should be bracketed as SBAR, not SBARQ."),
    ClauseType("SINV", "inverted declarative", "Inverted declarative sentence, i.e. one in which the subject follows the tensed verb or modal."),
    ClauseType("SQ", "inverted question", "Inverted yes/no question, or main clause of a wh-question, following the wh-phrase in SBARQ.")
)

private val phraseTypesMap = createIdTypeMap(
    PhraseType("ADJP", "Adjective Phrase"),
    PhraseType("ADVP", "Adverb Phrase"),
    PhraseType("CONJP", "Conjunction Phrase"),
    PhraseType("FRAG", "Fragment"),
    PhraseType("INTJ", "Interjection", "Corresponds approximately to the part-of-speech tag UH"),
    PhraseType("LST", "List marker", "Includes surrounding punctuation"),
    PhraseType("NAC", "Not Constituent", "Used to show the scope of certain prenominal modifiers within an NP"),
    PhraseType("NP", "Noun Phrase"),
    PhraseType("NX", "Used within certain complex NPs to mark the head of the noun phrase. Corresponds very roughly to N-bar level but used quite differently"),
    PhraseType("PP", "Prepositional Phrase"),
    PhraseType("PRN", "Parenthetical"),
    PhraseType("PRT", "Particle", "Category for words that should be tagged RP"),
    PhraseType("QP", "Quantifier Phrase", "Complex measure/amount phrase used within noun phrase"),
    PhraseType("RRC", "Reduced Relative Clause"),
    PhraseType("UCP", "Unlike Coordinated Phrase"),
    PhraseType("VP", "Verb Phrase"),
    PhraseType("WHADJP", "Wh-adjective Phrase", "Adjectival phrase containing a wh-adverb, as in how hot"),
    PhraseType("WHAVP", "Wh-adverb Phrase", "Introduces a clause with an NP gap. May be null (containing the 0 complementizer) or lexical, containing a wh-adverb such as how or why"),
    PhraseType("WHNP", "Wh-noun Phrase", "Introduces a clause with an NP gap. May be null (containing the 0 complementizer) or lexical, containing some wh-word, e.g. who, which book, whose daughter, none of which, or how many leopards"),
    PhraseType("WHPP", "Wh-prepositional Phrase", "Prepositional phrase containing a wh-noun phrase (such as of which or by whose authority) that either introduces a PP gap or is contained by a WHNP"),
    PhraseType("X", "Unknown", "Unknown, uncertain or unbracketable which is often used for bracketing typos and in bracketing the...the-constructions")
)

private val tokenTypesMap = createIdTypeMap(
    TokenType("CC", "conjunction", "function", "Coordinating conjunction"),
    TokenType("CD", "adjective", "property", "Cardinal number"),
    TokenType("DT", "determiner", "entity", "Determiner"),
    TokenType("EX", "pronoun", "entity", "Existential there"),
    TokenType("FW", "foreign word", "text", "Foreign word"),
    TokenType("IN", "preposition", "function", "Preposition or subordinating conjunction"),
    TokenType("JJ", "adjective", "property", "Adjective"),
    TokenType("JJR", "adjective", "property", "comparative"),
    TokenType("JJS", "adjective", "property", "superlative"),
    TokenType("LS", "punctuation", "function", "List item marker"),
    TokenType("MD", "adverb", "property", "Modal"),
    TokenType("NN", "noun", "type", "singular or mass"),
    TokenType("NNS", "noun", "type", "plural"),
    TokenType("NNP", "name", "entity", "Proper noun singular"),
    TokenType("NNPS", "name", "entity", "Proper noun plural"),
    TokenType("PDT", "determiner", "entity", "Predeterminer"),
    TokenType("POS", "determiner", "entity", "Possessive ending"),
    TokenType("PRP", "personal pronoun", "entity", "Personal pronoun"),
    TokenType("PRP$", "possessive pronoun", "entity", "Possessive pronoun"),
    TokenType("RB", "adverb", "property", "adverb"),
    TokenType("RBR", "adverb", "property", "comparative"),
    TokenType("RBS", "adverb", "property", "superlative"),
    TokenType("RP", "punctuation", "function", "Particle"),
    TokenType("SYM", "punctuation", "function", "Symbol"),
    TokenType("TO", "preposition", "function", "TO preposition"),
    TokenType("UH", "interjection", "view", "Interjection"),
    TokenType("VB", "verb", "function", "base form"),
    TokenType("VBD", "verb", "function", "past tense"),
    TokenType("VBG", "verb", "function", "gerund or present participle"),
    TokenType("VBN", "verb", "function", "past participle"),
    TokenType("VBP", "verb", "function", "non-3rd person singular present"),
    TokenType("VBZ", "verb", "function", "3rd person singular present"),
    TokenType("WDT", "determiner", "entity", "WH determiner"),
    TokenType("WP", "pronoun", "entity", "WH pronoun"),
    TokenType("WP$", "possessive pronoun", "entity", "Possessive WH pronoun"),
    TokenType("WRB", "adverb", "property", "WH adverb")
)

private val punctuationTypesMap = createIdTypeMap(*"\"'`()[]{},.!?:;".map{PunctuationType(it)}.toTypedArray())

private val allTypesMap = createAllTypesMap()

private fun createAllTypesMap(): Map<String, LevelType> {
    val map = mutableMapOf<String, LevelType>()
    map.putAll(clauseTypesMap)
    map.putAll(phraseTypesMap)
    map.putAll(tokenTypesMap)
    map.putAll(punctuationTypesMap)
    return map
}

private fun <T : LevelType> createIdTypeMap(vararg items: T): Map<String,T> {
    return mapOf(*items.map{Pair(it.id,it)}.toTypedArray())
}

private fun compileTokens(tokens: List<String>, index: AtomicInteger = AtomicInteger()): Map<String,Any?> {
    val map = mutableMapOf<String,Any?>()
    while (index.get() < tokens.size) {
        val at = index.getAndIncrement()
        val token = tokens[at]
        if (token == "ROOT")
            return compileTokens(tokens, index)
        if (token == ")")
            break
        if (token == "(") {
            if (map.isEmpty())
                continue
            val key = if (map.containsKey("child")) "child$at" else "child"
            map[key] = compileTokens(tokens, index)
        }
        if (map.isEmpty()) {
            val type = allTypesMap[token] ?: throw RuntimeException("Unknown token type: $token")
            map["type"] = "${type.id}=${type.label}"
            map["level"] = type::class.simpleName!!.replace("Type", "").lowercase()
        }
        else {
            val key = if (map.containsKey("word")) "word$at" else "word"
            map[key] = token
        }
    }
    return map
}
