package work.art1st.javarailways.simplesignals.persistence

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal enum class SimpleSignalsWorldDefinitionSource {
    PRIMARY,
    BACKUP
}

internal data class SimpleSignalsWorldDefinitionReadFailure(
    val file: Path,
    val error: Exception
)

internal sealed interface SimpleSignalsWorldDefinitionLoadResult {
    data object Missing : SimpleSignalsWorldDefinitionLoadResult

    data class Loaded(
        val definitions: SimpleSignalsWorldDefinitions,
        val source: SimpleSignalsWorldDefinitionSource,
        val ignoredFailures: List<SimpleSignalsWorldDefinitionReadFailure> = emptyList()
    ) : SimpleSignalsWorldDefinitionLoadResult

    data class Corrupted(
        val failures: List<SimpleSignalsWorldDefinitionReadFailure>
    ) : SimpleSignalsWorldDefinitionLoadResult
}

/** Owns primary/backup transport for one independent SimpleSignals persistence boundary. */
internal class SimpleSignalsWorldDefinitionStore(
    dataFolder: File,
    private val beforePrimaryReplace: () -> Unit = {}
) {
    private val lock = Any()
    private val worldsDirectory = dataFolder.toPath().resolve("worlds")
    private val preparedWorlds = hashSetOf<UUID>()

    fun load(worldId: UUID): SimpleSignalsWorldDefinitionLoadResult = synchronized(lock) {
        val primary = primaryFile(worldId)
        val backup = backupFile(worldId)
        if (Files.notExists(primary) && Files.notExists(backup)) {
            return@synchronized SimpleSignalsWorldDefinitionLoadResult.Missing
        }

        val failures = mutableListOf<SimpleSignalsWorldDefinitionReadFailure>()
        for ((file, source) in listOf(
            primary to SimpleSignalsWorldDefinitionSource.PRIMARY,
            backup to SimpleSignalsWorldDefinitionSource.BACKUP
        )) {
            if (Files.notExists(file)) continue
            try {
                val definitions = SimpleSignalsWorldDefinitionsCodec.decode(
                    Files.readString(file),
                    worldId
                )
                return@synchronized SimpleSignalsWorldDefinitionLoadResult.Loaded(
                    definitions,
                    source,
                    java.util.List.copyOf(failures)
                )
            } catch (failure: Exception) {
                failures += SimpleSignalsWorldDefinitionReadFailure(file, failure)
            }
        }
        SimpleSignalsWorldDefinitionLoadResult.Corrupted(java.util.List.copyOf(failures))
    }

    /**
     * Performs the complete document encoding, validation and staging write before publication.
     * [PreparedSave.commitPrimary] is deliberately limited to the final same-directory rename;
     * backup rotation and staging cleanup happen when the prepared value is closed.
     */
    fun prepareSave(
        worldId: UUID,
        definitions: SimpleSignalsWorldDefinitions
    ): PreparedSave = synchronized(lock) {
        require(definitions.worldId == worldId) {
            "Cannot save SimpleSignals definitions for ${definitions.worldId} as world $worldId"
        }
        check(preparedWorlds.add(worldId)) {
            "A SimpleSignals definition save is already prepared for world $worldId"
        }
        val primary = primaryFile(worldId)
        val backup = backupFile(worldId)
        val temporary = temporaryFile(worldId)
        val backupTemporary = backupTemporaryFile(worldId)
        Files.createDirectories(worldsDirectory)
        try {
            Files.deleteIfExists(temporary)
            Files.deleteIfExists(backupTemporary)
            Files.writeString(
                temporary,
                SimpleSignalsWorldDefinitionsCodec.encode(definitions),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            if (Files.isRegularFile(primary) && isValid(primary, worldId)) {
                Files.copy(primary, backupTemporary, StandardCopyOption.REPLACE_EXISTING)
            }
            PreparedSave(worldId, primary, backup, temporary, backupTemporary)
        } catch (failure: Exception) {
            preparedWorlds.remove(worldId)
            cleanup(temporary)
            cleanup(backupTemporary)
            throw failure
        }
    }

    fun save(worldId: UUID, definitions: SimpleSignalsWorldDefinitions) {
        prepareSave(worldId, definitions).use { prepared ->
            prepared.commitPrimary()
        }
    }

    internal inner class PreparedSave internal constructor(
        private val worldId: UUID,
        private val primary: Path,
        private val backup: Path,
        private val temporary: Path,
        private val backupTemporary: Path
    ) : AutoCloseable {
        private var committed = false
        private var closed = false

        /** The only persistence operation allowed inside the live publication commit. */
        fun commitPrimary() = synchronized(lock) {
            check(!closed) { "Prepared SimpleSignals save is already closed" }
            check(!committed) { "Prepared SimpleSignals save is already committed" }
            beforePrimaryReplace()
            replace(temporary, primary)
            committed = true
        }

        override fun close() = synchronized(lock) {
            if (closed) return@synchronized
            closed = true
            // Primary replacement is the commit point. Backup rotation is recovery-only and is
            // deliberately outside the live publication callback.
            if (committed && Files.exists(backupTemporary)) {
                try {
                    replace(backupTemporary, backup)
                } catch (_: Exception) {
                    // Keep the previous valid backup. The next successful save can rotate it.
                }
            }
            cleanup(temporary)
            cleanup(backupTemporary)
            preparedWorlds.remove(worldId)
        }
    }

    internal fun primaryFile(worldId: UUID): Path =
        worldsDirectory.resolve("$worldId-definitions.json")

    internal fun backupFile(worldId: UUID): Path =
        worldsDirectory.resolve("$worldId-definitions.json.bak")

    internal fun temporaryFile(worldId: UUID): Path =
        worldsDirectory.resolve("$worldId-definitions.json.tmp")

    internal fun backupTemporaryFile(worldId: UUID): Path =
        worldsDirectory.resolve("$worldId-definitions.json.bak.tmp")

    private fun replace(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun cleanup(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: Exception) {
            // Cleanup cannot change whether the primary commit succeeded. A later preparation
            // overwrites the stable staging name before writing a new document.
        }
    }

    private fun isValid(file: Path, worldId: UUID): Boolean = try {
        SimpleSignalsWorldDefinitionsCodec.decode(Files.readString(file), worldId)
        true
    } catch (_: Exception) {
        false
    }
}
