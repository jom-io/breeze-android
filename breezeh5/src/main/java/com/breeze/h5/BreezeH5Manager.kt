package com.breeze.h5

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.VisibleForTesting
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import com.breeze.h5.internal.FileUtil
import com.breeze.h5.internal.NetworkUtil
import com.breeze.h5.internal.VersionUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

object BreezeH5Manager {
    private const val TAG = "BreezeH5"
    private const val PREFS = "breeze_h5_prefs"
    private const val KEY_ACTIVE_VERSION = "active_version"
    private const val DEFAULT_DOMAIN = "appassets.androidplatform.net"
    private const val PATCH_CHAIN_LIMIT = 5
    private const val UPDATE_MARKER = ".updating.json"

    private lateinit var appContext: Context
    private lateinit var config: H5Config
    private var listener: H5UpdateListener? = null
    private var loadListener: H5LoadListener? = null
    private var assetLoader: WebViewAssetLoader? = null
    private val client = OkHttpClient()
    private var scheduler = Executors.newSingleThreadScheduledExecutor()
    private var scheduledTask: ScheduledFuture<*>? = null
    private var nextDelayMs: Long = 0
    private var lastEntryIsLocal: Boolean = false

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun initialize(
        context: Context,
        config: H5Config,
        updateListener: H5UpdateListener? = null,
        loadListener: H5LoadListener? = null,
    ) {
        this.appContext = context.applicationContext
        this.config = config
        this.listener = updateListener
        this.loadListener = loadListener
        buildAssetLoader()
        ensureSeedVersion()
        resetBackoffAndSchedule()
    }

    fun switchConfig(config: H5Config, updateListener: H5UpdateListener? = null, loadListener: H5LoadListener? = null) {
        Log.d(TAG, "switchConfig project=${config.projectName} baseUrl=${config.baseUrl}")
        stop()
        initialize(appContext, config, updateListener ?: listener, loadListener ?: this.loadListener)
    }

    fun attachWebView(webView: WebView, loadListener: H5LoadListener? = null) {
        loadListener?.let { this.loadListener = it }
        webView.webViewClient = webViewClient
    }

    fun resolveEntryUrl(): String? {
        val local = bestLocalIndexUrl()
        if (local != null) {
            lastEntryIsLocal = true
            return local
        }
        Log.w(TAG, "no local bundle, fallback=${config.fallbackUrl}")
        lastEntryIsLocal = false
        return config.fallbackUrl
    }

    fun fallbackUrl(): String? = config.fallbackUrl

    fun checkForUpdates() {
        scheduler.execute {
            if (config.useWifiOnly && !NetworkUtil.isWifi(appContext)) {
                Log.d(TAG, "skip check: not on Wi-Fi")
                return@execute
            }
            Log.d(TAG, "checkForUpdates start")
            val updated = tryDownloadNewVersion()
            updateBackoff(updated)
            scheduleNext()
        }
    }

    /**
     * 手动检查更新（同步执行，不重置定时/回退），返回是否有可用的新版本或已下载未激活的版本。
     */
    fun manualCheckForUpdates(): UpdateResult {
        if (hasPendingUnactivated()) {
            Log.d(TAG, "manualCheck: pending unactivated version")
            return UpdateResult.NEW_AVAILABLE
        }
        if (config.useWifiOnly && !NetworkUtil.isWifi(appContext)) {
            Log.d(TAG, "manualCheck: skip (wifi only)")
            return UpdateResult.NONE
        }
        val updated = tryDownloadNewVersion()
        return if (updated || hasPendingUnactivated()) {
            UpdateResult.NEW_AVAILABLE
        } else {
            UpdateResult.NONE
        }
    }

    fun stop() {
        scheduledTask?.cancel(true)
        scheduler.shutdownNow()
    }

    fun onHostResume() {
        if (!config.enablePeriodicCheck) return
        Log.d(TAG, "host resume: reset backoff")
        resetBackoffAndSchedule()
    }

