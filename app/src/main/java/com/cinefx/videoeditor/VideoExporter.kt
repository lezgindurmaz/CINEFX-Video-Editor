package com.cinefx.videoeditor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Effect
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.TimestampWrapper
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlMatrixTransformation
import androidx.media3.transformer.Effects
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.opengl.Matrix

class RangeVolumeProcessor(
    private val volumeInside: Float,
    private val volumeOutside: Float,
    private val rangeStartMs: Long,
    private val rangeEndMs: Long
) : AudioProcessor {
    private var activeFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private var bytesWritten = 0L

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean = pendingFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val remaining = inputBuffer.remaining()
        if (outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        val sampleSize = 2
        val sampleRate = activeFormat.sampleRate
        val channelCount = activeFormat.channelCount
        val bytesPerMs = (sampleRate * channelCount * sampleSize) / 1000.0f
        val startByte = (rangeStartMs * bytesPerMs).toLong()
        val endByte = (rangeEndMs * bytesPerMs).toLong()

        while (inputBuffer.hasRemaining()) {
            var sample = inputBuffer.getShort()
            val scale = if (bytesWritten in startByte..endByte) volumeInside else volumeOutside
            sample = (sample * scale).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            outputBuffer.putShort(sample)
            bytesWritten += sampleSize
        }
        outputBuffer.flip()
    }

    override fun getOutput(): ByteBuffer {
        val buffer = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return buffer
    }

    override fun queueEndOfStream() { inputEnded = true }
    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER
    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        bytesWritten = 0L
        activeFormat = pendingFormat
    }
    override fun reset() {
        flush()
        activeFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingFormat = AudioProcessor.AudioFormat.NOT_SET
    }
}

class ZoomTransformation(private val zoomIn: Boolean, private val durationUs: Long) : GlMatrixTransformation {
    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray {
        val progress = presentationTimeUs.toFloat() / durationUs.coerceAtLeast(1L).toFloat()
        val scale = if (zoomIn) 1.0f + (progress * 0.5f) else 1.5f - (progress * 0.5f)
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)
        Matrix.scaleM(matrix, 0, scale, scale, 1.0f)
        return matrix
    }
}

class SlideTransformation(private val slideLeft: Boolean, private val durationUs: Long) : GlMatrixTransformation {
    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray {
        val progress = presentationTimeUs.toFloat() / durationUs.coerceAtLeast(1L).toFloat()
        val translate = if (slideLeft) progress * 2.0f else progress * -2.0f
        val matrix = FloatArray(16)
        Matrix.setIdentityM(matrix, 0)
        Matrix.translateM(matrix, 0, translate, 0f, 0f)
        return matrix
    }
}

object VideoExporter {
    private const val TAG = "VideoExporter"

