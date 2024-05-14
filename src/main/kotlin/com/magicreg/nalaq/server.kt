package com.magicreg.nalaq

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.sessions.*
import io.ktor.util.StringValues
import java.io.*
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter

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

data class WebSession(val id: String)
data class ResponseData(val status: Int?, val type: String, val data: Any = "")
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
            call.request.local.remoteHost,
            inputStream)
        // TODO: also check the remote ip with call.request.origin
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
        else if (parts[0] == "text" || data.data is CharSequence)
            call.respondText("${data.data}\n", contentType, status)
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
        else -> ResponseData(StatusCode_MethodNotAllowed, DEFAULT_MIMETYPE)
    }
}

private fun getRequest(cx: Context, request: HttpRequest): ResponseData {
    val baseuri = cx.configuration.staticFolder ?: "."
    if (request.path == "/") {
        val links = if (cx.configuration.webContextName.isNullOrBlank())
            File(baseuri).list().toList()
        else {
            val list = mutableListOf(cx.configuration.webContextName)
            list.addAll(File(baseuri).list().toList())
            list
        }
        return printLinks(links, "/", baseuri, request.query)
    }
    val path = getPathParts(request.path)
    if (path[0] == cx.configuration.webContextName)
        return getContextValue(cx, path, request.query)
    return getFileContent(cx, request.path, baseuri, request.query)
}

private fun getFileContent(cx: Context, filename: String, baseuri: String, query: Map<String,Any?>): ResponseData {
    val file = File(baseuri+filename)
    if (file.exists()) {
        if (file.isDirectory) {
            if (filename[filename.length-1] != '/')
                return ResponseData(StatusCode_MovedPermanently, DEFAULT_MIMETYPE, "$filename/")
            for (type in INDEX_TYPES) {
                if (File(baseuri+filename+"index."+type).exists())
                    return getFileContent(cx, filename+"index."+type, baseuri, query)
            }
            return printLinks(listOf(*file.list()), filename, baseuri, query)
        }
        return ResponseData(StatusCode_OK, file.toURI().contentType() ?: DEFAULT_MIMETYPE, file)
    }
    return ResponseData(StatusCode_NotFound, DEFAULT_MIMETYPE, "$filename not found")
}

private fun getContextValue(cx: Context, path: List<String>, query: Map<String,Any?>): ResponseData {
    val value = getContextPathValue(cx, path)  ?: return ResponseData(StatusCode_NotFound, DEFAULT_MIMETYPE, "/"+path.joinToString("/")+" not found")
    val filtered = if (query.isNotEmpty()) Filter().mapCondition(query).filter(value.resolve()).simplify() else value.resolve()
    val buffer = ByteArrayOutputStream()
    // TODO: must do content_negotiation
    val mimetype = JSON_MIMETYPE
    val charset = defaultCharset()
    getFormat(mimetype)?.encode(filtered, buffer, charset)
    return ResponseData(StatusCode_OK, mimetype, buffer.toString(charset))
}

private fun postRequest(cx: Context, request: HttpRequest): ResponseData {
    if (request.path == "/")
        return ResponseData(StatusCode_MethodNotAllowed, DEFAULT_MIMETYPE)
    val path = getPathParts(request.path)
    if (path[0] == cx.configuration.webContextName)
        return postContextValue(cx, path, request)
    return postFileContent(cx, request.path, cx.configuration.staticFolder ?: ".", request)
}

private fun postFileContent(cx: Context, filename: String, baseuri: String, request: HttpRequest): ResponseData {
    val file = File(baseuri+filename)
    if (file.exists()) {
        if (file.isDirectory) {
            val type = request.query["content-type"]?.toString()?.split(";")?.get(0)?.trim() ?: DEFAULT_MIMETYPE
            val name = Math.random().toString().split(".")[1] + "." + getMimetypeExtensions(type)[0]
            FileOutputStream("$file/$name").use { output ->
                request.inputStream?.copyTo(output)
                output.close()
            }
            return ResponseData(StatusCode_OK, DEFAULT_MIMETYPE, "$filename/$name")
        }
        return ResponseData(StatusCode_BadRequest, DEFAULT_MIMETYPE, "Cannot add content to $filename")
    }
    return ResponseData(StatusCode_NotFound, DEFAULT_MIMETYPE, "$filename not found")
}

private fun postContextValue(cx: Context, path: List<String>, request: HttpRequest): ResponseData {
    val resource = getContextPathValue(cx, path) ?: return ResponseData(StatusCode_NotFound, DEFAULT_MIMETYPE, "/"+path.joinToString("/")+" not found")
    val body = requestBody(request)
    val fn = resource.toFunction()
    val value = if (fn != null)
        executeFunction(fn, mergeRequestData(request.query, body))
    else if (resource is Type)
        resource.newInstance(listRequestData(request.query, body))
    else
        addRequestData(resource, body)
    if (value is ResponseData)
        return value
    val buffer = ByteArrayOutputStream()
    // TODO: must do content_negotiation
    val mimetype = JSON_MIMETYPE
    val charset = defaultCharset()
    getFormat(mimetype)?.encode(value, buffer, charset)
    return ResponseData(StatusCode_OK, mimetype, buffer.toString(charset))
}

