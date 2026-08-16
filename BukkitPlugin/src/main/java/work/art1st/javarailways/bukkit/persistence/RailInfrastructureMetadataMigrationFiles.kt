package work.art1st.javarailways.bukkit.persistence

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Auditable file boundary for the one-time Rail metadata ownership migration. */
object RailInfrastructureMetadataMigrationFiles {
    data class Report(
        val source: Path,
        val recoveryPoint: Path,
        val migratedRailCount: Int,
        val applied: Boolean
    )

    fun migrate(source: Path, apply: Boolean): Report {
        require(Files.isRegularFile(source)) { "Infrastructure snapshot does not exist: $source" }
        val original = Files.readString(source)
        val outer = SplitWorldSnapshotCodec.decodeInfrastructure(original)
        val migrated = RailInfrastructureMetadataMigration.migrate(outer)
        val recoveryPoint = source.resolveSibling("${source.fileName}.rail-metadata-v1.recovery")
        if (migrated.migratedRailCount == 0) {
            return Report(source, recoveryPoint, 0, applied = false)
        }
        if (!apply) {
            return Report(source, recoveryPoint, migrated.migratedRailCount, applied = false)
        }
        require(!Files.exists(recoveryPoint)) {
            "Recovery point already exists; refusing to overwrite: $recoveryPoint"
        }
        Files.copy(source, recoveryPoint)
        val temp = source.resolveSibling("${source.fileName}.rail-metadata-migration.tmp")
        try {
            Files.writeString(temp, SplitWorldSnapshotCodec.encode(migrated.file))
            moveAtomic(temp, source)
        } catch (failure: Throwable) {
            Files.deleteIfExists(temp)
            throw failure
        }
        return Report(source, recoveryPoint, migrated.migratedRailCount, applied = true)
    }

    private fun moveAtomic(source: Path, target: Path) {
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
}