    private fun createEffectsForSegment(
        startMs: Long,
        durationMs: Long,
        isFirstSegment: Boolean,
        isLastSegment: Boolean,
        enableTransition: Boolean,
        textOverlays: List<VideoTextOverlay>,
        subtitles: List<SubtitleItem>,
        appliedEffects: List<EffectItem>,
        appliedFilters: List<FilterItem>,
        isJoinMode: Boolean,
        hasIntro: Boolean = false,
        hasOutro: Boolean = false,
        enableWatermark: Boolean = false
    ): List<Effect> {
        val effectsList = mutableListOf<Effect>()

        for (item in appliedEffects) {
            val startUs = (item.startMs - startMs).coerceAtLeast(0) * 1000L
            val endUs = (item.endMs - startMs).coerceAtMost(durationMs) * 1000L
            if (startUs < endUs && startUs < durationMs * 1000L) {
                val effectDurationUs = endUs - startUs
                val glEffect: GlEffect = when (item.type) {
                    EffectType.ZOOM_IN -> ZoomTransformation(true, effectDurationUs)
                    EffectType.ZOOM_OUT -> ZoomTransformation(false, effectDurationUs)
                    EffectType.SLIDE_LEFT -> SlideTransformation(true, effectDurationUs)
                    EffectType.SLIDE_RIGHT -> SlideTransformation(false, effectDurationUs)
                    EffectType.FADE_IN -> RgbFilter.createGrayscaleFilter()
                    EffectType.FADE_OUT -> RgbFilter.createGrayscaleFilter()
                }
                effectsList.add(TimestampWrapper(glEffect, startUs, endUs))
            }
        }

        for (filter in appliedFilters) {
            val startUs = (filter.startMs - startMs).coerceAtLeast(0) * 1000L
            val endUs = (filter.endMs - startMs).coerceAtMost(durationMs) * 1000L
            if (startUs < endUs && startUs < durationMs * 1000L) {
                val media3Filter: GlEffect = if (filter.type == FilterType.GRAYSCALE) {
                    RgbFilter.createGrayscaleFilter()
                } else {
                    val arr = FilterMatrices.toExportArray(filter.type)
                    RgbMatrix { _, _ -> arr }
                }
                effectsList.add(TimestampWrapper(media3Filter, startUs, endUs))
            }
        }

        if (textOverlays.isNotEmpty() || subtitles.isNotEmpty() || enableTransition || enableWatermark) {
            val bitmapOverlay = object : androidx.media3.effect.BitmapOverlay() {
                private var cachedBitmap: android.graphics.Bitmap? = null
                private var cachedCanvas: android.graphics.Canvas? = null

                private fun getOrCreateBitmap(width: Int, height: Int): Pair<android.graphics.Bitmap, android.graphics.Canvas> {
                    val bmp = cachedBitmap
                    val cvs = cachedCanvas
                    if (bmp != null && cvs != null && bmp.width == width && bmp.height == height && !bmp.isRecycled) {
                        bmp.eraseColor(android.graphics.Color.TRANSPARENT)
                        return bmp to cvs
                    }
                    cachedBitmap?.recycle()
                    val newBmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    val newCvs = android.graphics.Canvas(newBmp)
                    cachedBitmap = newBmp
                    cachedCanvas = newCvs
                    return newBmp to newCvs
                }

                override fun getBitmap(presentationTimeUs: Long): android.graphics.Bitmap {
                    val presentationTimeMs = presentationTimeUs / 1000
                    val width = 1280
                    val height = 720
                    val (bitmap, canvas) = getOrCreateBitmap(width, height)

                    for (item in textOverlays) {
                        val startInTrim = item.startMs - startMs
                        val endInTrim = item.endMs - startMs
                        if (presentationTimeMs in startInTrim..endInTrim) {
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                textSize = item.size
                                isAntiAlias = true
                                textAlign = android.graphics.Paint.Align.CENTER
                                setShadowLayer(6f, 3f, 3f, android.graphics.Color.BLACK)
                                typeface = when {
                                    item.isBold && item.isItalic -> android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC)
                                    item.isBold -> android.graphics.Typeface.DEFAULT_BOLD
                                    item.isItalic -> android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC)
                                    else -> android.graphics.Typeface.DEFAULT
                                }
                            }
                            val x = ((item.xCo + 1f) / 2f) * width
                            val y = ((1f - item.yCo) / 2f) * height
                            if (item.rotation != 0f) {
                                canvas.save()
                                canvas.rotate(item.rotation, x, y)
                                canvas.drawText(item.text, x, y, paint)
                                canvas.restore()
                            } else {
                                canvas.drawText(item.text, x, y, paint)
                            }
                        }
                    }

                    for (sub in subtitles) {
                        val startInTrim = sub.startMs - startMs
                        val endInTrim = sub.endMs - startMs
                        if (presentationTimeMs in startInTrim..endInTrim) {
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor(sub.colorHex)
                                textSize = height * 0.05f
                                isAntiAlias = true
                                textAlign = android.graphics.Paint.Align.CENTER
                                setShadowLayer(6f, 3f, 3f, android.graphics.Color.BLACK)
                            }
                            val x = width / 2f
                            var currentY = height * 0.88f
                            for (line in sub.text.split("\n")) {
                                canvas.drawText(line, x, currentY, paint)
                                currentY += paint.textSize + 12f
                            }
                        }
                    }

