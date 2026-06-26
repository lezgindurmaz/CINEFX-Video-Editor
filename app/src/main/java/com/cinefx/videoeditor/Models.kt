package com.cinefx.videoeditor

import android.net.Uri
import java.io.Serializable

data class VideoTextOverlay(
    val text: String,
    val size: Float,
    val xCo: Float,
    val yCo: Float,
    val startMs: Long,
    val endMs: Long,
    val rotation: Float = 0f,
    val isBold: Boolean = false,
    val isItalic: Boolean = false
) : Serializable

data class SubtitleItem(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val colorHex: String = "#FFFF00"
) : Serializable

enum class EffectType {
    ZOOM_IN, ZOOM_OUT, SLIDE_LEFT, SLIDE_RIGHT, FADE_IN, FADE_OUT
}

data class EffectItem(
    val type: EffectType,
    val startMs: Long,
    val endMs: Long
) : Serializable

enum class FilterType {
    GRAYSCALE, SEPIA, CYBERPUNK, VINTAGE, COOL, WARM
}

data class FilterItem(
    val type: FilterType,
    val startMs: Long,
    val endMs: Long
) : Serializable

/**
 * Groups all export parameters into a single config object.
 * Replaces the 25+ parameter export() call for readability and type-safety.
 */
data class ExportConfig(
    val videoUri: Uri,
    val startMs: Long,
    val endMs: Long,
    val audioUri: Uri? = null,
    val audioStartTrimMs: Long = 0L,
    val audioEndTrimMs: Long = 0L,
    val muteOriginalAudio: Boolean = false,
    val textOverlays: List<VideoTextOverlay> = emptyList(),
    val subtitles: List<SubtitleItem> = emptyList(),
    val videoUri2: Uri? = null,
    val startMs2: Long = 0L,
    val endMs2: Long = 0L,
    val enableTransition: Boolean = true,
    val originalVolume: Float = 1.0f,
    val volumeRangeStartMs: Long = 0L,
    val volumeRangeEndMs: Long = 0L,
    val enableVolumeDucking: Boolean = false,
    val musicVolume: Float = 1.0f,
    val musicRangeStartMs: Long = 0L,
    val musicRangeEndMs: Long = 0L,
    val enableMusicRange: Boolean = false,
    val introImageUri: Uri? = null,
    val introDurationMs: Long = 0L,
    val outroImageUri: Uri? = null,
    val outroDurationMs: Long = 0L,
    val effects: List<EffectItem> = emptyList(),
    val filters: List<FilterItem> = emptyList(),
    val enableWatermark: Boolean = false
)
