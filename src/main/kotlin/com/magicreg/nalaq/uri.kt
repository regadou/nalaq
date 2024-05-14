package com.magicreg.nalaq

import eu.maxschuster.dataurl.DataUrl
import eu.maxschuster.dataurl.DataUrlSerializer
import java.io.*
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

fun isBuiltinUriScheme(scheme: String): Boolean {
    return URI_SCHEMES.contains(scheme)
}

fun URI.readOnly(): Boolean {
    val uri = resolveRelativeTemplate(this)
    if (uri.scheme == "data" && uri.toString().indexOf(",") < 0)
        return false
    return getNamespace(uri.scheme)?.readOnly ?: READONLY_URI_SCHEMES.contains(uri.scheme)
}

fun URI.contentType(): String? {
    return when (this.scheme) {
        "data" -> this.toString().split(",")[0].split(";")[0].split(":")[1]
        "file" -> if (getContext().realFile(this.path).isDirectory) "inode/directory" else this.path.fileType()
        "ftp", "sftp", "ftps" -> this.path.fileType()
        "http", "https" -> {
            val type = this.toURL().openConnection().contentType
            if (type == null) null else type.split(";")[0].trim()
        }
        else -> null
    }
}

fun URI.get(headers: Map<String,String> = mapOf()): Any? {
    val uri = resolveRelativeTemplate(this)
    return when(uri.scheme) {
        "http", "https" -> httpRequest("get", uri, headers)
        "file" -> getFile(uri.path)
        "data" -> decodeData(uri.toString())
        "jdbc" -> try { Database(uri.toString()) } catch (e: Exception) { e }
        "nalaq" -> getNaLaQValue(uri.toString(), uri.query, uri.fragment)
        "geo"-> GeoLocation(uri)
        "sftp" -> RuntimeException("Uri scheme not implemented yet for GET: ${uri.scheme}")
        // TODO: mailto and sip could do contact search, check mailbox, voicemail, messages, log history, ...
        else -> {
            val ns = getNamespace(uri.scheme)
            if (ns != null) ns.value(uri.toString()) else RuntimeException("Unsupported uri scheme for GET: ${uri.scheme}")
        }
    }
}

fun URI.post(value: Any?, headers: Map<String,String> = mapOf()): Any? {
    val uri = resolveRelativeTemplate(this)
    return when(uri.scheme) {
        "http", "https" -> httpRequest("post", uri, headers,  value)
        "file" -> postFile(uri.path, value, headers)
        "data" -> encodeData(uri.toString(), value)
        "sftp", "mailto", "sip" -> throw RuntimeException("Uri scheme not implemented yet for POST: ${uri.scheme}")
        else -> getNamespace(uri.scheme)?.setValue(uri.toString(), value)
                ?: RuntimeException("Unsupported uri scheme for POST: ${uri.scheme}")
    }
}

fun URI.put(value: Any?, headers: Map<String,String> = mapOf()): Any? {
    val uri = resolveRelativeTemplate(this)
    return when(uri.scheme) {
        "http", "https" -> httpRequest("put", uri, headers,  value)
        "file" -> putFile(uri.path, value, headers)
        "data" -> encodeData(uri.toString(), value)
        "sftp", "mailto", "sip" -> throw RuntimeException("Uri scheme not implemented yet for PUT: ${uri.scheme}")
        else -> getNamespace(uri.scheme)?.setValue(uri.toString(), value)
                ?: RuntimeException("Unsupported uri scheme for PUT: ${uri.scheme}")
    }
}

fun URI.delete(headers: Map<String,String> = mapOf()): Any? {
    val uri = resolveRelativeTemplate(this)
    return when(uri.scheme) {
        "http", "https" -> httpRequest("delete", uri, headers)
        "file" -> deleteFile(uri.path)
        "sftp" -> RuntimeException("Uri scheme not implemented yet for DELETE: ${uri.scheme}")
        else -> getNamespace(uri.scheme)?.setValue(uri.toString(), null)
                ?: RuntimeException("Unsupported uri scheme for DELETE: ${uri.scheme}")
    }
}

private const val TEXT_FORMAT = "text/plain"
private const val DATA_FORMAT = "application/json"
private const val CONTENT_TYPE_HEADER = "content-type"
private val URI_SCHEMES = "http,https,file,data,geo,nalaq,jdbc,sftp,mailto,sip".split(",")
private val READONLY_URI_SCHEMES = "data,geo,nalaq".split(",")
private val DATA_SERIALIZER = DataUrlSerializer()

private fun resolveRelativeTemplate(uri: URI): URI {
    // TODO: find out template sections and resolve variables
    if (uri.scheme != null)
        return uri
    if (!contextInitialized())
        throw RuntimeException("Context is not initialized for relative uris, please use an absolute uri with a specified scheme")
    val cx = getContext()
    if (cx.uri.startsWith("file"))
        return cx.fileUri(File(uri.path))
    var path = uri.toString()
    if (path.startsWith("/"))
        return URI("${cx.uri}$path")
    val fullPath = File(cx.parentFolder(), path)
    return URI("${cx.uri}$fullPath")
}

private fun getNaLaQValue(uri: String, query: String?, fragment: String?): Any? {
    val txt = URLDecoder.decode(uri.substring(uri.indexOf(":")+1).split("#")[0].split("?")[0], defaultCharset())
    var value = txt.toExpression().resolve()
    if (!query.isNullOrBlank())
        value = toFilter(getFormat("form")!!.decodeText(query)).filter(value).simplify().resolve()
    if (!fragment.isNullOrBlank())
        value = value.type().property(fragment, value).getValue(value).resolve()
    return value
}

