package cop.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ChatSearchBufferTest {
    @Test
    fun `replay preserves add delete order and cop ids`() {
        val buffer = ChatSearchBuffer<String, String>()
        val replayed = mutableListOf<String>()

        buffer.queueMessage("first", 41)
        buffer.queueDeletion("signature-first")
        buffer.queueMessage("second", 73)

        buffer.drain(
            { message, id -> replayed += "add:$message:$id" },
            { signature -> replayed += "delete:$signature" },
        )

        assertEquals(
            listOf("add:first:41", "delete:signature-first", "add:second:73"),
            replayed,
        )
        assertEquals(0, buffer.size())
    }

    @Test
    fun `mutations queued during drain remain pending`() {
        val buffer = ChatSearchBuffer<String, String>()
        val replayed = mutableListOf<String>()
        buffer.queueMessage("current", 1)

        buffer.drain(
            { message, _ ->
                replayed += message
                buffer.queueMessage("next", 2)
            },
            { error("unexpected deletion") },
        )

        assertEquals(listOf("current"), replayed)
        assertEquals(1, buffer.size())
    }

    @Test
    fun `callback failure retains failed suffix before reentrant mutations for retry`() {
        val buffer = ChatSearchBuffer<String, String>()
        val replayed = mutableListOf<String>()
        val failure = IllegalStateException("replay failed")

        buffer.queueMessage("first", 1)
        buffer.queueDeletion("signature")
        buffer.queueMessage("last", 2)

        val thrown = assertFailsWith<IllegalStateException> {
            buffer.drain(
                { message, id ->
                    replayed += "add:$message:$id"
                    if (message == "first") buffer.queueMessage("queued-by-success", 3)
                },
                {
                    buffer.queueMessage("queued-by-failure", 4)
                    throw failure
                },
            )
        }

        assertSame(failure, thrown)
        assertEquals(listOf("add:first:1"), replayed)
        assertEquals(4, buffer.size())

        buffer.drain(
            { message, id -> replayed += "add:$message:$id" },
            { signature -> replayed += "delete:$signature" },
        )

        assertEquals(
            listOf(
                "add:first:1",
                "delete:signature",
                "add:last:2",
                "add:queued-by-success:3",
                "add:queued-by-failure:4",
            ),
            replayed,
        )
        assertEquals(0, buffer.size())
    }
}
