package com.magicreg.nalaq

import java.net.URI

fun createView(uri: URI): View {
    if (uri.scheme == "geo")
        return GeoLocation(uri)
    return when (val type = uri.contentType() ?: detectFileType(uri.path) ?: "text/plain") {
        "image" -> Image(uri)
        "audio" -> Audio(uri)
        "video" -> Video(uri)
        "model" -> Model(uri)
        "chemical" -> Chemical(uri)
        // TODO: text types and known application types could be converted to HTML document
        else -> throw RuntimeException("Unsupported mimetype $type for uri $uri")
    }
}

class Chemical(override val uri: URI): View {
    override val ets = "QET2S-2@PS3"
    override val description = null
    override fun toString() = printView(this)
}

class GeoLocation(override var uri: URI): View {
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
    override val description = "lat:$latitude lon:$longitude alt:$altitude"
    override fun toString() = printView(this)

    fun getCoordinate(index: Int): Double {
        if (index < 0 || index >= parts.size)
            return 0.0
        return parts[index].toDoubleOrNull() ?: 0.0
    }
}

class Image(override val uri: URI): View {
    override val ets = "QE3S-2@PS2"
    override val description = null
    override fun toString() = printView(this)
}

class Audio(override val uri: URI): View {
    override val ets = "QE2T-1@PT"
    override val description = null
    override fun toString() = printView(this)
}

class Video(override val uri: URI): View {
    override val ets = "(QE3T-1S-2@PS2,QE2T-1)@PT"
    override val description = null
    override fun toString() = printView(this)
}

class Model(override val uri: URI): View {
    override val ets = "QE3S-3@PS3"
    override val description = null
    override fun toString() = printView(this)
}

private fun printView(view: View): String {
    if (view.description != null)
        return view.description!!
    return "ets:${view.ets}->${view.uri}" // TODO: we need to support ets uri scheme (maybe with namespace class)
}
