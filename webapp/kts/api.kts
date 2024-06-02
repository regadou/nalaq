import com.magicreg.nalaq.*

fun nalaq(text: String): Any? {
    return getParser(TextParser.NALAQ).parse(text).resolve()
}

fun kotlin(text: String): Any? {
    return getParser(TextParser.KOTLIN).parse(text).resolve()
}

fun translate(text: String, src: String, dst: String): String {
    return (getParser(TextParser.TRANSLATE) as TranslateParser).translate(src, dst, text)
}

mapOf<String,Any?>(
    "/languages" to ::getLanguages,
    "/nalaq" to ::nalaq,
    "/kotlin" to ::kotlin,
    "/translate" to ::translate
)
