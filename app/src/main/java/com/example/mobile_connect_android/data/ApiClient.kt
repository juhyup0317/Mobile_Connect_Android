package com.example.mobile_connect_android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiError(val messageText: String, val status: Int) : Exception(messageText)

@OptIn(ExperimentalSerializationApi::class)
class ApiClient(var baseUrl: String = "", var context: android.content.Context? = null) {
    var onSignedOut: (() -> Unit)? = null

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val sharedPrefs by lazy {
        context?.getSharedPreferences("api_cookies", android.content.Context.MODE_PRIVATE)
    }

    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies.toMutableList()
            sharedPrefs?.let { prefs ->
                val editor = prefs.edit()
                cookies.forEach { cookie ->
                    val key = "${url.host}:${cookie.name}"
                    editor.putString(key, cookie.toString())
                }
                editor.apply()
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val inMemory = cookieStore[url.host]
            if (!inMemory.isNullOrEmpty()) return inMemory

            val loaded = mutableListOf<Cookie>()
            sharedPrefs?.let { prefs ->
                val all = prefs.all
                all.forEach { (key, value) ->
                    if (key.startsWith("${url.host}:") && value is String) {
                        Cookie.parse(url, value)?.let { loaded.add(it) }
                    }
                }
            }
            if (loaded.isNotEmpty()) {
                cookieStore[url.host] = loaded
            }
            return loaded
        }
    }

    fun clearCookies() {
        cookieStore.clear()
        sharedPrefs?.edit()?.clear()?.apply()
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Terminal sockets sit idle for minutes, so no read timeout; pings notice a dead link instead. */
    val webSocketClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    fun getOrigin(): String {
        val parsed = baseUrl.toHttpUrlOrNull() ?: return ""
        val portStr = if (parsed.port != 80 && parsed.port != 443) ":${parsed.port}" else ""
        return "${parsed.scheme}://${parsed.host}$portStr"
    }

    fun buildUrl(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val cleanBase = baseUrl.trimEnd('/')
        val full = cleanBase + path
        val urlBuilder = full.toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("Invalid URL: $full")
        query.forEach { (k, v) ->
            urlBuilder.addQueryParameter(k, v)
        }
        return urlBuilder.build()
    }

    fun newRequestBuilder(path: String, method: String = "GET", query: Map<String, String> = emptyMap()): Request.Builder {
        val url = buildUrl(path, query)
        val builder = Request.Builder()
            .url(url)
            .method(method, if (method == "GET" || method == "HEAD") null else "".toRequestBody())
            .header("Origin", getOrigin())
        return builder
    }

    suspend fun execute(path: String, method: String = "GET", query: Map<String, String> = emptyMap(), jsonBody: Any? = null, jsonBodyString: String? = null): ByteArray = withContext(Dispatchers.IO) {
        val builder = newRequestBuilder(path, method, query)
        if (jsonBodyString != null) {
            builder.method(method, jsonBodyString.toRequestBody("application/json; charset=utf-8".toMediaType()))
        } else if (jsonBody != null) {
            val bodyStr = when (jsonBody) {
                is LoginRequest -> json.encodeToString(jsonBody)
                is LogoutRequest -> json.encodeToString(jsonBody)
                is WriteRequest -> json.encodeToString(jsonBody)
                is OpRequest -> json.encodeToString(jsonBody)
                is SavePrefsRequest -> json.encodeToString(jsonBody)
                is NewSessionRequest -> json.encodeToString(jsonBody)
                is ReadNotesRequest -> json.encodeToString(jsonBody)
                is UiState -> json.encodeToString(jsonBody)
                is Prefs -> json.encodeToString(jsonBody)
                else -> jsonBody.toString()
            }
            builder.method(method, bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }

        val request = builder.build()
        val response = okHttpClient.newCall(request).execute()
        val bytes = response.body?.bytes() ?: ByteArray(0)

        checkResponse(response.code, bytes, path)
        bytes
    }

    private fun checkResponse(code: Int, bytes: ByteArray, path: String) {
        if (code in 200..299) return
        if (code == 401 && path != "/api/auth/login") {
            onSignedOut?.invoke()
        }
        var errorMsg = "Request failed ($code)"
        try {
            val str = String(bytes)
            val errObj = json.decodeFromString<ErrorResponseBody>(str)
            if (!errObj.error.isNullOrEmpty()) {
                errorMsg = errObj.error
            }
        } catch (_: Exception) {}
        throw ApiError(errorMsg, code)
    }

    // MARK: Endpoints

    suspend fun login(password: String, code: String) {
        execute("/api/auth/login", method = "POST", jsonBody = LoginRequest(password, code))
    }

    suspend fun logout(everywhere: Boolean) {
        try {
            execute("/api/auth/logout", method = "POST", jsonBody = LogoutRequest(everywhere))
        } catch (_: Exception) {}
    }

    suspend fun ping() {
        try {
            execute("/api/auth/ping", method = "POST")
        } catch (_: Exception) {}
    }

    suspend fun list(path: String): Listing {
        val bytes = execute("/api/fs/list", query = mapOf("path" to path))
        return json.decodeFromString(String(bytes))
    }

    suspend fun search(path: String, q: String): List<SearchHit> {
        val bytes = execute("/api/fs/search", query = mapOf("path" to path, "q" to q))
        val res = json.decodeFromString<SearchResponse>(String(bytes))
        return res.results
    }

    suspend fun read(path: String): FileInfo {
        val bytes = execute("/api/fs/read", query = mapOf("path" to path))
        return json.decodeFromString(String(bytes))
    }

    suspend fun download(path: String, targetFile: File) = withContext(Dispatchers.IO) {
        val url = buildUrl("/api/fs/raw", mapOf("path" to path, "dl" to "1"))
        val request = Request.Builder()
            .url(url)
            .header("Origin", getOrigin())
            .build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            checkResponse(response.code, response.body?.bytes() ?: ByteArray(0), "/api/fs/raw")
        }
        response.body?.byteStream()?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    suspend fun write(path: String, content: String, baseMtime: Double, force: Boolean = false): Double {
        val bytes = execute("/api/fs/write", method = "PUT", jsonBody = WriteRequest(path, content, baseMtime, force))
        val saved = json.decodeFromString<SavedResponse>(String(bytes))
        return saved.mtime
    }

    suspend fun upload(file: File, dir: String, overwrite: Boolean = false) = withContext(Dispatchers.IO) {
        val query = mutableMapOf("dir" to dir, "name" to file.name)
        if (overwrite) query["overwrite"] = "1"
        val url = buildUrl("/api/fs/upload", query)
        val fileBody = file.readBytes().toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder()
            .url(url)
            .put(fileBody)
            .header("Origin", getOrigin())
            .build()
        val response = okHttpClient.newCall(request).execute()
        val bytes = response.body?.bytes() ?: ByteArray(0)
        checkResponse(response.code, bytes, "/api/fs/upload")
    }

    suspend fun mkdir(path: String) { execute("/api/fs/op", method = "POST", jsonBody = OpRequest("mkdir", path = path)) }
    suspend fun touch(path: String) { execute("/api/fs/op", method = "POST", jsonBody = OpRequest("touch", path = path)) }
    suspend fun rename(from: String, to: String) { execute("/api/fs/op", method = "POST", jsonBody = OpRequest("rename", from = from, to = to)) }
    suspend fun copy(from: String, to: String) { execute("/api/fs/op", method = "POST", jsonBody = OpRequest("copy", from = from, to = to)) }
    suspend fun delete(path: String) { execute("/api/fs/op", method = "POST", jsonBody = OpRequest("delete", path = path)) }

    suspend fun loadState(): UiState {
        val bytes = execute("/api/state")
        return json.decodeFromString(String(bytes))
    }

    suspend fun saveState(state: UiState) {
        try {
            execute("/api/state", method = "PUT", jsonBody = state)
        } catch (_: Exception) {}
    }

    suspend fun loadPrefs(): Prefs {
        val bytes = execute("/api/prefs")
        val file = json.decodeFromString<PrefsFile>(String(bytes))
        return file.touch ?: Prefs()
    }

    suspend fun savePrefs(prefs: Prefs) {
        try {
            execute("/api/prefs", method = "PUT", jsonBody = SavePrefsRequest(prefs = prefs))
        } catch (_: Exception) {}
    }

    suspend fun setVoiceEnter(value: Boolean) {
        execute("/api/state", method = "PUT", jsonBodyString = "{\"voiceEnter\":$value}")
    }

    suspend fun transcribe(audioBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val url = buildUrl("/api/transcribe")
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", "speech.m4a", audioBytes.toRequestBody("audio/mp4".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Origin", getOrigin())
            .build()
        val response = okHttpClient.newCall(request).execute()
        val bytes = response.body?.bytes() ?: ByteArray(0)
        checkResponse(response.code, bytes, "/api/transcribe")
        val res = json.decodeFromString<TranscribeResponse>(String(bytes))
        res.text
    }

    suspend fun sessions(): List<TermSession> {
        val bytes = execute("/term/sessions")
        val res = json.decodeFromString<SessionsResponse>(String(bytes))
        return res.sessions
    }

    suspend fun newSession(cwd: String, preset: Preset): TermSession {
        val bytes = execute("/term/sessions", method = "POST", jsonBody = NewSessionRequest(cwd, preset, cols = 80, rows = 24))
        return json.decodeFromString(String(bytes))
    }

    suspend fun closeSession(id: String) {
        try {
            execute("/term/sessions/$id", method = "DELETE")
        } catch (_: Exception) {}
    }

    suspend fun markNotesRead(term: String?) {
        try {
            execute("/term/notes/read", method = "POST", jsonBody = ReadNotesRequest(term))
        } catch (_: Exception) {}
    }

    suspend fun clearNotes() {
        try {
            execute("/term/notes", method = "DELETE")
        } catch (_: Exception) {}
    }

    // Requests/Responses

    @Serializable private data class LoginRequest(val password: String, val code: String)
    @Serializable private data class LogoutRequest(val all: Boolean)
    @Serializable private data class WriteRequest(val path: String, val content: String, val baseMtime: Double, val force: Boolean)
    @Serializable private data class OpRequest(val op: String, val path: String? = null, val from: String? = null, val to: String? = null)
    @Serializable private data class SavePrefsRequest(val profile: String = "touch", val prefs: Prefs)
    @Serializable private data class NewSessionRequest(val cwd: String, val preset: Preset, val cols: Int, val rows: Int)
    @Serializable private data class ReadNotesRequest(val term: String?)
    @Serializable private data class ErrorResponseBody(val error: String? = null)
    @Serializable private data class SavedResponse(val mtime: Double)
    @Serializable private data class SearchResponse(val results: List<SearchHit>)
    @Serializable private data class TranscribeResponse(val text: String)
    @Serializable private data class SessionsResponse(val sessions: List<TermSession>)
}
