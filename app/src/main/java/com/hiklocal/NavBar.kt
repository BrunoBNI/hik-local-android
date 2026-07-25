package com.hiklocal

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Câble la barre de navigation partagée (topbar.xml), incluse dans chaque
 * écran. Évite de dupliquer cette logique quatre fois.
 */
object NavBar {

    enum class Tab { LIVE, MOSAIC, PLAYBACK, CAPTURES }

    /**
     * @param tabDirect etc. les TextView de l'onglet inclus dans l'écran
     * appelant (b.topbar.tabDirect, ...). Passés un par un plutôt que le
     * binding lui-même, pour rester indépendant du type de binding généré
     * par chaque écran.
     */
    fun setup(
        activity: AppCompatActivity,
        current: Tab,
        overflowButton: View,
        tabDirect: TextView,
        tabMosaic: TextView,
        tabPlayback: TextView,
        tabCaptures: TextView
    ) {
        val tabs = mapOf(
            Tab.LIVE to tabDirect,
            Tab.MOSAIC to tabMosaic,
            Tab.PLAYBACK to tabPlayback,
            Tab.CAPTURES to tabCaptures
        )
        for ((tab, view) in tabs) {
            view.setTextColor(
                activity.getColor(if (tab == current) R.color.accent else R.color.text_dim)
            )
        }

        tabDirect.setOnClickListener { go(activity, Tab.LIVE, current) }
        tabMosaic.setOnClickListener { go(activity, Tab.MOSAIC, current) }
        tabPlayback.setOnClickListener { go(activity, Tab.PLAYBACK, current) }
        tabCaptures.setOnClickListener { go(activity, Tab.CAPTURES, current) }

        overflowButton.setOnClickListener { showMenu(activity, overflowButton) }
    }

    private fun go(activity: AppCompatActivity, target: Tab, current: Tab) {
        if (target == current) return
        val cls = when (target) {
            Tab.LIVE -> MainActivity::class.java
            Tab.MOSAIC -> MosaicActivity::class.java
            Tab.PLAYBACK -> PlaybackActivity::class.java
            Tab.CAPTURES -> CapturesActivity::class.java
        }
        val intent = Intent(activity, cls).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        activity.startActivity(intent)
    }

    private fun showMenu(activity: AppCompatActivity, anchor: View) {
        val prefs = Prefs(activity)
        val host = Session.api?.host ?: prefs.host
        val menu = PopupMenu(activity, anchor)
        menu.menu.add(0, 1, 0, "Appareil : $host").isEnabled = false
        menu.menu.add(0, 2, 1, "Utilisateur : ${prefs.user}").isEnabled = false
        menu.menu.add(0, 3, 2, activity.getString(R.string.menu_native))
        menu.menu.add(0, 4, 3, activity.getString(R.string.menu_logout))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                3 -> {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$host/")))
                    true
                }
                4 -> {
                    Session.api = null
                    Session.cameras = emptyList()
                    val intent = Intent(activity, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    activity.startActivity(intent)
                    true
                }
                else -> false
            }
        }
        menu.show()
    }
}
