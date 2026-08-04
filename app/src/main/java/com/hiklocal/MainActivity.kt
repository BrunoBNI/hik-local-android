package com.hiklocal

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
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
import androidx.media3.ui.AspectRatioFrameLayout
import com.hiklocal.databinding.ActivityMainBinding
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
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

    companion object {
        /** Recul progressif entre les tentatives de reconnexion du direct. */
        private val RETRY_DELAYS_MS = longArrayOf(1500, 3000, 6000)
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var player: ExoPlayer? = null
    private var cameras: List<Camera> = emptyList()
    private var currentCam = 1
    private var muted = true

    /** Tentatives de reconnexion en cours pour la caméra actuellement affichée. */
    private var retriesLeft = 0
    private var retryHandler: Handler? = null

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
        b.recordButton.setOnClickListener { toggleRecording() }
        b.infoButton.setOnClickListener { showDeviceInfo() }

        setupStreamSpinner()
        setupRatioSpinner()
        setupZoom()
        loadCameras()
        applyLayout()

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
        showArrows()
        if (cameras.isEmpty()) return
        val i = cameras.indexOfFirst { it.id == currentCam }
        val next = ((if (i < 0) 0 else i) + delta + cameras.size) % cameras.size
        b.cameraSpinner.setSelection(next)   // déclenche startLive()
    }

    // ---------------------------------------------------------- Flux (menu)

    private fun setupStreamSpinner() {
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf(getString(R.string.live_stream_main), getString(R.string.live_stream_sub))
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        b.streamSpinner.adapter = adapter
        b.streamSpinner.setSelection(prefs.stream - 1)
        b.streamSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newStream = position + 1
                if (newStream != prefs.stream) {
                    prefs.stream = newStream
                    startLive()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ------------------------------------------------------- Affichage (menu)

    private fun setupRatioSpinner() {
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf(
                getString(R.string.live_ratio_169),
                getString(R.string.live_ratio_43),
                getString(R.string.live_ratio_native)
            )
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        b.ratioSpinner.adapter = adapter
        b.ratioSpinner.setSelection(prefs.ratio)
        applyRatio(prefs.ratio)
        b.ratioSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.ratio = position
                applyRatio(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** 0 = 16:9 forcé, 1 = 4:3 forcé, 2 = format natif de la caméra. */
    private fun applyRatio(mode: Int) {
        // En mode image, c'est l'ImageView qui affiche la vidéo : elle doit
        // suivre le même format, sinon le réglage n'a aucun effet sur les
        // caméras basculées en repli (c'est-à-dire la majorité ici).
        b.frameImage.scaleType = if (mode == 2) {
            ImageView.ScaleType.FIT_CENTER      // format natif : on respecte l'image
        } else {
            ImageView.ScaleType.FIT_XY          // format imposé : on remplit le cadre
        }

        // La largeur de l'écran est connue immédiatement, contrairement à
        // celle de la vue : s'appuyer dessus évite que le format ne
        // s'applique pas au tout premier affichage.
        val screenWidth = resources.displayMetrics.widthPixels
        val params = b.videoFrame.layoutParams as android.widget.LinearLayout.LayoutParams
        when (mode) {
            0 -> {
                params.height = (screenWidth * 9f / 16f).toInt(); params.weight = 0f
                b.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            1 -> {
                params.height = (screenWidth * 3f / 4f).toInt(); params.weight = 0f
                b.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            else -> {
                // Format natif : la vidéo occupe la place restante et garde
                // ses proportions d'origine, sans être étirée.
                params.height = 0; params.weight = 1f
                b.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }
        b.videoFrame.layoutParams = params
        applyVlcAspect()
    }

    // -------------------------------------------------------------- Direct

    private var videoWidth = 0
    private var videoHeight = 0
    private var pendingUrl: String? = null

    private fun startLive() {
        b.camNameOverlay.text = camLabelOf(currentCam)   // suit la caméra affichée
        if (recorder?.isRecording() == true) stopRecording()   // caméra changée : on ne mélange pas deux flux
        releaseVlc()             // on repart du lecteur natif à chaque fois
        stopFrameMode()          // on retente toujours la vidéo d'abord
        retryHandler?.removeCallbacksAndMessages(null)
        retriesLeft = RETRY_DELAYS_MS.size
        val url = api!!.liveUrl(currentCam, prefs.stream)
        pendingUrl = url
        releasePlayer()
        showStatus(null)
        b.loading.visibility = View.VISIBLE
        // Laisse le temps à la connexion précédente de se refermer côté
        // réseau avant d'en ouvrir une nouvelle : beaucoup d'enregistreurs
        // Hikvision limitent le nombre de sessions RTSP simultanées, et une
        // reconnexion trop immédiate peut être refusée pendant que
        // l'ancienne session se termine encore.
        val h = Handler(Looper.getMainLooper())
        retryHandler = h
        h.postDelayed({ play(url) }, 400)
    }

    private fun play(url: String) {
        if (url != pendingUrl) return   // une caméra plus récente a été sélectionnée entre-temps
        releasePlayer()
        showOnlyNative()

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
                    showStatus(null)   // efface un éventuel message "Reconnexion…" resté affiché
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val detail = error.cause?.message ?: error.message ?: "cause inconnue"

                // Certains firmwares Hikvision envoient une description SDP que
                // la bibliothèque RTSP d'Android rejette ("missing attribute
                // fmtp"). Le flux est pourtant bien là — ffmpeg le lit sans
                // problème, c'est pourquoi la version PC n'a jamais eu ce souci.
                // Inutile d'insister avec le lecteur vidéo dans ce cas : on
                // bascule directement sur les images JPEG de l'appareil, la
                // même source que la mosaïque, qui fonctionne pour toutes les
                // caméras.
                if (detail.contains("fmtp", ignoreCase = true) ||
                    detail.contains("SDP", ignoreCase = true) ||
                    detail.contains("IllegalArgument", ignoreCase = true)
                ) {
                    startVlcMode()
                    return
                }

                if (retriesLeft > 0) {
                    val attempt = RETRY_DELAYS_MS.size - retriesLeft + 1
                    val delay = RETRY_DELAYS_MS[RETRY_DELAYS_MS.size - retriesLeft]
                    retriesLeft--
                    showStatus("Reconnexion (tentative $attempt/${RETRY_DELAYS_MS.size})…")
                    val h = Handler(Looper.getMainLooper())
                    retryHandler = h
                    h.postDelayed({ play(url) }, delay)
                } else {
                    // Avant d'en venir aux images, on tente le moteur VLC.
                    startVlcMode()
                }
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
        vlcPlayer?.volume = if (muted) 0 else 100
        player?.volume = if (muted) 0f else 1f
        b.soundButton.setImageResource(
            if (muted) android.R.drawable.ic_lock_silent_mode
            else android.R.drawable.ic_lock_silent_mode_off
        )
    }

    // ------------------------------------------------------------- Infos

    private fun showDeviceInfo() {
        lifecycleScope.launch {
            when (val r = api!!.deviceInfo()) {
                is ApiResult.Ok -> {
                    val fields = api!!.parseDeviceInfo(r.value)
                    val message = if (fields.isEmpty()) r.value.take(500)
                    else fields.entries.joinToString("\n") { "${it.key} : ${it.value}" }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.info_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.info_close, null)
                        .show()
                }
                is ApiResult.Err -> Toast.makeText(
                    this@MainActivity, R.string.err_unreachable, Toast.LENGTH_SHORT
                ).show()
            }
        }
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
        if (vlcModeActive) {
            Toast.makeText(this, R.string.err_record_vlc, Toast.LENGTH_SHORT).show()
            return
        }
        if (frameModeActive) {
            // L'enregistrement capture le rendu du lecteur vidéo, absent en
            // mode image. La photo (📷) reste disponible, elle.
            Toast.makeText(this, "Enregistrement indisponible en mode image", Toast.LENGTH_SHORT).show()
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

    // ------------------------------------------------- Mode image (repli)

    // --------------------------------------------- Surfaces d'affichage

    /**
     * Trois surfaces se partagent la zone vidéo : le lecteur natif, celle de
     * VLC et l'image de repli. Elles doivent être strictement exclusives —
     * laisser deux surfaces vidéo actives fait apparaître l'image figée de
     * l'une par-dessus le flux de l'autre.
     */
    private fun showOnlyNative() {
        b.playerView.visibility = View.VISIBLE
        b.vlcLayout.visibility = View.GONE
        b.frameImage.visibility = View.GONE
    }

    private fun showOnlyVlc() {
        b.playerView.visibility = View.GONE
        b.vlcLayout.visibility = View.VISIBLE
        b.frameImage.visibility = View.GONE
    }

    private fun showOnlyFrames() {
        b.playerView.visibility = View.GONE
        b.vlcLayout.visibility = View.GONE
        b.frameImage.visibility = View.VISIBLE
    }

    // --------------------------------------- Vidéo par VLC (moteur ffmpeg)

    private var libVlc: LibVLC? = null
    private var vlcPlayer: MediaPlayer? = null
    private var vlcModeActive = false

    /**
     * Vraie vidéo, avec le son, pour les caméras dont le lecteur natif refuse
     * la fiche technique du flux ("missing attribute fmtp"). VLC repose sur
     * ffmpeg, bien plus tolérant — c'est exactement ce qui permet à la version
     * PC d'afficher les six caméras sans difficulté.
     */
    private fun startVlcMode() {
        if (vlcModeActive) return
        // Toujours repartir d'une session propre : une session précédente mal
        // libérée laisse son image à l'écran, et deux flux se superposent
        // quand on enchaîne les caméras. detachViews() suffit : vider la vue
        // détruirait les surfaces internes que VLCVideoLayout crée lui-même,
        // et le rattachement suivant échouerait.
        releaseVlc()
        vlcModeActive = true
        retryHandler?.removeCallbacksAndMessages(null)
        releasePlayer()
        stopFrameMode()
        showStatus(null)
        b.loading.visibility = View.VISIBLE
        showOnlyVlc()

        val url = api!!.liveUrl(currentCam, prefs.stream)
        val options = arrayListOf(
            "--rtsp-tcp",
            "--network-caching=1000",   // court : c'est du direct, la latence compte
            "--no-drop-late-frames",
            "--no-skip-frames"
        )
        // Un incident du moteur vidéo ne doit jamais emporter l'application :
        // en cas d'échec, on retombe sur le mode image, qui a toujours
        // fonctionné sur toutes les caméras.
        try {
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

            mp.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        b.loading.visibility = View.GONE
                        showStatus(null)
                    }
                    // Le format ne peut être imposé qu'une fois la sortie créée.
                    MediaPlayer.Event.Vout -> applyVlcAspect()
                    MediaPlayer.Event.EncounteredError -> {
                        releaseVlc()
                        startFrameMode()
                    }
                }
            }
            mp.play()
        } catch (e: Throwable) {
            releaseVlc()
            startFrameMode()
        }
    }

    /** Impose le format : ces caméras encodent en 960x1080 anamorphique. */
    private fun applyVlcAspect() {
        val mp = vlcPlayer ?: return
        if (prefs.ratio == 2) return          // format natif demandé : on n'impose rien
        mp.setAspectRatio(if (prefs.ratio == 1) "4:3" else "16:9")
        mp.setScale(0f)                       // 0 = adapter à la fenêtre
    }

    private fun releaseVlc() {
        val mp = vlcPlayer
        if (mp != null) {
            try {
                mp.setEventListener(null as MediaPlayer.EventListener?)
                if (mp.isPlaying) mp.stop()
                mp.detachViews()
                mp.release()
            } catch (e: Throwable) {
                // Libération partielle : sans importance, on repart à neuf.
            }
        }
        vlcPlayer = null
        try { libVlc?.release() } catch (e: Throwable) { }
        libVlc = null
        vlcModeActive = false
    }

    /**
     * Affichage par images successives, quand le lecteur vidéo refuse le flux
     * RTSP. Les images viennent de l'appareil en JPEG (même mécanisme que la
     * mosaïque, qui n'a jamais posé problème). Moins fluide qu'une vraie
     * vidéo et sans son, mais l'image s'affiche — sur toutes les caméras.
     */
    private var frameJob: kotlinx.coroutines.Job? = null
    private var frameModeActive = false

    private fun startFrameMode() {
        if (frameModeActive) return
        frameModeActive = true
        retryHandler?.removeCallbacksAndMessages(null)
        releasePlayer()
        showStatus(null)
        b.loading.visibility = View.VISIBLE
        showOnlyFrames()
        b.modeIndicator.visibility = View.VISIBLE
        Toast.makeText(this, R.string.live_frames_switch, Toast.LENGTH_SHORT).show()

        val camAtStart = currentCam
        frameJob?.cancel()
        frameJob = lifecycleScope.launch {
            var firstImage = true
            while (true) {
                if (camAtStart != currentCam) break   // caméra changée entre-temps
                val bytes = api?.snapshotFast(camAtStart, useSubStream = prefs.stream == 2)
                if (bytes != null) {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        b.frameImage.setImageBitmap(bmp)
                        if (firstImage) {
                            b.loading.visibility = View.GONE
                            videoWidth = bmp.width
                            videoHeight = bmp.height
                            firstImage = false
                        }
                    }
                }
                // ~3 images/seconde : compromis entre fluidité perçue et
                // charge réseau, l'appareil devant produire chaque JPEG.
                kotlinx.coroutines.delay(330)
            }
        }
    }

    private fun stopFrameMode() {
        frameJob?.cancel()
        frameJob = null
        frameModeActive = false
        b.modeIndicator.visibility = View.GONE
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

        var downX = 0f
        var downY = 0f
        var downTime = 0L

        b.videoFrame.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                    downX = event.x; downY = event.y
                    downTime = System.currentTimeMillis()
                    showArrows()     // un contact rappelle les commandes
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scale > 1.02f && event.pointerCount == 1) {
                        transX += event.x - lastX
                        transY += event.y - lastY
                        lastX = event.x; lastY = event.y
                        applyTransform()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    // Glisser horizontalement change de caméra. Indispensable
                    // en paysage, où les flèches s'effacent : viser un bouton
                    // masqué demandait deux gestes au lieu d'un.
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val quick = System.currentTimeMillis() - downTime < 700
                    val threshold = b.videoFrame.width * 0.18f
                    if (scale <= 1.02f && quick &&
                        kotlin.math.abs(dx) > threshold &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2
                    ) {
                        stepCamera(if (dx < 0) 1 else -1)   // vers la gauche = suivante
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
        // Traitée à part, avec un type explicite : la mêler aux autres vues
        // dans une même liste rendait le typage ambigu à la compilation.
        val vlcView: View = b.vlcLayout
        vlcView.scaleX = scale
        vlcView.scaleY = scale
        vlcView.pivotX = 0f
        vlcView.pivotY = 0f
        vlcView.translationX = transX
        vlcView.translationY = transY

        // Le zoom doit suivre la vue réellement affichée : lecteur vidéo en
        // temps normal, image en mode dégradé.
        for (v in listOf(b.playerView, b.frameImage)) {
            v.scaleX = scale
            v.scaleY = scale
            v.pivotX = 0f
            v.pivotY = 0f
            v.translationX = transX
            v.translationY = transY
        }
    }

    private var isFullscreenOn = false

    /**
     * En paysage, la vidéo occupe l'écran dans un cadre 16:9 centré et
     * l'interface s'efface : seul le nom de la caméra reste incrusté. En
     * portrait, elle garde son cadre au format choisi, sous les menus.
     */
    private fun applyLayout() {
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val immersive = landscape || isFullscreenOn

        b.topbar.root.visibility = if (immersive) View.GONE else View.VISIBLE
        b.headerRow.visibility = if (immersive) View.GONE else View.VISIBLE
        b.toolbarRow.visibility = if (immersive) View.GONE else View.VISIBLE
        b.camNameOverlay.visibility = if (immersive) View.VISIBLE else View.GONE
        b.camNameOverlay.text = camLabelOf(currentCam)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (immersive) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            // Cadre 16:9 aussi grand que possible, centré : remplir tout
            // l'écran déformerait l'image, la dalle n'étant pas en 16:9.
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            val params = b.videoFrame.layoutParams as android.widget.LinearLayout.LayoutParams
            if (w * 9 > h * 16) {          // écran plus large que du 16:9
                params.height = h
                params.width = h * 16 / 9
            } else {
                params.width = w
                params.height = w * 9 / 16
            }
            params.weight = 0f
            params.gravity = android.view.Gravity.CENTER
            b.videoFrame.layoutParams = params
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
            val params = b.videoFrame.layoutParams as android.widget.LinearLayout.LayoutParams
            params.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            params.gravity = android.view.Gravity.NO_GRAVITY
            b.videoFrame.layoutParams = params
            applyRatio(prefs.ratio)   // rétablit le cadre au format choisi
        }

        reattachVlcSurface()
        showArrows()                  // les flèches réapparaissent au changement
    }

    /**
     * À la rotation, l'activité n'est pas recréée mais la surface de rendu de
     * VLC est détruite : sans ce ré-attachement, l'image reste figée sur la
     * dernière trame et le format revient à celui de la source.
     */
    private fun reattachVlcSurface() {
        val mp = vlcPlayer ?: return
        try { mp.detachViews() } catch (e: Throwable) { return }
        b.vlcLayout.post {
            // La surface a pu changer entre-temps (caméra suivante, arrêt) :
            // sans cette vérification, l'ancien lecteur se rebrancherait et
            // son image se superposerait à la nouvelle.
            if (vlcPlayer === mp && vlcModeActive) {
                try {
                    mp.attachViews(b.vlcLayout, null, false, false)
                    applyVlcAspect()
                } catch (e: Throwable) {
                    releaseVlc()
                    startFrameMode()
                }
            }
        }
    }

    /** « Salon (4) » : nom donné dans le DVR, suivi du rang de la caméra. */
    private fun camLabelOf(id: Int): String =
        cameras.firstOrNull { it.id == id }?.label ?: "Caméra $id"

    private fun toggleFullscreen() {
        isFullscreenOn = !isFullscreenOn
        applyLayout()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // L'activité n'est pas recréée à la rotation (voir configChanges au
        // manifeste) : c'est ici qu'on réajuste la disposition.
        applyLayout()
    }

    // ------------------------------------------- Flèches à masquage auto

    private val arrowsHandler = Handler(Looper.getMainLooper())
    private var hideArrowsTask: Runnable? = null

    /**
     * Les flèches de changement de caméra s'effacent après quelques secondes
     * pour ne pas masquer l'image, et reviennent au moindre contact. C'est le
     * comportement attendu d'un lecteur vidéo.
     */
    private fun showArrows() {
        hideArrowsTask?.let { arrowsHandler.removeCallbacks(it) }
        for (v in listOf(b.prevButton, b.nextButton)) {
            v.visibility = View.VISIBLE
            v.animate().alpha(0.75f).setDuration(150).start()
        }
        val task = Runnable {
            for (v in listOf(b.prevButton, b.nextButton)) {
                v.animate().alpha(0f).setDuration(400).withEndAction {
                    v.visibility = View.INVISIBLE
                }.start()
            }
        }
        hideArrowsTask = task
        arrowsHandler.postDelayed(task, 3500)
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
        b.statusText.setOnClickListener { if (message != null) startLive() }
    }

    // ------------------------------------------------------- Cycle de vie

    override fun onStop() {
        super.onStop()
        // Libère le flux dès que l'écran passe en arrière-plan : inutile de
        // consommer la bande passante et la batterie. Un enregistrement en
        // cours est arrêté plutôt que perdu silencieusement en arrière-plan.
        retryHandler?.removeCallbacksAndMessages(null)
        if (recorder?.isRecording() == true) stopRecording()
        stopFrameMode()
        releaseVlc()
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
