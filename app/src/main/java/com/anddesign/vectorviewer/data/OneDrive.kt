package com.anddesign.vectorviewer.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.anddesign.vectorviewer.format.VectorFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

/**
 * Conexión directa con OneDrive (Microsoft Graph).
 *
 * Inicio de sesión: OAuth 2.0 con PKCE en una pestaña del navegador (Custom Tabs).
 * Microsoft devuelve el control a la app por el URI msauth://… registrado en Azure,
 * que solo funciona con la firma fija de la app.
 * Permisos: Files.Read (solo lectura de los archivos del usuario).
 */
object OneDrive {

    const val CLIENT_ID = "da9218da-8270-4d91-8d03-8e074e54bc5f"
    const val REDIRECT_URI = "msauth://com.anddesign.vectorviewer/GsuwuLJpjKmNqUgj5pACvNxE2sI%3D"
    const val SCHEME = "onedrive"
    private const val AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0"
    private const val GRAPH = "https://graph.microsoft.com/v1.0"
    private const val SCOPES = "Files.Read User.Read offline_access openid profile"
    private const val ITEM_FIELDS = "id,name,size,folder,file,lastModifiedDateTime,parentReference"

    data class Account(val name: String, val email: String)

    data class Item(
        val id: String,
        val name: String,
        val isFolder: Boolean,
        val size: Long,
        val modified: Long,
        val path: String,
        val thumbnailUrl: String?,
    ) {
        /** URI interno con el que el visor, los recientes y las miniaturas abren este archivo. */
        val fileUri: String
            get() = "$SCHEME://item/$id?name=${enc(name)}&size=$size&mod=$modified"

        val folderUri: String get() = "$SCHEME://folder/$id"
    }

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val tokenLock = Any()
    private var loaded = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("onedrive", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        if (p.getString("refresh_token", null) != null) {
            _account.value = Account(p.getString("name", "") ?: "", p.getString("email", "") ?: "")
        }
    }

    fun isSignedIn(context: Context): Boolean {
        init(context)
        return _account.value != null
    }

    // ---------- Inicio de sesión ----------

    fun startSignIn(context: Context) {
        val verifier = randomUrlSafe(48)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlSafe(16)
        prefs(context).edit().putString("pkce_verifier", verifier).putString("pkce_state", state).apply()
        val url = "$AUTHORITY/authorize" +
            "?client_id=$CLIENT_ID" +
            "&response_type=code" +
            "&redirect_uri=${enc(REDIRECT_URI)}" +
            "&response_mode=query" +
            "&scope=${enc(SCOPES)}" +
            "&state=$state" +
            "&code_challenge=$challenge" +
            "&code_challenge_method=S256" +
            "&prompt=select_account"
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
    }

    fun isRedirect(uri: Uri?): Boolean = uri?.scheme == "msauth"

