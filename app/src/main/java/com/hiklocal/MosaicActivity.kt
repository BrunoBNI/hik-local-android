package com.hiklocal

import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hiklocal.databinding.ActivityMosaicBinding
import com.hiklocal.databinding.TileMosaicBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mosaïque en photos actualisées automatiquement, pas en vidéo continue.
 *
 * Décoder six flux RTSP HD en simultané ferait chauffer un téléphone et
 * viderait sa batterie en quelques minutes — un coût que la version PC n'a
 * pas. Une photo par caméra toutes les 2,5 secondes donne une vue quasi
 * instantanée pour une fraction de la charge, et chaque caméra peut être
 * coupée pour ne plus consommer de bande passante du tout.
 */
class MosaicActivity : AppCompatActivity() {

    private lateinit var b: ActivityMosaicBinding
    private lateinit var prefs: Prefs
    private var cameras: List<Camera> = emptyList()
    private val jobs = HashMap<Int, Job>()
    private lateinit var off: MutableSet<Int>

    private val api: HikApi? get() = Session.api

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMosaicBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        off = prefs.mosaicOff.toMutableSet()

        if (api == null) { finish(); return }

        NavBar.setup(
            this, NavBar.Tab.MOSAIC, b.topbar.overflowButton,
            b.topbar.tabDirect, b.topbar.tabMosaic, b.topbar.tabPlayback, b.topbar.tabCaptures
        )

        b.allOnButton.setOnClickListener { setAll(false) }
        b.allOffButton.setOnClickListener { setAll(true) }
        b.deviceLabel.text = api!!.host   // provisoire, remplacé dès que les infos arrivent

        loadDeviceLabel()
        loadCameras()
    }

    /** Modèle + numéro de série en en-tête, façon fiche d'appareil. */
    private fun loadDeviceLabel() {
        lifecycleScope.launch {
            val r = api!!.deviceInfo()
            if (r is ApiResult.Ok) {
                val fields = api!!.parseDeviceInfo(r.value)
                val model = fields["Modèle"]
                val serial = fields["Numéro de série"]
                b.deviceLabel.text = when {
                    model != null && serial != null -> "$model ($serial)"
                    model != null -> model
                    else -> api!!.host
                }
            }
        }
    }

    private fun loadCameras() {
        val cached = Session.cameras
        if (cached.isNotEmpty()) {
            cameras = cached
            buildGrid()
            return
        }
        lifecycleScope.launch {
            when (val r = api!!.cameras()) {
                is ApiResult.Ok -> {
                    cameras = r.value
                    Session.cameras = cameras
                    buildGrid()
                }
                is ApiResult.Err -> { /* la vue reste vide ; l'utilisateur peut revenir au Direct */ }
            }
        }
    }

    private fun setAll(offValue: Boolean) {
        off = if (offValue) cameras.map { it.id }.toMutableSet() else mutableSetOf()
        prefs.mosaicOff = off
        buildGrid()
    }

    private fun buildGrid() {
        stopAll()
        b.grid.removeAllViews()
        // Adapte le nombre de colonnes à la largeur réelle de l'écran plutôt
        // qu'un chiffre fixe : 2 sur un téléphone étroit, jusqu'à 4 sur un
        // écran large ou en mode paysage, pour ne pas gaspiller l'espace.
        val columns = (resources.configuration.screenWidthDp / 150).coerceIn(2, 4)
        b.grid.columnCount = columns

        for (cam in cameras) {
            val tile = TileMosaicBinding.inflate(LayoutInflater.from(this), b.grid, false)
            val params = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            )
            tile.root.layoutParams = params
            applyAspectRatio(tile.tileFrame)

            tile.tileLabel.text = cam.label
            setTileOff(tile, cam.id in off)

            tile.tileToggle.setOnClickListener {
                if (cam.id in off) off.remove(cam.id) else off.add(cam.id)
                prefs.mosaicOff = off
                setTileOff(tile, cam.id in off)
                if (cam.id in off) stopTile(cam.id) else startTile(cam.id, tile)
            }

            tile.root.setOnClickListener {
                if (cam.id !in off) {
                    prefs.lastCamera = cam.id
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                    )
                }
            }

            b.grid.addView(tile.root)
            if (cam.id !in off) startTile(cam.id, tile)
        }
        updateCount()
    }

    private fun setTileOff(tile: TileMosaicBinding, isOff: Boolean) {
        tile.tileOff.visibility = if (isOff) View.VISIBLE else View.GONE
        tile.tileImage.visibility = if (isOff) View.GONE else View.VISIBLE
        tile.tileToggle.setImageResource(
            if (isOff) android.R.drawable.ic_media_play else android.R.drawable.ic_lock_power_off
        )
    }

    /** Une image ImageView n'a pas de ratio fixe dans GridLayout : on le pose à la mesure. */
    private fun applyAspectRatio(frame: FrameLayout) {
        frame.addOnLayoutChangeListener { v, l, _, r, _, oldL, _, oldR, _ ->
            val width = r - l
            val oldWidth = oldR - oldL
            if (width > 0 && width != oldWidth) {
                v.layoutParams = v.layoutParams.apply { height = width * 9 / 16 }
            }
        }
    }

    /** Boucle de rafraîchissement d'une vignette : s'arrête net dès la coupure. */
    private fun startTile(camId: Int, tile: TileMosaicBinding) {
        jobs[camId]?.cancel()
        jobs[camId] = lifecycleScope.launch {
            while (true) {
                val bytes = api?.snapshot(camId)
                if (bytes != null) {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        tile.tileImage.setImageBitmap(bmp)
                        tile.tileLoading.visibility = View.GONE
                    }
                }
                delay(2500)
            }
        }
    }

    private fun stopTile(camId: Int) {
        jobs[camId]?.cancel()
        jobs.remove(camId)
    }

    private fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun updateCount() {
        val active = cameras.size - off.size
        b.countLabel.text = "$active flux actif" + (if (active > 1) "s" else "") +
            " sur ${cameras.size}" +
            (if (off.isNotEmpty()) " — ${off.size} coupé" + (if (off.size > 1) "s" else "") else "")
    }

    override fun onStop() {
        super.onStop()
        stopAll()   // aucune requête en arrière-plan quand l'écran n'est pas visible
    }

    override fun onStart() {
        super.onStart()
        if (cameras.isNotEmpty() && jobs.isEmpty()) buildGrid()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Le nombre de colonnes dépend de la largeur d'écran : à recalculer
        // après une rotation, faute de quoi l'activité garde l'ancienne
        // disposition (elle n'est pas recréée, voir configChanges au manifeste).
        if (cameras.isNotEmpty()) buildGrid()
    }
}
