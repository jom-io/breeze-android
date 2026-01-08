package com.breeze.h5.internal

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal object FileUtil {
    fun copyAsset(context: Context, assetPath: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
    }

    fun unzip(zipFile: File, targetDir: File) {
        targetDir.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun checksumSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = stream.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = stream.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun deleteQuietly(file: File) {
        if (file.exists()) {
            file.deleteRecursively()
        }
    }

    fun copyDir(src: File, dest: File) {
        if (!src.exists()) return
        if (dest.exists()) dest.deleteRecursively()
        src.copyRecursively(dest, overwrite = true)
    }

    fun deletePaths(root: File, paths: List<String>) {
        paths.forEach { rel ->
            // 防止路径穿越
            if (rel.contains("..")) return@forEach
            val target = File(root, rel)
            if (target.exists()) {
                target.deleteRecursively()
            }
        }
    }
}
