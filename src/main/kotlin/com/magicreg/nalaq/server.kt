package com.magicreg.nalaq

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.sessions.*
import io.ktor.util.StringValues
import java.io.*
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneOffset

fun startServer(config: Configuration) {
    val topContext = getContext()
    if (config.serverPort == null || config.serverPort < 1)
        throw RuntimeException("Invalid server port: ${config.serverPort}")
    embeddedServer(Netty, port = config.serverPort) {
        install(Sessions) {cookie<WebSession>("WEB_SESSION", storage = SessionStorageMemory())}
        routing {route("/{path...}") {handle{ processRequest(topContext, call)}}}
        println("Server listening on port ${config.serverPort}...")
    }.start(true)
}

fun Any?.response(vararg args: Any): ResponseData {
    var status: Int? = null
    var type: String? = null
    for (arg in args) {
        if (arg is Number)
            status = arg.toInt()
        else if (arg.isText())
            type = arg.toText()
    }
    return ResponseData(status ?: StatusCode_OK, type ?: DEFAULT_MIMETYPE, this)
}

data class WebSession(val id: String)
data class ResponseData(val status: Int?, val type: String, val data: Any?)
data class HttpRequest(
    val host: String,
    val method: String,
    val path: String,
    val version: String,
    val query: Map<String,Any>,
    val headers: Map<String,Any>,
    val remoteHost: String,
    val inputStream: InputStream?
)

private const val StatusCode_OK = 200
private const val StatusCode_Created = 201
private const val StatusCode_Accepted = 202
private const val StatusCode_NoContent = 204
private const val StatusCode_MovedPermanently = 301
private const val StatusCode_Found = 302
private const val StatusCode_BadRequest = 400
private const val StatusCode_Unauthorized = 401
private const val StatusCode_Forbidden = 403
private const val StatusCode_NotFound = 404
private const val StatusCode_MethodNotAllowed = 405
private const val StatusCode_Conflict = 409
private const val StatusCode_InternalServerError = 500
private const val StatusCode_NotImplemented = 501
private const val StatusCode_BadGateway = 502
private const val StatusCode_ServiceUnavailable = 503
private const val StatusCode_GatewayTimeout = 504

private const val DEBUG = true
private const val DEFAULT_MIMETYPE = "text/html"
private const val JSON_MIMETYPE = "application/json"
private val INDEX_TYPES = arrayOf("html", "htm", "json", "txt", "kts", "nalaq", "nlq")
private val METHODS_WITH_BODY = arrayOf("POST", "PUT")
private val INTERNAL_SERVER_ERROR = HttpStatusCode(StatusCode_InternalServerError, "Internal Server Error")

private suspend fun processRequest(topcx: Context, call: ApplicationCall) {
    val webcx = getSessionContext(call, topcx)
    try {
        val method = call.request.httpMethod.value.uppercase()
        val inputStream = if (METHODS_WITH_BODY.contains(method)) call.receiveStream() else null
        val request =  HttpRequest(
            call.request.host(),
            method,
            call.request.path(),
            call.request.httpVersion,
            getMap(call.request.queryParameters, false),
            getMap(call.request.headers, true),
            call.request.origin.remoteAddress,
            inputStream)
        val data = httpRequest(webcx, request)
        val parts = data.type.split("/")
        val contentType = ContentType(parts[0], parts[1], listOf(HeaderValueParam("charset", "utf8")))
        val status = HttpStatusCode.fromValue(data.status ?: StatusCode_OK)
        if (status == HttpStatusCode.Found)
            call.respondRedirect(data.data.toString(), false)
        else if (status == HttpStatusCode.MovedPermanently)
            call.respondRedirect(data.data.toString(), true)
        else if (data.data is File)
            call.respondOutputStream(contentType, status) { FileInputStream(data.data).copyTo(this) }
        else {
            val bytes = if (data.data is ByteArray) data.data else data.data.toString().toByteArray()
            call.respondBytes(bytes, contentType, status)
        }
    }
    catch (e: Exception) {
        val stacktrace = getStacktrace(e)
        System.err.println(stacktrace)
        val msg = if (!DEBUG) e.toString() else "<html><body><h2 style='text-align:center'>System error</h2><pre>\n$stacktrace\n</pre></body></html>"
        call.respondText(msg+"\n", ContentType.Text.Html, INTERNAL_SERVER_ERROR)
    }
    webcx.close(false)
}