    /** Recibe el msauth://…?code=… y lo cambia por tokens. Devuelve un mensaje de error o null. */
    suspend fun completeSignIn(context: Context, redirect: Uri): String? = withContext(Dispatchers.IO) {
        val p = prefs(context)
        redirect.getQueryParameter("error_description")?.let { return@withContext it }
        val code = redirect.getQueryParameter("code") ?: return@withContext "Sin código de autorización"
        if (redirect.getQueryParameter("state") != p.getString("pkce_state", null)) {
            return@withContext "Respuesta de inicio de sesión no válida"
        }
        val verifier = p.getString("pkce_verifier", null) ?: return@withContext "Sesión de inicio expirada"
        try {
            val json = tokenRequest(
                mapOf(
                    "client_id" to CLIENT_ID,
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "code_verifier" to verifier,
                    "scope" to SCOPES,
                )
            )
            saveTokens(context, json)
            p.edit().remove("pkce_verifier").remove("pkce_state").apply()
            val me = JSONObject(get(context, "$GRAPH/me?\$select=displayName,mail,userPrincipalName"))
            val acc = Account(
                me.optString("displayName"),
                me.optString("mail").ifBlank { me.optString("userPrincipalName") },
            )
            p.edit().putString("name", acc.name).putString("email", acc.email).apply()
            _account.value = acc
            null
        } catch (e: Exception) {
            e.message ?: "Error al iniciar sesión"
        }
    }

    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
        _account.value = null
        Repos.library.favoriteFolders.value
            .filter { it.uri.startsWith("$SCHEME://") }
            .forEach { Repos.library.removeFavoriteFolder(it.uri) }
    }

    private fun saveTokens(context: Context, json: JSONObject) {
        val expiresAt = System.currentTimeMillis() + (json.optLong("expires_in", 3600) - 120) * 1000
        prefs(context).edit()
            .putString("access_token", json.getString("access_token"))
            .putString("refresh_token", json.optString("refresh_token").ifBlank { prefs(context).getString("refresh_token", "") })
            .putLong("expires_at", expiresAt)
            .apply()
    }

    private fun accessToken(context: Context): String = synchronized(tokenLock) {
        val p = prefs(context)
        val token = p.getString("access_token", null)
        if (token != null && System.currentTimeMillis() < p.getLong("expires_at", 0)) return token
        val refresh = p.getString("refresh_token", null) ?: throw IOException("No has iniciado sesión en OneDrive")
        val json = try {
            tokenRequest(
                mapOf(
                    "client_id" to CLIENT_ID,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                    "scope" to SCOPES,
                )
            )
        } catch (e: IOException) {
            if (e.message?.contains("invalid_grant") == true) {
                signOut(context)
                throw IOException("La sesión de OneDrive expiró. Vuelve a iniciar sesión.")
            }
            throw e
        }
        saveTokens(context, json)
        json.getString("access_token")
    }

    // ---------- Microsoft Graph ----------

    /** Contenido de una carpeta ("root" = raíz). Solo carpetas y formatos compatibles. */
    suspend fun children(context: Context, folderId: String): List<Item> = withContext(Dispatchers.IO) {
        val out = ArrayList<Item>()
        var url: String? = "$GRAPH/me/drive/items/$folderId/children" +
            "?\$top=200&\$select=$ITEM_FIELDS&\$expand=thumbnails(\$select=medium)"
        while (url != null && out.size < 2000) {
            val json = JSONObject(get(context, url))
            parseItems(json, out)
            url = json.optString("@odata.nextLink").ifBlank { null }
        }
        out
    }

    /** Búsqueda en todo el OneDrive del usuario (nombre y contenido indexado por Microsoft). */
    suspend fun search(context: Context, query: String): List<Item> = withContext(Dispatchers.IO) {
        val q = enc(query.replace("'", "''"))
        val json = JSONObject(
            get(context, "$GRAPH/me/drive/root/search(q='$q')?\$top=200&\$select=$ITEM_FIELDS&\$expand=thumbnails(\$select=medium)")
        )
        ArrayList<Item>().also { parseItems(json, it) }
    }

    /** Descarga un archivo a la caché (si no cambió desde la última vez, reutiliza la copia). */
    fun downloadToCache(context: Context, itemId: String, name: String, modified: Long): File {
        val dir = File(context.cacheDir, "docs").apply { mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val target = File(dir, "od_${itemId.replace(Regex("[^A-Za-z0-9]"), "")}_${modified}_$safe")
        if (target.exists() && target.length() > 0) return target
        val meta = JSONObject(get(context, "$GRAPH/me/drive/items/$itemId"))
        val downloadUrl = meta.optString("@microsoft.graph.downloadUrl").ifBlank { throw IOException("OneDrive no entregó el archivo") }
        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode !in 200..299) throw IOException("Descarga fallida (${conn.responseCode})")
            val tmp = File(target.path + ".tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            tmp.renameTo(target)
        } finally {
            conn.disconnect()
        }
        return target
    }

    fun cachedFileExists(context: Context, itemId: String, modified: Long): Boolean {
        val dir = File(context.cacheDir, "docs")
        val prefix = "od_${itemId.replace(Regex("[^A-Za-z0-9]"), "")}_${modified}_"
        return dir.listFiles()?.any { it.name.startsWith(prefix) && !it.name.endsWith(".tmp") } == true
    }

    private fun parseItems(json: JSONObject, out: MutableList<Item>) {
        val arr = json.optJSONArray("value") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name")
            val isFolder = o.has("folder")
            if (!isFolder && !VectorFormat.isSupportedName(name)) continue
            val parentPath = o.optJSONObject("parentReference")?.optString("path").orEmpty()
                .substringAfter("root:", "").trim('/').replace("/", " / ")
            val thumb = o.optJSONArray("thumbnails")?.optJSONObject(0)?.optJSONObject("medium")?.optString("url")
            out.add(
                Item(
                    id = o.getString("id"),
                    name = name,
                    isFolder = isFolder,
                    size = o.optLong("size", -1),
                    modified = runCatching { Instant.parse(o.optString("lastModifiedDateTime")).toEpochMilli() }.getOrDefault(0L),
                    path = parentPath,
                    thumbnailUrl = thumb?.ifBlank { null },
                )
            )
        }
    }

    // ---------- Utilidades ----------

    fun itemIdFromUri(uri: Uri): String? = if (uri.scheme == SCHEME) uri.pathSegments.firstOrNull() else null

    private fun get(context: Context, url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Authorization", "Bearer ${accessToken(context)}")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("OneDrive ($code): ${graphError(body)}")
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun tokenRequest(form: Map<String, String>): JSONObject {
        val conn = URL("$AUTHORITY/token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            conn.outputStream.use { it.write(form.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }.toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
            if (code !in 200..299) {
                throw IOException(json.optString("error") + ": " + json.optString("error_description").substringBefore("\r").substringBefore("\n"))
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun graphError(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull()?.ifBlank { null } ?: body.take(200)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun randomUrlSafe(bytes: Int): String = b64url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
}
