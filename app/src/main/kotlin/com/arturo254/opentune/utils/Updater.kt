/*
 * OpenTune Project Original (2026)
 * Arturo254 (github.com/Arturo254)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.arturo254.opentune.utils

import androidx.datastore.preferences.core.edit
import com.arturo254.opentune.BuildConfig
import com.arturo254.opentune.App
import com.arturo254.opentune.constants.GitHubReleasesEtagKey
import com.arturo254.opentune.constants.GitHubReleasesFingerprintKey
import com.arturo254.opentune.constants.GitHubReleasesJsonKey
import com.arturo254.opentune.constants.GitHubReleasesLastCheckedAtKey
import com.arturo254.opentune.constants.UpdateChannel
import com.arturo254.opentune.constants.UpdateChannelKey
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

data class GitCommit(
    val sha: String,
    val message: String,
    val author: String,
    val date: String,
    val url: String
)

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String?,
    val publishedAt: String,
    val htmlUrl: String
)

data class NightlyInfo(
    val versionName: String,
    val apkUrl: String,
    val changelog: String?,
    val publishedAt: String
)

/**
 * Resultado de [Updater.checkForUpdate].
 *
 * @param tagName        Tag del release en GitHub (ej. "v1.4.2")
 * @param versionName    Versión normalizada sin prefijo "v" (ej. "1.4.2")
 * @param downloadUrl    URL directa a `app-universal-release.apk` del release
 * @param releasePageUrl URL de la página HTML del release en GitHub
 * @param releaseNotes   Changelog / body del release (puede ser null)
 * @param publishedAt    Fecha de publicación en ISO 8601
 */
data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val downloadUrl: String,
    val releasePageUrl: String,
    val releaseNotes: String?,
    val publishedAt: String,
)

private const val APK_ASSET_NAME = "app-universal-release.apk"
private const val STABLE_JSON_URL = "https://dezzy.space/update.json"
private const val NIGHTLY_JSON_URL = "https://dezzy.space/update-nightly.json"

private data class ReleasesNetworkResult(
    val status: HttpStatusCode,
    val body: String?,
    val etag: String?,
)

object Updater {
    private val client = HttpClient()
    private const val ReleaseCacheCheckIntervalMs: Long = 6 * 60 * 60 * 1000L
    var lastCheckTime = -1L
        private set

    var cachedStableDownloadUrl: String = "https://pub-815e6afa49994827a0d0cab5f802a819.r2.dev/app-universal-gms-release.apk"
    var cachedNightlyDownloadUrl: String = "https://pub-2218e6bbd5b948e1b5d882cf4d92086d.r2.dev/app-universal-release.apk"

    private var cachedNightlyInfo: NightlyInfo? = null

    private data class SemVer(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: List<PreReleaseIdentifier>,
    ) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int {
            val majorCompare = major.compareTo(other.major)
            if (majorCompare != 0) return majorCompare
            val minorCompare = minor.compareTo(other.minor)
            if (minorCompare != 0) return minorCompare
            val patchCompare = patch.compareTo(other.patch)
            if (patchCompare != 0) return patchCompare

            val thisIsStable = preRelease.isEmpty()
            val otherIsStable = other.preRelease.isEmpty()
            if (thisIsStable && !otherIsStable) return 1
            if (!thisIsStable && otherIsStable) return -1

            val maxIndex = minOf(preRelease.size, other.preRelease.size)
            for (i in 0 until maxIndex) {
                val c = preRelease[i].compareTo(other.preRelease[i])
                if (c != 0) return c
            }
            return preRelease.size.compareTo(other.preRelease.size)
        }

