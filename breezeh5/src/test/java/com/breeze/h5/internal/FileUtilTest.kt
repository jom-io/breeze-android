package com.breeze.h5.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileUtilTest {
    @Test
    fun `deletePaths keeps protected and skips unsafe paths`() {
        val root = createTempDir()
        val keep = File(root, "keep.js").apply { writeText("ok") }
        val remove = File(root, "remove.js").apply { writeText("bad") }

        FileUtil.deletePaths(
            root,
            listOf("keep.js", "remove.js", "../escape.js", "/abs.js", "C:\\abs.js"),
            keepPaths = setOf("keep.js"),
        )

        assertThat(keep.exists()).isTrue()
        assertThat(remove.exists()).isFalse()
    }

    @Test
    fun `listZipEntries normalizes entry paths`() {
        val root = createTempDir()
        val zip = File(root, "test.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("a/b.js"))
            out.write("x".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("/c.css"))
            out.write("y".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("dir/"))
            out.closeEntry()
        }

        val entries = FileUtil.listZipEntries(zip)

        assertThat(entries).contains("a/b.js", "c.css")
    }
}
