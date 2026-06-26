package com.cinefx.videoeditor

import android.graphics.ColorMatrix

/**
 * Single source of truth for all filter color matrices.
 * Both preview (android.graphics.ColorMatrix) and export (media3 RgbMatrix column-major 4x4)
 * are derived from the same core 3x3 RGB transform values.
 */
object FilterMatrices {

    // Core RGB 3x3 transform: matrix[outputChannel][inputChannel]
    // GRAYSCALE is handled separately via setSaturation(0) / createGrayscaleFilter()
    private val coreMatrices: Map<FilterType, Array<FloatArray>> = mapOf(
        FilterType.SEPIA to arrayOf(
            floatArrayOf(0.393f, 0.769f, 0.189f),
            floatArrayOf(0.349f, 0.686f, 0.168f),
            floatArrayOf(0.272f, 0.534f, 0.131f)
        ),
        FilterType.CYBERPUNK to arrayOf(
            floatArrayOf(1.5f, -0.5f, 0.5f),
            floatArrayOf(-0.5f, 1.0f, 1.5f),
            floatArrayOf(0.5f, 0f, 2.0f)
        ),
        FilterType.VINTAGE to arrayOf(
            floatArrayOf(0.9f, 0.1f, 0.1f),
            floatArrayOf(0.2f, 0.8f, 0.1f),
            floatArrayOf(0.1f, 0.2f, 0.7f)
        ),
        FilterType.COOL to arrayOf(
            floatArrayOf(0.7f, 0f, 0.3f),
            floatArrayOf(0f, 0.8f, 0.5f),
            floatArrayOf(0f, 0f, 1.4f)
        ),
        FilterType.WARM to arrayOf(
            floatArrayOf(1.4f, 0f, 0f),
            floatArrayOf(0f, 1.1f, 0f),
            floatArrayOf(0f, 0f, 0.8f)
        )
    )

    /**
     * Returns an android.graphics.ColorMatrix for preview rendering.
     * Uses row-major 4x5 format: [R_R, R_G, R_B, R_A, R_T, G_R, ...]
     */
    fun toPreviewMatrix(type: FilterType): ColorMatrix {
        if (type == FilterType.GRAYSCALE) {
            return ColorMatrix().apply { setSaturation(0f) }
        }
        val m = coreMatrices[type] ?: return ColorMatrix()
        return ColorMatrix(floatArrayOf(
            m[0][0], m[0][1], m[0][2], 0f, 0f,
            m[1][0], m[1][1], m[1][2], 0f, 0f,
            m[2][0], m[2][1], m[2][2], 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    /**
     * Returns a column-major 4x4 float array for media3 RgbMatrix export.
     * Format: [col0_r, col0_g, col0_b, col0_a, col1_r, ...]
     */
    fun toExportArray(type: FilterType): FloatArray {
        if (type == FilterType.GRAYSCALE) {
            // Identity-like; actual grayscale uses RgbFilter.createGrayscaleFilter()
            return floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        }
        val m = coreMatrices[type] ?: return floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        return floatArrayOf(
            m[0][0], m[1][0], m[2][0], 0f,
            m[0][1], m[1][1], m[2][1], 0f,
            m[0][2], m[1][2], m[2][2], 0f,
            0f, 0f, 0f, 1f
        )
    }
}
