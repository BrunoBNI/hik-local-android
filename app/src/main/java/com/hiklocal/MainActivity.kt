package com.hiklocal

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.hiklocal.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var player: ExoPlayer? = null
    private var cameras: List<Camera> = emptyList()
    private var currentCam = 1
    private var muted = true

    private val api: HikApi?
        get() = Session.api

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        if (api == null) {          // session perdue (application relancée)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        b.prevButton.setOnClickListener { stepCamera(-1) }
        b.nextButton.setOnClickListener { stepCamera(1) }
        b.playbackButton.setOnClickListener { openPlayback() }
        b.soundButton.setOnClickListener { toggleSound() }
        b.snapshotButton.setOnClickListener { withStoragePermission { saveSnapshot() } }
        b.streamButton.setOnClickListener { toggleStream() }
        b.recordButton.setOnClickListener { toggleRecording() }

        setupZoom()
        loadCameras()

        NavBar.setup(
            this, NavBar.Tab.LIVE, b.topbar.overflowButton,
            b.topbar.tabDirect, b.topbar.tabMosaic, b.topbar.tabPlayback, b.topbar.tabCaptures
        )
    }

    // ------------------------------------------------------------ Caméras

    private fun loadCameras() {
        showStatus(null)
        lifecycleScope.launch {
            when (val r = api!!.cameras()) {
                is ApiResult.Ok -> {
                    cameras = r.value
                    Session.cameras = cameras
                    if (cameras.isEmpty()) {
                        showStatus(getString(R.string.err_no_camera))
                        return@launch
                    }
                    val adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_item,
                        cameras.map { it.label }
                    )
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    b.cameraSpinner.adapter = adapter

                    val index = cameras.indexOfFirst { it.id == prefs.lastCamera }
                    b.cameraSpinner.setSelection(if (index >= 0) index else 0)

                    b.cameraSpinner.onItemSelectedListener =
                        object : AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(
                                parent: AdapterView<*>?, view: View?, position: Int, id: Long
                            ) {
                                currentCam = cameras[position].id
                                prefs.lastCamera = currentCam
                                startLive()
                            }

                            override fun onNothingSelected(parent: AdapterView<*>?) {}
                        }
                }
                is ApiResult.Err -> showStatus(getString(R.string.err_unreachable))
            }
        }
    }

    private fun stepCamera(delta: Int) {
        if (cameras.isEmpty()) return
        val i = cameras.indexOfFirst { it.id == currentCam }
        val next = ((if (i < 0) 0 else i) + delta + cameras.size) % cameras.size
        b.cameraSpinner.setSelection(next)   // déclenche startLive()
    }

    // -------------------------------------------------------------- Direct

    private var videoWidth = 0
    private var videoHeight = 0

    private fun startLive() {
        if (recorder?.isRecording() == true) stopRecording()   // caméra changée : on ne mélange pas deux flux
        val url = api!!.liveUrl(currentCam, prefs.stream)
        play(url)
    }

    private fun play(url: String) {
        releasePlayer()
        showStatus(null)
        b.loading.visibility = View.VISIBLE

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        b.playerView.player = exo
        exo.volume = if (muted) 0f else 1f

        // Le RTSP en UDP passe mal sur beaucoup de réseaux Wi-Fi : on force le
        // TCP, plus fiable, au prix d'une latence légèrement supérieure.
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(12_000)
            .createMediaSource(MediaItem.fromUri(url))

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    b.loading.visibility = View.GONE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                b.loading.visibility = View.GONE
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
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        b.playerView.player = null
    }

    private fun toggleSound() {
        muted = !muted
        player?.volume = if (muted) 0f else 1f
        b.soundButton.setImageResource(
            if (muted) android.R.drawable.ic_lock_silent_mode
            else android.R.drawable.ic_lock_silent_mode_off
        )
    }

    private fun toggleStream() {
        prefs.stream = if (prefs.stream == 1) 2 else 1
        Toast.makeText(
            this,
            if (prefs.stream == 1) "Flux principal" else "Flux secondaire",
            Toast.LENGTH_SHORT
        ).show()
        startLive()
    }

    // ---------------------------------------------------------- Permission

    /**
     * Sur Android 8 et 9, écrire dans la galerie exige une permission ;
     * à partir d'Android 10 le stockage cloisonné s'en passe.
     */
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

    // --------------------------------------------------------------- Photo

    private fun saveSnapshot() {
        lifecycleScope.launch {
            val bytes = api!!.snapshot(currentCam)
            if (bytes == null) {
                Toast.makeText(this@MainActivity, R.string.err_unreachable, Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "hik_${currentCam}_$stamp.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri == null) {
                Toast.makeText(this@MainActivity, "Enregistrement impossible", Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(this@MainActivity, "Photo enregistrée : $name", Toast.LENGTH_SHORT)
                .show()
        }
    }

    // --------------------------------------------------- Enregistrement vidéo

    private var recorder: VideoRecorder? = null

    private fun toggleRecording() {
        val rec = recorder
        if (rec != null && rec.isRecording()) {
            stopRecording()
            return
        }
        val surfaceView = b.playerView.videoSurfaceView as? SurfaceView
        if (surfaceView == null || videoWidth <= 0 || videoHeight <= 0) {
            Toast.makeText(this, "Vidéo pas encore prête", Toast.LENGTH_SHORT).show()
            return
        }
        val newRecorder = VideoRecorder(currentCam)
        val started = newRecorder.start(cacheDir, surfaceView, videoWidth, videoHeight) { success, file, message ->
            onRecordingStopped(success, file, message)
        }
        if (started) {
            recorder = newRecorder
            b.recIndicator.visibility = View.VISIBLE
            b.recordButton.setImageResource(android.R.drawable.presence_video_busy)
        } else {
            Toast.makeText(this, "Impossible de démarrer l'enregistrement", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        recorder?.stop()
    }

    /** Toujours appelé sur le fil principal par VideoRecorder, quelle qu'en soit la cause. */
    private fun onRecordingStopped(success: Boolean, file: File?, message: String) {
        b.recIndicator.visibility = View.GONE
        b.recordButton.setImageResource(android.R.drawable.presence_video_online)
        recorder = null
        if (!success || file == null) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            return
        }
        withStoragePermission { saveVideoToGallery(file) }
    }

    private fun saveVideoToGallery(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            var savedName: String? = null
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val name = "hik_${currentCam}_video_$stamp.mp4"
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
                    this@MainActivity,
                    if (savedName != null) "Vidéo enregistrée : $savedName"
                    else "Enregistrement de la vidéo impossible",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ------------------------------------------------------ Zoom / plein écran

    private var scale = 1f
    private var transX = 0f
    private var transY = 0f
    private lateinit var scaleDetector: ScaleGestureDetector

    private fun setupZoom() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyZoom(scale * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })

        var lastX = 0f
        var lastY = 0f

        b.videoFrame.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scale > 1.02f && event.pointerCount == 1) {
                        transX += event.x - lastX
                        transY += event.y - lastY
                        lastX = event.x; lastY = event.y
                        applyTransform()
                    }
                }
            }
            true
        }

        b.zoomInButton.setOnClickListener { applyZoom(scale * 1.4f, b.videoFrame.width / 2f, b.videoFrame.height / 2f) }
        b.zoomOutButton.setOnClickListener { applyZoom(scale / 1.4f, b.videoFrame.width / 2f, b.videoFrame.height / 2f) }
        b.zoomResetButton.setOnClickListener { resetZoom() }
        b.fullscreenButton.setOnClickListener { toggleFullscreen() }
    }

    private fun applyZoom(newScale: Float, focusX: Float, focusY: Float) {
        val clamped = max(1f, min(6f, newScale))
        if (clamped == scale) return
        // Garde le point sous les doigts à sa place pendant le zoom.
        transX = focusX - (focusX - transX) * (clamped / scale)
        transY = focusY - (focusY - transY) * (clamped / scale)
        scale = clamped
        if (scale <= 1.02f) { scale = 1f; transX = 0f; transY = 0f }
        applyTransform()
    }

    private fun resetZoom() {
        scale = 1f; transX = 0f; transY = 0f
        applyTransform()
    }

    private fun applyTransform() {
        b.playerView.scaleX = scale
        b.playerView.scaleY = scale
        b.playerView.pivotX = 0f
        b.playerView.pivotY = 0f
        b.playerView.translationX = transX
        b.playerView.translationY = transY
    }

    private var isFullscreenOn = false

    private fun toggleFullscreen() {
        isFullscreenOn = !isFullscreenOn
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (isFullscreenOn) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            b.topbar.root.visibility = View.GONE
            b.headerRow.visibility = View.GONE
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
            b.topbar.root.visibility = View.VISIBLE
            b.headerRow.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------- Lecture

    private fun openPlayback() {
        val intent = Intent(this, PlaybackActivity::class.java)
        intent.putExtra(PlaybackActivity.EXTRA_CAM, currentCam)
        startActivity(intent)
    }

    private fun showStatus(message: String?) {
        b.statusText.text = message.orEmpty()
        b.statusText.visibility = if (message == null) View.GONE else View.VISIBLE
        if (message != null) b.loading.visibility = View.GONE
    }

    // ------------------------------------------------------- Cycle de vie

    override fun onStop() {
        super.onStop()
        // Libère le flux dès que l'écran passe en arrière-plan : inutile de
        // consommer la bande passante et la batterie. Un enregistrement en
        // cours est arrêté plutôt que perdu silencieusement en arrière-plan.
        if (recorder?.isRecording() == true) stopRecording()
        releasePlayer()
    }

    override fun onStart() {
        super.onStart()
        if (cameras.isNotEmpty() && player == null) startLive()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isFullscreenOn) { toggleFullscreen(); return }
        super.onBackPressed()
    }
}
