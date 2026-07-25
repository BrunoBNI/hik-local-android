package com.hiklocal

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.hiklocal.databinding.ActivityPlaybackBinding
import java.util.Calendar

/**
 * Relecture des enregistrements.
 *
 * Le flux RTSP d'archive ne se déplace pas : pour changer d'instant, on
 * relance simplement la lecture à la nouvelle heure. C'est aussi ce que fait
 * la version PC de ce service.
 */
class PlaybackActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CAM = "cam"
    }

    private lateinit var b: ActivityPlaybackBinding
    private var player: ExoPlayer? = null
    private var cameras: List<Camera> = emptyList()
    private var currentCam = 1

    private var year = 0
    private var month = 0      // 0-11, convention Calendar
    private var day = 0
    private var minuteOfDay = 0

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

        b.timeSeek.progress = minuteOfDay
        b.timeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    minuteOfDay = progress
                    updateLabels()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            /** On ne relance la lecture qu'au relâchement, pas à chaque pixel. */
            override fun onStopTrackingTouch(sb: SeekBar?) {
                playFromCurrent()
            }
        })
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
        b.timeSeek.progress = minuteOfDay
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
        val endOfDay = 1439
        val url = api!!.playbackUrl(currentCam, stamp(minuteOfDay), stamp(endOfDay))
        play(url)
    }

    private fun play(url: String) {
        stop()
        b.statusText.visibility = View.GONE
        b.loading.visibility = View.VISIBLE

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        b.playerView.player = exo

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
        })

        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun showStatus(message: String) {
        b.loading.visibility = View.GONE
        b.statusText.text = message
        b.statusText.visibility = View.VISIBLE
    }

    private fun stop() {
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
