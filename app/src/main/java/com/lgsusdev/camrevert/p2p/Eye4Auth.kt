package com.lgsusdev.camrevert.p2p

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Cliente mínimo de la **cuenta EYE4** — la nube de O-KAM Pro.
 *
 * ### Por qué hace falta
 *
 * El túnel P2P y el `login()` del SDK **no autorizan los CGI**: la cámara responde
 * `result= 0;  result=-2;  ` a todo lo que no sea `get_status.cgi` hasta que se le
 * envía, dentro de la propia sesión, el preludio
 *
 * ```
 * eye4_authentication.cgi?loginAccount=<userId>&loginToken=<token>&
 * ```
 *
 * y contesta `result= 0;  eye4_auth=1;  `. Solo entonces `decoder_control.cgi`,
 * `camera_control.cgi`, etc. devuelven `var result="ok"`. **Verificado en equipo**:
 * sin el preludio las 9 órdenes PTZ fallan; con él las 9 se ejecutan.
 *
 * ### Qué se pide (dos peticiones HTTPS, y solo al conectar)
 *
 * Reproduce los pasos del visor oficial (comparado con `okam_native/account.py`):
 *
 * 1. `POST /user/summary` con `name=<email>&oemid=VSTC` → `{"userid":<id numérico>,…}`
 * 2. `GET /login/token?userid=<id>&password=<md5(clave)>&type=PC` → `{"token":"…"}`
 *
 * El `token` va firmado por el servidor por cada sesión (el payload se repite, la
 * firma no), así que se pide fresco en cada conexión. Es la **única** salida a
 * Internet de CamRevert: no se envía nada más, ni telemetría, ni el vídeo.
 *
 * No se usa `org.json` a propósito: las respuestas son planas y así no se añade
 * ninguna dependencia (ni RAM) a la app.
 */
object Eye4Auth {

    private const val API = "https://api.eye4.cn"
    private const val OEM_ID = "VSTC"

    /** Cabecera que exige el servicio de cuentas para aceptar una versión de cliente. */
    private const val CLIENT_VERSION = "10.0.1"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_BYTES = 64 * 1024

    /** Cuenta y token que se pasan a `eye4_authentication.cgi`. */
    data class Credential(val userId: String, val token: String)

    /** `"userid":<id numérico>`, con o sin comillas. */
    private val USER_ID = Regex("\"userid\"\\s*:\\s*\"?(\\d+)")

    /**
     * `"token":"O1WG…%2F…%3D%3D"`. El servicio lo devuelve **ya percent-encoded**
     * (`%2F` = `/`, `%2B` = `+`, `%3D` = `=`, que es lo que llevaría el base64), así
     * que se usa tal cual: volver a codificarlo lo rompería (`%` → `%25`).
     */
    private val TOKEN = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * Pide un [Credential] nuevo. Devuelve `null` ante cualquier fallo (red, credenciales
     * rechazadas o respuesta inesperada) — el llamante decide qué hacer; nunca lanza.
     */
    suspend fun fetch(
        accountUser: String,
        accountPassword: String,
        log: (String) -> Unit = {},
    ): Credential? = withContext(Dispatchers.IO) {
        val name = accountUser.trim()
        if (name.isEmpty() || accountPassword.isEmpty()) {
            log("cuenta EYE4 no configurada (usuario/clave vacíos)")
            return@withContext null
        }

        val summary = request(
            method = "POST",
            path = "/user/summary",
            body = formOf("name" to name, "oemid" to OEM_ID),
        )
        if (summary == null) {
            log("POST /user/summary sin respuesta")
            return@withContext null
        }
        val userId = USER_ID.find(summary)?.groupValues?.get(1)
        if (userId == null) {
            log("POST /user/summary no devolvió userid: ${summary.take(120)}")
            return@withContext null
        }

        val digest = md5Hex(accountPassword)
        val login = request(
            method = "GET",
            path = "/login/token?userid=$userId&password=$digest&type=PC",
        )
        if (login == null) {
            log("GET /login/token sin respuesta")
            return@withContext null
        }
        val token = TOKEN.find(login)?.groupValues?.get(1)
        if (token == null) {
            log("GET /login/token no devolvió token: ${login.take(120)}")
            return@withContext null
        }

        log("token de cuenta obtenido (userid=$userId, token=${token.length} chars)")
        Credential(userId, token)
    }

    /** `application/x-www-form-urlencoded`, como el visor oficial. */
    private fun formOf(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
        }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** `md5` hexadecimal en minúsculas de la clave de la cuenta (protocolo oficial). */
    private fun md5Hex(value: String): String {
        val md = MessageDigest.getInstance("MD5")
        val out = md.digest(value.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(out.size * 2)
        for (b in out) hex.append("%02x".format(b.toInt() and 0xFF))
        return hex.toString()
    }

    /** GET/POST con las cabeceras del visor. Devuelve el cuerpo (≤ [MAX_BYTES]) o `null`. */
    private fun request(method: String, path: String, body: String? = null): String? = try {
        val conn = (URL(API + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("client_version", CLIENT_VERSION)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.US_ASCII)) }
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                conn.inputStream.use { readLimited(it) }
            }
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        null
    }

    /** Lectura acotada y **segura en UTF-8** (no se parte un carácter entre lecturas). */
    private fun readLimited(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() <= MAX_BYTES) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