private fun httpRequest(method: String, uri: URI, headers: Map<String,String>, value: Any? = null): Any? {
    val requestBuilder = HttpRequest.newBuilder().uri(uri)
    for (name in headers.keys)
        requestBuilder.setHeader(name, headers[name])
    val request = when (method) {
        "get" -> requestBuilder.GET()
        "put" -> requestBuilder.PUT(HttpRequest.BodyPublishers.ofInputStream(prepareData(value, requestBuilder, headers[CONTENT_TYPE_HEADER])))
        "post" -> requestBuilder.POST(HttpRequest.BodyPublishers.ofInputStream(prepareData(value, requestBuilder, headers[CONTENT_TYPE_HEADER])))
        "delete" -> requestBuilder.DELETE()
        else -> return RuntimeException("Invalid HTTP method: $method")
    }
    val response = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build().send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
    if (response.statusCode() >= 400)
        return RuntimeException("Error with $uri GET: "+response.statusCode())
    val parts = response.headers().firstValue("content-type").orElse(TEXT_FORMAT).split(";")
    val charset = parts.firstOrNull { it.contains("charset=")}?.split("=")?.get(1)?.trim() ?: defaultCharset()
    return decode(parts[0].trim(), response.body(), charset)
}

private fun prepareData(value: Any?, requestBuilder: HttpRequest.Builder, headerType: String?): () -> InputStream {
    val type = headerType ?: DATA_FORMAT
    requestBuilder.setHeader(CONTENT_TYPE_HEADER, type)
    val output = ByteArrayOutputStream()
    encode(value, type, output)
    return { ByteArrayInputStream(output.toByteArray()) }
}

private fun getFile(filename: String?): Any? {
    val file = if (contextInitialized()) getContext().realFile(filename ?: "") else File(filename)
    return if (file.isDirectory)
        file.listFiles().map { it.toURI() }
    else if (file.exists())
        decode(file.toString().fileType(), FileInputStream(file))
    else
        null
}

private fun putFile(filename: String?, value: Any?, headers: Map<String,String>): Any? {
    val cx = getContext()
    val file = cx.realFile(filename ?: "")
    if (canSaveFile(file)) {
        val type = headers["content-type"] ?: file.toString().fileType()
        encode(value, type, FileOutputStream(file))
        return cx.fileUri(File(filename))
    }
    else
        return null
}

private fun postFile(filename: String?, value: Any?, headers: Map<String,String>): Any? {
    val cx = getContext()
    val file = cx.realFile(filename ?: "")
    if (file.isDirectory) {
        val type = headers["content-type"] ?: if (value is CharSequence || value is Number || value is Boolean) TEXT_FORMAT else DATA_FORMAT
        val name = Math.random().toString().split(".")[1] + "." + getMimetypeExtensions(type)[0]
        val newFile = File("$file/$name")
        encode(value, file.toString().fileType(), FileOutputStream(newFile))
        return cx.fileUri(newFile)
    }
    return putFile(filename, value, headers)
}

private fun deleteFile(filename: String?): Boolean {
    val cx = getContext()
    val file = cx.realFile(filename ?: "")
    if (!file.exists())
        return false
    if (file.isDirectory) {
        for (f in file.listFiles())
            deleteFile(cx.fileUri(f).path)
    }
    return file.delete()
}

private fun canSaveFile(file: File): Boolean {
    if (file.isDirectory)
        return false
    if (file.exists())
        return true
    if (file.parentFile.exists())
        return true
    return file.parentFile.mkdirs()
}

private fun decodeData(uri: String): Any? {
    val ending = if (uri.indexOf(",") < 0) "," else ""
    val uridata = DATA_SERIALIZER.unserialize(uri+ending)
    val charset = if (uridata.encoding.name == "URL") defaultCharset() else uridata.encoding.name
    return try { decode(uridata.mimeType, ByteArrayInputStream(uridata.data), charset) } catch (e: Exception) { e }
}

private fun encodeData(uri: String, value: Any?): Any? {
    val txt = if (uri.contains(",")) uri else "$uri,"
    if (txt == "data:," && value.isText())
        return value.toText().detectFormat(true) ?: throw RuntimeException("Cannot detect format for string:\n$value")
    val uridata = DATA_SERIALIZER.unserialize(txt)
    val output = ByteArrayOutputStream()
    encode(value, uridata.mimeType, output, defaultCharset())
    val outdata = DataUrl(output.toByteArray(), uridata.encoding, uridata.mimeType)
    return DATA_SERIALIZER.serialize(outdata).toUri().toString()
}

private fun decode(type: String?, input: InputStream, charset: String = defaultCharset()): Any? {
    val mimetype = type ?: TEXT_FORMAT
    val format = (getFormat(mimetype) ?: getFormat(TEXT_FORMAT))!!
    val value = format.decode(input, charset)
    input.close()
    return value
}

private fun encode(value: Any?, type: String?, output: OutputStream, charset: String = defaultCharset()) {
    val mimetype = type ?: TEXT_FORMAT
    val format = (getFormat(mimetype) ?: getFormat(TEXT_FORMAT))!!
    format.encode(value, output, charset)
    output.flush()
    output.close()
}

