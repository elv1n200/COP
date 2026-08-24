package cop.utils.ui.rendering

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class NVGFrameTargetPoolTest {
    private class FakeTarget(
        val name: String,
        val size: NVGSpecialRenderer.TargetSize,
    ) : AutoCloseable {
        var closeCount = 0

        override fun close() {
            closeCount++
        }
    }

    private data class Fixture(
        val pool: NVGSpecialRenderer.FrameTargetPool<FakeTarget, FakeTarget>,
        val colors: MutableList<FakeTarget>,
        val depths: MutableList<FakeTarget>,
    )

    private fun fixture(): Fixture {
        val colors = mutableListOf<FakeTarget>()
        val depths = mutableListOf<FakeTarget>()
        val pool = NVGSpecialRenderer.FrameTargetPool<FakeTarget, FakeTarget>(
            createColor = { slot, size ->
                FakeTarget("color-$slot-${size.width}x${size.height}", size).also(colors::add)
            },
            colorSize = FakeTarget::size,
            createDepth = { size ->
                FakeTarget("depth-${size.width}x${size.height}", size).also(depths::add)
            },
        )
        return Fixture(pool, colors, depths)
    }

    @Test
    fun `same-frame states own colors but share matching depth`() {
        val fixture = fixture()
        val size = NVGSpecialRenderer.TargetSize(1920, 1080)
        val frameOne = Any()

        val first = fixture.pool.acquire(frameOne, size)
        val second = fixture.pool.acquire(frameOne, size)

        assertNotSame(first.color, second.color)
        assertSame(first.depth, second.depth)

        val nextFrame = fixture.pool.acquire(Any(), size)
        assertSame(first.color, nextFrame.color)
        assertSame(first.depth, nextFrame.depth)
        assertEquals(2, fixture.colors.size)
        assertEquals(1, fixture.depths.size)

        fixture.pool.close()
        fixture.colors.forEach { assertEquals(1, it.closeCount) }
        fixture.depths.forEach { assertEquals(1, it.closeCount) }
    }

    @Test
    fun `equal but distinct tokens start distinct frames`() {
        data class EqualToken(val value: Int)

        val fixture = fixture()
        val size = NVGSpecialRenderer.TargetSize(800, 600)
        val first = fixture.pool.acquire(EqualToken(1), size)
        val second = fixture.pool.acquire(EqualToken(1), size)

        assertSame(first.color, second.color)
        assertSame(first.depth, second.depth)
        assertEquals(1, fixture.colors.size)
        fixture.pool.close()
    }

    @Test
    fun `same frame uses separate depth targets only for separate sizes`() {
        val fixture = fixture()
        val frame = Any()
        val small = NVGSpecialRenderer.TargetSize(800, 600)
        val large = NVGSpecialRenderer.TargetSize(1600, 1200)

        val firstSmall = fixture.pool.acquire(frame, small)
        val firstLarge = fixture.pool.acquire(frame, large)
        val secondSmall = fixture.pool.acquire(frame, small)

        assertNotSame(firstSmall.color, firstLarge.color)
        assertNotSame(firstSmall.color, secondSmall.color)
        assertNotSame(firstSmall.depth, firstLarge.depth)
        assertSame(firstSmall.depth, secondSmall.depth)
        assertEquals(3, fixture.colors.size)
        assertEquals(2, fixture.depths.size)
        fixture.pool.close()
    }

    @Test
    fun `resize replaces its slot and stale peak resources are pruned`() {
        val fixture = fixture()
        val oldSize = NVGSpecialRenderer.TargetSize(1280, 720)
        val newSize = NVGSpecialRenderer.TargetSize(2560, 1440)
        val oldFrame = Any()

        val oldFirst = fixture.pool.acquire(oldFrame, oldSize)
        val oldSecond = fixture.pool.acquire(oldFrame, oldSize)

        val resized = fixture.pool.acquire(Any(), newSize)
        assertEquals(1, oldFirst.color.closeCount)
        assertEquals(0, oldSecond.color.closeCount)
        assertEquals(0, oldFirst.depth.closeCount)

        val stable = fixture.pool.acquire(Any(), newSize)
        assertSame(resized.color, stable.color)
        assertSame(resized.depth, stable.depth)
        assertEquals(1, oldSecond.color.closeCount)
        assertEquals(1, oldFirst.depth.closeCount)

        fixture.pool.close()
    }

    @Test
    fun `close is idempotent and closed pools reject acquisition`() {
        val fixture = fixture()
        fixture.pool.acquire(Any(), NVGSpecialRenderer.TargetSize(640, 480))

        fixture.pool.close()
        fixture.pool.close()

        fixture.colors.forEach { assertEquals(1, it.closeCount) }
        fixture.depths.forEach { assertEquals(1, it.closeCount) }
        assertFailsWith<IllegalStateException> {
            fixture.pool.acquire(Any(), NVGSpecialRenderer.TargetSize(640, 480))
        }
    }

    @Test
    fun `failed depth creation keeps the color slot reusable`() {
        val size = NVGSpecialRenderer.TargetSize(1024, 768)
        val colors = mutableListOf<FakeTarget>()
        var depthAttempts = 0
        val pool = NVGSpecialRenderer.FrameTargetPool<FakeTarget, FakeTarget>(
            createColor = { slot, targetSize ->
                FakeTarget("color-$slot", targetSize).also(colors::add)
            },
            colorSize = FakeTarget::size,
            createDepth = { targetSize ->
                depthAttempts++
                if (depthAttempts == 1) error("simulated depth allocation failure")
                FakeTarget("depth", targetSize)
            },
        )
        val frame = Any()

        assertFailsWith<IllegalStateException> { pool.acquire(frame, size) }
        val recovered = pool.acquire(frame, size)

        assertEquals(1, colors.size)
        assertSame(colors.single(), recovered.color)
        assertEquals(2, depthAttempts)
        pool.close()
    }

    @Test
    fun `target dimensions must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            NVGSpecialRenderer.TargetSize(0, 480)
        }
        assertFailsWith<IllegalArgumentException> {
            NVGSpecialRenderer.TargetSize(640, -1)
        }
    }
}
