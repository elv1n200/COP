package cop.config

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigRecoveryTest {
    @Test
    fun `same corrupt contents reuse one backup`() {
        val directory = Files.createTempDirectory("cop-config-recovery-").toFile()
        try {
            val config = directory.resolve("settings.json")
            val bytes = "{broken-json".toByteArray()
            config.writeBytes(bytes)

            val first = assertNotNull(ConfigRecovery.backup(config))
            val second = assertNotNull(ConfigRecovery.backup(config))

            assertEquals(first.canonicalFile, second.canonicalFile)
            assertEquals(1, directory.listFiles { file -> file.extension == "bak" }?.size)
            assertContentEquals(bytes, first.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `keeps only three distinct recoveries`() {
        val directory = Files.createTempDirectory("cop-config-recovery-").toFile()
        try {
            val config = directory.resolve("settings.json")
            repeat(5) { index ->
                config.toPath().writeText("broken-$index")
                assertNotNull(ConfigRecovery.backup(config))
            }

            assertEquals(3, directory.listFiles { file -> file.extension == "bak" }?.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `blank files do not create useless backups`() {
        val directory = Files.createTempDirectory("cop-config-recovery-").toFile()
        try {
            val config = directory.resolve("settings.json").apply { createNewFile() }
            assertNull(ConfigRecovery.backup(config))
            assertEquals(0, directory.listFiles { file -> file.extension == "bak" }?.size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
