package com.cinefx.videoeditor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

object Utils {
    private const val TAG = "VideoEditorUtils"
    const val MAX_SRT_SIZE_BYTES = 1_048_576L // 1 MB

    fun getVideoDurationMs(context: Context, videoUri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, videoUri)
            val timeStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            timeStr?.toLong() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting video duration: ${e.message}", e)
            0L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting file name: ${e.message}", e)
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result ?: "video_file.mp4"
    }

    fun formatTime(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        val remainingMs = (ms % 1000) / 100
        return String.format("%02d:%02d.%01d", minutes, seconds, remainingMs)
    }

    fun estimateStreamSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun parseSrt(content: String): List<SubtitleItem> {
        val items = mutableListOf<SubtitleItem>()
        // Normalize line endings and split by double blank lines (or multiple newlines)
        val blocks = content.replace("\r\n", "\n").split("\n\n")
        for (block in blocks) {
            val lines = block.trim().lines().filter { it.isNotBlank() }
            if (lines.size >= 3) {
                val timeLine = lines[1]
                val textLines = lines.drop(2)
                
                // Matches standard SRT timestamp formats like "00:01:20,123 --> 00:01:23,456"
                // Support comma or dot separator for milliseconds
                val regex = """(\d+):(\d+):(\d+)[,.](\d+)\s*-->\s*(\d+):(\d+):(\d+)[,.](\d+)""".toRegex()
                val matchResult = regex.find(timeLine)
                if (matchResult != null) {
                    val startMs = parseTimeParts(
                        matchResult.groupValues[1],
                        matchResult.groupValues[2],
                        matchResult.groupValues[3],
                        matchResult.groupValues[4]
                    )
                    val endMs = parseTimeParts(
                        matchResult.groupValues[5],
                        matchResult.groupValues[6],
                        matchResult.groupValues[7],
                        matchResult.groupValues[8]
                    )
                    val text = textLines.joinToString("\n")
                    items.add(SubtitleItem(startMs, endMs, text))
                }
            }
        }
        return items
    }

    private fun parseTimeParts(hoursStr: String, minutesStr: String, secondsStr: String, msStr: String): Long {
        val hrs = hoursStr.toLongOrNull() ?: 0L
        val mins = minutesStr.toLongOrNull() ?: 0L
        val secs = secondsStr.toLongOrNull() ?: 0L
        val ms = msStr.toLongOrNull() ?: 0L
        return (hrs * 3600 + mins * 60 + secs) * 1000 + ms
    }
}