private fun httpRequest(cx: Context, request: HttpRequest): ResponseData {
    logRequest(request.method, request.path, request.remoteHost)
    return when (request.method) {
        "GET" -> getRequest(cx, request)
        "POST" -> postRequest(cx, request)
        "PUT" -> putRequest(cx, request)
        "DELETE" -> deleteRequest(cx, request)
        else -> ResponseData(StatusCode_MethodNotAllowed, DEFAULT_MIMETYPE, "")
    }
}

private fun getRequest(cx: Context, request: HttpRequest): ResponseData {
    val baseuri = cx.configuration.webFolder ?: "."
    if (request.path == "/")
        return printLinks(File(baseuri).list(), "/", baseuri, request.query)
    return getFileContent(cx, request, request.path)
}

private fun getFileContent(cx: Context, request: HttpRequest, filename: String): ResponseData {
    val baseuri = cx.configuration.webFolder ?: "."
    val file = File(baseuri+filename)
    if (file.exists()) {
        if (file.isDirectory) {
            if (filename[filename.length-1] != '/')
                return ResponseData(StatusCode_MovedPermanently, DEFAULT_MIMETYPE, "$filename/")
            for (type in INDEX_TYPES) {
                if (File(baseuri+filename+"index."+type).exists())
                    return getFileContent(cx, request,filename+"index."+type)
            }
            return printLinks(file.list(), filename, baseuri, request.query)
        }
        return executeScript(cx, request, file) ?: ResponseData(StatusCode_OK, file.toURI().contentType() ?: DEFAULT_MIMETYPE, file)
    }
    return ResponseData(StatusCode_NotFound, DEFAULT_MIMETYPE, "$filename not found")
}

private fun postRequest(cx: Context, request: HttpRequest): ResponseData {
    val baseuri = cx.configuration.webFolder ?: "."
    val filename = request.path
    val file = File(baseuri+filename)
    if (file.exists()) {
        if (file.isDirectory) {
            if (filename[filename.length-1] != '/')
                return ResponseData(StatusCode_MovedPermanently, DEFAULT_MIMETYPE, "$filename/")
            for (type in INDEX_TYPES) {
                if (File(baseuri+filename+"index."+type).exists())
                    return postFileExecute(cx, request, File(filename+"index."+type))
            }
            val type = request.headers["content-type"]?.toString()?.split(";")?.get(0)?.trim() ?: DEFAULT_MIMETYPE
            val name = Math.random().toString().split(".")[1] + "." + getMimetypeExtensions(type)[0]
            FileOutputStream("$file/$name").use { output ->
                request.inputStream?.copyTo(output)
                output.close()
            }
            return ResponseData(StatusCode_OK, DEFAULT_MIMETYPE, "$filename/$name")
        }
        return postFileExecute(cx, request, file)
    }
    return ResponseData(StatusCode_NotFound, DEFAULT_MIMETYPE, "$filename not found")
}

private fun postFileExecute(cx: Context, request: HttpRequest, file: File): ResponseData {
    return executeScript(cx, request, file) ?: ResponseData(StatusCode_BadRequest, DEFAULT_MIMETYPE, "Cannot add content to $file")
}

private fun putRequest(cx: Context, request: HttpRequest): ResponseData {
    if (request.path == "/")
        return ResponseData(StatusCode_MethodNotAllowed, DEFAULT_MIMETYPE, "")
    val baseuri = cx.configuration.webFolder ?: "."
    val filename = request.path
    val file = File(baseuri+filename)
    if (file.exists()) {
        if (file.isDirectory)
            return ResponseData(StatusCode_BadRequest, DEFAULT_MIMETYPE, "Cannot add content to $filename")
        // TODO: add script support or overwrite file or block from overwriting file
        FileOutputStream("$file").use { output ->
            request.inputStream?.copyTo(output)
            output.close()
        }
        return ResponseData(StatusCode_OK, DEFAULT_MIMETYPE, "$filename")
    }
    return ResponseData(StatusCode_NotFound, DEFAULT_MIMETYPE, "$filename not found")
}

private fun deleteRequest(cx: Context, request: HttpRequest): ResponseData {
    if (request.path == "/")
        return ResponseData(StatusCode_MethodNotAllowed, DEFAULT_MIMETYPE, "")
    val filename = request.path
    val baseuri = cx.configuration.webFolder ?: "."
    val file = File(baseuri+filename)
    // TODO: add script support or delete file or block from deleting file
    val result = if (file.exists()) file.delete() else false
    return ResponseData(StatusCode_OK, JSON_MIMETYPE, result)
}

