package com.hiklocal

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Authentification HTTP Digest (RFC 2617, algorithme MD5).
 *
 * Les appareils Hikvision refusent le Basic par défaut et exigent le Digest.
 * OkHttp ne le fournit pas en standard, et plutôt que d'ajouter une
 * dépendance externe pour une soixantaine de lignes, on l'implémente ici.
 */
class DigestAuthenticator(
    private val user: String,
    private val password: String
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Une seule tentative : sans cela, un mot de passe erroné boucle.
        if (response.request.header("Authorization") != null) return null

        val challenge = response.headers("WWW-Authenticate")
            .firstOrNull { it.startsWith("Digest", ignoreCase = true) } ?: return null

        val p = parseChallenge(challenge)
        val realm = p["realm"] ?: return null
        val nonce = p["nonce"] ?: return null
        val qop = p["qop"]
        val opaque = p["opaque"]
        val algorithm = p["algorithm"] ?: "MD5"

        val url = response.request.url
        val uri = buildString {
            append(url.encodedPath)
            url.encodedQuery?.let { append("?").append(it) }
        }
        val method = response.request.method

        val ha1 = md5("$user:$realm:$password")
        val ha2 = md5("$method:$uri")

        val useQop = qop != null && qop.split(",").any { it.trim() == "auth" }
        val nc = "00000001"
        val cnonce = randomHex()

        val digest = if (useQop) {
            md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }

        val header = buildString {
            append("Digest username=\"").append(user).append("\"")
            append(", realm=\"").append(realm).append("\"")
            append(", nonce=\"").append(nonce).append("\"")
            append(", uri=\"").append(uri).append("\"")
            append(", response=\"").append(digest).append("\"")
            append(", algorithm=").append(algorithm)
            if (opaque != null) append(", opaque=\"").append(opaque).append("\"")
            if (useQop) {
                append(", qop=auth")
                append(", nc=").append(nc)
                append(", cnonce=\"").append(cnonce).append("\"")
            }
        }

        return response.request.newBuilder().header("Authorization", header).build()
    }

    /** Découpe `Digest realm="x", nonce="y", qop="auth"` en couples clé/valeur. */
    private fun parseChallenge(header: String): Map<String, String> {
        val body = header.substringAfter("Digest").trim()
        val out = HashMap<String, String>()
        val regex = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,]*))""")
        for (m in regex.findAll(body)) {
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }.trim()
            out[key] = value
        }
        return out
    }

    private fun md5(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
