package com.breeze.h5.image

import android.content.Context
import android.util.Log
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

/**
 * 图片缓存管理器（迁移自宿主），使用 app filesDir/image_cache 持久化文件。
 */
class ImageCacheManager(context: Context) {
    private val tag = "ImageCacheManager"
    private val cacheDir: File
    private val downloadExecutor: ExecutorService = Executors.newFixedThreadPool(3)

    init {
        cacheDir = File(context.filesDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        Log.d(tag, "ImageCacheManager init dir=${cacheDir.absolutePath}")
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
            val data = connection.inputStream.use { it.readBytes() }
            val elapsed = System.currentTimeMillis() - start
            Log.d(tag, "loadBlocking ok len=${data.size} cost=${elapsed}ms url=$url")
            CachedResult(data, contentType)
        } catch (e: Exception) {
            Log.e(tag, "loadBlocking failed url=$url", e)
            null
        } finally {
            connection.disconnect()
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

    data class CachedResult(val data: ByteArray, val mime: String)

    companion object {
        private const val CACHE_DIR_NAME = "image_cache"
    }
}
