package work.art1st.javarailways.bukkit.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotCodec
import work.art1st.javarailways.core.persistence.WorldSnapshotCodec
import java.nio.file.Files
import java.nio.file.Path

class RailInfrastructureMetadataMigrationTest {
    @Test
    fun `moves legacy Core rail metadata into Basic without retaining a runtime compatibility path`() {
        val outer = legacyFile()

        val migrated = RailInfrastructureMetadataMigration.migrate(outer)
        val core = WorldSnapshotCodec.decodeInfrastructure(migrated.file.coreSnapshotJson)
        val basic = BasicInfrastructureSnapshotCodec.decode(migrated.file.basicSnapshotJson)
        val metadata = basic.railMetadata.single().metadata

        assertEquals(1, migrated.migratedRailCount)
        assertEquals(2, core.formatVersion)
        assertFalse(migrated.file.coreSnapshotJson.contains("\"metadata\""))
        assertEquals(30.0, metadata.designSpeedMetersPerSecond)
        assertEquals(20.0, metadata.speedLimitMetersPerSecond)
        assertEquals(25.0, metadata.derailSpeedMetersPerSecond)
        assertTrue(metadata.gradeLockedAtA)
    }

    @Test
    fun `dry run does not write and apply preserves a recovery point`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("world-infrastructure.json")
        val original = SplitWorldSnapshotCodec.encode(legacyFile())
        Files.writeString(file, original)

        val dryRun = RailInfrastructureMetadataMigrationFiles.migrate(file, apply = false)
        assertEquals(1, dryRun.migratedRailCount)
        assertEquals(original, Files.readString(file))
        assertFalse(Files.exists(dryRun.recoveryPoint))

        val applied = RailInfrastructureMetadataMigrationFiles.migrate(file, apply = true)
        assertTrue(Files.exists(applied.recoveryPoint))
        assertEquals(original, Files.readString(applied.recoveryPoint))
        SplitWorldSnapshotCodec.decodeInfrastructure(Files.readString(file)).core()
    }

    private fun legacyFile(): InfrastructureFileV2 {
        val legacyCore = """
            {"formatVersion":1,"infrastructureRevision":7,"rails":[{
              "id":"00000000-0000-0000-0000-000000000001",
              "curveAB":{"pointList":[]},
              "metadata":{"designSpeedMetersPerSecond":30.0,"speedLimitMetersPerSecond":20.0,
                "derailSpeedMetersPerSecond":25.0,"allowedDirection":"AB",
                "geometry":{"curveKind":"FIVE_SEGMENT","curveRadiusMeters":300.0,
                  "automaticSuperelevation":true,"maxSuperelevationMeters":0.15,
                  "allowableCantDeficiencyMeters":0.075,"parallel":null},
                "assembly":null,"createdBySplit":null,"turnoutAtA":null,"turnoutAtB":null,
                "gradeLockedAtA":true,"gradeLockedAtB":false},
              "nextRailAtA":[],"nextRailAtB":[],"activeTrackAtA":null,"activeTrackAtB":null
            }]}
        """.trimIndent()
        val legacyBasic = """
            {"formatVersion":2,"infrastructureRevision":7,"destinations":[],"wayPoints":[],
             "railMetadata":[]}
        """.trimIndent()
        val simpleAto = """
            {"formatVersion":1,"infrastructureRevision":7,"lines":[],"lineRevision":0}
        """.trimIndent()
        return InfrastructureFileV2(2, 7L, legacyCore, legacyBasic, simpleAto)
    }
}
