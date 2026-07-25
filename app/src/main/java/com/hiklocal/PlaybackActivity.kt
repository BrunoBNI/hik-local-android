package com.hiklocal

import android.Manifest
import android.app.DatePickerDialog
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
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.hiklocal.databinding.ActivityPlaybackBinding
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
    private var player: ExoPlayer? = null
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
                    stop()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun setupControls() {
        b.dateButton.setOnClickListener { pickDate() }
        b.playButton.setOnClickListener { playFromCurrent() }
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

    private fun play(url: String) {
        stop()
        paused = false
        b.pauseButton.text = "⏸"
        b.statusText.visibility = View.GONE
        b.loading.visibility = View.VISIBLE

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        b.playerView.player = exo
        exo.volume = if (muted) 0f else 1f
        exo.playbackParameters = PlaybackParameters(speed)

        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(15_000)
            .createMediaSource(MediaItem.fromUri(url))

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) b.loading.visibility = View.GONE
                if (state == Player.STATE_ENDED) showStatus(getString(R.string.err_no_video))
            }

            override fun onPlayerError(error: PlaybackException) {
                showStatus(getString(R.string.err_no_video))
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height
            }
        })

        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true

        playbackStartWallMs = System.currentTimeMillis()
        playbackStartMinute = minuteOfDay
        startCursor()
    }

    // -------------------------------------------------- Pause / son / vitesse

    private fun togglePause() {
        val p = player ?: run { playFromCurrent(); return }
        paused = !paused
        p.playWhenReady = !paused
        b.pauseButton.text = if (paused) "▶" else "⏸"
        if (paused) stopCursor() else startCursor()
    }

    private fun toggleSound() {
        muted = !muted
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
            Toast.makeText(this, "Vidéo pas encore prête", Toast.LENGTH_SHORT).show()
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
        stopCursor()
        b.timeline.setCursor(null)
        player?.release()
        player = null
        b.playerView.player = null
        b.loading.visibility = View.GONE
    }

    override fun onStop() {
        super.onStop()
        stop()
    }
}
