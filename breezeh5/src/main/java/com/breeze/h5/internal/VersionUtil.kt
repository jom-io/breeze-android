package com.breeze.h5.internal

import java.io.File

object VersionUtil {
    private const val PREFIX = "v"

    fun parseVersionName(name: String): Int? {
        if (!name.startsWith(PREFIX, ignoreCase = true)) return null
        return name.substring(PREFIX.length).toIntOrNull()
    }

    fun versionFolder(version: Int): String = "$PREFIX$version"

    fun findVersions(root: File): List<Int> {
        if (!root.exists() || !root.isDirectory) return emptyList()
        return root.listFiles()?.mapNotNull { if (it.isDirectory) parseVersionName(it.name) else null }?.sorted()
            ?: emptyList()
    }

    fun cleanupOldVersions(root: File, keep: Int) {
        if (keep <= 0) return
        val versions = findVersions(root)
        if (versions.size <= keep) return
        val toDelete = versions.dropLast(keep)
        toDelete.forEach { version ->
            val dir = File(root, versionFolder(version))
            dir.deleteRecursively()
        }
    }
}
