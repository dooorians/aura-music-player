package com.ikko.aura.source.addon.model

import kotlinx.serialization.Serializable

@Serializable
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val resources: List<String> = emptyList(),
    val settings: List<AddonSetting> = emptyList()
)

@Serializable
data class AddonSetting(
    val key: String,
    val type: String,
    val default: String,
    val options: List<SettingOption> = emptyList()
)

@Serializable
data class SettingOption(val label: String, val value: String)

@Serializable
data class AddonSearchResponse(val tracks: List<AddonSearchResult> = emptyList())

@Serializable
data class AddonSearchResult(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val artworkURL: String? = null,
    val format: String,
    val audioQuality: String,
    val streamURL: String? = null
)

@Serializable
data class AddonStream(
    val url: String,
    val format: String,
    val quality: String,
    val codec: String,
    val container: String,
    val manifest: String = "none",
    val encrypted: Boolean = false,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val bitrate: Int = 0
)
