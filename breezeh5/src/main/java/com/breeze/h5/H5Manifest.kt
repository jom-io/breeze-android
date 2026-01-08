package com.breeze.h5

data class H5Manifest(
    val version: Int,
    val url: String,
    val hash: String,
    val size: Long? = null,
    val patchFrom: Int? = null,
    val patchUrl: String? = null,
    val patchHash: String? = null,
    val patchSize: Long? = null,
    val deleted: List<String>? = null,
)
