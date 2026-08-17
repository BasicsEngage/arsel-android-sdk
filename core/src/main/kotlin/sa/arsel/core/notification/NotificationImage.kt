package sa.arsel.core.notification

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import sa.arsel.core.log.ArselLog
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bounded big-picture image fetch.
 *
 * Three separate bounds, all load-bearing. Android destroys the FCM message-handling service at
 * roughly 20 seconds, and this fetch sits on that clock: borrowing the 15s API timeout for both
 * connect *and* read spends ~30s here and loses the notification and its engagement together. Size is
 * capped because the URL is customer-controlled, and the decode is downsampled because a full-res
 * camera JPEG decoded at 1:1 is an OOM on a low-memory handset — where the bitmap is then scaled
 * down to a shade-sized thumbnail anyway.
 */
internal object NotificationImage {
    fun load(
        url: String,
        log: ArselLog,
    ): Bitmap? {
        val bytes =
            runCatching { fetchBounded(url) }
                .onFailure { log.w("notification image fetch failed; rendering text-only", it) }
                .getOrNull()
                ?: return null

        return runCatching { decodeDownsampled(bytes) }
            .onFailure { log.w("notification image decode failed; rendering text-only", it) }
            .getOrNull()
    }

    private fun fetchBounded(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = true
                }
            conn.inputStream.use { readBounded(it, System.currentTimeMillis() + TOTAL_BUDGET_MS) }
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Null on either bound rather than a partial image: a truncated JPEG decodes to a half-grey
     * rectangle, which looks like a product bug in a way that "no image" does not.
     */
    private fun readBounded(
        stream: InputStream,
        deadlineMs: Long,
    ): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK_BYTES)
        while (true) {
            if (System.currentTimeMillis() > deadlineMs) return null
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Bounds pass first: `inJustDecodeBounds` allocates nothing, so an oversized image costs nothing. */
    private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** `inSampleSize` is rounded down to a power of two by the decoder anyway, so step by two. */
    private fun sampleSizeFor(
        width: Int,
        height: Int,
    ): Int {
        var sample = 1
        while (width / (sample * 2) >= TARGET_WIDTH_PX || height / (sample * 2) >= TARGET_HEIGHT_PX) {
            sample *= 2
        }
        return sample
    }

    private const val TIMEOUT_MS = 5_000
    private const val TOTAL_BUDGET_MS = 8_000L
    private const val MAX_BYTES = 1_048_576
    private const val CHUNK_BYTES = 16_384

    /** Comfortably above what `BigPictureStyle` renders on any current density. */
    private const val TARGET_WIDTH_PX = 1_024
    private const val TARGET_HEIGHT_PX = 512
}