private fun getSessionContext(call: ApplicationCall, parent: Context): Context {
    val session : WebSession? = call.sessions.get<WebSession>()
    var local = call.request.local
    val baseuri = URI("${local.scheme}://${local.serverHost}:${local.serverPort}")
    val cx = findContext(session?.id, true) ?: getContext(parent, baseuri)
    if (session == null)
        call.sessions.set(WebSession(cx.name))
    cx.requestUri = local.uri.split("?")[0]
    return cx
}

private fun getStacktrace(e: Exception): String {
    val output = ByteArrayOutputStream()
    val pw = PrintWriter(output)
    e.printStackTrace(pw)
    pw.flush()
    return output.toString()
}

private fun getMap(values: StringValues, normalize: Boolean): Map<String,Any> {
    val map = mutableMapOf<String,Any>()
    values.forEach { k, list ->
        val key = if (normalize) k.normalize() else k
        val value = list.simplify()
        if (value != null)
            map[key] = value
    }
    return map
}

private fun logRequest(method: String, uri: String, remote: String) {
    println(printTime(System.currentTimeMillis())+" "+remote+" "+method+" "+uri)
}

private fun printTime(millis: Long): String {
    val zone = ZoneOffset.systemDefault()
    val offSet = zone.rules.getOffset(LocalDateTime.now())
    val t = LocalDateTime.ofEpochSecond(millis/1000, (millis%1000).toInt()*1000, offSet)
    return t.toString().split(".")[0].split("T").joinToString(" ")
}

private fun printLinks(links: Array<String>, prefix: String, baseuri: String, query: Map<String,Any?>?): ResponseData {
    val filter =  if (query == null || query.isEmpty()) null else toFilter(query)
    val files = links.map { File("$baseuri$prefix$it") }.sortedBy { "${!it.isDirectory}-${it.name.lowercase()}" }.filter { filter?.filter(listOf(it))?.isNotEmpty() ?: true }
    var html = "<table border=0 cellpadding=2 cellspacing=2>"
    for (file in files) {
        val name = file.name
        val size = if (file.exists()) file.length() else "&nbsp;"
        val date = if (file.exists()) printTime(file.lastModified()) else "&nbsp;"
        html += "<tr><td align=right>$size</td><td>$date</td><td><a href='$prefix$name'>$name</a></td></tr>\n"
    }
    return ResponseData(StatusCode_OK, "text/html", "$html</table>")
}

private fun executeScript(cx: Context, request: HttpRequest, file: File): ResponseData? {
    val contentType = if (request.method == "GET") null else request.headers["content-type"]?.toString()
    val type = detectFileType(file.toString()) ?: contentType ?: return null
    val format = getScriptingFormat(type, contentType) ?: return null
    if (!format.scripting)
        return null
    if (!format.supported)
        return ResponseData(StatusCode_NotImplemented, DEFAULT_MIMETYPE, "${format.mimetype} execution not implemented")
    val code = if (format.mimetype == contentType) request.inputStream?.readAllBytes().toText() else file.readText()
    val value = format.decodeText(code).resolve()
    if (value is ResponseData)
        return value
    val bytes = ByteArrayOutputStream()
    val responseType = contentNegotiation(request)
    val responseFormat = getFormat(responseType)
    if (responseFormat != null) {
        responseFormat.encode(value, bytes, defaultCharset())
        return ResponseData(StatusCode_OK, responseType, bytes.toByteArray())
    }
    else {
        format.encode(value, bytes, defaultCharset())
        return ResponseData(StatusCode_OK, format.mimetype, bytes.toByteArray())
    }
}

private fun getScriptingFormat(fileType: String, contentType: String?): Format? {
    val format = getFormat(fileType)
    if (format != null && format.scripting && format.supported)
        return format
    if (contentType == null)
        return format
    return getFormat(contentType) ?: return format
}

private fun contentNegotiation(request: HttpRequest): String {
    val accept = request.headers["accept"]?.toString() ?: return DEFAULT_MIMETYPE
    for (level in accept.split(";")) {
        for (part in level.split(",")) {
            val type = part.trim()
            if (type.isEmpty() || type.startsWith("q="))
                continue
            val format = getFormat(type)
            if (format != null && format.supported)
                return format.mimetype
        }
    }
    return DEFAULT_MIMETYPE
}
