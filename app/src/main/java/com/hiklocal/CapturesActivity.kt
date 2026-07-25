package com.hiklocal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hiklocal.databinding.ActivityCapturesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Galerie des photos prises depuis l'application (bouton 📷 du Direct).
 * Interroge la galerie du téléphone plutôt que de dupliquer un stockage : les
 * photos y sont déjà, et restent visibles même si l'application est
 * désinstallée.
 */
class CapturesActivity : AppCompatActivity() {

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
        // à partir de 13, c'est une permission dédiée aux images.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, readPermissionName())
            != PackageManager.PERMISSION_GRANTED
        ) {
            askReadPermission.launch(readPermissionName())
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, readPermissionName())
            != PackageManager.PERMISSION_GRANTED
        ) {
            askReadPermission.launch(readPermissionName())
            return
        }
        loadCaptures()
    }

    private fun loadCaptures() {
        lifecycleScope.launch {
            val uris = withContext(Dispatchers.IO) { queryCaptures() }
            if (uris.isEmpty()) {
                showEmpty(getString(R.string.captures_empty))
                return@launch
            }
            b.emptyText.visibility = View.GONE
            b.grid.removeAllViews()
            b.grid.columnCount = 3
            for (uri in uris) {
                val iv = ImageView(this@CapturesActivity)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setImageURI(uri)
                iv.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                val params = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                )
                params.width = 0
                params.height = 280
                params.setMargins(4, 4, 4, 4)
                iv.layoutParams = params
                b.grid.addView(iv)
            }
        }
    }

    /** Uniquement les photos que l'application a elle-même enregistrées. */
    private fun queryCaptures(): List<Uri> {
        val out = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("hik_%")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, sort
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    out.add(
                        Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idCol).toString()
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // Permission refusée entre-temps : la galerie reste vide, pas de plantage.
        }
        return out
    }

    private fun showEmpty(message: String) {
        b.emptyText.text = message
        b.emptyText.visibility = View.VISIBLE
        b.grid.removeAllViews()
    }
}
