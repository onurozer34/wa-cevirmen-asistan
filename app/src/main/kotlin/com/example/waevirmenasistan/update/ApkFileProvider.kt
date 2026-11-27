package com.example.waevirmenasistan.update

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Lightweight zero-dependency ContentProvider to serve downloaded OTA APKs
 * from app cacheDir/updates/ safely to Android's PackageInstaller.
 */
class ApkFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = resolveSafeApkFile(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val values: Array<Any?> = Array(cols.size) { idx ->
            when (cols[idx]) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> if (file.exists()) file.length() else 0L
                else -> null
            }
        }
        cursor.addRow(values)
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolveSafeApkFile(uri)
        if (!file.exists()) {
            throw FileNotFoundException("APK dosyası bulunamadı: ${file.name}")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun resolveSafeApkFile(uri: Uri): File {
        val ctx = context ?: throw IllegalStateException("Context yok")
        val updatesDir = File(ctx.cacheDir, "updates").canonicalFile
        val fileName = uri.lastPathSegment?.takeIf { it.endsWith(".apk") } ?: "WA-Cevirmen-Asistan.apk"
        val target = File(updatesDir, fileName).canonicalFile
        if (!target.path.startsWith(updatesDir.path + File.separator)) {
            throw SecurityException("Geçersiz APK dosya yolu")
        }
        return target
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        fun buildApkUri(packageName: String, fileName: String): Uri {
            return Uri.parse("content://$packageName.apkprovider/updates/$fileName")
        }
    }
}
