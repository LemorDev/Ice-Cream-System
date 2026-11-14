package com.icecreampost.pos.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.icecreampost.pos.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class PosRelease(
    val applicationId: String,
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
)

internal fun parsePosRelease(json: Json, manifest: String, applicationId: String, installedVersion: Int): PosRelease? {
    val release = try {
        json.decodeFromString<PosRelease>(manifest)
    } catch (_: SerializationException) {
        error("The release manifest is invalid.")
    }
    check(release.applicationId == applicationId) { "The release channel belongs to a different POS package." }
    check(release.versionCode > 0 && release.versionName.isNotBlank() && release.versionName.length <= 64 &&
        release.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "The release manifest is invalid." }
    releaseUrl(release.apkUrl)
    return release.takeIf { it.versionCode > installedVersion }
}

private fun releaseUrl(value: String): HttpUrl = checkNotNull(value.toHttpUrlOrNull()?.takeIf {
    it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
}) { "The release channel requires a valid HTTPS URL." }

@Singleton
class PosUpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    val isConfigured = BuildConfig.UPDATE_MANIFEST_URL.isNotBlank()
    // Release downloads use their own client and carry no POS credentials.
    private val client = OkHttpClient.Builder()
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.MINUTES)
        .build()
    private val stagingDirectory = File(context.cacheDir, "pos-updates")

    suspend fun latestRelease(): PosRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(releaseUrl(BuildConfig.UPDATE_MANIFEST_URL.trim()))
            .cacheControl(CacheControl.FORCE_NETWORK).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Could not check the release channel (HTTP ${response.code})." }
            val source = checkNotNull(response.body) { "The release channel returned an empty response." }.source()
            check(!source.request(32_769L)) { "The release manifest is too large." }
            parsePosRelease(json, source.readUtf8(), context.packageName, BuildConfig.VERSION_CODE)
        }
    }

    suspend fun download(release: PosRelease): File = withContext(Dispatchers.IO) {
        check(stagingDirectory.isDirectory || stagingDirectory.mkdirs()) { "Could not open storage for the update." }
        val pending = File.createTempFile("download-", ".part", stagingDirectory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val request = Request.Builder().url(releaseUrl(release.apkUrl)).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Could not download the update (HTTP ${response.code})." }
                val body = checkNotNull(response.body) { "The download was empty." }
                val limit = 200L * 1024 * 1024
                check(body.contentLength() <= limit) { "The update is too large." }
                body.byteStream().use { input ->
                    pending.outputStream().buffered().use { output ->
                        val bytes = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            ensureActive()
                            val size = input.read(bytes)
                            if (size < 0) break
                            total += size
                            check(total <= limit) { "The update is too large." }
                            digest.update(bytes, 0, size)
                            output.write(bytes, 0, size)
                        }
                    }
                }
            }
            val checksum = digest.digest().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
            check(checksum.equals(release.sha256, ignoreCase = true)) { "The download failed its checksum. Try again." }
            val ready = File(stagingDirectory, "update.apk")
            check((!ready.exists() || ready.delete()) && pending.renameTo(ready)) { "Could not save the downloaded update." }
            try {
                verifyPackage(ready, release)
                ready
            } catch (error: Exception) {
                ready.delete()
                throw error
            }
        } finally {
            pending.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(apk: File, release: PosRelease) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = checkNotNull(manager.getPackageArchiveInfo(apk.absolutePath, flags)) { "The update APK is invalid." }
        val installed = manager.getPackageInfo(context.packageName, flags)
        check(archive.packageName == installed.packageName &&
            PackageInfoCompat.getLongVersionCode(archive) == release.versionCode.toLong() &&
            PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(installed)) {
            "The downloaded APK does not match this POS release."
        }
        val signers = signingCertificates(archive)
        check(signers.isNotEmpty() && signers == signingCertificates(installed)) {
            "The update APK was signed with a different key."
        }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificates(info: PackageInfo): Set<Signature> = if (Build.VERSION.SDK_INT >= 28) {
        info.signingInfo?.apkContentsSigners?.toSet().orEmpty()
    } else info.signatures?.toSet().orEmpty()

    @Suppress("DEPRECATION")
    fun installerIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val installer = context.packageManager.queryIntentActivities(intent,
            PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY).firstOrNull()?.activityInfo
            ?: error("Android's package installer is unavailable on this device.")
        return intent.setClassName(installer.packageName, installer.name)
    }
}
