package com.ztdrop.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/** 本机缩略图不进入聊天信令；始终按采样尺寸解码，不在聊天 UI 线程读取原图。 */
internal object ChatImages {
    private val workers = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    fun isImage(name: String) = Regex("(?i).*\\.(png|jpe?g|gif|webp|bmp)$").matches(name)
    fun decode(context: Context, uri: Uri, edge: Int = 1024): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (bounds.outWidth / sample > edge || bounds.outHeight / sample > edge) sample *= 2
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null,
                BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
    fun savePreview(context: Context, id: String, uri: Uri): String? {
        if (!validId(id)) return null
        val bitmap = decode(context, uri) ?: return null
        return try {
            val directory = File(context.filesDir, "chat-images").apply { mkdirs() }
            val file = File(directory, "$id.jpg")
            val temp = File(directory, "$id.tmp")
            temp.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
            require(temp.renameTo(file))
            Uri.fromFile(file).toString()
        } catch (_: Exception) { null } finally { bitmap.recycle() }
    }
    fun loadInto(context: Context, uri: String, image: ImageView, onFailure: () -> Unit = {}) {
        if (image.tag == uri && image.drawable != null) return
        image.tag = uri; image.setImageDrawable(null)
        val cached = cache.get(uri)
        if (cached != null) { image.setImageBitmap(cached); return }
        workers.execute {
            val bitmap = decode(context.applicationContext, Uri.parse(uri))
            if (bitmap != null) cache.put(uri, bitmap)
            image.post { if (image.tag == uri) { if (bitmap != null) image.setImageBitmap(bitmap) else onFailure() } }
        }
    }
}
