package cop.utils

import cop.utils.ui.rendering.Image
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ProtocolException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageLoadingPolicyTest {
    @Test
    fun `HTTP URL recognition uses the URI scheme instead of a string prefix`() {
        assertTrue(WebUtils.isHttpUrl("http://example.com/image.png"))
        assertTrue(WebUtils.isHttpUrl(" HTTPS://example.com/image.png?token=secret "))

        assertFalse(WebUtils.isHttpUrl("http_logo.png"))
        assertFalse(WebUtils.isHttpUrl("httpsomething.png"))
        assertFalse(WebUtils.isHttpUrl("httpx://example.com/image.png"))
        assertFalse(WebUtils.isHttpUrl("file:///tmp/image.png"))
        assertFalse(WebUtils.isHttpUrl("http:opaque"))
    }

    @Test
    fun `redirects stay on HTTP transports and never downgrade HTTPS`() {
        assertEquals(
            URI("https://cdn.example.com/images/icon.png"),
            WebUtils.resolveHttpRedirect(
                URI("http://example.com/assets/start.png"),
                "https://cdn.example.com/images/icon.png",
            ),
        )
        assertEquals(
            URI("https://example.com/images/icon.png"),
            WebUtils.resolveHttpRedirect(
                URI("https://example.com/assets/start.png"),
                "../images/icon.png",
            ),
        )

        assertFailsWith<ProtocolException> {
            WebUtils.resolveHttpRedirect(
                URI("https://example.com/start.png"),
                "http://example.com/insecure.png",
            )
        }
        assertFailsWith<ProtocolException> {
            WebUtils.resolveHttpRedirect(
                URI("http://example.com/start.png"),
                "file:///tmp/image.png",
            )
        }
    }

    @Test
    fun `HTTP error display strips query credentials and fragments`() {
        assertEquals(
            "https://example.com:8443/assets/image.png",
            WebUtils.displayUri(
                URI("https://user:password@example.com:8443/assets/image.png?token=secret#fragment")
            ),
        )
    }

    @Test
    fun `encoded input accepts the exact limit and always closes its stream`() {
        val input = CloseTrackingInputStream(byteArrayOf(1, 2, 3, 4))

        assertContentEquals(
            byteArrayOf(1, 2, 3, 4),
            Image.readEncodedBytes(input, maxBytes = 4),
        )
        assertTrue(input.wasClosed)
    }

    @Test
    fun `encoded input rejects empty and oversized sources`() {
        val empty = CloseTrackingInputStream(byteArrayOf())
        assertFailsWith<IOException> { Image.readEncodedBytes(empty, maxBytes = 4) }
        assertTrue(empty.wasClosed)

        val oversized = CloseTrackingInputStream(byteArrayOf(1, 2, 3, 4, 5))
        assertFailsWith<IOException> { Image.readEncodedBytes(oversized, maxBytes = 4) }
        assertTrue(oversized.wasClosed)
    }

    @Test
    fun `bounded stream enforces bytes actually read and closes its owner`() {
        var ownerClosed = false
        val oversized = BoundedInputStream(
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)),
            maxBytes = 4,
            onClose = { ownerClosed = true },
        )

        assertFailsWith<IOException> { oversized.readBytes() }
        assertTrue(ownerClosed)

        var exactOwnerClosed = false
        val exact = BoundedInputStream(
            ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
            maxBytes = 4,
            onClose = { exactOwnerClosed = true },
        )
        assertContentEquals(byteArrayOf(1, 2, 3, 4), exact.use { it.readBytes() })
        assertTrue(exactOwnerClosed)
    }

    @Test
    fun `SVG extension detection ignores URL query and preserves explicit format`() {
        assertTrue(Image.hasSvgExtension("https://example.com/icons/gear.SVG?token=secret#preview"))
        assertTrue(Image.hasSvgExtension("/assets/cop/ui/images/gear.svg"))
        assertFalse(Image.hasSvgExtension("/assets/cop/ui/images/gear.svg.png"))

        val explicitSvg = Image(
            identifier = "extensionless-image",
            isSVG = true,
            stream = ByteArrayInputStream("<svg/>".toByteArray()),
        )
        try {
            assertTrue(explicitSvg.isSVG)
        } finally {
            explicitSvg.stream.close()
        }
    }

    @Test
    fun `image error labels redact signed URL credentials`() {
        assertEquals(
            "https://example.com:8443/assets/image.png",
            Image.sanitizedIdentifier(
                "https://user:password@example.com:8443/assets/image.png?token=secret#preview",
            ),
        )
        assertEquals(
            "/assets/cop/ui/images/gear.svg",
            Image.sanitizedIdentifier("/assets/cop/ui/images/gear.svg"),
        )
    }

    @Test
    fun `RGBA dimensions are positive and bounded`() {
        assertEquals(4, Image.checkedRgbaByteCount(1, 1))
        assertEquals(67_108_864, Image.checkedRgbaByteCount(4096, 4096))

        assertFailsWith<IllegalArgumentException> { Image.checkedRgbaByteCount(0, 1) }
        assertFailsWith<IllegalArgumentException> { Image.checkedRgbaByteCount(1, 0) }
        assertFailsWith<IllegalArgumentException> { Image.checkedRgbaByteCount(4097, 1) }
        assertFailsWith<IllegalArgumentException> { Image.checkedRgbaByteCount(1, 4097) }
    }

    private class CloseTrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var wasClosed = false
            private set

        override fun close() {
            wasClosed = true
            super.close()
        }
    }
}
