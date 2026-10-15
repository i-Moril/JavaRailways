package work.art1st.javarailways.basic.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString
import java.util.UUID
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology

class RailInfrastructureMetadataStoreTest {
    @Test
    fun `static speed subset captures effective curve subgrade and explicit missing speed without live reads`() {
        val rails = List(3) { UUID.randomUUID() }
        val topology = buildTestTopology(7, rails.map { TestTopologyRail(it, 100.0) }, emptyMap())
        val store = RailInfrastructureMetadataStore()
        store.put(rails[0], RailInfrastructureMetadata(speedLimits = SpeedLimitMetadata(listOf(
            SpeedLimitEntry(20.0, SpeedLimitType.CURVE), SpeedLimitEntry(30.0, SpeedLimitType.SUBGRADE)))))
        store.put(rails[1], RailInfrastructureMetadata(speedLimits = SpeedLimitMetadata(listOf(
            SpeedLimitEntry(25.0, SpeedLimitType.SUBGRADE)))))
        val captured = store.speedLimits(topology, rails)
        assertEquals(mapOf(rails[0] to 20.0, rails[1] to 25.0, rails[2] to null), captured)
        store.remove(rails[0])
        store.put(rails[1], RailInfrastructureMetadata.EMPTY)
        assertEquals(20.0, captured[rails[0]])
        assertEquals(25.0, captured[rails[1]])
        assertThrows(UnsupportedOperationException::class.java) { (captured as MutableMap).clear() }
        assertThrows(IllegalArgumentException::class.java) { store.speedLimits(topology, listOf(UUID.randomUUID())) }
    }

    @Test
    fun `authoring and turnout edits preserve all speed categories and curve safety`() {
        val original = RailInfrastructureMetadata(
            authoring = RailAuthoringMetadata(designSpeedMetersPerSecond = 30.0),
            operating = RailOperatingMetadata(turnoutAtA = TurnoutEndpointMetadata(20.0, 5.0)),
            speedLimits = SpeedLimitMetadata(listOf(
                SpeedLimitEntry(25.0, SpeedLimitType.CURVE),
                SpeedLimitEntry(20.0, SpeedLimitType.SUBGRADE)
            )),
            curveSafety = CurveSafetyMetadata(28.0)
        )

        val authoringEdit = original.copy(authoring = original.authoring?.copy(gradeLockedAtA = true))
        assertEquals(original.operating, authoringEdit.operating)
        assertEquals(original.speedLimits, authoringEdit.speedLimits)
        assertEquals(original.curveSafety, authoringEdit.curveSafety)

        val turnoutEdit = authoringEdit.copy(operating = authoringEdit.operating.copy(turnoutAtB = TurnoutEndpointMetadata(30.0, 5.0)))
        assertEquals(authoringEdit.authoring, turnoutEdit.authoring)
        assertEquals(original.speedLimits, turnoutEdit.speedLimits)
        assertEquals(original.curveSafety, turnoutEdit.curveSafety)
    }

    @Test
    fun `fixed grouped JSON preserves absent authoring and all speed categories`() {
        val json = Json { encodeDefaults = true }
        val metadata = json.decodeFromString<RailInfrastructureMetadata>(
            """{"operating":{"turnoutAtA":null,"turnoutAtB":null},
                "speedLimits":{"entries":[{"valueMetersPerSecond":25.0,"type":"CURVE"},
                    {"valueMetersPerSecond":20.0,"type":"SUBGRADE"}]},
                "curveSafety":{"baseCurveDerailSpeedMetersPerSecond":28.0}}"""
        )

        assertNull(metadata.authoring)
        assertEquals(25.0, metadata.speedLimits.value(SpeedLimitType.CURVE))
        assertEquals(20.0, metadata.speedLimits.value(SpeedLimitType.SUBGRADE))
        assertEquals(28.0, metadata.curveSafety.baseCurveDerailSpeedMetersPerSecond)
        val encoded = json.encodeToString(metadata)
        assertEquals(setOf("authoring", "operating", "speedLimits", "curveSafety"), json.parseToJsonElement(encoded).jsonObject.keys)
        assertEquals(metadata, json.decodeFromString<RailInfrastructureMetadata>(encoded))
        assertEquals(RailInfrastructureMetadata.EMPTY, json.decodeFromString<RailInfrastructureMetadata>("{}"))
    }

    @Test
    fun `stores authoring operating speed and safety metadata by rail id`() {
        val railId = UUID.randomUUID()
        val metadata = RailInfrastructureMetadata(
            authoring = RailAuthoringMetadata(
                curveKind = RailCurveKind.FIVE_SEGMENT,
                designSpeedMetersPerSecond = 30.0,
                gradeLockedAtA = true
            ),
            operating = RailOperatingMetadata(),
            speedLimits = SpeedLimitMetadata(
                listOf(
                    SpeedLimitEntry(30.0, SpeedLimitType.SUBGRADE),
                    SpeedLimitEntry(20.0, SpeedLimitType.CURVE)
                )
            ),
            curveSafety = CurveSafetyMetadata(25.0)
        )
        val store = RailInfrastructureMetadataStore()

        store.put(railId, metadata)

        assertEquals(metadata, store[railId])
        assertEquals(20.0, store.getValue(railId).speedLimits.effectiveLimitMetersPerSecond())
        assertEquals(metadata, store.snapshot()[railId])
        store.remove(railId)
        assertNull(store[railId])
    }

    @Test
    fun `rejects duplicate speed limit types`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeedLimitMetadata(
                listOf(
                    SpeedLimitEntry(30.0, SpeedLimitType.CURVE),
                    SpeedLimitEntry(20.0, SpeedLimitType.CURVE)
                )
            )
        }
    }

    @Test
    fun `requires explicit lookup when metadata is missing`() {
        val railId = UUID.randomUUID()
        val store = RailInfrastructureMetadataStore()

        assertNull(store[railId])
        val failure = assertThrows(NoSuchElementException::class.java) {
            store.getValue(railId)
        }
        assertEquals("Rail infrastructure metadata not found: $railId", failure.message)
    }

}
