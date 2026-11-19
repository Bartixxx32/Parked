package com.bartixxx.parked.family

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Shrinks a spot photo before it is encrypted and sent to the family.
 *
 * The original stays untouched in the spot's private photo directory. Recipients
 * get a 1280 px JPEG that is small enough for the Supabase free tier and still
 * readable as a parking photo. [ImageDecoder] applies the EXIF rotation, so the
 * received image is upright.
 */
object FamilyPhotoCompressor {

    const val MAX_DIMENSION = 1280
    const val MAX_BYTES = 900_000

    private const val FIRST_QUALITY = 82
    private const val MIN_QUALITY = 40
    private const val QUALITY_STEP = 10

    fun compress(file: File): ByteArray? = runCatching {
        val source = ImageDecoder.createSource(file)
        var bitmap: Bitmap? = null
        try {
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                if (width > 0 && height > 0) {
                    val scale = minOf(
                        1f,
                        MAX_DIMENSION.toFloat() / maxOf(width, height).toFloat(),
                    )
                    if (scale < 1f) {
                        decoder.setTargetSize(
                            (width * scale).toInt().coerceAtLeast(1),
                            (height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
            }
            bitmap = decoded
            encodeWithinLimit(decoded)
        } finally {
            bitmap?.recycle()
        }
    }.getOrNull()

    private fun encodeWithinLimit(bitmap: Bitmap): ByteArray? {
        var quality = FIRST_QUALITY
        var encoded = encode(bitmap, quality)
        if (encoded.isEmpty()) return null
        while (encoded.size > MAX_BYTES && quality > MIN_QUALITY) {
            quality -= QUALITY_STEP
            encoded = encode(bitmap, quality)
        }
        if (encoded.size <= MAX_BYTES) return encoded

        // Extremely detailed photos can still exceed the cap at 1280 px. Drop
        // the longest edge to 960 px and try again before giving up.
        val scale = 960f / maxOf(bitmap.width, bitmap.height).toFloat()
        if (scale >= 1f) return null
        val smaller = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        return try {
            quality = FIRST_QUALITY
            encoded = encode(smaller, quality)
            while (encoded.size > MAX_BYTES && quality > MIN_QUALITY) {
                quality -= QUALITY_STEP
                encoded = encode(smaller, quality)
            }
            encoded.takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
        } finally {
            if (smaller !== bitmap) smaller.recycle()
        }
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
            return ByteArray(0)
        }
        return output.toByteArray()
    }
}