    fun setLoadListener(listener: H5LoadListener?) {
        this.loadListener = listener
    }

    fun activateVersion(version: Int): Boolean {
        val root = projectRoot()
        val index = File(root, "${VersionUtil.versionFolder(version)}/index.html")
        return if (index.exists()) {
            saveActiveVersion(version)
            true
        } else {
            false
        }
    }

    fun bestLocalVersion(): Int? {
        val root = projectRoot()
        val active = activeVersion()
        val versions = VersionUtil.findVersions(root)
        val best = when {
            active != null && versions.contains(active) -> active
            versions.isNotEmpty() -> versions.maxOrNull()
            else -> null
        }
        best?.let { Log.d(TAG, "bestLocalVersion=$it") } ?: Log.w(TAG, "no local version found")
        return best
    }

    private fun hasPendingUnactivated(): Boolean {
        val active = activeVersion() ?: return bestLocalVersion() != null
        val best = bestLocalVersion() ?: return false
        return best > active
    }

    /** 当前激活的版本（用户已确认使用的版本），若未激活则返回 null */
    fun currentActiveVersion(): Int? = activeVersion()

    fun bestLocalIndexUrl(): String? {
        val root = projectRoot()
        val active = activeVersion()
        if (active != null) {
            localIndexUrlIfExists(root, active)?.let {
                Log.d(TAG, "localIndexUrl active=$active url=$it")
                return it
            }
        }
        val versions = VersionUtil.findVersions(root)
        val newest = versions.maxOrNull()
        if (newest != null) {
            localIndexUrlIfExists(root, newest)?.let {
                Log.d(TAG, "localIndexUrl newest=$newest url=$it")
                saveActiveVersion(newest)
                return it
            }
        }
        return null
    }

    fun mapToLocalIfPossible(url: String?): String {
        if (url.isNullOrBlank()) return url ?: ""
        if (!lastEntryIsLocal) return url
        val remoteMatch = config.remoteDomains.any { domain -> url.contains(domain, ignoreCase = true) }
        if (!remoteMatch) return url
        if (config.routePrefixes.isNotEmpty()) {
            val hit = config.routePrefixes.any { prefix -> url.contains(prefix, ignoreCase = true) }
            if (!hit) return url
        }
        val local = bestLocalIndexUrl() ?: return url
        val hashIndex = url.indexOf('#')
        val queryIndex = url.indexOf('?')
        val suffix = when {
            hashIndex >= 0 -> url.substring(hashIndex)
            queryIndex >= 0 -> url.substring(queryIndex)
            else -> ""
        }
        val rewritten = local + suffix
        Log.d(TAG, "mapToLocal: $url -> $rewritten")
        return rewritten
    }

    @VisibleForTesting
    internal fun buildAssetLoader() {
        // 以 filesDir/<projectName>/ 为根，并使用 /<projectName>/ 作为前缀，避免路径重复或环境切换时的双重目录
        val root = projectRoot()
        root.mkdirs()
        val prefix = "/${config.projectName}/"
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(DEFAULT_DOMAIN)
            .addPathHandler(prefix, WebViewAssetLoader.InternalStoragePathHandler(appContext, root))
            .build()
        Log.d(TAG, "assetLoader built prefix=$prefix root=${root.absolutePath}")
    }

