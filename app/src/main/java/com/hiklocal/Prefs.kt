package com.hiklocal

import android.content.Context
import android.content.SharedPreferences

/**
 * Réglages persistants. Les identifiants ne sont enregistrés que si
 * l'utilisateur le demande explicitement, et ne quittent jamais l'appareil.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("hiklocal", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "192.168.1.64").orEmpty()
        set(v) = sp.edit().putString("host", v).apply()

    var user: String
        get() = sp.getString("user", "admin").orEmpty()
        set(v) = sp.edit().putString("user", v).apply()

    var password: String
        get() = sp.getString("password", "").orEmpty()
        set(v) = sp.edit().putString("password", v).apply()

    var remember: Boolean
        get() = sp.getBoolean("remember", false)
        set(v) = sp.edit().putBoolean("remember", v).apply()

    /** Dernière caméra regardée : l'application y revient au lancement. */
    var lastCamera: Int
        get() = sp.getInt("lastCamera", 1)
        set(v) = sp.edit().putInt("lastCamera", v).apply()

    /** 1 = flux principal, 2 = flux secondaire. */
    var stream: Int
        get() = sp.getInt("stream", 1)
        set(v) = sp.edit().putInt("stream", v).apply()

    /** Caméras désactivées dans la mosaïque : n'utilisent plus de bande passante. */
    var mosaicOff: Set<Int>
        get() = sp.getString("mosaicOff", "")
            .orEmpty()
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
        set(v) = sp.edit().putString("mosaicOff", v.joinToString(",")).apply()

    fun clearCredentials() {
        sp.edit().remove("password").putBoolean("remember", false).apply()
    }
}
