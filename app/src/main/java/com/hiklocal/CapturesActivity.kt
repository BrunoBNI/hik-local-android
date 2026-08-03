package com.hiklocal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hiklocal.databinding.ActivityCapturesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Galerie des captures prises depuis l'application : photos (bouton 📷) et
 * vidéos (bouton ⏺, direct ou lecture). Interroge la galerie du téléphone
 * plutôt que de dupliquer un stockage : les fichiers y sont déjà, et
 * restent visibles même si l'application est désinstallée.
 */
class CapturesActivity : AppCompatActivity() {

    private data class CaptureItem(val uri: Uri, val isVideo: Boolean, val dateAdded: Long)

    private lateinit var b: ActivityCapturesBinding

    private val askReadPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadCaptures() else showEmpty(getString(R.string.captures_permission))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCapturesBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (Session.api == null) { finish(); return }

        NavBar.setup(
            this, NavBar.Tab.CAPTURES, b.topbar.overflowButton,
            b.topbar.tabDirect, b.topbar.tabMosaic, b.topbar.tabPlayback, b.topbar.tabCaptures
        )

        ensurePermissionThenLoad()
    }

    private fun readPermissionName() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun ensurePermissionThenLoad() {
        // Avant Android 10, lire la galerie exige la permission explicitement ;
        // à partir de 13, c'est une permission dédiée aux images (les vidéos
        // ont READ_MEDIA_VIDEO, mais READ_MEDIA_IMAGES suffit ici : on ne lit
        // que les fichiers que l'application a elle-même créés).
        val needsPermission = (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            Build.VERSION.SDK_INT >= 33) &&
            ContextCompat.checkSelfPermission(this, readPermissionName()) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            askReadPermission.launch(readPermissionName())
        } else {
            loadCaptures()
        }
    }

    private fun loadCaptures() {
        lifecycleScope.launch {
            // Le décodage des vignettes vidéo est fait ici, hors du fil
            // principal : il peut être lent sur un fichier volumineux.
            val entries = withContext(Dispatchers.IO) {
                queryCaptures().map { item ->
                    val thumb = if (item.isVideo) videoThumbnail(item.uri) else null
                    Triple(item.uri, item.isVideo, thumb)
                }
            }
            if (entries.isEmpty()) {
                showEmpty(getString(R.string.captures_empty))
                return@launch
            }
            b.emptyText.visibility = View.GONE
            b.grid.removeAllViews()
            val columns = (resources.configuration.screenWidthDp / 130).coerceIn(2, 4)
            b.grid.columnCount = columns
            val density = resources.displayMetrics.density
            val tileSizePx = (140 * density).toInt()
            val marginPx = (4 * density).toInt()

            for ((uri, isVideo, thumb) in entries) {
                val tile = FrameLayout(this@CapturesActivity)
                val iv = ImageView(this@CapturesActivity)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                if (isVideo && thumb != null) iv.setImageBitmap(thumb) else if (!isVideo) iv.setImageURI(uri)
                tile.addView(iv, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                ))

                if (isVideo) {
                    val badge = TextView(this@CapturesActivity).apply {
                        text = "▶"
                        setTextColor(getColor(R.color.text))
                        textSize = 18f
                        setBackgroundColor(0x99000000.toInt())
                        setPadding(marginPx, marginPx / 2, marginPx, marginPx / 2)
                    }
                    tile.addView(badge, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    ))
                }

                tile.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, uri)) }

                val params = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                )
                params.width = 0
                params.height = tileSizePx
                params.setMargins(marginPx, marginPx, marginPx, marginPx)
                tile.layoutParams = params
                b.grid.addView(tile)
            }
        }
    }

    /** Vignette d'une vidéo, sans jamais faire planter la galerie si elle échoue. */
    private fun videoThumbnail(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            contentResolver.loadThumbnail(uri, android.util.Size(320, 320), null)
        } else {
            val id = uri.lastPathSegment?.toLongOrNull()
            if (id != null) {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
                )
            } else null
        }
    } catch (e: Exception) {
        null
    }

    /** Photos et vidéos que l'application a elle-même enregistrées, triées ensemble. */
    private fun queryCaptures(): List<CaptureItem> {
        val out = mutableListOf<CaptureItem>()

        fun collect(collection: Uri, isVideo: Boolean) {
            val idCol = MediaStore.MediaColumns._ID
            val dateCol = MediaStore.MediaColumns.DATE_ADDED
            try {
                contentResolver.query(
                    collection, arrayOf(idCol, dateCol),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("hik_%"),
                    "$dateCol DESC"
                )?.use { cursor ->
                    val ic = cursor.getColumnIndexOrThrow(idCol)
                    val dc = cursor.getColumnIndexOrThrow(dateCol)
                    while (cursor.moveToNext()) {
                        out.add(
                            CaptureItem(
                                Uri.withAppendedPath(collection, cursor.getLong(ic).toString()),
                                isVideo, cursor.getLong(dc)
                            )
                        )
                    }
                }
            } catch (e: SecurityException) {
                // Permission refusée entre-temps : cette collection reste vide.
            }
        }

        collect(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
        collect(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        return out.sortedByDescending { it.dateAdded }
    }

    private fun showEmpty(message: String) {
        b.emptyText.text = message
        b.emptyText.visibility = View.VISIBLE
        b.grid.removeAllViews()
    }
}
