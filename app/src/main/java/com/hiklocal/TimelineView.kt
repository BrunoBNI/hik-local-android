package com.hiklocal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Frise horaire sur 24 h avec zoom et défilement, comparable à celle de la
 * version PC. N'affiche pas les segments réellement enregistrés : cette
 * information vient d'une recherche ISAPI qui, sur cet appareil, s'est
 * révélée verrouillée derrière la session web propriétaire du DVR (déjà
 * documenté côté PC) — reproduire ce contournement sur Android n'a pas paru
 * raisonnable pour ce lot. La frise reste pleinement utilisable pour choisir
 * une heure ; elle ne pré-indique juste pas où sont les enregistrements.
 */
class TimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    fun interface OnSeekListener { fun onSeek(minuteOfDay: Int) }

    var onSeek: OnSeekListener? = null
    var onScrubPreview: ((Int?) -> Unit)? = null

    private var viewStartMs = 0L          // fenêtre visible, en ms depuis minuit
    private var viewSpanMs = DAY_MS
    private var cursorMinute: Int? = null

    private val trackPaint = Paint().apply { color = 0xFF1B222B.toInt() }
    private val tickPaint = Paint().apply { color = 0x22FFFFFF }
    private val tickMajorPaint = Paint().apply { color = 0x44FFFFFF }
    private val cursorPaint = Paint().apply { color = 0xFFFFCC00.toInt(); strokeWidth = 4f }
    private val scrubPaint = Paint().apply { color = 0x99FFFFFF.toInt(); strokeWidth = 3f }
    private val labelPaint = Paint().apply {
        color = 0xFF9FADBB.toInt(); textSize = 26f; isAntiAlias = true
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val focusMs = viewStartMs + (detector.focusX / width) * viewSpanMs
                zoomTo(viewSpanMs / detector.scaleFactor, focusMs)
                return true
            }
        })

    private var dragStartX = 0f
    private var dragging = false
    private var scrubbing = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x; dragging = false; scrubbing = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - dragStartX
                    if (kotlin.math.abs(dx) > 8) {
                        scrubbing = true
                        val ms = xToMs(event.x)
                        onScrubPreview?.invoke(msToMinute(ms))
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!scaleDetector.isInProgress) {
                    val ms = xToMs(event.x)
                    onSeek?.onSeek(msToMinute(ms))
                }
                onScrubPreview?.invoke(null)
                scrubbing = false
            }
        }
        return true
    }

    private fun xToMs(x: Float): Long {
        val frac = (x / width).coerceIn(0f, 1f)
        return viewStartMs + (frac * viewSpanMs).toLong()
    }

    private fun msToMinute(ms: Long) = (ms / 60_000L).toInt().coerceIn(0, 1439)

    /** Repositionne le curseur (pendant la lecture) sans changer la fenêtre visible. */
    fun setCursor(minuteOfDay: Int?) {
        cursorMinute = minuteOfDay
        invalidate()
    }

    fun resetFullDay() {
        viewStartMs = 0; viewSpanMs = DAY_MS
        invalidate()
    }

    fun zoomBy(factor: Float) {
        zoomTo(viewSpanMs / factor, viewStartMs + viewSpanMs / 2)
    }

    private fun zoomTo(newSpan: Long, focusMs: Long) {
        val span = newSpan.coerceIn(MIN_SPAN_MS, DAY_MS)
        viewStartMs = (focusMs - (focusMs - viewStartMs) * (span.toFloat() / viewSpanMs)).toLong()
        viewSpanMs = span
        viewStartMs = viewStartMs.coerceIn(0, DAY_MS - viewSpanMs)
        invalidate()
    }

    private fun msToX(ms: Long): Float = ((ms - viewStartMs).toFloat() / viewSpanMs) * width

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), trackPaint)

        val step = tickStep()
        var t = (viewStartMs / step) * step
        while (t <= viewStartMs + viewSpanMs) {
            if (t in viewStartMs..(viewStartMs + viewSpanMs)) {
                val x = msToX(t)
                val major = t % 3_600_000L == 0L
                canvas.drawLine(x, 0f, x, height.toFloat(), if (major) tickMajorPaint else tickPaint)
                if (major || step >= 3_600_000L) {
                    val h = (t / 3_600_000L) % 24
                    val m = (t / 60_000L) % 60
                    val label = if (step >= 3_600_000L) "%02dh".format(h) else "%02d:%02d".format(h, m)
                    canvas.drawText(label, x + 4f, height - 8f, labelPaint)
                }
            }
            t += step
        }

        cursorMinute?.let { minute ->
            val ms = minute * 60_000L
            if (ms in viewStartMs..(viewStartMs + viewSpanMs)) {
                canvas.drawLine(msToX(ms), 0f, msToX(ms), height.toFloat(), cursorPaint)
            }
        }
    }

    /** Un pas de graduation lisible : au plus une dizaine de traits visibles. */
    private fun tickStep(): Long {
        val candidates = longArrayOf(
            60_000, 300_000, 600_000, 900_000, 1_800_000,
            3_600_000, 7_200_000, 10_800_000, 21_600_000
        )
        return candidates.firstOrNull { viewSpanMs / it <= 12 } ?: 21_600_000
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val MIN_SPAN_MS = 60_000L   // 1 minute : le zoom maximal
    }
}
