package com.example.ussdhelper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkName: String,
    val apkSize: Long,
    val isNewerVersion: Boolean
)

object AppUpdater {
    private const val GITHUB_OWNER = "tinkorosh"
    private const val GITHUB_REPO = "ussd-two"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /**
     * Checks GitHub for the latest release in the repository.
     */
    suspend fun checkLatestRelease(
        currentVersionName: String,
        currentVersionCode: Int = 1,
        lastInstalledTag: String? = null
    ): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val url = URL(RELEASES_API_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "USSD-Assistant-Android")
                connectTimeout = 12000
                readTimeout = 12000
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(
                    Exception("GitHub API returned HTTP $responseCode (${connection.responseMessage}). Make sure a release has been published in $GITHUB_OWNER/$GITHUB_REPO.")
                )
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)

            val tagName = json.optString("tag_name", "v1.0")
            val title = json.optString("name", tagName)
            val body = json.optString("body", "No release notes provided.")

            // Locate the .apk asset in assets array
            var apkDownloadUrl = ""
            var apkName = "update.apk"
            var apkSize = 0L

            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkName = name
                        apkDownloadUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (apkDownloadUrl.isEmpty()) {
                return@withContext Result.failure(
                    Exception("Release $tagName found, but no .apk file was attached to the release assets.")
                )
            }

            val isNewer = isRemoteVersionNewer(tagName, currentVersionName, currentVersionCode, lastInstalledTag)

            Result.success(
                ReleaseInfo(
                    tagName = tagName,
                    title = title,
                    releaseNotes = body,
                    apkDownloadUrl = apkDownloadUrl,
                    apkName = apkName,
                    apkSize = apkSize,
                    isNewerVersion = isNewer
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads the APK file from GitHub release asset with live progress callback.
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            val apkFile = File(downloadDir, "USSD_Update.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }

            // Follow redirects if any
            var currentUrl = downloadUrl
            var connection: HttpURLConnection
            var redirects = 0
            while (true) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "USSD-Assistant-Android")
                    connectTimeout = 15000
                    readTimeout = 20000
                }

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == 307 || status == 308) {
                    val newUrl = connection.getHeaderField("Location")
                    if (newUrl != null && redirects < 5) {
                        currentUrl = newUrl
                        redirects++
                        continue
                    }
                }
                break
            }

            val totalBytes = connection.contentLength.toLong()
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else -1
                        withContext(Dispatchers.Main) {
                            onProgress(percent, downloadedBytes, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            Result.success(apkFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Checks whether unknown app source installation is allowed, and starts system installation flow.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        // Android 8.0+ unknown sources permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return false
            }
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(installIntent)
        return true
    }

    /**
     * Compares remote semantic version string against current version name and code.
     */
    private fun isRemoteVersionNewer(
        remoteTag: String,
        localVersion: String,
        localVersionCode: Int = 1,
        lastInstalledTag: String? = null
    ): Boolean {
        val cleanRemote = remoteTag.removePrefix("v").removePrefix("V").trim()
        val cleanLocal = localVersion.removePrefix("v").removePrefix("V").trim()

        if (cleanRemote.equals(cleanLocal, ignoreCase = true)) return false

        if (!lastInstalledTag.isNullOrEmpty()) {
            val cleanLastInstalled = lastInstalledTag.removePrefix("v").removePrefix("V").trim()
            if (cleanRemote.equals(cleanLastInstalled, ignoreCase = true)) return false
        }

        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = cleanLocal.split(".").mapNotNull { it.toIntOrNull() }

        // If remote tag is e.g. 1.0.6 where 6 is build number, and local is 1.0 with build 6+
        if (remoteParts.size == 3 && localParts.size == 2 && localVersionCode > 1) {
            if (remoteParts[0] == localParts[0] && remoteParts[1] == localParts[1]) {
                return remoteParts[2] > localVersionCode
            }
        }

        val length = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until length) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }

        return false
    }
}