private fun putRequest(cx: Context, request: HttpRequest): ResponseData {
    if (request.path == "/")
        return ResponseData(StatusCode_MethodNotAllowed, DEFAULT_MIMETYPE)
    val path = getPathParts(request.path)
    if (path[0] == cx.configuration.webContextName)
        return putContextValue(cx, path, request)
    return putFileContent(cx, request.path, cx.configuration.staticFolder ?: ".", request)
}

private fun putFileContent(cx: Context, filename: String, baseuri: String, request: HttpRequest): ResponseData {
    val file = File(baseuri+filename)
    if (file.exists()) {
        if (file.isDirectory)
            return ResponseData(StatusCode_BadRequest, DEFAULT_MIMETYPE, "Cannot add content to $filename")
        FileOutputStream("$file").use { output ->
            request.inputStream?.copyTo(output)
            output.close()
        }
        return ResponseData(StatusCode_OK, DEFAULT_MIMETYPE, "$filename")
    }
    return ResponseData(StatusCode_NotFound, DEFAULT_MIMETYPE, "$filename not found")
}

private fun putContextValue(cx: Context, path: List<String>, request: HttpRequest): ResponseData {
    val body = requestBody(request)
    if (!cx.pathValue(path.subList(1, path.size), body))
        return ResponseData(StatusCode_NotFound, DEFAULT_MIMETYPE, "/"+path.joinToString("/")+" not found")
    return ResponseData(StatusCode_OK, JSON_MIMETYPE, true)
}

private fun deleteRequest(cx: Context, request: HttpRequest): ResponseData {
    if (request.path == "/")
        return ResponseData(StatusCode_MethodNotAllowed, DEFAULT_MIMETYPE)
    if (getPathParts(request.path)[0] == cx.configuration.webContextName)
        return ResponseData(StatusCode_MethodNotAllowed, DEFAULT_MIMETYPE)
    val filename = request.path
    val baseuri = cx.configuration.staticFolder ?: "."
    val file = File(baseuri+filename)
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

private fun getPathParts(path: String): List<String> {
    val parts = path.split("/").toMutableList()
    parts.removeAll { it.isBlank() }
    return parts
}

private fun getContextPathValue(cx: Context, path: List<String>): Any? {
    return if (path.size == 1)
        cx.names
    else
        cx.path(path.subList(1, path.size)).resolve()
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

private fun printLinks(links: Collection<String>, prefix: String, baseuri: String, query: Map<String,Any?>?): ResponseData {
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

private fun executeFunction(function: KFunction<Any?>, args: Map<String,Any?>): Any? {
    val params = mutableMapOf<KParameter,Any?>()
    for ((index, key) in args.keys.withIndex())
        params[function.parameters.firstOrNull { it.name == key } ?: function.parameters[index] ] = args[key]
    return function.callBy(params)
}

private fun addRequestData(resource: Any?, body: Any?): ResponseData {
    // TODO: if both resource and body are views and appendable then append body to resource
    if (resource is MutableCollection<*>)
        (resource as MutableCollection<Any?>).addAll(listRequestData(null, body))
    else if (resource is MutableMap<*,*>)
        (resource as MutableMap<String,Any?>).putAll(mergeRequestData(null, body))
    else
        return ResponseData(StatusCode_BadRequest, DEFAULT_MIMETYPE)
    return ResponseData(StatusCode_Accepted, JSON_MIMETYPE, true)
}

private fun requestBody(request: HttpRequest): Any? {
    val parts = (request.headers["content-type"]?.toString() ?: DEFAULT_MIMETYPE).split(";")
    val mimetype = parts[0].trim()
    val charset = parts.firstOrNull { it.contains("charset=")}?.split("=")?.get(1)?.trim() ?: defaultCharset()
    return getFormat(mimetype)!!.decode(request.inputStream!!, charset)
}

private fun bodyToList(body: Any?): List<Any?> {
    return if (body is Collection<*>)
        body.toList()
    else if (body is Array<*>)
        body.toList()
    else
        listOf(body)
}

private fun mergeRequestData(query: Map<String,Any?>?, body: Any?): Map<String,Any?> {
    val params = mutableMapOf<String, Any?>()
    if (query != null)
        params.putAll(query)
    for (item in bodyToList(body))
        params.putAll(toMap(item) as Map<String,Any?>)
    return params
}

private fun listRequestData(query: Map<String,Any?>?, body: Any?): List<Any?> {
    val params = mutableListOf<Any?>()
    if (query != null)
        params.addAll(query.values)
    params.addAll(bodyToList(body))
    return params
}
