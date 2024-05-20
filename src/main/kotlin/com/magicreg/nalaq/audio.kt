package com.magicreg.nalaq

import ai.picovoice.cheetah.Cheetah
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.*
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*

fun speechInputStream(uri: URI?): BufferedReader {
    val key = uri?.toString() ?: ""
    val oldReader = speechInputMap[key]
    if (oldReader != null)
        return BufferedReader(oldReader)
    val input = if (uri == null) microphoneInput() else bufferedAudioInput(uri.toURL().openStream())
    val reader = SpeechReader(input)
    speechInputMap[key] = reader
    reader.start()
    return BufferedReader(reader)
}

fun stopSpeechInput(uri: URI?): Boolean {
    val old = speechInputMap.remove(uri?.toString() ?: "") ?: return false
    old.close()
    return true
}

class SpeechReader(val audioStream: AudioInputStream): Reader() {
    val configuration = getContext().configuration
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
            if (!processor.isRunning()) {
                start()
                Thread.sleep(threadWaitTime)
            }
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
                    Thread.sleep(threadWaitTime)
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

class PicoVoiceSpeechProcessor(private val reader: SpeechReader): SpeechProcessor {
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
        reader.audioStream.use { input ->
            val cheetah = Cheetah.Builder()
                .setAccessKey(reader.configuration.picoAccessKey)
                .setLibraryPath(Cheetah.LIBRARY_PATH)
                .setModelPath(Cheetah.MODEL_PATH)
                .setEndpointDuration(2f) // seconds
                .setEnableAutomaticPunctuation(false)
                .build()
            val frameLength = cheetah.frameLength;
            val captureBuffer = ByteBuffer.allocate(frameLength * 2);
            captureBuffer.order(ByteOrder.LITTLE_ENDIAN);
            val cheetahBuffer = ShortArray(frameLength)
            var numBytesRead = 0
            while (running.get() && input.read(captureBuffer.array()).also { numBytesRead = it } >= 0) {
                if (numBytesRead != frameLength * 2)
                    continue
                captureBuffer.asShortBuffer().get(cheetahBuffer)
                val transcriptObj = cheetah.process(cheetahBuffer);
                reader.write(transcriptObj.transcript.lowercase())
                if (transcriptObj.isEndpoint) {
                    val finalTranscriptObj = cheetah.flush();
                    reader.write(finalTranscriptObj.transcript.lowercase() + "\n")
                }
            }
            cheetah.delete()
            Thread.sleep(threadWaitTime)
            running.set(false)
            stopped.set(true)
        }
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
        bytes = output.toByteArray()
        return bytes.size
    }

    fun outputAudio(output: OutputStream, type: AudioFileFormat.Type): Int {
        if (bytes.isEmpty())
            return 0
        val audio = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
        return AudioSystem.write(audio, type, output)
    }
}

private const val threadWaitTime = 100L
private const val sampleRate = 16000F
private const val sendPartial = false
private val speechInputMap = mutableMapOf<String,SpeechReader>()
private var voskModel: Model? = null
private val audioFormats = arrayOf<List<String>>(
    "audio/x-aiff,aif,aiff,aifc".split(","),
    "audio/basic,au,snd".split(","),
    "audio/x-wav,wav".split(","),
    "audio/mpeg,mp3,mpga,mpega,mp1,mp2".split(","),
    "audio/ogg,oga,ogg,opus,spx".split(",")
)
private val audioFormatsMap = initAudioFormats()

private fun speechProcessor(reader: SpeechReader): SpeechProcessor {
    return when (val selectedEngine = reader.configuration.speechEngine) {
        SpeechEngine.VOSK -> VoskSpeechProcessor(reader)
        SpeechEngine.PICO -> PicoVoiceSpeechProcessor(reader)
        else -> throw RuntimeException("Unknown speech engine: $selectedEngine")
    }
}

private fun microphoneInput(): AudioInputStream {
    val format = AudioFormat(sampleRate, 16, 1, true, false)
    val targetLine = AudioSystem.getTargetDataLine(format)
    targetLine.open(format)
    targetLine.start()
    return AudioInputStream(targetLine)
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
    if (voskModel == null) {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val uri = reader.configuration.voskSpeechModel ?: throw RuntimeException("Vosk speech model is not configured")
        if (uri.scheme != "file")
            throw RuntimeException("Vosk speech model file must be a local file")
        voskModel = Model(uri.path)
    }
    return voskModel!!
}

private fun initAudioFormats(): Map<String,List<String>> {
    val map = mutableMapOf<String,List<String>>()
    for (format in audioFormats) {
        for (label in format)
            map[label] = format
    }
    return map
}
