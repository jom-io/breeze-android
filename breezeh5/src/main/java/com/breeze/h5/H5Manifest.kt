package com.breeze.h5

data class H5Manifest(
    val version: Int,
    val url: String,
    val hash: String,
    val size: Long? = null,
)