                    if (enableTransition && isJoinMode) {
                        val fadeMs = 1000L
                        var drawFade = false
                        var fadeAlpha = 0f
                        if (!isFirstSegment && !hasIntro && presentationTimeMs <= fadeMs) {
                            fadeAlpha = 1f - (presentationTimeMs.toFloat() / fadeMs).coerceIn(0f, 1f)
                            drawFade = true
                        } else if (!isLastSegment && !hasOutro && presentationTimeMs >= (durationMs - fadeMs)) {
                            fadeAlpha = (presentationTimeMs - (durationMs - fadeMs)).toFloat() / fadeMs
                            drawFade = true
                        }
                        if (drawFade && fadeAlpha > 0.01f) {
                            val p = android.graphics.Paint().apply {
                                color = android.graphics.Color.BLACK
                                alpha = (fadeAlpha * 255).toInt()
                                style = android.graphics.Paint.Style.FILL
                            }
                            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
                        }
                    }

                    if (enableWatermark) {
                        val wmPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            alpha = 140
                            textSize = height * 0.04f
                            isAntiAlias = true
                            textAlign = android.graphics.Paint.Align.RIGHT
                            setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        }
                        canvas.drawText(
                            "CINEFX \u2022 Free Version",
                            width - (width * 0.03f),
                            height - (height * 0.04f),
                            wmPaint
                        )
                    }

                    return bitmap
                }

                override fun getOverlaySettings(presentationTimeUs: Long): androidx.media3.effect.OverlaySettings =
                    androidx.media3.effect.OverlaySettings.Builder().build()
            }
            effectsList.add(androidx.media3.effect.OverlayEffect(com.google.common.collect.ImmutableList.of(bitmapOverlay)))
        }
        return effectsList
    }

    fun export(
        context: Context,
        config: ExportConfig,
        scope: CoroutineScope,
        onProgress: (Float) -> Unit,
        onSuccess: (Uri) -> Unit,
        onError: (Exception) -> Unit
    ) {
        scope.launch {
            try {
                val outputDir = File(context.cacheDir, "edited_videos").apply { if (!exists()) mkdirs() }
                val outputFile = File(outputDir, "edited_video_${System.currentTimeMillis()}.mp4")
                var totalDuration = 0L
                val videoSegments = mutableListOf<EditedMediaItem>()

                if (config.introImageUri != null && config.introDurationMs > 0L) {
                    totalDuration += config.introDurationMs
                    val introFile = copyUriToCache(context, config.introImageUri, "intro_image.png")
                    if (introFile != null) {
                        val introEffects = createEffectsForSegment(
                            0, config.introDurationMs, true, false,
                            config.enableTransition, emptyList(), emptyList(), emptyList(), emptyList(),
                            false, false, false
                        )
                        videoSegments.add(
                            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(introFile)))
                                .setDurationUs(config.introDurationMs * 1000L)
                                .setFrameRate(30)
                                .setEffects(Effects(
                                    com.google.common.collect.ImmutableList.of(),
                                    com.google.common.collect.ImmutableList.copyOf(introEffects)
                                ))
                                .build()
                        )
                    } else {
                        Log.w(TAG, "Intro image could not be cached, skipping intro")
                    }
                }

                val duration1 = config.endMs - config.startMs
                totalDuration += duration1
                val isJoinMode = config.videoUri2 != null || config.outroImageUri != null || config.introImageUri != null
                val seg1Effects = createEffectsForSegment(
                    config.startMs, duration1,
                    videoSegments.isEmpty(),
                    config.videoUri2 == null && config.outroImageUri == null,
                    config.enableTransition,
                    config.textOverlays, config.subtitles, config.effects, config.filters,
                    isJoinMode,
                    config.introImageUri != null,
                    config.videoUri2 != null || config.outroImageUri != null,
                    config.enableWatermark
                )

                val videoEditedItemBuilder1 = EditedMediaItem.Builder(
                    MediaItem.Builder()
                        .setUri(config.videoUri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(config.startMs)
                                .setEndPositionMs(config.endMs)
                                .build()
                        )
                        .build()
                )
                if (config.muteOriginalAudio) videoEditedItemBuilder1.setRemoveAudio(true)

                val seg1AudioProcessors = mutableListOf<AudioProcessor>()
                if (!config.muteOriginalAudio) {
                    if (config.enableVolumeDucking) {
                        val oS = config.volumeRangeStartMs.coerceAtLeast(0)
                        val oE = config.volumeRangeEndMs.coerceAtMost(duration1)
                        if (oS < oE) seg1AudioProcessors.add(RangeVolumeProcessor(config.originalVolume, 1.0f, oS, oE))
                    } else if (config.originalVolume < 0.99f || config.originalVolume > 1.01f) {
                        seg1AudioProcessors.add(RangeVolumeProcessor(config.originalVolume, config.originalVolume, 0, duration1))
                    }
                }
                videoSegments.add(
                    videoEditedItemBuilder1.setEffects(Effects(
                        com.google.common.collect.ImmutableList.copyOf(seg1AudioProcessors),
                        com.google.common.collect.ImmutableList.copyOf(seg1Effects)
                    )).build()
                )

                // NOTE: Effects/filters/subtitles are currently only applied to the first video segment.
                // Second video segment uses empty lists — extending this is a future enhancement.
                if (config.videoUri2 != null) {
                    val duration2 = config.endMs2 - config.startMs2
                    totalDuration += duration2
                    val seg2Effects = createEffectsForSegment(
                        config.startMs2, duration2, false, config.outroImageUri == null,
                        config.enableTransition, emptyList(), emptyList(), emptyList(), emptyList(),
                        true, false, false, config.enableWatermark
                    )
                    val videoMediaItem2 = MediaItem.Builder()
                        .setUri(config.videoUri2)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(config.startMs2)
                                .setEndPositionMs(config.endMs2)
                                .build()
                        )
                        .build()
                    val videoEditedItemBuilder2 = EditedMediaItem.Builder(videoMediaItem2)
                    if (config.muteOriginalAudio) videoEditedItemBuilder2.setRemoveAudio(true)

                    val seg2AudioProcessors = mutableListOf<AudioProcessor>()
                    if (!config.muteOriginalAudio) {
                        if (config.enableVolumeDucking) {
                            val oS = (config.volumeRangeStartMs - duration1).coerceAtLeast(0)
                            val oE = (config.volumeRangeEndMs - duration1).coerceAtMost(duration2)
                            if (oS < oE) seg2AudioProcessors.add(RangeVolumeProcessor(config.originalVolume, 1.0f, oS, oE))
                        } else if (config.originalVolume < 0.99f || config.originalVolume > 1.01f) {
                            seg2AudioProcessors.add(RangeVolumeProcessor(config.originalVolume, config.originalVolume, 0, duration2))
                        }
                    }
                    videoSegments.add(
                        videoEditedItemBuilder2.setEffects(Effects(
                            com.google.common.collect.ImmutableList.copyOf(seg2AudioProcessors),
                            com.google.common.collect.ImmutableList.copyOf(seg2Effects)
                        )).build()
                    )
                }

                if (config.outroImageUri != null && config.outroDurationMs > 0L) {
                    totalDuration += config.outroDurationMs
                    val outroFile = copyUriToCache(context, config.outroImageUri, "outro_image.png")
                    if (outroFile != null) {
                        val outroEffects = createEffectsForSegment(
                            0, config.outroDurationMs, false, true,
                            config.enableTransition, emptyList(), emptyList(), emptyList(), emptyList(),
                            false, false, false
                        )
                        videoSegments.add(
                            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(outroFile)))
                                .setDurationUs(config.outroDurationMs * 1000L)
                                .setFrameRate(30)
                                .setEffects(Effects(
                                    com.google.common.collect.ImmutableList.of(),
                                    com.google.common.collect.ImmutableList.copyOf(outroEffects)
                                ))
                                .build()
                        )
                    } else {
                        Log.w(TAG, "Outro image could not be cached, skipping outro")
                    }
                }

                val sequences = mutableListOf(EditedMediaItemSequence(videoSegments))
                if (config.audioUri != null) {
                    val audioEndMs = if (config.audioEndTrimMs > 0) config.audioEndTrimMs else (config.audioStartTrimMs + totalDuration)
                    val audioEditedItemBuilder = EditedMediaItem.Builder(
                        MediaItem.Builder()
                            .setUri(config.audioUri)
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(config.audioStartTrimMs)
                                    .setEndPositionMs(audioEndMs)
                                    .build()
                            )
                            .build()
                    ).setRemoveVideo(true)

                    val musicProcessors = mutableListOf<AudioProcessor>()
                    if (config.enableMusicRange) {
                        musicProcessors.add(RangeVolumeProcessor(config.musicVolume, 0.0f, config.musicRangeStartMs, config.musicRangeEndMs))
                    } else if (config.musicVolume < 0.99f || config.musicVolume > 1.01f) {
                        musicProcessors.add(RangeVolumeProcessor(config.musicVolume, config.musicVolume, 0, totalDuration))
                    }
                    if (musicProcessors.isNotEmpty()) {
                        audioEditedItemBuilder.setEffects(Effects(
                            com.google.common.collect.ImmutableList.copyOf(musicProcessors),
                            com.google.common.collect.ImmutableList.of()
                        ))
                    }
                    sequences.add(EditedMediaItemSequence(audioEditedItemBuilder.build()))
                }

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
                    .setAudioMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
                    .build()
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        val galleryUri = insertVideoToGallery(context, outputFile, "EditedVideo_${System.currentTimeMillis()}")
                        onSuccess(galleryUri ?: Uri.fromFile(outputFile))
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        onError(exportException)
                    }
                })
                transformer.start(
                    Composition.Builder(sequences).experimentalSetForceAudioTrack(true).build(),
                    outputFile.absolutePath
                )
                scope.launch {
                    val progressHolder = ProgressHolder()
                    while (true) {
                        try {
                            val state = transformer.getProgress(progressHolder)
                            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                                onProgress(progressHolder.progress / 100f)
                            } else if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                                break
                            }
                        } catch (_: Exception) { break }
                        delay(250)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                onError(e)
            }
        }
    }

    private fun copyUriToCache(context: Context, uri: Uri, fileName: String): File? {
        return try {
            val destFile = File(File(context.cacheDir, "outro_cache").apply { if (!exists()) mkdirs() }, fileName)
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, options)
            }
            val sampleSize = generateSequence(1) { it * 2 }
                .first { options.outWidth / it <= 1920 && options.outHeight / it <= 1920 }
            val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null
            destFile.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            if (destFile.exists() && destFile.length() > 0) destFile else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache image: ${e.message}", e)
            null
        }
    }

    private fun insertVideoToGallery(context: Context, sourceFile: File, title: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$title.mp4")
            put(MediaStore.Video.Media.TITLE, title)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CINEFX")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, contentValues)
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(sourceFile).use { input -> input.copyTo(output) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert video to gallery: ${e.message}", e)
                context.contentResolver.delete(uri, null, null)
                return null
            }
        }
        return uri
    }
}