    /** 插件内置的 WebViewClient：处理 appassets 拦截与兜底 */
    val webViewClient: WebViewClient by lazy {
        object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val loader = assetLoader ?: return null
                // 远端域名（remoteDomains/routePrefixes）重写到本地入口
                val mapped = mapToLocalIfPossible(request.url.toString())
                var target = if (mapped != request.url.toString()) Uri.parse(mapped) else request.url

                // appassets 域名下，补全 projectName 与版本号前缀
                if (target.host.equals(DEFAULT_DOMAIN, ignoreCase = true)) {
                    target = ensureAppassetsPath(target)
                    // 如果是缺失的 favicon 之类的资源，避免报错，返回空响应兜底
                    if (target.path?.endsWith(".ico") == true) {
                        return emptyResponse("image/x-icon")
                    }
                }

                return loader.shouldInterceptRequest(target)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url.toString()
                val mapped = mapToLocalIfPossible(url)
                if (mapped != url) {
                    Log.d(TAG, "shouldOverrideUrlLoading mapToLocal: $url -> $mapped")
                    view.post { view.loadUrl(mapped) }
                    return true
                }
                return false
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
                if (url.isNullOrBlank()) return false
                val mapped = mapToLocalIfPossible(url)
                if (mapped != url) {
                    Log.d(TAG, "shouldOverrideUrlLoading(map) $url -> $mapped")
                    view.post { view.loadUrl(mapped) }
                    return true
                }
                return false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                handleLoadError(view, request.url.toString(), error.description?.toString())
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                handleLoadError(view, failingUrl, description)
            }

            private fun handleLoadError(view: WebView, url: String?, description: String?) {
                val fallback = config.fallbackUrl
                if (!fallback.isNullOrBlank() && !isFallbackUrl(url)) {
                    Log.w(TAG, "load failed for $url, try fallback $fallback")
                    view.post { view.loadUrl(fallback) }
                    return
                }
                Log.e(TAG, "load failed after fallback, url=$url, reason=$description")
                this@BreezeH5Manager.loadListener?.onAllFailed(url, description)
            }

