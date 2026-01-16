package com.breeze.h5.internal

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
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
        normalizeUnzipRoot(targetDir)
    }

    private fun normalizeUnzipRoot(targetDir: File) {
        val rootIndex = File(targetDir, "index.html")
        if (rootIndex.exists()) return
        val entries = targetDir.listFiles() ?: return
        val candidate = entries
            .filter { it.isDirectory && it.name != "__MACOSX" }
            .firstOrNull { File(it, "index.html").exists() } ?: return
        candidate.listFiles()?.forEach { child ->
            val dest = File(targetDir, child.name)
            if (!child.renameTo(dest)) {
                child.copyRecursively(dest, overwrite = true)
                child.deleteRecursively()
            }
        }
        candidate.deleteRecursively()
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
        deletePaths(root, paths, emptySet())
    }

    fun deletePaths(root: File, paths: List<String>, keepPaths: Set<String>) {
        paths.forEach { rel ->
            val normalized = normalizeRelativePath(rel) ?: return@forEach
            if (keepPaths.contains(normalized) || keepPaths.contains(rel)) return@forEach
            val target = File(root, normalized)
            if (target.exists()) {
                target.deleteRecursively()
            }
        }
    }

    fun listZipEntries(zipFile: File): Set<String> {
        ZipFile(zipFile).use { zip ->
            val result = mutableSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    val normalized = normalizeZipPath(entry.name)
                    if (normalized.isNotBlank()) {
                        result.add(normalized)
                    }
                }
            }
            return result
        }
    }

    private fun normalizeZipPath(path: String): String {
        return path.replace("\\", "/").trimStart('/')
    }

    private fun normalizeRelativePath(path: String): String? {
        if (path.isBlank()) return null
        val normalized = path.replace("\\", "/").trimStart('/')
        if (normalized.isBlank()) return null
        if (normalized.contains("..")) return null
        if (isAbsoluteLike(path)) return null
        return normalized
    }

    private fun isAbsoluteLike(path: String): Boolean {
        if (path.startsWith("/") || path.startsWith("\\")) return true
        return Regex("^[A-Za-z]:[\\\\/].*").matches(path)
    }
}
