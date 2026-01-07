package com.breeze.h5.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

class VersionUtilTest {
    @Test
    fun `parseVersionName extracts integers`() {
        assertThat(VersionUtil.parseVersionName("v1")).isEqualTo(1)
        assertThat(VersionUtil.parseVersionName("V12")).isEqualTo(12)
        assertThat(VersionUtil.parseVersionName("1")).isNull()
        assertThat(VersionUtil.parseVersionName("v")).isNull()
    }

    @Test
    fun `cleanupOldVersions removes oldest`() {
        val root = createTempDir()
        listOf("v1", "v2", "v3", "v4").forEach { name ->
            File(root, name).mkdirs()
        }

        VersionUtil.cleanupOldVersions(root, keep = 2)

        assertThat(File(root, "v1").exists()).isFalse()
        assertThat(File(root, "v2").exists()).isFalse()
        assertThat(File(root, "v3").exists()).isTrue()
        assertThat(File(root, "v4").exists()).isTrue()
    }
}