            private fun isFallbackUrl(url: String?): Boolean {
                val fallback = config.fallbackUrl ?: return false
                return url?.startsWith(fallback) == true
            }
        }
    }

    private fun ensureSeedVersion() {
        val root = projectRoot()
        val versions = VersionUtil.findVersions(root)
        if (versions.isNotEmpty()) {
            Log.d(TAG, "seed present, versions=$versions")
            return
        }
        val seedVersion = findBestSeedVersion() ?: config.seedVersion
        val assetPath = "${config.assetBasePath}/${VersionUtil.versionFolder(seedVersion)}/${config.assetZipName}"
        val zipFile = File(root, "${VersionUtil.versionFolder(seedVersion)}/${config.assetZipName}")
        try {
            FileUtil.copyAsset(appContext, assetPath, zipFile)
            FileUtil.unzip(zipFile, File(root, VersionUtil.versionFolder(seedVersion)))
            saveActiveVersion(seedVersion)
            Log.d(TAG, "seed copied from assets $assetPath (version=$seedVersion)")
        } catch (e: Exception) {
            Log.w(TAG, "seed copy failed: ${e.message}")
        }
    }

    /**
     * 查找 assets 中可用的最高版本种子（形如 v3/v9），如果不存在则返回 null 使用配置的 seedVersion。
     */
    private fun findBestSeedVersion(): Int? {
        return try {
            val assets = appContext.assets
            val dirs = assets.list(config.assetBasePath) ?: return null
            val versions = dirs.mapNotNull { name ->
                if (name.startsWith("v")) {
                    name.removePrefix("v").toIntOrNull()
                } else {
                    null
                }
            }
            versions.maxOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun resetBackoffAndSchedule() {
        if (!config.enablePeriodicCheck) {
            Log.d(TAG, "periodic check disabled")
            return
        }
        val remaining = scheduledTask?.getDelay(TimeUnit.MILLISECONDS)
        if (remaining != null && remaining in 0 until config.initialCheckDelayMillis) {
            Log.d(TAG, "skip resetBackoff (remaining=${remaining}ms < initial=${config.initialCheckDelayMillis})")
            return
        }
        nextDelayMs = config.initialCheckDelayMillis
        scheduleNext()
    }

    private fun scheduleNext() {
        if (!config.enablePeriodicCheck) return
        if (scheduler.isShutdown || scheduler.isTerminated) {
            scheduler = Executors.newSingleThreadScheduledExecutor()
        }
        scheduledTask?.cancel(false)
        val delay = max(0, nextDelayMs)
        Log.d(TAG, "schedule next check in ${delay / 1000}s")
        scheduledTask = scheduler.schedule({ checkForUpdates() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun tryDownloadNewVersion(): Boolean {
        cleanupIncompleteUpdate()
        val latestVersion = fetchLastVersion() ?: return false
        val root = projectRoot()
        val current = VersionUtil.findVersions(root).maxOrNull() ?: config.seedVersion
        if (latestVersion <= current) {
            Log.d(TAG, "no update: remote=$latestVersion current=$current")
            return false
        }

        val manifestLatest = fetchManifest(latestVersion) ?: return false
        val usePatchChain = latestVersion - current in 1..PATCH_CHAIN_LIMIT
        val updatedVersion = if (usePatchChain) {
            applyPatchChain(current, latestVersion) ?: run {
                Log.w(TAG, "patch chain failed, fallback full v$latestVersion")
                if (downloadFullBundle(manifestLatest)) manifestLatest.version else null
            }
        } else {
            if (downloadFullBundle(manifestLatest)) manifestLatest.version else null
        } ?: return false

        VersionUtil.cleanupOldVersions(root, config.keepVersions)
        val immediate = listener?.onVersionReady(updatedVersion, localIndexUrl(updatedVersion)) ?: false
        if (immediate) {
            saveActiveVersion(updatedVersion)
            Log.d(TAG, "version $updatedVersion activated immediately")
        }
        return true
    }

    private fun updateBackoff(updated: Boolean) {
        val minInterval = config.minCheckIntervalMillis
        val maxInterval = config.maxCheckIntervalMillis
        if (updated) {
            nextDelayMs = minInterval
            Log.d(TAG, "backoff reset to min after update: ${nextDelayMs / 1000}s")
            return
        }
        val multiplied = (nextDelayMs * config.backoffMultiplier).toLong()
        nextDelayMs = min(maxInterval, max(minInterval, multiplied))
        Log.d(TAG, "backoff no-update -> next delay ${nextDelayMs / 1000}s")
    }

    private fun fetchManifest(latest: Int): H5Manifest? {
        val manifestUrl = config.manifestUrlFor(latest)
        Log.d(TAG, "fetch manifest version=$latest url=$manifestUrl")
        val request = Request.Builder().url(manifestUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "manifest request failed code=${response.code} url=$manifestUrl")
                return null
            }
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val version = json.optInt("version", -1)
            val url = json.optString("url")
            val hash = json.optString("hash")
            val size: Long? = if (json.has("size")) json.optLong("size") else null
            if (version <= 0 || url.isBlank() || hash.isBlank()) {
                Log.w(TAG, "manifest invalid version=$version url=$url hash=$hash")
                return null
            }
            val patchFrom: Int? = if (json.has("patchFrom")) json.optInt("patchFrom", -1).takeIf { it > 0 } else null
            val patchUrl: String? = json.optString("patchUrl").takeIf { it.isNotBlank() }
            val patchHash: String? = json.optString("patchHash").takeIf { it.isNotBlank() }
            val patchSize: Long? = if (json.has("patchSize")) json.optLong("patchSize") else null
            val deleted: List<String>? = json.optJSONArray("deleted")?.let { array ->
                buildList {
                    for (i in 0 until array.length()) {
                        val path = array.optString(i)
                        if (!path.isNullOrBlank()) add(path)
                    }
                }.takeIf { it.isNotEmpty() }
            }
            return H5Manifest(
                version = version,
                url = url,
                hash = hash,
                size = size,
                patchFrom = patchFrom,
                patchUrl = patchUrl,
                patchHash = patchHash,
                patchSize = patchSize,
                deleted = deleted,
            )
        }
    }

    private fun fetchLastVersion(): Int? {
        val lastUrl = config.lastVersionUrl
        val request = Request.Builder().url(lastUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "lastversion request failed code=${response.code} url=$lastUrl")
                return null
            }
            val body = response.body?.string()?.trim()
            val value = when {
                body.isNullOrBlank() -> null
                body.startsWith("{") -> runCatching { JSONObject(body).optInt("version", -1) }.getOrNull()?.takeIf { it > 0 }
                else -> body.removePrefix("v").removePrefix("V").toIntOrNull()
            }
            if (value == null) {
                Log.w(TAG, "lastversion parse failed body=$body")
            } else {
                Log.d(TAG, "lastversion=$value from=$lastUrl")
            }
            return value
        }
    }

    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            target.outputStream().use { output ->
                response.body?.byteStream()?.copyTo(output) ?: throw IOException("Empty body for $url")
            }
        }
    }

    private fun cleanupIncompleteUpdate() {
        val marker = File(projectRoot(), UPDATE_MARKER)
        if (!marker.exists()) return
        val target = runCatching {
            val txt = marker.readText()
            JSONObject(txt).optInt("target", -1).takeIf { it > 0 }
        }.getOrNull()
        if (target != null) {
            val dir = File(projectRoot(), VersionUtil.versionFolder(target))
            FileUtil.deleteQuietly(dir)
            Log.w(TAG, "cleaned incomplete update for v$target")
        }
        marker.delete()
    }

    private fun markUpdating(version: Int) {
        val marker = File(projectRoot(), UPDATE_MARKER)
        marker.parentFile?.mkdirs()
        runCatching { marker.writeText(JSONObject(mapOf("target" to version)).toString()) }
    }

    private fun clearUpdating() {
        File(projectRoot(), UPDATE_MARKER).delete()
    }

    private fun downloadFullBundle(manifest: H5Manifest): Boolean {
        val root = projectRoot()
        val targetDir = File(root, VersionUtil.versionFolder(manifest.version))
        FileUtil.deleteQuietly(targetDir)
        targetDir.mkdirs()
        val zipFile = File(targetDir, config.assetZipName)
        markUpdating(manifest.version)
        return try {
            Log.d(TAG, "download full version=${manifest.version} url=${manifest.url}")
            download(manifest.url, zipFile)
            if (manifest.hash.isNotBlank()) {
                val actual = FileUtil.checksumSha256(zipFile)
                if (!actual.equals(manifest.hash, ignoreCase = true)) {
                    throw IOException("Hash mismatch for version ${manifest.version}")
                }
            }
            if (manifest.size != null && manifest.size > 0 && zipFile.length() != manifest.size) {
                throw IOException("Size mismatch for version ${manifest.version}")
            }
            FileUtil.unzip(zipFile, targetDir)
            clearUpdating()
            true
        } catch (e: Exception) {
            Log.e(TAG, "download full failed version=${manifest.version}: ${e.message}")
            FileUtil.deleteQuietly(targetDir)
            clearUpdating()
            false
        }
    }

    private fun applyPatchChain(currentVersion: Int, targetVersion: Int): Int? {
        var from = currentVersion
        var next = from + 1
        while (next <= targetVersion) {
            val manifest = fetchManifest(next) ?: return null
            val patchFrom = manifest.patchFrom
            if (patchFrom == null || patchFrom != from || manifest.patchUrl.isNullOrBlank() || manifest.patchHash.isNullOrBlank()) {
                Log.w(TAG, "patch unavailable for v$next (patchFrom=$patchFrom, url=${manifest.patchUrl})")
                return null
            }
            val ok = applySinglePatch(manifest, from)
            if (!ok) return null
            from = next
            next++
        }
        return from
    }

    private fun applySinglePatch(manifest: H5Manifest, baseVersion: Int): Boolean {
        val root = projectRoot()
        val baseDir = File(root, VersionUtil.versionFolder(baseVersion))
        if (!baseDir.exists()) {
            Log.w(TAG, "base version not found for patch, v$baseVersion")
            return false
        }
        val targetDir = File(root, VersionUtil.versionFolder(manifest.version))
        FileUtil.deleteQuietly(targetDir)
        targetDir.mkdirs()

        val patchFile = File(projectRoot(), "patch_${manifest.version}.zip")
        markUpdating(manifest.version)
        return try {
            FileUtil.copyDir(baseDir, targetDir)
            Log.d(TAG, "download patch v${manifest.version} from=${manifest.patchFrom} url=${manifest.patchUrl}")
            download(manifest.patchUrl!!, patchFile)
            if (!manifest.patchHash.isNullOrBlank()) {
                val actual = FileUtil.checksumSha256(patchFile)
                if (!actual.equals(manifest.patchHash, ignoreCase = true)) {
                    throw IOException("Patch hash mismatch for version ${manifest.version}")
                }
            }
            if (manifest.patchSize != null && manifest.patchSize > 0 && patchFile.length() != manifest.patchSize) {
                throw IOException("Patch size mismatch for version ${manifest.version}")
            }
            FileUtil.unzip(patchFile, targetDir)
            manifest.deleted?.let { FileUtil.deletePaths(targetDir, it) }
            clearUpdating()
            true
        } catch (e: Exception) {
            Log.e(TAG, "apply patch failed version=${manifest.version}: ${e.message}")
            FileUtil.deleteQuietly(targetDir)
            clearUpdating()
            false
        } finally {
            patchFile.delete()
        }
    }

    private fun localIndexUrlIfExists(root: File, version: Int): String? {
        val localIndex = File(root, "${VersionUtil.versionFolder(version)}/index.html")
        return if (localIndex.exists()) {
            localIndexUrl(version)
        } else {
            null
        }
    }

    fun localIndexUrl(version: Int): String {
        val path = "/${config.projectName}/${VersionUtil.versionFolder(version)}/index.html"
        return "https://$DEFAULT_DOMAIN$path"
    }

    private fun activeVersion(): Int? = prefs.getInt(KEY_ACTIVE_VERSION, -1).takeIf { it > 0 }

    private fun saveActiveVersion(version: Int) {
        prefs.edit().putInt(KEY_ACTIVE_VERSION, version).apply()
    }

    private fun projectRoot(): File = File(appContext.filesDir, config.projectName)

    /** 确保 appassets 路径包含 /projectName/vX/ 前缀，缺失时补全到当前最佳本地版本 */ 
    private fun ensureAppassetsPath(uri: Uri): Uri {
        var path = uri.encodedPath ?: return uri
        val projectPrefix = "/${config.projectName}/"
        if (!path.startsWith(projectPrefix)) {
            path = projectPrefix + path.removePrefix("/").removePrefix("\\")
        }
        val best = bestLocalVersion() ?: return uri.buildUpon().path(path).build()
        val versionPrefix = "$projectPrefix${VersionUtil.versionFolder(best)}/"
        if (!path.startsWith(versionPrefix)) {
            val suffix = path.removePrefix(projectPrefix).removePrefix("/")
            path = versionPrefix + suffix
        }
        return uri.buildUpon().path(path).build()
    }

    /**
        * 提供给宿主自定义 WebViewClient 直接复用插件的拦截逻辑。
        * - 仅处理 appassets.androidplatform.net + 已注册的 path handler
        * - 未命中返回 null，宿主可继续处理自己的协议或兜底
        */
    fun interceptRequest(request: WebResourceRequest): WebResourceResponse? {
        return interceptRequest(request.url)
    }

    fun interceptRequest(url: Uri): WebResourceResponse? {
        return assetLoader?.shouldInterceptRequest(url)
    }

    private fun emptyResponse(mime: String = "text/plain"): WebResourceResponse {
        return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    /** 触发入口加载：本地优先，缺失则 fallback */
    fun loadEntry(webView: WebView) {
        val entry = resolveEntryUrl() ?: return
        webView.loadUrl(entry)
    }
}

fun interface H5UpdateListener {
    fun onVersionReady(version: Int, url: String): Boolean
}

fun interface H5LoadListener {
    fun onAllFailed(url: String?, reason: String?)
}

enum class UpdateResult {
    NONE,
    NEW_AVAILABLE,
}
