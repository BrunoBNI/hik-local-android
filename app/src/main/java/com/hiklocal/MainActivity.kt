package com.hiklocal

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.hiklocal.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        b.snapshotButton.setOnClickListener { takeSnapshot() }
        b.streamButton.setOnClickListener { toggleStream() }

        setupPtz()
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

    private fun startLive() {
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

    // --------------------------------------------------------------- Photo

    /**
     * Sur Android 8 et 9, écrire dans la galerie exige une permission ;
     * à partir d'Android 10 le stockage cloisonné s'en passe.
     */
    private val askWritePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) saveSnapshot()
            else Toast.makeText(this, "Permission refusée", Toast.LENGTH_SHORT).show()
        }

    private fun takeSnapshot() {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

        if (needsPermission) {
            askWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveSnapshot()
        }
    }

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

    // ----------------------------------------------------------------- PTZ

    private fun setupPtz() {
        bindPtz(b.ptzUp, 0, 60, 0)
        bindPtz(b.ptzDown, 0, -60, 0)
        bindPtz(b.ptzLeft, -60, 0, 0)
        bindPtz(b.ptzRight, 60, 0, 0)
        bindPtz(b.ptzZoomIn, 0, 0, 60)
        bindPtz(b.ptzZoomOut, 0, 0, -60)
    }

    /** Le mouvement dure tant que le doigt reste posé, comme sur un pupitre. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindPtz(view: View, pan: Int, tilt: Int, zoom: Int) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sendPtz(pan, tilt, zoom, report = true)
                    v.isPressed = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sendPtz(0, 0, 0, report = false)
                    v.isPressed = false
                    v.performClick()
                }
            }
            true
        }
    }

    private fun sendPtz(pan: Int, tilt: Int, zoom: Int, report: Boolean) {
        lifecycleScope.launch {
            val r = api!!.ptz(currentCam, pan, tilt, zoom)
            if (report && r is ApiResult.Err) {
                Toast.makeText(
                    this@MainActivity,
                    if (r.kind == ErrorKind.DEVICE) getString(R.string.ptz_unsupported)
                    else getString(R.string.err_unreachable),
                    Toast.LENGTH_SHORT
                ).show()
            }
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
        // consommer la bande passante et la batterie.
        releasePlayer()
    }

    override fun onStart() {
        super.onStart()
        if (cameras.isNotEmpty() && player == null) startLive()
    }
}
