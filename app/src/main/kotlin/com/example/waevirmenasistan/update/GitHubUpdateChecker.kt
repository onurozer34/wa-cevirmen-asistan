package com.example.waevirmenasistan.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class RemoteReleaseInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val releaseNotes: String
)

object GitHubUpdateChecker {

    const val GITHUB_OWNER = "onurozer34"
    const val GITHUB_REPO = "wa-cevirmen-asistan"
    const val CLOUDFLARE_OTA_MANIFEST_URL = "https://wa-cevirmen-ota.mor-media-drive-sync-bridge.workers.dev/version.json"
    private const val RELEASES_LATEST_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    private const val RAW_MANIFEST_URL = "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/main/version.json"

    fun checkForUpdate(currentVersionName: String, currentVersionCode: Int): RemoteReleaseInfo? {
        val remote = runCatching { fetchFromCloudflareOta() }.getOrNull()
            ?: runCatching { fetchFromLatestRelease() }.getOrNull()
            ?: runCatching { fetchFromRawManifest() }.getOrNull()
            ?: return null

        val hasNewerCode = remote.versionCode > 0 && remote.versionCode > currentVersionCode
        val hasNewerName = compareSemanticVersions(remote.versionName, currentVersionName) > 0
        return if (hasNewerCode || hasNewerName) remote else null
    }

    fun parseReleaseApiJson(rawJson: String): RemoteReleaseInfo? {
        val json = JSONObject(rawJson)
        val tag = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
        if (tag.isEmpty()) return null

        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl = ""
        for (i in 0 until assets.length()) {
            val item = assets.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = item.optString("browser_download_url").trim()
                break
            }
        }
        if (apkUrl.isEmpty()) return null

        val notes = json.optString("body").trim()
        val parsedCode = Regex("versionCode\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(notes)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

        return RemoteReleaseInfo(
            versionName = tag,
            versionCode = parsedCode,
            downloadUrl = apkUrl,
            releaseNotes = notes
        )
    }

    fun parseVersionJson(rawJson: String): RemoteReleaseInfo? {
        val json = JSONObject(rawJson)
        val vName = json.optString("versionName").trim().removePrefix("v").removePrefix("V")
        val vCode = json.optInt("versionCode", 0)
        val url = json.optString("downloadUrl").trim()
        if (vName.isEmpty() || url.isEmpty()) return null
        return RemoteReleaseInfo(
            versionName = vName,
            versionCode = vCode,
            downloadUrl = url,
            releaseNotes = json.optString("notes").trim()
        )
    }

    fun compareSemanticVersions(remote: String, current: String): Int {
        val rParts = remote.trim().removePrefix("v").removePrefix("V").split('.').map { it.toIntOrNull() ?: 0 }
        val cParts = current.trim().removePrefix("v").removePrefix("V").split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r != c) return r.compareTo(c)
        }
        return 0
    }

    fun downloadApk(context: Context, downloadUrl: String): File {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetFile = File(updatesDir, "WA-Cevirmen-Asistan.apk")
        if (targetFile.exists()) targetFile.delete()

        var current = downloadUrl
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "WA-Cevirmen-Asistan-Updater")
                setRequestProperty("Accept", "application/octet-stream,*/*")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")?.trim().orEmpty()
                    if (loc.isEmpty()) error("İndirme yönlendirmesi boş (HTTP $code)")
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) {
                    error("APK indirilemedi (HTTP $code)")
                }
                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (targetFile.length() < 40_000) {
                    error("İndirilen APK dosyası eksik veya geçersiz.")
                }
                return targetFile
            } finally {
                conn.disconnect()
            }
        }
        error("APK indirme yönlendirme sınırını aştı.")
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallUnknownAppsSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun launchApkInstaller(context: Context, apkFile: File) {
        val apkUri = ApkFileProvider.buildApkUri(context.packageName, apkFile.name)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun fetchFromCloudflareOta(): RemoteReleaseInfo? {
        val body = httpGetJson(CLOUDFLARE_OTA_MANIFEST_URL)
        return parseVersionJson(body)
    }

    private fun fetchFromLatestRelease(): RemoteReleaseInfo? {
        val body = httpGetJson(RELEASES_LATEST_URL)
        return parseReleaseApiJson(body)
    }

    private fun fetchFromRawManifest(): RemoteReleaseInfo? {
        val body = httpGetJson(RAW_MANIFEST_URL)
        return parseVersionJson(body)
    }

    private fun httpGetJson(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "WA-Cevirmen-Asistan-Updater")
            setRequestProperty("Accept", "application/vnd.github+json,application/json")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) error("GitHub HTTP $code")
            text
        } finally {
            conn.disconnect()
        }
    }
}
