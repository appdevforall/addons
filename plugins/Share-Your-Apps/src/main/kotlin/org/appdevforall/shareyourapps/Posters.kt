package org.appdevforall.shareyourapps

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream

/**
 * Makes a video's poster: a JPEG of the frame 1.5 seconds in, or halfway through a shorter
 * video — the very first frame is often black or mid-fade. Wide frames are scaled to 960 pixels
 * across, which is plenty for a 160-pixel-high strip. Any failure means no poster, never a failed publish.
 */
object Posters {
    private const val MAX_WIDTH = 960

    fun make(video: File, out: File): Boolean {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(video.path)
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = minOf(1_500L, durationMs / 2) * 1000
            val frame = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return false
            val scaled = if (frame.width > MAX_WIDTH)
                Bitmap.createScaledBitmap(frame, MAX_WIDTH, frame.height * MAX_WIDTH / frame.width, true) else frame
            FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (e: Exception) {
            out.delete()
            false
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }
}
