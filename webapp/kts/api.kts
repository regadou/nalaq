import com.magicreg.nalaq.*
import javax.sound.sampled.AudioSystem

fun kotlin(text: String): Any? {
    return getParser(TextParser.KOTLIN).parse(text).resolve()
}

fun nalaq(text: String): Any? {
    return getParser(TextParser.NALAQ).parse(text).resolve()
}

fun translate(text: String, src: String, dst: String): String {
    return text.translate(src, dst)
}

fun audioFormats(): List<String> {
    val formats = mutableListOf<String>()
    for (format in AudioSystem.getAudioFileTypes())
        formats.add(getExtensionMimetype(format.extension) ?: "audio/${format.extension}")
    return formats.sorted()
}

fun generateLinks(endpoints: List<String>): String {
    val html = mutableListOf("<html><title>NaLaQ API</title><h1 style='text-align:center'>NaLaQ API</h1>")
    for (endpoint in endpoints)
        html.add("<a href='/api/${endpoint}'>${endpoint}</a><br>")
    html.add("</html>")
    return html.joinToString("\n")
}

mapOf<String,Any?>(
    "/api" to generateLinks("audio,kotlin,languages,nalaq,translate".split(",")),
    "/api/audio" to ::audioFormats,
    "/api/kotlin" to ::kotlin,
    "/api/languages" to ::getLanguages,
    "/api/nalaq" to ::nalaq,
    "/api/translate" to ::translate
)
