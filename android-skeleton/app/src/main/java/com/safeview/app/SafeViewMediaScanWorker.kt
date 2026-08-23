package com.safeview.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Incrementally scans accessible shared media; source files are never copied or stored. */
class SafeViewMediaScanWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? SafeViewApp ?: return@withContext Result.failure()
        if (!hasMediaPermission()) return@withContext Result.failure()
        val prefs = SettingsPrefs(applicationContext)
        val db = SafeViewMediaDatabase(applicationContext)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        val since = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SCAN, 0L)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} > ?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            (since / 1000L).toString()
        )
        applicationContext.contentResolver.query(
            MediaStore.Files.getContentUri("external"), projection, selection, args,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            while (cursor.moveToNext() && !isStopped) {
                val id = cursor.getLong(idIndex).toString()
                val type = if (cursor.getInt(typeIndex) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) "video" else "image"
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri("external"),
                    cursor.getLong(idIndex)
                )
                val bitmap = if (type == "video") extractVideoFrame(uri) else decodeImage(uri)
                val classified = bitmap?.let {
                    try { app.classifier.classify(it, prefs.explicitThreshold, prefs.revealingThreshold) }
                    finally { it.recycle() }
                }
                val result = classified?.let { ClassificationResult(it.category, it.confidence) }
                    ?: ClassificationResult(ContentCategory.UNCERTAIN, 0f)
                val mode = if (prefs.strict) ProtectionMode.STRICT else ProtectionMode.BALANCED
                db.upsertMedia(id, type, result, SafeViewPolicyEngine.decide(result, mode))
            }
        }
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_SCAN, System.currentTimeMillis()).apply()
        Result.success()
    }

    private fun hasMediaPermission(): Boolean {
        val image = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        val video = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(applicationContext, image) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(applicationContext, video) == PackageManager.PERMISSION_GRANTED
    }

    private fun decodeImage(uri: android.net.Uri): Bitmap? = try {
        applicationContext.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }

    private fun extractVideoFrame(uri: android.net.Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(applicationContext, uri)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    companion object {
        const val UNIQUE_WORK = "safeview-media-scan"
        private const val PREFS = "safeview_media_scan"
        private const val KEY_LAST_SCAN = "last_scan"
    }
}
