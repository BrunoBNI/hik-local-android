package com.hiklocal

import android.Manifest
import android.app.DatePickerDialog
import android.content.res.Configuration
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.SurfaceView
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.hiklocal.databinding.ActivityPlaybackBinding
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Relecture des enregistrements.
 *
 * Le flux RTSP d'archive ne se déplace pas : pour changer d'instant, on
 * relance simplement la lecture à la nouvelle heure. La pause, en revanche,
 * n'exige pas de relancer le flux — ExoPlayer sait mettre n'importe quelle
 * source en attente.
 *
 * La frise ne présente pas les segments réellement enregistrés (voir la note
 * dans TimelineView) : elle reste utilisable pour naviguer, juste sans ce
 * repère visuel supplémentaire.
 */
class PlaybackActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CAM = "cam"
        val SPEEDS = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f, 8f, 16f)
    }

    private lateinit var b: ActivityPlaybackBinding
    private var player: ExoPlayer? = null      // sert à la lecture d'un extrait téléchargé
    private var libVlc: LibVLC? = null         // lecture du flux d'archive
    private var vlcPlayer: MediaPlayer? = null
    private var cameras: List<Camera> = emptyList()
    private var currentCam = 1

    private var year = 0
    private var month = 0      // 0-11, convention Calendar
    private var day = 0
    private var minuteOfDay = 0

    private var muted = true
    private var speed = 1f
    private var paused = false

    private var videoWidth = 0
    private var videoHeight = 0
    private var recorder: VideoRecorder? = null

    private val cursorHandler = Handler(Looper.getMainLooper())
    private var cursorRunnable: Runnable? = null
    private var playbackStartWallMs = 0L
    private var playbackStartMinute = 0

    private val api: HikApi? get() = Session.api

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlaybackBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (api == null) { finish(); return }

        currentCam = intent.getIntExtra(EXTRA_CAM, 1)

        val now = Calendar.getInstance()
        year = now.get(Calendar.YEAR)
        month = now.get(Calendar.MONTH)
        day = now.get(Calendar.DAY_OF_MONTH)
        minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        setupCameras()
        setupControls()
        updateLabels()
        applyLayout()

        NavBar.setup(
            this, NavBar.Tab.PLAYBACK, b.topbar.overflowButton,
            b.topbar.tabDirect, b.topbar.tabMosaic, b.topbar.tabPlayback, b.topbar.tabCaptures
        )
    }

    private fun setupCameras() {
        cameras = Session.cameras
        if (cameras.isEmpty()) return
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, cameras.map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        b.cameraSpinner.adapter = adapter
        val index = cameras.indexOfFirst { it.id == currentCam }
        b.cameraSpinner.setSelection(if (index >= 0) index else 0)
        b.cameraSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    currentCam = cameras[position].id
                    // Changement de caméra : on coupe net la lecture en cours
                    // plutôt que de la laisser tourner sur l'ancienne source.
                    stop()
                    b.timeline.setCursor(null)
                    b.timeLabel.text = ""
                    showStatus(getString(R.string.pb_hint))
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun setupControls() {
        b.dateButton.setOnClickListener { pickDate() }
        b.pbFullscreenButton.setOnClickListener { toggleFullscreen() }
        b.back5.setOnClickListener { shift(-5) }
        b.back1.setOnClickListener { shift(-1) }
        b.fwd1.setOnClickListener { shift(1) }
        b.fwd5.setOnClickListener { shift(5) }
        b.pauseButton.setOnClickListener { togglePause() }
        b.pbSoundButton.setOnClickListener { toggleSound() }
        b.speedDown.setOnClickListener { changeSpeed(-1) }
        b.speedUp.setOnClickListener { changeSpeed(1) }
        b.pbRecordButton.setOnClickListener { toggleRecording() }

        b.tlZoomIn.setOnClickListener { b.timeline.zoomBy(1.6f) }
        b.tlZoomOut.setOnClickListener { b.timeline.zoomBy(1 / 1.6f) }
        b.tlReset.setOnClickListener { b.timeline.resetFullDay() }

        b.timeline.onSeek = TimelineView.OnSeekListener { minute ->
            minuteOfDay = minute
            updateLabels()
            playFromCurrent()
        }
        b.timeline.onScrubPreview = { minute ->
            if (minute != null) {
                b.timeLabel.text = "%02d:%02d".format(minute / 60, minute % 60)
            } else {
                updateLabels()
            }
        }
    }

    private fun pickDate() {
        DatePickerDialog(this, { _, y, m, d ->
            year = y; month = m; day = d
            updateLabels()
            stop()
        }, year, month, day).show()
    }

    private fun shift(minutes: Int) {
        minuteOfDay = (minuteOfDay + minutes).coerceIn(0, 1439)
        updateLabels()
        playFromCurrent()
    }

    private fun updateLabels() {
        b.timeLabel.text = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
        b.dateButton.text = "%02d/%02d/%04d".format(day, month + 1, year)
    }

    /**
     * L'appareil attend un horodatage de la forme 20260723T200000Z. Les
     * valeurs choisies sont envoyées telles quelles, sans conversion de fuseau :
     * l'enregistreur les interprète dans son propre temps, ce qui correspond à
     * ce que l'utilisateur lit sur ses images.
     */
    private fun stamp(minutes: Int): String =
        "%04d%02d%02dT%02d%02d00Z".format(year, month + 1, day, minutes / 60, minutes % 60)

    private fun playFromCurrent() {
        if (recorder?.isRecording() == true) stopRecording()   // on ne change pas d'instant en pleine capture
        val endOfDay = 1439
        val url = api!!.playbackUrl(currentCam, stamp(minuteOfDay), stamp(endOfDay))
        play(url)
    }

    /**
     * Lecture par LibVLC, et non par le lecteur Android.
     *
     * C'est le même principe que la version PC : un moteur fondé sur ffmpeg,
     * qui accepte les descriptions SDP non strictement conformes de ces
     * caméras. Le lecteur intégré d'Android, lui, les refuse catégoriquement
     * ("SDP format error"), ce qui rendait la lecture impossible.
     */
    private fun play(url: String) {
        stop()
        paused = false
        b.pauseButton.text = "⏸"
        b.statusText.visibility = View.GONE
        b.loading.visibility = View.VISIBLE

        val options = arrayListOf(
            "--rtsp-tcp",              // plus fiable que l'UDP en Wi-Fi
            "--network-caching=1500",  // absorbe les à-coups du réseau
            "--no-drop-late-frames",
            "--no-skip-frames"
        )
        val vlc = LibVLC(this, options)
        libVlc = vlc
        val mp = MediaPlayer(vlc)
        vlcPlayer = mp
        mp.attachViews(b.vlcLayout, null, false, false)

        val media = Media(vlc, android.net.Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        mp.media = media
        media.release()

        mp.volume = if (muted) 0 else 100
        mp.rate = speed
        mp.setAspectRatio("16:9")   // ces caméras encodent en 960x1080 anamorphique
        mp.setScale(0f)             // 0 = adapter à la fenêtre

        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    b.loading.visibility = View.GONE
                    b.statusText.visibility = View.GONE
                }
                MediaPlayer.Event.EndReached -> showStatus(getString(R.string.err_no_video))
                MediaPlayer.Event.EncounteredError -> {
                    // Si même VLC n'y arrive pas, il n'y a probablement pas
                    // d'enregistrement à cet instant : on tente alors la
                    // récupération de l'extrait par téléchargement.
                    playByDownload()
                }
            }
        }

        mp.play()

        playbackStartWallMs = System.currentTimeMillis()
        playbackStartMinute = minuteOfDay
        startCursor()
    }

    private fun releaseVlc() {
        vlcPlayer?.let {
            // Le type doit être explicite : sans cela, Kotlin ne sait pas
            // quelle surcharge de setEventListener viser avec null.
            it.setEventListener(null as MediaPlayer.EventListener?)
            if (it.isPlaying) it.stop()
            it.detachViews()
            it.release()
        }
        vlcPlayer = null
        libVlc?.release()
        libVlc = null
    }

    // ------------------------------------ Lecture par téléchargement (repli)

    /** Durée d'un extrait téléchargé. Court, pour ne pas faire attendre. */
    private val segmentMinutes = 2
    private var downloadJob: kotlinx.coroutines.Job? = null
    private var downloadedFile: java.io.File? = null

    /**
     * Récupère l'extrait par HTTP puis le lit depuis le téléphone. Aucun RTSP
     * n'intervient, ce qui contourne le refus du lecteur. Bonus : sur un
     * fichier local, la pause, la vitesse et le déplacement fonctionnent
     * pleinement, contrairement à un flux en direct.
     */
    private fun playByDownload() {
        downloadJob?.cancel()
        releasePlayerOnly()
        b.loading.visibility = View.VISIBLE
        showStatus(getString(R.string.pb_downloading))

        val startMin = minuteOfDay
        val endMin = (minuteOfDay + segmentMinutes).coerceAtMost(1439)
        val cam = currentCam
        val file = java.io.File(cacheDir, "playback_${cam}_$startMin.mp4")

        downloadJob = lifecycleScope.launch {
            val result = api!!.downloadSegment(cam, stamp(startMin), stamp(endMin), file) { bytes ->
                val mo = bytes / 1_000_000.0
                runOnUiThread {
                    showStatus(getString(R.string.pb_downloading) + " %.1f Mo".format(mo))
                }
            }
            if (!result.ok) {
                // Le détail vient de l'appareil lui-même : c'est ce qui permet
                // de corriger précisément plutôt que de deviner.
                showStatus(getString(R.string.err_playback_download) +
                    (if (result.detail.isNotEmpty()) "\n\n" + result.detail else ""))
                file.delete()
                return@launch
            }
            downloadedFile?.delete()          // libère l'extrait précédent
            downloadedFile = file
            b.statusText.visibility = View.GONE
            playLocalFile(file)
        }
    }

    private fun playLocalFile(file: java.io.File) {
        releasePlayerOnly()
        b.loading.visibility = View.VISIBLE

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        b.playerView.player = exo
        exo.volume = if (muted) 0f else 1f
        exo.playbackParameters = PlaybackParameters(speed)

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) b.loading.visibility = View.GONE
                if (state == Player.STATE_ENDED) {
                    // Extrait terminé : on enchaîne sur les minutes suivantes,
                    // ce qui donne une lecture continue malgré le découpage.
                    minuteOfDay = (minuteOfDay + segmentMinutes).coerceAtMost(1439)
                    updateLabels()
                    if (minuteOfDay < 1439) playByDownload()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                showStatus(getString(R.string.err_playback_format))
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height
            }
        })

        exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        exo.prepare()
        exo.playWhenReady = true

        playbackStartWallMs = System.currentTimeMillis()
        playbackStartMinute = minuteOfDay
        startCursor()
    }

    /** Libère le lecteur sans toucher au reste (curseur, état du téléchargement). */
    private fun releasePlayerOnly() {
        releaseVlc()
        player?.release()
        player = null
        b.playerView.player = null
    }

    // ---------------------------------------------- Mise en page / rotation

    private var isFullscreenOn = false

    /**
     * En portrait, la vidéo occupe un cadre 16:9 sous les menus. En paysage,
     * elle prend toute la place disponible et l'interface se réduit au
     * minimum : c'est l'orientation qu'on choisit justement pour voir grand.
     */
    private fun applyLayout() {
        val landscape = resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        val immersive = landscape || isFullscreenOn

        b.topbar.root.visibility = if (immersive) View.GONE else View.VISIBLE
        b.headerRow.visibility = if (immersive) View.GONE else View.VISIBLE
        b.timelineTools.visibility = if (immersive) View.GONE else View.VISIBLE
        b.timeline.visibility = if (immersive) View.GONE else View.VISIBLE

        val params = b.videoFrame.layoutParams as android.widget.LinearLayout.LayoutParams
        if (immersive) {
            params.height = 0
            params.weight = 1f
        } else {
            // 16:9 calculé sur la largeur d'écran, connue immédiatement.
            params.height = (resources.displayMetrics.widthPixels * 9f / 16f).toInt()
            params.weight = 0f
        }
        b.videoFrame.layoutParams = params

        // VLC impose lui aussi le format, sinon il conserve celui de la source
        // (960x1080 sur ces caméras, donc une image étirée en hauteur).
        vlcPlayer?.let {
            it.setAspectRatio("16:9")
            it.setScale(0f)          // 0 = adapter à la fenêtre
        }

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (immersive) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun toggleFullscreen() {
        isFullscreenOn = !isFullscreenOn
        applyLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // L'activité n'est pas recréée à la rotation (voir configChanges au
        // manifeste) : c'est ici qu'on réajuste la disposition.
        applyLayout()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isFullscreenOn) { toggleFullscreen(); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // -------------------------------------------------- Pause / son / vitesse

    private fun togglePause() {
        val vlc = vlcPlayer
        val exo = player
        if (vlc == null && exo == null) { playFromCurrent(); return }
        paused = !paused
        vlc?.let { if (paused) it.pause() else it.play() }
        exo?.playWhenReady = !paused
        b.pauseButton.text = if (paused) "▶" else "⏸"
        if (paused) stopCursor() else startCursor()
    }

    private fun toggleSound() {
        muted = !muted
        vlcPlayer?.volume = if (muted) 0 else 100
        player?.volume = if (muted) 0f else 1f
        b.pbSoundButton.setImageResource(
            if (muted) android.R.drawable.ic_lock_silent_mode
            else android.R.drawable.ic_lock_silent_mode_off
        )
    }

    /**
     * La vitesse change le rythme de lecture côté téléphone, mais l'appareil
     * envoie les images à son propre rythme : au-delà de ×4, l'image peut
     * marquer des pauses le temps que les données suivantes arrivent — ce
     * n'est pas un blocage, juste la limite du débit disponible.
     */
    private fun changeSpeed(dir: Int) {
        val i = SPEEDS.toList().indexOf(speed).let { if (it < 0) 2 else it }
        val next = SPEEDS[(i + dir).coerceIn(0, SPEEDS.size - 1)]
        if (next == speed) return
        // On resynchronise la base de temps du curseur sur la position actuelle
        // avant de changer de vitesse, sinon il saute en avant ou en arrière.
        playbackStartMinute = currentEstimatedMinute()
        playbackStartWallMs = System.currentTimeMillis()
        speed = next
        b.speedLabel.text = "×" + (if (speed < 1f) speed.toString() else speed.toInt().toString())
        vlcPlayer?.rate = speed
        player?.playbackParameters = PlaybackParameters(speed)
    }

    // ------------------------------------------------------- Curseur (frise)

    private fun currentEstimatedMinute(): Int {
        if (paused) return minuteOfDay
        val elapsedS = (System.currentTimeMillis() - playbackStartWallMs) / 1000.0 * speed
        return (playbackStartMinute + (elapsedS / 60.0)).toInt().coerceIn(0, 1439)
    }

    private fun startCursor() {
        stopCursor()
        val r = object : Runnable {
            override fun run() {
                val m = currentEstimatedMinute()
                minuteOfDay = m
                b.timeline.setCursor(m)
                updateLabels()
                cursorHandler.postDelayed(this, 1000)
            }
        }
        cursorRunnable = r
        cursorHandler.post(r)
    }

    private fun stopCursor() {
        cursorRunnable?.let { cursorHandler.removeCallbacks(it) }
        cursorRunnable = null
    }

    // --------------------------------------------------- Enregistrement vidéo

    private fun toggleRecording() {
        val rec = recorder
        if (rec != null && rec.isRecording()) {
            stopRecording()
            return
        }
        val surfaceView = b.playerView.videoSurfaceView as? SurfaceView
        if (surfaceView == null || videoWidth <= 0 || videoHeight <= 0 || player == null) {
            // La lecture d'archive passe par LibVLC, dont le rendu n'est pas
            // capturable par ce mécanisme. L'enregistrement reste disponible
            // sur le direct.
            Toast.makeText(this, R.string.err_record_playback, Toast.LENGTH_SHORT).show()
            return
        }
        val newRecorder = VideoRecorder(currentCam)
        val started = newRecorder.start(cacheDir, surfaceView, videoWidth, videoHeight) { success, file, message ->
            onRecordingStopped(success, file, message)
        }
        if (started) {
            recorder = newRecorder
            b.pbRecordButton.setImageResource(android.R.drawable.presence_video_busy)
        } else {
            Toast.makeText(this, "Impossible de démarrer l'enregistrement", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        recorder?.stop()
    }

    private fun onRecordingStopped(success: Boolean, file: File?, message: String) {
        b.pbRecordButton.setImageResource(android.R.drawable.presence_video_online)
        recorder = null
        if (!success || file == null) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }
        withStoragePermission { saveVideoToGallery(file) }
    }

    private var pendingAfterPermission: (() -> Unit)? = null
    private val askWritePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingAfterPermission?.invoke()
            else Toast.makeText(this, "Permission refusée", Toast.LENGTH_SHORT).show()
            pendingAfterPermission = null
        }

    private fun withStoragePermission(action: () -> Unit) {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingAfterPermission = action
            askWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    private fun saveVideoToGallery(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            var savedName: String? = null
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val name = "hik_${currentCam}_playback_$stamp.mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    savedName = name
                }
            } catch (e: Exception) {
                savedName = null
            } finally {
                file.delete()
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@PlaybackActivity,
                    if (savedName != null) "Vidéo enregistrée : $savedName"
                    else "Enregistrement de la vidéo impossible",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ------------------------------------------------------------ Arrêt

    private fun showStatus(message: String) {
        b.loading.visibility = View.GONE
        b.statusText.text = message
        b.statusText.visibility = View.VISIBLE
    }

    private fun stop() {
        if (recorder?.isRecording() == true) stopRecording()
        downloadJob?.cancel()
        downloadJob = null
        stopCursor()
        b.timeline.setCursor(null)
        releaseVlc()
        player?.release()
        player = null
        b.playerView.player = null
        b.loading.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        // Les extraits téléchargés sont temporaires : inutile d'occuper le
        // stockage du téléphone une fois l'écran quitté.
        downloadedFile?.delete()
        downloadedFile = null
    }

    override fun onStop() {
        super.onStop()
        stop()
    }
}
