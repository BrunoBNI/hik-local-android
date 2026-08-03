package com.hiklocal

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Une caméra telle que l'appareil la déclare. */
data class Camera(val id: Int, val name: String, val enabled: Boolean) {
    /** « Salon (4) » si l'appareil donne un nom, sinon « Caméra 4 ». */
    val label: String get() = if (name.isNotBlank()) "$name ($id)" else "Caméra $id"
}

/** Cause d'échec d'un appel, pour afficher un message utile. */
enum class ErrorKind { UNREACHABLE, CREDENTIALS, DEVICE }

/** Résultat d'un appel : succès avec charge utile, ou échec explicable. */
sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(val kind: ErrorKind, val detail: String = "") : ApiResult<Nothing>()
}

class HikApi(
    val host: String,
    private val user: String,
    private val password: String,
    private val httpPort: Int = 80,
    private val rtspPort: Int = 554
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .authenticator(DigestAuthenticator(user, password))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = "http://$host:$httpPort$path"

    // ------------------------------------------------------------- ISAPI

    private suspend fun get(path: String): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url(path)).build()).execute().use { r ->
                when {
                    r.isSuccessful -> ApiResult.Ok(r.body?.string().orEmpty())
                    r.code == 401 -> ApiResult.Err(ErrorKind.CREDENTIALS)
                    else -> ApiResult.Err(ErrorKind.DEVICE, "HTTP ${r.code}")
                }
            }
        } catch (e: Exception) {
            ApiResult.Err(ErrorKind.UNREACHABLE, e.message.orEmpty())
        }
    }

    /** Vérifie l'adresse et les identifiants d'un coup. */
    suspend fun checkConnection(): ApiResult<String> = get("/ISAPI/System/deviceInfo")

    /** Même appel que checkConnection(), sous un nom plus parlant pour l'écran Infos. */
    suspend fun deviceInfo(): ApiResult<String> = get("/ISAPI/System/deviceInfo")

    /** Extrait quelques champs lisibles de la réponse XML deviceInfo. */
    fun parseDeviceInfo(xml: String): Map<String, String> {
        val fields = listOf(
            "deviceName" to "Nom", "model" to "Modèle",
            "firmwareVersion" to "Firmware", "serialNumber" to "Numéro de série"
        )
        val out = LinkedHashMap<String, String>()
        for ((tag, label) in fields) {
            Regex("<$tag>(.*?)</$tag>").find(xml)?.groupValues?.get(1)?.trim()?.let {
                if (it.isNotEmpty()) out[label] = it
            }
        }
        return out
    }

    /**
     * Caméras déclarées par l'appareil. Les entrées sans caméra branchée
     * (videoInputEnabled = false) sont écartées : inutile de les proposer.
     */
    suspend fun cameras(): ApiResult<List<Camera>> {
        return when (val r = get("/ISAPI/System/Video/inputs/channels")) {
            is ApiResult.Err -> r
            is ApiResult.Ok -> {
                val blocks = Regex(
                    "<VideoInputChannel\\b.*?</VideoInputChannel>",
                    setOf(RegexOption.DOT_MATCHES_ALL)
                ).findAll(r.value)

                val all = blocks.mapNotNull { b ->
                    val block = b.value
                    val id = Regex("<id>\\s*(\\d+)\\s*</id>").find(block)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                    val name = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)
                        .find(block)?.groupValues?.get(1)?.trim().orEmpty()
                    val enabled = Regex("<videoInputEnabled>(.*?)</videoInputEnabled>")
                        .find(block)?.groupValues?.get(1)?.trim()
                        ?.equals("true", ignoreCase = true) ?: true
                    Camera(id, name, enabled)
                }.toList()

                val active = all.filter { it.enabled }
                ApiResult.Ok(if (active.isNotEmpty()) active else all)
            }
        }
    }

    /**
     * Commande d'orientation continue. Envoyer 0,0,0 arrête le mouvement.
     * Sur un enregistreur, le canal PTZ est le numéro de caméra seul.
     */
    suspend fun ptz(cam: Int, pan: Int, tilt: Int, zoom: Int): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val xml = "<PTZData><pan>$pan</pan><tilt>$tilt</tilt><zoom>$zoom</zoom></PTZData>"
            try {
                val req = Request.Builder()
                    .url(url("/ISAPI/PTZCtrl/channels/$cam/continuous"))
                    .put(xml.toRequestBody("application/xml".toMediaType()))
                    .build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) ApiResult.Ok(Unit)
                    else ApiResult.Err(ErrorKind.DEVICE, "HTTP ${r.code}")
                }
            } catch (e: Exception) {
                ApiResult.Err(ErrorKind.UNREACHABLE, e.message.orEmpty())
            }
        }

    /** Photo pleine résolution prise à l'instant. */
    suspend fun snapshot(cam: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url("/ISAPI/Streaming/channels/${cam}01/picture"))
                .build()
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // -------------------------------------------------------------- RTSP

    /**
     * Les identifiants voyagent dans l'URL RTSP : ils doivent être encodés,
     * sinon un mot de passe contenant @ ou : casse l'adresse.
     */
    private fun rtspBase(): String {
        val u = Uri.encode(user)
        val p = Uri.encode(password)
        return "rtsp://$u:$p@$host:$rtspPort"
    }

    /** Flux direct. stream = 1 (principal, HD) ou 2 (secondaire, fluide). */
    fun liveUrl(cam: Int, stream: Int): String =
        "${rtspBase()}/Streaming/Channels/$cam${"%02d".format(stream)}"

    /**
     * Flux d'archive entre deux instants, au format attendu par l'appareil
     * (20260723T200000Z). Le son est inclus s'il a été enregistré.
     */
    fun playbackUrl(cam: Int, start: String, end: String): String =
        "${rtspBase()}/Streaming/tracks/${cam}01?starttime=$start&endtime=$end"
}
