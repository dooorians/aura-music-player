package com.ikko.aura.data.model

@androidx.compose.runtime.Immutable
data class Artist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
)
