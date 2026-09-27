package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotCodec
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.persistence.WorldSnapshotCodec
import work.art1st.javarailways.simpleato.persistence.SimpleATOInfrastructureSnapshotCodec

class RailInfrastructureMetadataMigrationTest {
    @Test
    fun `migrates the real outer V1 and Basic V1 ownership boundary directly`() {
        val migrated = RailInfrastructureMetadataMigration.migrate(
            outerV1(currentCore(), legacyBasicWithMetadata())
        )
        val core = WorldSnapshotCodec.decodeInfrastructure(migrated.file.coreSnapshotJson)
        val basic = BasicInfrastructureSnapshotCodec.decode(migrated.file.basicSnapshotJson)
        val simpleAto = SimpleATOInfrastructureSnapshotCodec.decode(migrated.file.simpleAtoSnapshotJson)

        assertEquals(2, migrated.file.formatVersion)
        assertEquals(2, basic.formatVersion)
        assertEquals(1, simpleAto.formatVersion)
        assertEquals(2, core.formatVersion)
        assertEquals(1, migrated.migratedRailCount)
        assertEquals(listOf("standalone"), basic.wayPoints.map { it.id })
        assertEquals(30.0, basic.railMetadata.single().metadata.designSpeedMetersPerSecond)
        assertEquals(null, basic.railMetadata.single().metadata.operating.turnoutAtA)
        assertEquals(ViewDirection.AB, simpleAto.operatingDirections.single().permittedDirection)
        assertEquals(listOf("central"), simpleAto.destinations.map { it.id })
        assertEquals(listOf("main"), simpleAto.lines.map { it.id })
        assertEquals(4L, simpleAto.lineRevision)
        assertFalse(migrated.file.basicSnapshotJson.contains("allowedDirection"))
    }

    @Test
    fun `also folds legacy Core V1 metadata into the same real source transition`() {
        val migrated = RailInfrastructureMetadataMigration.migrate(
            outerV1(legacyCoreWithMetadata(), legacyBasicWithoutMetadata())
        )
        val basic = BasicInfrastructureSnapshotCodec.decode(migrated.file.basicSnapshotJson)
        val simpleAto = SimpleATOInfrastructureSnapshotCodec.decode(migrated.file.simpleAtoSnapshotJson)

        assertFalse(migrated.file.coreSnapshotJson.contains("\"metadata\""))
        assertEquals(20.0, basic.railMetadata.single().metadata.speedLimitMetersPerSecond)
        assertEquals(ViewDirection.BA, simpleAto.operatingDirections.single().permittedDirection)
    }

    @Test
    fun `rejects ambiguous metadata ownership instead of merging it`() {
        assertThrows(IllegalArgumentException::class.java) {
            RailInfrastructureMetadataMigration.migrate(
                outerV1(legacyCoreWithMetadata(), legacyBasicWithMetadata())
            )
        }
    }

    @Test
    fun `rejects revision mismatches at both legacy owner boundaries`() {
        assertThrows(IllegalArgumentException::class.java) {
            RailInfrastructureMetadataMigration.migrate(
                outerV1(
                    currentCore(),
                    legacyBasicWithMetadata().replaceFirst(
                        "\"infrastructureRevision\":7",
                        "\"infrastructureRevision\":8"
                    )
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RailInfrastructureMetadataMigration.migrate(
                outerV1(
                    currentCore().replaceFirst(
                        "\"infrastructureRevision\":7",
                        "\"infrastructureRevision\":8"
                    ),
                    legacyBasicWithMetadata()
                )
            )
        }
    }

    private fun outerV1(core: String, basic: String): String = JsonObject(
        mapOf(
            "formatVersion" to JsonPrimitive(1),
            "infrastructureRevision" to JsonPrimitive(7L),
            "coreSnapshotJson" to JsonPrimitive(core),
            "basicSnapshotJson" to JsonPrimitive(basic)
        )
    ).toString()

    private fun currentCore(): String = """
        {"formatVersion":2,"infrastructureRevision":7,"rails":[{
          "id":"00000000-0000-0000-0000-000000000001",
          "curveAB":{"pointList":[]},
          "nextRailAtA":[],"nextRailAtB":[],"activeTrackAtA":null,"activeTrackAtB":null
        }]}
    """.trimIndent()

    private fun legacyCoreWithMetadata(): String = """
        {"formatVersion":1,"infrastructureRevision":7,"rails":[{
          "id":"00000000-0000-0000-0000-000000000001",
          "curveAB":{"pointList":[]},
          "metadata":{"designSpeedMetersPerSecond":30.0,"speedLimitMetersPerSecond":20.0,
            "derailSpeedMetersPerSecond":25.0,"allowedDirection":"BA",
            "geometry":{"curveKind":"FIVE_SEGMENT","curveRadiusMeters":300.0,
              "automaticSuperelevation":true,"maxSuperelevationMeters":0.15,
              "allowableCantDeficiencyMeters":0.075,"parallel":null},
            "assembly":null,"createdBySplit":null,"turnoutAtA":null,"turnoutAtB":null,
            "gradeLockedAtA":true,"gradeLockedAtB":false},
          "nextRailAtA":[],"nextRailAtB":[],"activeTrackAtA":null,"activeTrackAtB":null
        }]}
    """.trimIndent()

    private fun legacyBasicWithoutMetadata(): String = """
        {"formatVersion":1,"infrastructureRevision":7,"destinations":[],"wayPoints":[],
         "lines":[],"lineRevision":0,"railMetadata":[]}
    """.trimIndent()

    private fun legacyBasicWithMetadata(): String = """
        {"formatVersion":1,"infrastructureRevision":7,
         "destinations":[{"id":"central","displayName":"Central",
           "railId":"00000000-0000-0000-0000-000000000001","arcLengthInAbMeters":0.0}],
         "wayPoints":[{"id":"standalone","displayName":"Standalone",
           "railId":"00000000-0000-0000-0000-000000000001","arcLengthInAbMeters":0.0}],
         "lines":[{"id":"main","displayName":"Main","upStationIds":[],"downStationIds":[]}],
         "lineRevision":4,
         "railMetadata":[{
           "railId":"00000000-0000-0000-0000-000000000001",
           "metadata":{
             "authoring":{"designSpeedMetersPerSecond":30.0,
               "geometry":{"curveKind":"STRAIGHT","curveRadiusMeters":null,
                 "automaticSuperelevation":false,"maxSuperelevationMeters":0.15,
                 "allowableCantDeficiencyMeters":0.075,"parallel":null},
               "assembly":null,"createdBySplit":null,"gradeLockedAtA":false,
               "gradeLockedAtB":false,"curveSpeedLimitAutomatic":true},
             "operating":{"allowedDirection":"AB","turnoutAtA":null,"turnoutAtB":null},
             "speedLimits":{"entries":[{"valueMetersPerSecond":30.0,"type":"SUBGRADE"}]},
             "curveSafety":{"baseCurveDerailSpeedMetersPerSecond":null}
           }
         }]}
    """.trimIndent()
}
