package com.breeze.h5.image

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * 图片缓存拦截器：处理 localimg:// 协议，兼容宿主旧缓存。
 */
class ImageCacheInterceptor(
    private val cacheManager: ImageCacheManager,
    private val assetLoader: WebViewAssetLoader?
) {
    private val tag = "ImageCache"

    fun intercept(uri: Uri): WebResourceResponse? {
        val url = uri.toString()
        if (!url.startsWith("localimg://")) return null
        Log.d(tag, ">>> 检测到 localimg:// 协议请求")
        val httpsUrl = url.replaceFirst("localimg://", "https://")

        // 1) 命中缓存直接返回
        cacheManager.loadCachedStream(httpsUrl)?.let { (stream, mime, filePath) ->
            Log.d(tag, ">>> 使用缓存图片: $httpsUrl, file=$filePath")
            return WebResourceResponse(mime, "utf-8", stream)
        }

        // 2) 同步拉取原图
        val result = cacheManager.loadBlocking(httpsUrl)
        if (result != null) {
            // 异步缓存
            cacheManager.downloadAsync(httpsUrl, null)
            return WebResourceResponse(result.mime, "utf-8", ByteArrayInputStream(result.data))
        }

        Log.w(tag, ">>> 无法加载原图，返回 null")
        return null
    }
}
