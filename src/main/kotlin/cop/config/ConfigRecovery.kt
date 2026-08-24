package cop.config

import cop.CopMod.logger
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Preserves a bounded set of distinct broken config files for recovery. */
@PublishedApi
internal object ConfigRecovery {
    private const val MAX_BACKUP_BYTES = 16L * 1024 * 1024
    private const val MAX_BACKUPS_PER_FILE = 3

    @PublishedApi
    internal fun backup(file: File): File? {
        if (!file.isFile) return null
        val size = runCatching { file.length() }.getOrDefault(0L)
        if (size <= 0L || size > MAX_BACKUP_BYTES) {
            if (size > MAX_BACKUP_BYTES) {
                logger.warn("Skipping corrupt config backup for '${file.name}': file is $size bytes")
            }
            return null
        }

        return runCatching {
            val digest = sha256(file).take(16)
            val parent = file.absoluteFile.parentFile ?: return@runCatching null
            Files.createDirectories(parent.toPath())
            val backup = File(parent, "${file.name}.corrupt-$digest.bak")
            if (!backup.exists()) {
                Files.copy(
                    file.toPath(),
                    backup.toPath(),
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
                logger.warn("Preserved unreadable config '${file.name}' as '${backup.name}'")
            }
            pruneOldBackups(file, keep = backup)
            backup
        }.onFailure {
            logger.warn("Failed to preserve unreadable config '${file.name}'", it)
        }.getOrNull()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun pruneOldBackups(file: File, keep: File) {
        val prefix = "${file.name}.corrupt-"
        val backups = file.absoluteFile.parentFile
            ?.listFiles { candidate ->
                candidate.isFile && candidate.name.startsWith(prefix) && candidate.name.endsWith(".bak")
            }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
            .orEmpty()

        val retained = LinkedHashSet<File>()
        retained += keep
        backups.forEach { if (retained.size < MAX_BACKUPS_PER_FILE) retained += it }
        backups.filterNot(retained::contains).forEach { stale ->
            runCatching { Files.deleteIfExists(stale.toPath()) }
                .onFailure { logger.warn("Failed to prune old config backup '${stale.name}'", it) }
        }
    }
}
