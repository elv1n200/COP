package cop.utils

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URI

object WebUtils {
    internal const val DEFAULT_MAX_RESPONSE_BYTES: Long = 16L * 1024L * 1024L
    private const val MAX_REDIRECTS = 5
    private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

    fun setupConnection(url: String, timeout: Int = 5000, useCaches: Boolean = true): InputStream {
        return setupConnection(url, timeout, useCaches, DEFAULT_MAX_RESPONSE_BYTES)
    }

    /**
     * Opens a bounded HTTP(S) response body. The original three-argument
     * overload intentionally remains in place for source and binary
     * compatibility; callers that need a smaller limit can opt into this one.
     */
    fun setupConnection(url: String, timeout: Int, useCaches: Boolean, maxBytes: Long): InputStream {
        require(timeout > 0) { "timeout must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }

        var currentUri = requireHttpUri(url)
        for (redirectCount in 0..MAX_REDIRECTS) {
            val connection = currentUri.toURL().openConnection() as? HttpURLConnection
                ?: throw ProtocolException("Only HTTP(S) connections are supported")
            var bodyHandedOff = false
            var closeRedirectBody = false

            try {
                connection.requestMethod = "GET"
                connection.useCaches = useCaches
                connection.readTimeout = timeout
                connection.connectTimeout = timeout
                connection.doInput = true
                connection.instanceFollowRedirects = false

                val status = connection.responseCode
                when {
                    status in 200..299 -> {
                        val declaredLength = connection.contentLengthLong
                        if (declaredLength == 0L) {
                            throw IOException("Empty HTTP response from ${displayUri(currentUri)}")
                        }
                        if (declaredLength > maxBytes) {
                            throw IOException(
                                "HTTP response from ${displayUri(currentUri)} exceeds the $maxBytes-byte limit"
                            )
                        }

                        val result = BoundedInputStream(
                            connection.inputStream,
                            maxBytes,
                            connection::disconnect,
                        )
                        bodyHandedOff = true
                        return result
                    }

                    status in REDIRECT_STATUSES -> {
                        closeRedirectBody = true
                        if (redirectCount == MAX_REDIRECTS) {
                            throw ProtocolException("Too many HTTP redirects (maximum $MAX_REDIRECTS)")
                        }
                        val location = connection.getHeaderField("Location")
                            ?: throw ProtocolException("HTTP $status response has no Location header")
                        currentUri = resolveHttpRedirect(currentUri, location)
                    }

                    else -> throw IOException("HTTP $status response from ${displayUri(currentUri)}")
                }
            } finally {
                if (!bodyHandedOff) {
                    if (closeRedirectBody) runCatching { connection.inputStream.close() }
                    runCatching { connection.errorStream?.close() }
                    runCatching { connection.disconnect() }
                }
            }
        }

        throw ProtocolException("Too many HTTP redirects (maximum $MAX_REDIRECTS)")
    }

    internal fun isHttpUrl(value: String): Boolean = parseHttpUri(value) != null

    internal fun resolveHttpRedirect(current: URI, location: String): URI {
        val currentUri = parseHttpUri(current.toString())
            ?: throw ProtocolException("Invalid HTTP redirect source")
        val resolved = try {
            currentUri.resolve(location.trim())
        } catch (error: IllegalArgumentException) {
            throw ProtocolException("Invalid HTTP redirect target").also { it.initCause(error) }
        }
        val target = parseHttpUri(resolved.toString())
            ?: throw ProtocolException("Redirect target must use HTTP or HTTPS")

        if (currentUri.scheme.equals("https", ignoreCase = true) &&
            target.scheme.equals("http", ignoreCase = true)
        ) {
            throw ProtocolException("Refusing HTTPS-to-HTTP redirect")
        }
        return target
    }

    internal fun displayUri(uri: URI): String {
        val host = uri.host ?: return "${uri.scheme}://<host>${uri.path.orEmpty()}"
        return runCatching {
            URI(uri.scheme, null, host, uri.port, uri.path, null, null).toASCIIString()
        }.getOrElse {
            "${uri.scheme}://<host>${uri.path.orEmpty()}"
        }
    }

    private fun requireHttpUri(value: String): URI = parseHttpUri(value)
        ?: throw IllegalArgumentException("URL must be an absolute HTTP or HTTPS URI")

    private fun parseHttpUri(value: String): URI? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        val isHttp = uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)
        return uri.takeIf {
            isHttp && it.isAbsolute && !it.isOpaque && !it.rawAuthority.isNullOrBlank()
        }
    }
}

/**
 * Delivers at most [maxBytes] from [input]. If the source contains another
 * byte, reading fails and closes both the body and its owner (an HTTP
 * connection in production). Mark/reset is deliberately disabled so the byte
 * accounting cannot be bypassed.
 */
internal class BoundedInputStream(
    input: InputStream,
    private val maxBytes: Long,
    private val onClose: () -> Unit = {},
) : FilterInputStream(input) {
    private var delivered = 0L
    private var closed = false
    private var eofVerified = false

    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    override fun read(): Int {
        ensureOpen()
        if (delivered == maxBytes) return verifyEndOfInput()

        return super.read().also { value ->
            if (value != -1) delivered++
        }
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
        if (length == 0) return 0
        ensureOpen()
        if (delivered == maxBytes) return verifyEndOfInput()

        val allowed = minOf(length.toLong(), maxBytes - delivered).toInt()
        return super.read(bytes, offset, allowed).also { count ->
            if (count > 0) delivered += count.toLong()
        }
    }

    override fun skip(amount: Long): Long {
        if (amount <= 0) return 0
        ensureOpen()
        if (delivered == maxBytes) {
            verifyEndOfInput()
            return 0
        }

        val skipped = super.skip(minOf(amount, maxBytes - delivered))
        delivered += skipped
        return skipped
    }

    override fun available(): Int {
        ensureOpen()
        val remaining = maxBytes - delivered
        return minOf(super.available().toLong(), remaining).toInt()
    }

    override fun markSupported(): Boolean = false

    override fun mark(readLimit: Int) = Unit

    override fun reset(): Unit = throw IOException("mark/reset is not supported")

    override fun close() {
        if (closed) return
        closed = true

        var failure: Throwable? = null
        try {
            super.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            onClose()
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Stream is closed")
    }

    private fun verifyEndOfInput(): Int {
        if (eofVerified) return -1
        if (super.read() == -1) {
            eofVerified = true
            return -1
        }

        val error = IOException("Response exceeds the $maxBytes-byte limit")
        try {
            close()
        } catch (closeError: Throwable) {
            error.addSuppressed(closeError)
        }
        throw error
    }
}
