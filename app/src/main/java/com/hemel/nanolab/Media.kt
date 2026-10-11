package com.hemel.nanolab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object Media {
    const val MAX_VIDEO_BYTES = 500L * 1024L * 1024L

    val thumbCache = LruCache<String, Bitmap>(48)

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "file"
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun sizeOf(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && c.moveToFirst()) return c.getLong(i)
            }
        } catch (e: Exception) {
        }
        return -1L
    }

    fun importImage(ctx: Context, dir: File, uri: Uri): Attachment? {
        val bmp = decodeBitmap(ctx, uri, 1600) ?: return null
        val id = newId()
        val f = File(dir, "$id.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return Attachment(id, "image", f.absolutePath, displayName(ctx, uri), "image/jpeg")
    }

    fun importVideo(ctx: Context, dir: File, uri: Uri): Attachment {
        val size = sizeOf(ctx, uri)
        if (size > MAX_VIDEO_BYTES) throw IllegalStateException("ভিডিও ৫০০ MB এর বেশি বড়")
        val mime = ctx.contentResolver.getType(uri) ?: "video/mp4"
        val ext = when {
            mime.contains("webm") -> "webm"
            mime.contains("3gpp") -> "3gp"
            mime.contains("quicktime") -> "mov"
            else -> "mp4"
        }
        val id = newId()
        val f = File(dir, "$id.$ext")
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("ভিডিও খোলা যায়নি")
        input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return Attachment(id, "video", f.absolutePath, displayName(ctx, uri), mime)
    }

    fun loadThumb(att: Attachment, maxSide: Int): Bitmap? {
        return try {
            if (att.kind == "image") {
                val b = BitmapFactory.Options()
                b.inJustDecodeBounds = true
                BitmapFactory.decodeFile(att.path, b)
                var sample = 1
                while (maxOf(b.outWidth, b.outHeight) / sample > maxSide * 2) sample *= 2
                val o = BitmapFactory.Options()
                o.inSampleSize = sample
                BitmapFactory.decodeFile(att.path, o)
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(att.path)
                    val fr = r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (fr == null) null else scaleDown(fr, maxSide)
                } finally {
                    r.release()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxSide) return b
        val s = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * s).toInt()), maxOf(1, (b.height * s).toInt()), true)
    }

    fun fileBase64(path: String): String =
        Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)

    fun bytesBase64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    fun extractFrames(path: String, count: Int, maxSide: Int = 768): List<ByteArray> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val out = ArrayList<ByteArray>()
            for (i in 1..count) {
                val tUs = if (durMs > 0) durMs * 1000L * i / (count + 1) else 0L
                val bmp = r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                val sc = scaleDown(bmp, maxSide)
                val bos = ByteArrayOutputStream()
                sc.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                out.add(bos.toByteArray())
            }
            return out
        } finally {
            r.release()
        }
    }
}
