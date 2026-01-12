package com.breeze.h5.image

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 图片缓存管理器（迁移自宿主），使用 app filesDir/image_cache 持久化文件。
 */
class ImageCacheManager(context: Context) {
    private val tag = "ImageCacheManager"
    private val cacheDir: File
    // 小文件：当前拦截线程一次下载写缓存；大文件：单线程异步队列缓存
    private val downloadExecutor: ExecutorService = Executors.newFixedThreadPool(4)
    private val largeFileExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // 过期清理单线程，避免与下载写冲突（支持延迟启动）
    private val cleanupExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    init {
        cacheDir = File(context.filesDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        Log.d(tag, "ImageCacheManager init dir=${cacheDir.absolutePath}")
        scheduleCleanupIfNeeded()
    }

    interface DownloadCallback {
        fun onSuccess(cachedFile: File)
        fun onError(e: Exception)
    }

    fun getCacheKey(url: String): String {
        return try {
            val md5 = MessageDigest.getInstance("MD5")
            val hash = md5.digest(url.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            url.hashCode().toString()
        }
    }

    fun getCachedFile(cacheKey: String): File? {
        val files = cacheDir.listFiles() ?: return File(cacheDir, "$cacheKey.jpg")
        files.forEach { file ->
            val name = file.name
            val base = name.substringBeforeLast('.', name)
            if (base == cacheKey) return file
        }
        return File(cacheDir, "$cacheKey.jpg")
    }

    fun isCached(url: String): Boolean {
        val file = getCachedFile(getCacheKey(url))
        return file != null && file.exists() && file.length() > 0
    }

    data class CachedStream(val stream: InputStream, val mime: String, val path: String)

    fun loadCachedStream(url: String): CachedStream? {
        val cacheKey = getCacheKey(url)
        val file = getCachedFile(cacheKey) ?: return null
        if (!file.exists() || file.length() == 0L) return null
        touch(file)
        val mime = getMimeType(file.name)
        return CachedStream(FileInputStream(file), mime, file.absolutePath)
    }

    fun downloadAsync(url: String, callback: DownloadCallback?) {
        downloadExecutor.execute {
            try {
                val connection = openConnection(url) ?: throw IOException("openConnection failed")
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP $code")
                }
                val contentType = connection.contentType
                val ext = getExtensionFromContentType(contentType, url)
                val cacheKey = getCacheKey(url)
                val target = File(cacheDir, cacheKey + ext)
                connection.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
                touch(target)
                callback?.onSuccess(target)
            } catch (e: Exception) {
                Log.e(tag, "download failed url=$url", e)
                callback?.onError(e)
            }
        }
    }

    fun loadBlocking(url: String): CachedResult? {
        val start = System.currentTimeMillis()
        val connection = openConnection(url) ?: return null
        return try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.e(tag, "loadBlocking HTTP $code url=$url")
                return null
            }
            val contentType = connection.contentType ?: guessMime(url)
            val cacheKey = getCacheKey(url)
            val ext = getExtensionFromContentType(contentType, url)
            val target = File(cacheDir, cacheKey + ext)

            // 小文件：一次下载返回+写缓存；大文件：直接返回流，异步排队缓存
            val isLarge = connection.contentLengthLong > CACHE_ASYNC_THRESHOLD_BYTES
            if (isLarge) {
                Log.d(tag, "loadBlocking large file, async cache queued url=$url size=${connection.contentLengthLong}")
                cacheAsync(url)
                val mime = contentType
                val data = connection.inputStream.use { it.readBytes() }
                val elapsed = System.currentTimeMillis() - start
                Log.d(tag, "loadBlocking large return len=${data.size} cost=${elapsed}ms url=$url")
                return CachedResult(data, mime)
            }

            val data: ByteArray
            connection.inputStream.use { input ->
                val bufferOut = ByteArrayOutputStream()
                FileOutputStream(target).use { fileOut ->
                    copyStream(input, bufferOut, fileOut)
                }
                data = bufferOut.toByteArray()
            }
            touch(target)
            val elapsed = System.currentTimeMillis() - start
            Log.d(tag, "loadBlocking ok len=${data.size} cost=${elapsed}ms url=$url cache=${target.absolutePath}")
            CachedResult(data, contentType)
        } catch (e: Exception) {
            Log.e(tag, "loadBlocking failed url=$url", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheAsync(url: String) {
        largeFileExecutor.execute {
            try {
                val conn = openConnection(url) ?: return@execute
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) return@execute
                val contentType = conn.contentType ?: guessMime(url)
                val cacheKey = getCacheKey(url)
                val ext = getExtensionFromContentType(contentType, url)
                val target = File(cacheDir, cacheKey + ext)
                conn.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        copyStream(input, null, output)
                    }
                }
                touch(target)
                Log.d(tag, "async cache done url=$url file=${target.absolutePath}")
            } catch (e: Exception) {
                Log.e(tag, "async cache failed url=$url", e)
            }
        }
    }

    private fun copyStream(input: InputStream, out1: ByteArrayOutputStream?, out2: FileOutputStream) {
        val buf = ByteArray(8 * 1024)
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
            out2.write(buf, 0, n)
            out1?.write(buf, 0, n)
        }
    }

    private fun openConnection(url: String): HttpURLConnection? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.instanceFollowRedirects = true
            conn
        } catch (e: Exception) {
            Log.e(tag, "openConnection error url=$url", e)
            null
        }
    }

    private fun getExtensionFromContentType(contentType: String?, url: String): String {
        val lower = contentType?.lowercase() ?: ""
        return when {
            lower.contains("png") -> ".png"
            lower.contains("webp") -> ".webp"
            lower.contains("gif") -> ".gif"
            lower.contains("jpeg") || lower.contains("jpg") -> ".jpg"
            url.lowercase().endsWith(".png") -> ".png"
            url.lowercase().endsWith(".webp") -> ".webp"
            url.lowercase().endsWith(".gif") -> ".gif"
            else -> ".jpg"
        }
    }

    private fun getMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".jpeg") || lower.endsWith(".jpg") -> "image/jpeg"
            else -> "image/jpeg"
        }
    }

    private fun guessMime(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".jpeg") || lower.endsWith(".jpg") -> "image/jpeg"
            else -> "image/jpeg"
        }
    }

    private fun touch(file: File) {
        try {
            file.setLastModified(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(tag, "touch failed file=${file.absolutePath}", e)
        }
    }

    private fun scheduleCleanupIfNeeded() {
        val now = System.currentTimeMillis()
        val last = readLastCleanup()
        if (now - last < CLEAN_INTERVAL_MS) {
            Log.d(tag, "cleanup skip: last=$last interval=${now - last}ms")
            return
        }
        cleanupExecutor.schedule({
            try {
                Log.d(tag, "cleanup start after delay=${INIT_CLEAN_DELAY_MS}ms")
                cleanExpired(now)
                writeLastCleanup(now)
            } catch (e: Exception) {
                Log.e(tag, "cleanup failed", e)
            }
        }, INIT_CLEAN_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun cleanExpired(now: Long) {
        val files = cacheDir.listFiles() ?: return
        var deleted = 0
        files.forEach { file ->
            if (file.isFile) {
                val age = now - file.lastModified()
                if (age > EXPIRE_MS) {
                    if (file.delete()) {
                        deleted++
                    }
                }
            }
        }
        Log.d(tag, "cleanup done deleted=$deleted dir=${cacheDir.absolutePath}")
    }

    private fun readLastCleanup(): Long {
        val f = File(cacheDir, CLEAN_MARKER)
        if (!f.exists()) return 0
        return try {
            f.readText().trim().toLong()
        } catch (_: Exception) {
            0
        }
    }

    private fun writeLastCleanup(ts: Long) {
        try {
            File(cacheDir, CLEAN_MARKER).writeText(ts.toString())
        } catch (e: Exception) {
            Log.w(tag, "write last cleanup failed", e)
        }
    }

    data class CachedResult(val data: ByteArray, val mime: String)

    companion object {
        private const val CACHE_DIR_NAME = "image_cache"
        // 超过该大小（字节）视为大文件：首次返回流，不阻塞写缓存，后台排队缓存
        private const val CACHE_ASYNC_THRESHOLD_BYTES = 2 * 1024 * 1024L // 2MB
        private const val EXPIRE_MS = 7L * 24 * 60 * 60 * 1000 // 7天未访问删除
        private const val CLEAN_INTERVAL_MS = 6L * 60 * 60 * 1000 // 最小清理间隔 6 小时
        private const val CLEAN_MARKER = ".last_cleanup"
        private const val INIT_CLEAN_DELAY_MS = 30_000L // 避开启动前30s
    }
}
