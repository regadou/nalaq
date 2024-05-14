package com.magicreg.nalaq

import java.net.URI

fun createView(uri: URI): View {
    if (uri.scheme == "geo")
        return GeoLocation(uri)
    val type = uri.contentType() ?: uri.path.fileType() ?: "text/plain"
    return when (type) {
        "image" -> Image(uri)
        "audio" -> Audio(uri)
        "video" -> Video(uri)
        "model" -> Model(uri)
        "chemical" -> Chemical(uri)
        else -> throw RuntimeException("Unsupported mimetype $type for uri $uri")
    }
}

abstract class View {
    abstract val uri: URI
    abstract val ets: String
    override fun toString(): String {
        return "ets:$ets->$uri" // TODO: we need to support ets uri scheme (maybe with namespace class)
    }
}

class Chemical(override val uri: URI): View() {
    override val ets = "QET2S-2@PS3"
}

class GeoLocation(override var uri: URI): View() {
    // TODO: move it to a space.kt file with shape classes (Path, Area and Volume)
    init {
        if (uri.scheme != "geo")
            throw RuntimeException("Invalid uri scheme for geolocation: ${uri.scheme}")
    }
    override val ets = "PS3"
    private val parts = uri.toString().substring(4).split(";")[0].split(",")
    val latitude = getCoordinate(0)
    val longitude = getCoordinate(1)
    val altitude = getCoordinate(2)

    fun getCoordinate(index: Int): Double {
        if (index < 0 || index >= parts.size)
            return 0.0
        return parts[index].toDoubleOrNull() ?: 0.0
    }
}

class Image(override val uri: URI): View() {
    override val ets = "QE3S-2@PS2"
}

class Audio(override val uri: URI): View() {
    override val ets = "QE2T-1@PT"
}

class Video(override val uri: URI): View() {
    override val ets = "(QE3T-1S-2@PS2,QE2T-1)@PT"
}

class Model(override val uri: URI): View() {
    override val ets = "QE3S-3@PS3"
}
