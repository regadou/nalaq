package com.magicreg.nalaq

import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.*
import java.net.URI
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*

fun microphoneInput(audioFormat: AudioFormat = defaultAudioFormat): AudioInputStream {
    val targetLine = AudioSystem.getTargetDataLine(audioFormat)
    targetLine.open(audioFormat)
    targetLine.start()
    return AudioInputStream(targetLine)
}

fun speechInputStream(language: String, uri: URI? = null, audioFormat: AudioFormat = defaultAudioFormat): BufferedReader {
    val key = "$language-$uri"
    val oldReader = speechInputMap[key]
    if (oldReader != null)
        return BufferedReader(oldReader)
    val input = if (uri == null) microphoneInput(audioFormat) else bufferedAudioInput(uri.toURL().openStream())
    val reader = SpeechReader(language, input)
    speechInputMap[key] = reader
    reader.start()
    return BufferedReader(reader)
}

fun stopSpeechInput(language: String, uri: URI? = null): Boolean {
    val key = "$language-$uri"
    val old = speechInputMap.remove(key) ?: return false
    old.close()
    return true
}

class SpeechReader(val language: String, val audioStream: AudioInputStream): Reader() {
    val speechModelsFolder = getContext().configuration.speechModelsFolder
    private val buffer = StringBuilder()
    private val processor = speechProcessor(this)
    private var closed = false

    override fun ready(): Boolean {
        return processor.isRunning() && buffer.isNotEmpty()
    }

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        if (buffer.isEmpty()) {
            if (processor.wasStopped() || closed)
                return -1
            if (!processor.isRunning())
                start()
        }
        if (len <= 0)
            return 0
        val toread = len.coerceAtMost(buffer.length)
        buffer.toCharArray(cbuf, off, 0, toread)
        buffer.delete(0, toread)
        return toread
    }

    override fun close() {
        closed = true
        processor.stopRunning()
        audioStream.close()
        buffer.clear()
    }

    fun start() {
        if (!closed && !processor.isRunning() && !processor.wasStopped())
            processor.start()
    }

    fun write(txt: String) {
        buffer.append(txt)
    }
}

class SpeechWriter(language: String, voiceCommandTemplate: String): Writer() {
    private val voiceCommand = createVoiceCommand(language, voiceCommandTemplate)
    private val bufferSize = 1024
    private val buffer = ByteArray(bufferSize)
    private var process: Process? = null

    override fun close() {
        if (process != null) {
            process!!.destroy()
            process = null
        }
    }

    override fun flush() {}

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        val text = String(if (off == 0 && len == cbuf.size) cbuf else CharArray(len) { cbuf[off+it] })
        val input = audioInputStream(text)
        val speakers = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, input.format)) as SourceDataLine
        speakers.open(input.format)
        speakers.start()
        var bytesRead = 0
        do {
            bytesRead = input.read(buffer)
            if (bytesRead > 0)
                speakers.write(buffer, 0, bytesRead)
        } while (bytesRead > 0)
        speakers.drain()
        speakers.close()
    }

    fun audioInputStream(text: String): AudioInputStream {
        close()
        process = startProcess()
        process!!.outputStream.write(text.toByteArray(Charset.forName(defaultCharset())))
        process!!.outputStream.flush()
        process!!.outputStream.close()
        return AudioSystem.getAudioInputStream(process!!.inputStream)
    }

    private fun startProcess(): Process {
        return ProcessBuilder(*voiceCommand)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    }

    private fun createVoiceCommand(language: String, voiceCommandTemplate: String): Array<String> {
        val langObject = getLanguage(language)
        if (langObject == null || langObject.voice.isNullOrBlank())
            throw RuntimeException("Invalid speech language: $language")
        val index = voiceCommandTemplate.indexOf(voiceTemplateVariable)
        val command = if (index < 0) "$voiceCommandTemplate ${langObject.voice}" else voiceCommandTemplate.replace(voiceTemplateVariable, langObject.voice)
        return command.split(" ").toTypedArray()
    }
}

class VoskSpeechProcessor(private val reader: SpeechReader): SpeechProcessor {
    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    override fun isRunning(): Boolean {
        return running.get()
    }

    override fun wasStopped(): Boolean {
        return stopped.get()
    }

    override fun stopRunning() {
        running.set(false)
    }

    override fun start() {
        if (!stopped.get()) {
            running.set(true)
            Thread(this).start()
        }
    }

