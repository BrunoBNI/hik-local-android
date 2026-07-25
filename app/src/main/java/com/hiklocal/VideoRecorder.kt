package com.hiklocal

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Enregistrement vidéo local, par capture périodique du rendu vidéo puis
 * ré-encodage en H.264 — le même principe que le service PC (qui utilise
 * OpenCV pour ça), mais avec les briques bas niveau d'Android : PixelCopy
 * pour extraire une image du rendu ExoPlayer, MediaCodec pour l'encoder,
 * MediaMuxer pour écrire le fichier MP4.
 *
 * C'est la partie la plus délicate de l'application. Le direct et la lecture
 * s'appuient sur des API Android matures et déjà éprouvées (ExoPlayer,
 * RTSP) ; ceci combine trois API bas niveau qui n'ont pas pu être testées
 * sur un vrai téléphone avant livraison. Les dimensions sont donc bridées et
 * chaque étape est protégée pour échouer proprement plutôt que de faire
 * planter l'application.
 */
class VideoRecorder(private val camId: Int) {

    companion object {
        /** Largeur maximale d'encodage : garde la mémoire et le CPU sous contrôle
         *  quelle que soit la résolution native de la caméra. */
        private const val MAX_WIDTH = 640
        private const val BITRATE = 900_000
        private const val FPS = 8
    }

    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var drainThread: Thread? = null
    private var captureThread: Thread? = null
    private var tempFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isRecording() = running

    /**
     * Démarre la capture. `onStopped` est toujours appelé sur le fil principal,
     * que l'arrêt vienne de [stop], d'une erreur, ou de la durée maximale.
     */
    fun start(
        cacheDir: File,
        sourceView: SurfaceView,
        rawWidth: Int,
        rawHeight: Int,
        maxDurationMs: Long = 180_000L,
        onStopped: (success: Boolean, file: File?, message: String) -> Unit
    ): Boolean {
        if (running || rawWidth <= 0 || rawHeight <= 0) return false

        val scale = if (rawWidth > MAX_WIDTH) MAX_WIDTH.toFloat() / rawWidth else 1f
        val width = ((rawWidth * scale).toInt() / 2) * 2
        val height = ((rawHeight * scale).toInt() / 2) * 2
        if (width <= 0 || height <= 0) return false

        val file = File(cacheDir, "hik_rec_${camId}_${System.currentTimeMillis()}.mp4")
        val enc: MediaCodec
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            postResult(onStopped, false, null, "Encodeur vidéo indisponible sur cet appareil")
            return false
        }

        val inputSurface = try {
            enc.createInputSurface()
        } catch (e: Exception) {
            enc.release()
            postResult(onStopped, false, null, "Surface d'encodage indisponible")
            return false
        }

        val mux: MediaMuxer
        try {
            mux = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            enc.release()
            postResult(onStopped, false, null, "Impossible de créer le fichier vidéo")
            return false
        }

        try {
            enc.start()
        } catch (e: Exception) {
            mux.release(); enc.release()
            postResult(onStopped, false, null, "Démarrage de l'encodeur impossible")
            return false
        }

        codec = enc; muxer = mux; tempFile = file
        trackIndex = -1; muxerStarted = false
        running = true

        drainThread = Thread { drainLoop(enc, mux) }.also { it.start() }
        captureThread = Thread {
            captureLoop(sourceView, inputSurface, width, height, maxDurationMs, onStopped)
        }.also { it.start() }

        return true
    }

    /** Lit les paquets encodés et les écrit dans le fichier, jusqu'à la fin de flux. */
    private fun drainLoop(enc: MediaCodec, mux: MediaMuxer) {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val outIndex = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val buf = enc.getOutputBuffer(outIndex)
                        if (buf != null && muxerStarted && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            mux.writeSampleData(trackIndex, buf, info)
                        }
                        enc.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }
        } catch (e: Exception) {
            // La finalisation se charge de nettoyer même si la vidange a échoué.
        }
    }

    /** Capture une image du rendu toutes les 1/FPS s, jusqu'à l'arrêt ou la durée max. */
    private fun captureLoop(
        sourceView: SurfaceView,
        inputSurface: android.view.Surface,
        width: Int,
        height: Int,
        maxDurationMs: Long,
        onStopped: (Boolean, File?, String) -> Unit
    ) {
        val intervalMs = (1000L / FPS).coerceAtLeast(50L)
        val deadline = System.currentTimeMillis() + maxDurationMs
        try {
            while (running && System.currentTimeMillis() < deadline) {
                val bitmap = captureFrame(sourceView, width, height)
                if (bitmap != null && running) {
                    try {
                        val canvas = inputSurface.lockCanvas(null)
                        canvas.drawBitmap(bitmap, 0f, 0f, null)
                        inputSurface.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        // image sautée, on continue avec la suivante
                    }
                    bitmap.recycle()
                }
                Thread.sleep(intervalMs)
            }
        } finally {
            finish(onStopped)
        }
    }

    private fun captureFrame(sourceView: SurfaceView, width: Int, height: Int): Bitmap? {
        val latch = CountDownLatch(1)
        var result: Bitmap? = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(sourceView, result!!, { status ->
                if (status != PixelCopy.SUCCESS) result = null
                latch.countDown()
            }, mainHandler)
        } catch (e: Exception) {
            result = null
            latch.countDown()
        }
        latch.await(500, TimeUnit.MILLISECONDS)
        return result
    }

    /** Demande l'arrêt ; la finalisation a lieu sur le fil de capture, puis [onStopped]. */
    fun stop() {
        running = false
    }

    private fun finish(onStopped: (Boolean, File?, String) -> Unit) {
        try { codec?.signalEndOfInputStream() } catch (e: Exception) { }
        try { drainThread?.join(4000) } catch (e: Exception) { }
        try { muxer?.stop() } catch (e: Exception) { }
        try { muxer?.release() } catch (e: Exception) { }
        try { codec?.stop() } catch (e: Exception) { }
        try { codec?.release() } catch (e: Exception) { }

        val file = tempFile
        codec = null; muxer = null; drainThread = null; captureThread = null; running = false

        if (file != null && file.exists() && file.length() > 1000) {
            postResult(onStopped, true, file, "Enregistrement terminé")
        } else {
            postResult(onStopped, false, null, "Échec de l'enregistrement")
        }
    }

    private fun postResult(
        onStopped: (Boolean, File?, String) -> Unit,
        success: Boolean, file: File?, message: String
    ) {
        mainHandler.post { onStopped(success, file, message) }
    }
}
