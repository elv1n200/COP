package cop.utils.ui.rendering

import cop.utils.WebUtils.setupConnection
import cop.utils.WebUtils.isHttpUrl
import cop.utils.WebUtils.displayUri
import org.lwjgl.system.MemoryUtil
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.net.URI

/**
 * from OdinFabric (BSD 3-Clause)
 * copyright (c) 2025-2026 odtheking
 * original: https://github.com/odtheking/OdinFabric/blob/main/src/main/kotlin/com/odtheking/odin/utils/ui/rendering/Image.kt
 */
class Image(
    val identifier: String,
    var isSVG: Boolean = false,
    var stream: InputStream = getStream(identifier),
    private var buffer: ByteBuffer? = null
) {

    init {
        isSVG = isSVG || hasSvgExtension(identifier)
    }

    /** Identifier safe for logs: signed remote-URL credentials are omitted. */
    internal val logIdentifier: String
        get() = sanitizedIdentifier(identifier)

    fun buffer(): ByteBuffer {
        if (buffer == null) {
            val bytes = readEncodedBytes(stream)
            val allocated = MemoryUtil.memAlloc(bytes.size)
            try {
                allocated.put(bytes)
                allocated.flip()
                buffer = allocated
            } catch (error: Throwable) {
                MemoryUtil.memFree(allocated)
                throw error
            }
        }
        return buffer ?: throw IllegalStateException("Image has no data")
    }

    /** Release the encoded native buffer after NanoVG has uploaded the image. */
    internal fun releaseBuffer() {
        buffer?.let(MemoryUtil::memFree)
        buffer = null
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Image) return false
        return identifier == other.identifier
    }

    override fun hashCode(): Int {
        return identifier.hashCode()
    }

    companion object {
        internal const val MAX_ENCODED_IMAGE_BYTES = 16 * 1024 * 1024
        internal const val MAX_IMAGE_DIMENSION = 4096
        internal const val MAX_IMAGE_PIXELS = 16_777_216L

        internal fun readEncodedBytes(
            input: InputStream,
            maxBytes: Int = MAX_ENCODED_IMAGE_BYTES,
        ): ByteArray {
            require(maxBytes in 1 until Int.MAX_VALUE) { "maxBytes must be positive" }
            val bytes = input.use { it.readNBytes(maxBytes + 1) }
            if (bytes.isEmpty()) throw IOException("Image input is empty")
            if (bytes.size > maxBytes) {
                throw IOException("Image input exceeds the $maxBytes-byte limit")
            }
            return bytes
        }

        internal fun checkedRgbaByteCount(width: Int, height: Int): Int {
            require(width in 1..MAX_IMAGE_DIMENSION) {
                "Image width must be between 1 and $MAX_IMAGE_DIMENSION pixels (was $width)"
            }
            require(height in 1..MAX_IMAGE_DIMENSION) {
                "Image height must be between 1 and $MAX_IMAGE_DIMENSION pixels (was $height)"
            }

            val pixels = width.toLong() * height.toLong()
            require(pixels <= MAX_IMAGE_PIXELS) {
                "Image has $pixels pixels; maximum is $MAX_IMAGE_PIXELS"
            }
            return Math.toIntExact(pixels * 4L)
        }

        internal fun hasSvgExtension(identifier: String): Boolean {
            val trimmed = identifier.trim()
            val uriPath = runCatching { URI(trimmed).path }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
            val candidate = uriPath
                ?: trimmed.substringBefore('?').substringBefore('#')
            return candidate.endsWith(".svg", ignoreCase = true)
        }

        internal fun sanitizedIdentifier(identifier: String): String {
            val trimmed = identifier.trim()
            if (!isHttpUrl(trimmed)) return trimmed
            return runCatching { displayUri(URI(trimmed)) }.getOrDefault("<remote image>")
        }

        private fun getStream(path: String): InputStream {
            val trimmedPath = path.trim()
            return if (isHttpUrl(trimmedPath)) setupConnection(trimmedPath)
            else {
                val file = File(trimmedPath)
                if (file.exists() && file.isFile) Files.newInputStream(file.toPath())
                else this::class.java.getResourceAsStream(trimmedPath) ?: throw FileNotFoundException(trimmedPath)
            }
        }
    }
}