    override fun run() {
        if (!running.get())
            return
        voskSpeechModel(reader).use { model ->
            reader.audioStream.use { input ->
                Recognizer(model, sampleRate).use { recognizer ->
                    var lastIsPartial = false
                    var count: Int = 0
                    val bytes = ByteArray(4096)
                    while (running.get() && input.read(bytes).also { count = it } >= 0) {
                        if (recognizer.acceptWaveForm(bytes, count)) {
                            reader.write(getText(recognizer.result) + "\n")
                            lastIsPartial = false
                        }
                        else if (sendPartial) {
                            reader.write(getText(recognizer.result) + "\r")
                            lastIsPartial = true
                        }
                        else
                            lastIsPartial = true
                    }
                    if (lastIsPartial)
                        reader.write(getText(recognizer.finalResult) + "\n")
                    running.set(false)
                    stopped.set(true)
                }
            }
        }
    }

    private fun getText(json: String): String {
        return json.split('"')[3]
    }
}

class AudioType(val type: AudioFileFormat.Type): Format {
    private val labels: List<String> = audioFormatsMap[type.extension] ?: throw RuntimeException("Unknown audio extension ${type.extension} from $type")
    override val mimetype: String = labels[0]
    override val extensions: List<String> = labels.subList(1, labels.size)
    override val supported: Boolean = true

    override fun decode(input: InputStream, charset: String): Any? {
        val stream = AudioStream()
        stream.inputAudio(input)
        return stream
    }

    override fun encode(value: Any?, output: OutputStream, charset: String) {
        val audio = toAudioStream(value)
        audio.outputAudio(output, type)
    }

    override fun toString(): String {
        return "Format<$mimetype>(${extensions.joinToString(",")})"
    }
}

class AudioStream {
    private var format: AudioFormat = AudioFormat(0f, 0, 0, true, true)
    private var bytes: ByteArray = byteArrayOf()
    private var frameLength: Long = 0
    val audioFormat: AudioFormat get() = format
    val size: Int get() = bytes.size
    val frames: Long get() = frameLength

    fun audioInputStream(): AudioInputStream {
        return AudioInputStream(ByteArrayInputStream(bytes), format, frameLength)
    }

    fun inputAudio(input: InputStream): Int {
        val audio = bufferedAudioInput(input)
        format = audio.format
        frameLength = audio.frameLength
        val output = ByteArrayOutputStream()
        val bufferSize = format.sampleRate * format.frameSize
        audio.copyTo(output, bufferSize.toInt())
        bytes = output.toByteArray() // TODO: bytes should be added, not replaced
        return bytes.size
    }

    fun outputAudio(output: OutputStream, type: AudioFileFormat.Type): Int {
        if (bytes.isEmpty())
            return 0
        val audio = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
        return AudioSystem.write(audio, type, output)
    }
}

private const val sampleRate = 16000F
private const val sendPartial = false
private const val voiceTemplateVariable = "{voice}"
private val defaultAudioFormat = AudioFormat(sampleRate, 16, 1, true, false)
private val speechInputMap = mutableMapOf<String,SpeechReader>()
private var voskModelMap = mutableMapOf<String,Model>()
private val audioFormats = arrayOf<List<String>>(
    "audio/x-aiff,aif,aiff,aifc".split(","),
    "audio/basic,au,snd".split(","),
    "audio/x-wav,wav".split(","),
    "audio/mpeg,mp3,mpga,mpega,mp1,mp2".split(","),
    "audio/ogg,oga,ogg,opus,spx".split(",")
)
private val audioFormatsMap = initAudioFormats()

private fun speechProcessor(reader: SpeechReader): SpeechProcessor {
    return VoskSpeechProcessor(reader)
}

private fun bufferedAudioInput(input: InputStream): AudioInputStream {
    return if (input is AudioInputStream)
        input
    else if (input is BufferedInputStream)
        AudioSystem.getAudioInputStream(input)
    else
        AudioSystem.getAudioInputStream(BufferedInputStream(input))
}

private fun voskSpeechModel(reader: SpeechReader): Model {
    if (voskModelMap.containsKey(reader.language))
        return voskModelMap[reader.language]!!
    LibVosk.setLogLevel(LogLevel.WARNINGS)
    val modelsFolder = reader.speechModelsFolder ?: throw RuntimeException("Vosk speech model is not configured")
    val file = File(modelsFolder).canonicalFile
    if (!file.isDirectory) {
        val state = if (file.exists()) "is not a directory" else "does not exist"
        throw RuntimeException("Speech model folder $state: $file")
    }
    val lang = getLanguage(reader.language) ?: throw RuntimeException("Unsupported language")
    if (lang.model.isNullOrBlank())
        throw RuntimeException("Vosk speech model is not not define for this language: $lang")
    val model = Model("$file/${lang.model}")
    voskModelMap[reader.language] = model
    return model
}

private fun initAudioFormats(): Map<String,List<String>> {
    val map = mutableMapOf<String,List<String>>()
    for (format in audioFormats) {
        for (label in format)
            map[label] = format
    }
    return map
}
