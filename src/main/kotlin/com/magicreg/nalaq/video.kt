package com.magicreg.nalaq

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.*
import javax.sound.sampled.AudioFileFormat


fun getMediaFormats(): List<MediaFormat> {
    return mediaFormats
}

class MediaFormat(data: MediaData): Format {
    override val mimetype: String = data.mimetype
    override val extensions: List<String> = data.extensions.toList()
    override val scripting: Boolean = false
    override val supported: Boolean = true
    val description = data.description
    val canDecode = data.decode
    val canEncode = data.encode

    override fun decode(input: InputStream, charset: String): Any? {
        if (!canDecode)
            throw RuntimeException("Cannot decode $mimetype $description")
        val bytes = input.readAllBytes()
        val streams = probeStreams(bytes)
        if (streams.isEmpty())
            throw RuntimeException("No data found in stream for $mimetype")
        if (streams.size == 1 && streams[0].type == MediaStreamType.AUDIO) {
            val stream = AudioStream()
            val audio = if (streams[0].encoding == "pcm")
                ByteArrayInputStream(bytes)
            else
                runCommand(listOf("ffmpeg", "-i", "pipe:0", "-f", "wav", "-c", "pcm_s16le", "pipe:1"), bytes)
            stream.inputAudio(audio)
            return stream
        }
        throw RuntimeException("Video decoding not implemented yet")
    }

    override fun encode(value: Any?, output: OutputStream, charset: String) {
        if (!canEncode)
            throw RuntimeException("Cannot encode $mimetype $description")
        if (mimetype.split("/")[0] == "audio") {
            val audio = toAudioStream(value)
            // TODO: check the format of the audio if it is compatible with the mimetype and call ffmpeg to convert it if needed
            audio.outputAudio(output, AudioFileFormat.Type.WAVE)
        }
        else
            throw RuntimeException("Video encoding not implemented yet")
    }

    override fun toString(): String {
        return "Format($mimetype)"
    }
}

data class MediaData(
    val mimetype: String,
    var description: String
) {
    val extensions = mutableSetOf<String>()
    var decode: Boolean = false
    var encode: Boolean = false
}

private data class MediaStream(
    val type: MediaStreamType,
    val encoding: String,
    val encodingSize: String
)

private enum class MediaStreamType { AUDIO, VIDEO, SUBTITLE, OTHER }
private val mediaTypes = "audio,video".split(",")
private val mediaFormats = initMediaFormats()

private fun initMediaFormats(): List<MediaFormat> {
    val ext2mime = mutableMapOf<String,String>()
    for (entry in loadAllMimetypes().entries) {
        if (mediaTypes.contains(entry.key.split("/")[0])) {
            for (ext in entry.value)
                ext2mime[ext] = entry.key
        }
    }

    val medias = mutableMapOf<String,MediaData>()
    val lines = runCommand(listOf("ffprobe","-formats")).readAllBytes().toText().split("\n")
    var started = false

    for (line in lines) {
        if (!started || line.isBlank()) {
            if (line.trim() == "--")
                started = true
            continue
        }
        val tokenizer = StringTokenizer(line)
        val definition = mutableListOf<String>()
        val description = mutableListOf<String>()
        while (tokenizer.hasMoreElements()) {
            val token = tokenizer.nextElement().toString()
            if (definition.size < 2)
                definition.add(token.lowercase())
            else
                description.add(token)
        }
        val extensions = definition[1].split(",")
        for (ext in extensions) {
            var mime = ext2mime[ext] ?: continue
            val media = medias[mime] ?: MediaData(mime, description.joinToString(" "))
            if (media.extensions.isEmpty())
                medias[mime] = media
            else if (extensions.size == 1)
                media.description = description.joinToString(" ")
            media.extensions.add(ext)
            if (definition[0].contains("d"))
                media.decode = true
            if (definition[0].contains("e"))
                media.encode = true
        }
    }

    val formats = mutableListOf<MediaFormat>()
    for (media in medias.values) {
        formats.add(MediaFormat(media))
        if (media.mimetype == "video/webm") {
            val webm = MediaData("audio/webm", "WebM audio")
            webm.extensions.addAll(listOf("mka","webm"))
            webm.decode = true
            webm.encode = true
            formats.add(MediaFormat(webm))
        }
    }
    return formats
}

private fun probeStreams(bytes: ByteArray): List<MediaStream> {
    val streams = mutableListOf<MediaStream>()
    val response = runCommand(listOf("ffprobe", "pipe:0"), bytes, null).readAllBytes().toString(Charset.forName(defaultCharset()))
    val lines = response.split("\n").filter{it.contains("Stream #")}
    for (line in lines) {
        val parts = line.split(":")
        val type = try { MediaStreamType.valueOf(parts[2].trim().uppercase()) } catch(e: Exception) { MediaStreamType.OTHER }
        val encoding = parts[3].trim().lowercase().split(" ")[0].split("_")
        streams.add(MediaStream(type, encoding[0], if (encoding.size>1) encoding[1] else ""))
    }
    return streams
}