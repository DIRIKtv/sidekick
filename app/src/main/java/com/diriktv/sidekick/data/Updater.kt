package com.diriktv.sidekick.data

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.diriktv.sidekick.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Checks GitHub Releases for a newer APK and hands it to Android's package installer. */
object Updater {
    const val REPO = "DIRIKtv/sidekick"
    private const val DAILY_MS = 24 * 60 * 60 * 1000L
    /** Even in "every open" mode, don't ask GitHub again within this long (GitHub rate limits by IP). */
    private const val MIN_GAP_MS = 2 * 60 * 1000L

    /** How often installed apps look for a new release on their own. */
    enum class Schedule(val key: String, val label: String, val sub: String?) {
        OPEN("open", "Every time the app opens", "Recommended"),
        DAILY("daily", "Once a day", null),
        OFF("off", "Off", "Only when you check in Settings");

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: OPEN
        }
    }

    /** Set when the app comes to the foreground; the main screen runs the check and clears it. */
    @Volatile var openedPending = true

    /** A newer version found by the last check (and not skipped), shown as a dot on Settings. */
    @Volatile var available: Release? = null

    data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

    /**
     * The latest release if it's newer than this build, else null. [force] returns it anyway
     * (debug builds use it to try the update screens). Throws on network errors.
     */
    suspend fun checkNow(force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        KickApi.http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext null // no releases yet
            if (!resp.isSuccessful) throw IOException("GitHub returned ${resp.code}")
            val o = JSONObject(resp.body?.string().orEmpty())
            val version = o.optString("tag_name").removePrefix("v")
            val assets = o.optJSONArray("assets")
            var apk: JSONObject? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a; break }
            }
            if (apk == null || (!force && !isNewer(version, BuildConfig.VERSION_NAME))) return@withContext null
            Release(version, o.optString("body").trim(), apk.getString("browser_download_url"), apk.optLong("size"))
        }
    }

    /**
     * The automatic check when the app opens, following the viewer's schedule (every open, daily
     * or off), skipping a version they chose to skip.
     */
    suspend fun checkIfDue(context: Context): Release? {
        val sp = context.getSharedPreferences("sidekick", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val gap = when (Prefs(context).updateSchedule) {
            Schedule.OFF -> return null
            Schedule.DAILY -> DAILY_MS
            Schedule.OPEN -> MIN_GAP_MS
        }
        if (now - sp.getLong("update_checked", 0) < gap) return null
        val release = runCatching { checkNow() }.getOrNull()
        sp.edit().putLong("update_checked", now).apply()
        return release?.takeIf { it.version != sp.getString("update_skipped", null) }.also { available = it }
    }

    fun skip(context: Context, version: String) {
        context.getSharedPreferences("sidekick", Context.MODE_PRIVATE).edit().putString("update_skipped", version).apply()
    }

    /** Downloads the APK, reporting progress 0–100. */
    /** Downloads the release's APK, reporting bytes done and the total (0 when unknown) as it goes. */
    suspend fun download(context: Context, release: Release, onProgress: (done: Long, total: Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "Sidekick-${release.version}.apk")
        if (!isGitHub(release.apkUrl.toHttpUrlOrNull()?.host)) throw IOException("That update isn't from Sidekick's GitHub page, so it wasn't downloaded.")
        KickApi.http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
            // Followed redirects must end up on GitHub's own download servers too.
            if (!isGitHub(resp.request.url.host)) throw IOException("The download was sent somewhere other than GitHub, so it was stopped.")
            if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
            val body = resp.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastAt = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // A few updates a second is plenty for the meter.
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastAt > 120) {
                            lastAt = now
                            val d = done
                            withContext(Dispatchers.Main) { onProgress(d, total) }
                        }
                    }
                    withContext(Dispatchers.Main) { onProgress(done, total.coerceAtLeast(done)) }
                }
            }
        }
        try {
            verify(context, file)
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        file
    }

    private fun isGitHub(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "github.com" || h.endsWith(".github.com") || h.endsWith(".githubusercontent.com")
    }

    /**
     * Checks the downloaded APK is Sidekick, signed with the same key as the installed app, before
     * the installer sees it. Android would refuse a mismatch anyway, but this says why plainly.
     * When Android can't read the file's certificates (some older versions), the installer decides.
     */
    @Suppress("DEPRECATION")
    private fun verify(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            else android.content.pm.PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
            ?: throw IOException("The download isn't a complete app. Try again.")
        // Debug builds install alongside the release app, so they compare against its name.
        val expected = context.packageName.removeSuffix(".debug")
        if (archive.packageName != expected) throw IOException("The download isn't Sidekick, so it won't be installed.")
        if (archive.packageName != context.packageName) return // a debug build fetching the release: not an update of itself
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun certs(info: android.content.pm.PackageInfo): Set<String>? {
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
            } else info.signatures
            return sigs?.filterNotNull()?.map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
                ?.toSet()?.takeIf { it.isNotEmpty() }
        }
        val mine = certs(installed) ?: return
        val theirs = certs(archive) ?: return
        if (mine.intersect(theirs).isEmpty()) {
            throw IOException("This update isn't signed by Sidekick's developer, so it won't be installed. Get Sidekick from github.com/$REPO instead.")
        }
    }

    /** Whether Android will let this app open the installer (Android 8+ asks per app). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the viewer allows this app to install updates. */
    fun openInstallPermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            true
        } catch (e: ActivityNotFoundException) {
            false // Fire OS hides this screen; the viewer enables it under Developer Options instead
        }
    }

    fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }

    /** Compares dotted versions numerically: 1.10.0 > 1.9.2. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
