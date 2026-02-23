package com.mobileclaw.agent.service

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Checks for app updates via GitHub Releases.
 * Compares the current version with the latest release tag.
 * If a newer version exists, prompts the user to download.
 */
class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        // Update this to the user's repo
        private const val GITHUB_REPO = "Mokksh-bhatt/IDK"
        private const val RELEASES_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        const val CURRENT_VERSION = "1.0.0"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val isUpdateAvailable: Boolean
    )

    /**
     * Check GitHub Releases for a newer version.
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub API error: ${response.code}")
                return@withContext null
            }

            val release = json.parseToJsonElement(body).jsonObject
            val tagName = release["tag_name"]?.jsonPrimitive?.content ?: return@withContext null
            val latestVersion = tagName.removePrefix("v")
            val releaseNotes = release["body"]?.jsonPrimitive?.contentOrNull ?: "Bug fixes and improvements"

            // Find the APK asset in the release
            val assets = release["assets"]?.jsonArray
            val apkAsset = assets?.firstOrNull { asset ->
                val name = asset.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                name.endsWith(".apk")
            }

            val downloadUrl = apkAsset?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.content
                ?: "https://github.com/$GITHUB_REPO/releases/latest" // Fallback to release page

            val isNewer = isVersionNewer(latestVersion, CURRENT_VERSION)

            UpdateInfo(
                version = latestVersion,
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes,
                isUpdateAvailable = isNewer
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
            null
        }
    }

    /**
     * Download the APK using Android DownloadManager.
     */
    fun downloadUpdate(url: String) {
        try {
            if (url.endsWith(".apk")) {
                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle("MobileClaw Update")
                    .setDescription("Downloading latest version...")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MobileClaw-update.apk")
                    .setMimeType("application/vnd.android.package-archive")

                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
            } else {
                // Open the browser to the release page
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download update", e)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) {}
        return false
    }
}
