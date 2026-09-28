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

    fun save(worldId: UUID, definitions: SimpleSignalsWorldDefinitions) = synchronized(lock) {
        require(definitions.worldId == worldId) {
            "Cannot save SimpleSignals definitions for ${definitions.worldId} as world $worldId"
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
            beforePrimaryReplace()
            replace(temporary, primary)

            // The primary replacement above is the commit point. Backup rotation is recovery-only:
            // once the new primary is durable, failure to refresh the old-primary backup must not
            // turn a successful commit into a reported failure.
            if (Files.exists(backupTemporary)) {
                try {
                    replace(backupTemporary, backup)
                } catch (_: Exception) {
                    // Keep the previous valid backup. The next successful save can rotate it.
                }
            }
        } finally {
            try {
                Files.deleteIfExists(temporary)
            } catch (_: Exception) {
                // Cleanup failure after the commit point does not invalidate the saved primary.
            }
            try {
                Files.deleteIfExists(backupTemporary)
            } catch (_: Exception) {
                // A stale staging file is ignored and overwritten by the next save.
            }
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

    private fun isValid(file: Path, worldId: UUID): Boolean = try {
        SimpleSignalsWorldDefinitionsCodec.decode(Files.readString(file), worldId)
        true
    } catch (_: Exception) {
        false
    }
}
