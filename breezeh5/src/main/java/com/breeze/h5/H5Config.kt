package com.breeze.h5

/**
 * Configuration for BreezeH5.
 */
data class H5Config(
    val projectName: String,
    val baseUrl: String,
    val fallbackUrl: String? = null,
    val remoteDomains: List<String> = emptyList(),
    val routePrefixes: List<String> = emptyList(),
    val assetBasePath: String = projectName,
    val assetZipName: String = "dist.zip",
    val lastVersionPath: String = "lastversion",
    val manifestPattern: String = "v%d/manifest.json",
    val seedVersion: Int = 1,
    val initialCheckDelayMillis: Long = 30_000L,
    val minCheckIntervalMillis: Long = 60_000L,
    val maxCheckIntervalMillis: Long = 5 * 60_000L,
    val backoffMultiplier: Double = 2.0,
    val enablePeriodicCheck: Boolean = true,
    val keepVersions: Int = 5,
    val useWifiOnly: Boolean = true,
) {
    private val trimmedBase: String
        get() = baseUrl.trimEnd('/')

    val lastVersionUrl: String
        get() = "$trimmedBase/$lastVersionPath"

    fun manifestUrlFor(version: Int): String {
        return if (manifestPattern.contains("%d")) {
            "$trimmedBase/${String.format(manifestPattern, version)}"
        } else {
            "$trimmedBase/${manifestPattern.replace("{version}", version.toString())}"
        }
    }
}