        fun normalizedName(): String =
            if (preRelease.isEmpty()) {
                "$major.$minor.$patch"
            } else {
                "$major.$minor.$patch-" + preRelease.joinToString(".") { it.raw }
            }
    }

    private sealed interface PreReleaseIdentifier : Comparable<PreReleaseIdentifier> {
        val raw: String
    }

    private data class NumericIdentifier(
        override val raw: String,
        val value: Long,
    ) : PreReleaseIdentifier {
        override fun compareTo(other: PreReleaseIdentifier): Int =
            when (other) {
                is NumericIdentifier -> value.compareTo(other.value)
                is AlphaIdentifier -> -1
            }
    }

    private data class AlphaIdentifier(
        override val raw: String,
    ) : PreReleaseIdentifier {
        override fun compareTo(other: PreReleaseIdentifier): Int =
            when (other) {
                is NumericIdentifier -> 1
                is AlphaIdentifier -> raw.compareTo(other.raw)
            }
    }

    private val semVerRegex =
        Regex("""(?i)\bv?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?\b""")

    private fun parseSemVerOrNull(text: String): SemVer? {
        val match = semVerRegex.find(text) ?: return null
        val major = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val minor = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        val patch = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return null
        val preReleaseText = match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }
        val preRelease =
            preReleaseText
                ?.split('.')
                ?.filter { it.isNotBlank() }
                ?.map { identifier ->
                    if (identifier.all { it.isDigit() }) {
                        NumericIdentifier(raw = identifier, value = identifier.toLong())
                    } else {
                        AlphaIdentifier(raw = identifier)
                    }
                }
                ?: emptyList()
        return SemVer(
            major = major,
            minor = minor,
            patch = patch,
            preRelease = preRelease,
        )
    }

    private fun parseReleaseSemVerOrNull(release: ReleaseInfo): SemVer? =
        parseSemVerOrNull(release.tagName) ?: parseSemVerOrNull(release.name)

    internal fun isSameVersion(a: String, b: String): Boolean {
        return true // Temporarily hidden for future integration
        /*
        val aSemVer = parseSemVerOrNull(a)
        val bSemVer = parseSemVerOrNull(b)
        return if (aSemVer != null && bSemVer != null) {
            aSemVer.major == bSemVer.major &&
                    aSemVer.minor == bSemVer.minor &&
                    aSemVer.patch == bSemVer.patch &&
                    aSemVer.preRelease == bSemVer.preRelease
        } else {
            a.trim() == b.trim()
        }
        */
    }

    internal fun findLatestRelease(releases: List<ReleaseInfo>): ReleaseInfo? {
        if (releases.isEmpty()) return null
        val parsed =
            releases.mapNotNull { release ->
                parseReleaseSemVerOrNull(release)?.let { version -> version to release }
            }

        if (parsed.isEmpty()) return releases.firstOrNull()

        val stable = parsed.filter { it.first.preRelease.isEmpty() }
        val candidates = stable.ifEmpty { parsed }
        return candidates.maxWithOrNull(compareBy({ it.first }, { it.second.publishedAt }))?.second
    }

    private fun preferredReleaseVersionNameOrNull(release: ReleaseInfo): String? =
        parseReleaseSemVerOrNull(release)?.normalizedName()

    private fun parseReleasesJson(json: String): List<ReleaseInfo> {
        val jsonArray = JSONArray(json)
        val releases = ArrayList<ReleaseInfo>(jsonArray.length())
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(i)
            releases.add(
                ReleaseInfo(
                    tagName = item.optString("tag_name", ""),
                    name = item.optString("name", ""),
                    body = if (item.has("body")) item.optString("body") else null,
                    publishedAt = item.optString("published_at", ""),
                    htmlUrl = item.optString("html_url", "")
                )
            )
        }
        return releases
    }

    private fun getTopReleaseFingerprint(releases: List<ReleaseInfo>): String {
        val latest = findLatestRelease(releases) ?: return ""
        return listOf(
            latest.tagName,
            latest.name,
            latest.publishedAt,
            latest.body.orEmpty(),
            latest.htmlUrl,
        ).joinToString("||")
    }

    private suspend fun fetchReleasesNetwork(
        perPage: Int,
        cachedEtag: String?,
    ): ReleasesNetworkResult {
        val response: HttpResponse =
            client.get("https://api.github.com/repos/dezzy-music/Dezzy/releases?per_page=$perPage") {
                headers {
                    append("Accept", "application/vnd.github+json")
                    append("User-Agent", "OpenTune")
                    if (!cachedEtag.isNullOrBlank()) {
                        append("If-None-Match", cachedEtag)
                    }
                }
            }
        val etag = response.headers["ETag"]
        return when (response.status) {
            HttpStatusCode.NotModified ->
                ReleasesNetworkResult(
                    status = response.status,
                    body = null,
                    etag = cachedEtag ?: etag,
                )
            else ->
                ReleasesNetworkResult(
                    status = response.status,
                    body = response.bodyAsText(),
                    etag = etag,
                )
        }
    }

    // ─── Canal helpers ─────────────────────────────────────────────────────────

    private suspend fun getCurrentUpdateChannel(): UpdateChannel {
        val channelName = App.instance.dataStore.getAsync(UpdateChannelKey) ?: UpdateChannel.STABLE.name
        return runCatching { UpdateChannel.valueOf(channelName) }.getOrDefault(UpdateChannel.STABLE)
    }

    private val okHttpClient = okhttp3.OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private suspend fun fetchJsonUrl(url: String, fallbackUrl: String? = null): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val urls = listOfNotNull(url, fallbackUrl)
            var lastException: Exception? = null
            for (u in urls) {
                try {
                    val request = okhttp3.Request.Builder()
                        .url(u)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "application/json, text/plain, */*")
                        .build()
                    val response = okHttpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) return@withContext body
                    }
                } catch (e: Exception) {
                    lastException = e
                }
            }
            throw lastException ?: java.io.IOException("Failed to fetch JSON from $url")
        }
    }

    private suspend fun fetchNightlyJson(): Result<NightlyInfo> = runCatching {
        // Primary: GitHub Releases API
        try {
            val request = okhttp3.Request.Builder()
                .url("https://api.github.com/repos/dezzy-music/Dezzy/releases")
                .header("User-Agent", "OpenTune-App")
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val releasesArray = JSONArray(bodyStr)
                    if (releasesArray.length() > 0) {
                        val firstRelease = releasesArray.getJSONObject(0)
                        val tagName = firstRelease.optString("tag_name", "3.0.6").removePrefix("v")
                        val assets = firstRelease.optJSONArray("assets")
                        var apkUrlVal = ""
                        if (assets != null && assets.length() > 0) {
                            apkUrlVal = assets.getJSONObject(0).optString("browser_download_url", "")
                        }
                        if (apkUrlVal.isBlank()) {
                            apkUrlVal = "https://github.com/dezzy-music/Dezzy/releases/download/$tagName/app-universal-release.apk"
                        }
                        cachedNightlyDownloadUrl = apkUrlVal
                        return@runCatching NightlyInfo(
                            versionName = tagName,
                            apkUrl = apkUrlVal,
                            changelog = firstRelease.optString("body", ""),
                            publishedAt = firstRelease.optString("published_at", "")
                        )
                    }
                }
            }
        } catch (_: Exception) { }

        // Fallback: dezzy.space
        val response = try {
            fetchJsonUrl(
                NIGHTLY_JSON_URL,
                "https://pub-2218e6bbd5b948e1b5d882cf4d92086d.r2.dev/app-universal-release.apk"
            )
        } catch (e: Exception) {
            return@runCatching NightlyInfo(
                versionName = BuildConfig.VERSION_NAME,
                apkUrl = cachedNightlyDownloadUrl,
                changelog = null,
                publishedAt = ""
            )
        }

        val json = JSONObject(response)
        val assets = json.optJSONArray("assets")
        val firstAssetUrl = if (assets != null && assets.length() > 0) {
            assets.getJSONObject(0).optString("url")
        } else {
            ""
        }
        val apkUrlVal = firstAssetUrl.ifEmpty { cachedNightlyDownloadUrl }
        cachedNightlyDownloadUrl = apkUrlVal

        NightlyInfo(
            versionName = json.optString("name", BuildConfig.VERSION_NAME),
            apkUrl = apkUrlVal,
            changelog = json.optString("description", "").takeIf { it.isNotEmpty() },
            publishedAt = json.optString("date", "")
        )
    }

    private suspend fun fetchStableJson(): Result<ReleaseInfo> = runCatching {
        // Primary: GitHub Releases API (most reliable, guaranteed up to date)
        try {
            val request = okhttp3.Request.Builder()
                .url("https://api.github.com/repos/dezzy-music/Dezzy/releases/latest")
                .header("User-Agent", "OpenTune-App")
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val json = JSONObject(bodyStr)
                    val tagName = json.optString("tag_name", "3.0.6").removePrefix("v")
                    val name = json.optString("name", tagName)
                    val body = json.optString("body", "")
                    val publishedAt = json.optString("published_at", "")
                    val assets = json.optJSONArray("assets")
                    var downloadUrl = ""
                    if (assets != null && assets.length() > 0) {
                        downloadUrl = assets.getJSONObject(0).optString("browser_download_url", "")
                    }
                    if (downloadUrl.isBlank()) {
                        downloadUrl = "https://github.com/dezzy-music/Dezzy/releases/download/$tagName/app-universal-release.apk"
                    }
                    cachedStableDownloadUrl = downloadUrl
                    return@runCatching ReleaseInfo(
                        tagName = tagName,
                        name = name,
                        body = body,
                        publishedAt = publishedAt,
                        htmlUrl = downloadUrl
                    )
                }
            }
        } catch (_: Exception) { }

        // Fallback: dezzy.space
        val response = try {
            fetchJsonUrl(
                STABLE_JSON_URL,
                "https://pub-815e6afa49994827a0d0cab5f802a819.r2.dev/app-universal-gms-release.apk"
            )
        } catch (e: Exception) {
            return@runCatching ReleaseInfo(
                tagName = BuildConfig.VERSION_NAME,
                name = BuildConfig.VERSION_NAME,
                body = null,
                publishedAt = "",
                htmlUrl = cachedStableDownloadUrl
            )
        }

        val json = JSONObject(response)
        val assets = json.optJSONArray("assets")
        val firstAssetUrl = if (assets != null && assets.length() > 0) {
            assets.getJSONObject(0).optString("url")
        } else {
            ""
        }
        val apkUrlVal = firstAssetUrl.ifEmpty { cachedStableDownloadUrl }
        cachedStableDownloadUrl = apkUrlVal

        ReleaseInfo(
            tagName = json.optString("version", BuildConfig.VERSION_NAME),
            name = json.optString("name", BuildConfig.VERSION_NAME),
            body = json.optString("description", "").takeIf { it.isNotEmpty() },
            publishedAt = json.optString("date", ""),
            htmlUrl = apkUrlVal
        )
    }

    // ─── Funciones públicas (compatibilidad obligatoria) ───────────────────────

    suspend fun getCachedReleases(): List<ReleaseInfo> {
        if (getCurrentUpdateChannel() == UpdateChannel.NIGHTLY) return emptyList()
        val cachedJson = App.instance.dataStore.getAsync(GitHubReleasesJsonKey)
        return cachedJson
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { parseReleasesJson(it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun getLatestVersionName(): Result<String> = runCatching {
        when (getCurrentUpdateChannel()) {
            UpdateChannel.STABLE -> {
                val latest = getLatestReleaseInfo().getOrThrow()
                preferredReleaseVersionNameOrNull(latest) ?: latest.name.ifBlank { latest.tagName }
            }
            UpdateChannel.NIGHTLY -> {
                fetchNightlyJson().getOrThrow().versionName
            }
        }
    }

    suspend fun getLatestReleaseNotes(): Result<String?> = runCatching {
        when (getCurrentUpdateChannel()) {
            UpdateChannel.STABLE -> getLatestReleaseInfo().getOrThrow().body
            UpdateChannel.NIGHTLY -> fetchNightlyJson().getOrThrow().changelog
        }
    }

    suspend fun getLatestReleaseInfo(): Result<ReleaseInfo> = runCatching {
        when (getCurrentUpdateChannel()) {
            UpdateChannel.STABLE -> {
                val latest = fetchStableJson().getOrThrow()
                lastCheckTime = System.currentTimeMillis()
                latest
            }
            UpdateChannel.NIGHTLY -> {
                val nightly = fetchNightlyJson().getOrThrow()
                cachedNightlyInfo = nightly
                ReleaseInfo(
                    tagName = nightly.versionName,
                    name = nightly.versionName,
                    body = nightly.changelog,
                    publishedAt = nightly.publishedAt,
                    htmlUrl = nightly.apkUrl
                )
            }
        }
    }

    // ─── Comprobación de actualización ─────────────────────────────────────────

    /**
     * Comprueba si hay una versión más reciente que [currentVersionName].
     *
     * - Detecta el canal activo automáticamente.
     * - STABLE: Consulta el JSON remoto de dezzy.space.
     * - NIGHTLY: Consulta el JSON remoto de dezzy.space.
     * - Devuelve `null` dentro del [Result] cuando ya se tiene la versión más
     *   reciente instalada.
     */
    suspend fun checkForUpdate(currentVersionName: String): Result<UpdateInfo?> =
        runCatching {
            when (getCurrentUpdateChannel()) {
                UpdateChannel.STABLE -> checkForUpdateStable(currentVersionName)
                UpdateChannel.NIGHTLY -> checkForUpdateNightly(currentVersionName)
            }
        }

    private suspend fun checkForUpdateStable(currentVersionName: String): UpdateInfo? {
        val latest = getLatestReleaseInfo().getOrThrow()
        val latestVersionName =
            preferredReleaseVersionNameOrNull(latest)
                ?: latest.name.ifBlank { latest.tagName }

        if (isSameVersion(latestVersionName, currentVersionName)) return null

        val downloadUrl = latest.htmlUrl

        return UpdateInfo(
            tagName        = latest.tagName,
            versionName    = latestVersionName,
            downloadUrl    = downloadUrl,
            releasePageUrl = latest.htmlUrl,
            releaseNotes   = latest.body,
            publishedAt    = latest.publishedAt,
        )
    }

    private suspend fun checkForUpdateNightly(currentVersionName: String): UpdateInfo? {
        val nightly = fetchNightlyJson().getOrThrow()
        return if (isSameVersion(nightly.versionName, currentVersionName)) {
            null
        } else {
            cachedNightlyInfo = nightly
            UpdateInfo(
                tagName        = nightly.versionName,
                versionName    = nightly.versionName,
                downloadUrl    = nightly.apkUrl,
                releasePageUrl = nightly.apkUrl,
                releaseNotes   = nightly.changelog,
                publishedAt    = nightly.publishedAt,
            )
        }
    }

    /**
     * Devuelve la URL de descarga de fallback para la etiqueta dada.
     */
    private suspend fun resolveApkDownloadUrl(tagName: String): String {
        return cachedStableDownloadUrl
    }

    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getCommitHistory(count: Int = 20, branch: String = "master"): Result<List<GitCommit>> =
        runCatching {
            // Deshabilitado para no depender de la API de GitHub rate-limited.
            emptyList<GitCommit>()
        }

    fun getLatestDownloadUrl(): String {
        val channel = runBlocking {
            kotlin.runCatching { getCurrentUpdateChannel() }.getOrDefault(UpdateChannel.STABLE)
        }
        return when (channel) {
            UpdateChannel.STABLE -> cachedStableDownloadUrl
            UpdateChannel.NIGHTLY -> cachedNightlyDownloadUrl
        }
    }

    suspend fun getAllReleases(
        perPage: Int = 30,
        forceRefresh: Boolean = false,
    ): Result<List<ReleaseInfo>> =
        runCatching {
            if (getCurrentUpdateChannel() == UpdateChannel.NIGHTLY) {
                return@runCatching emptyList()
            }
            val stableRelease = fetchStableJson().getOrThrow()
            lastCheckTime = System.currentTimeMillis()
            listOf(stableRelease)
        }
}