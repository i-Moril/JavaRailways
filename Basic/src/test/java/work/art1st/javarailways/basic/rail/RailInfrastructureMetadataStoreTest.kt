package work.art1st.javarailways.basic.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

class RailInfrastructureMetadataStoreTest {
    @Test
    fun `stores authoring operating speed and safety metadata by rail id`() {
        val railId = UUID.randomUUID()
        val metadata = RailInfrastructureMetadata(
            authoring = RailAuthoringMetadata(
                curveKind = RailCurveKind.FIVE_SEGMENT,
                designSpeedMetersPerSecond = 30.0,
                gradeLockedAtA = true
            ),
            operating = RailOperatingMetadata(allowedDirection = ViewDirection.AB),
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

    @Test
    fun `snapshot revision advances only after an effective metadata change`() {
        val firstRail = UUID.randomUUID()
        val secondRail = UUID.randomUUID()
        val first = RailInfrastructureMetadata(
            operating = RailOperatingMetadata(allowedDirection = ViewDirection.AB)
        )
        val replacement = first.copy(
            operating = RailOperatingMetadata(allowedDirection = ViewDirection.BA)
        )
        val store = RailInfrastructureMetadataStore()

        assertEquals(0L, store.snapshotWithRevision().revision)
        store.put(firstRail, first)
        assertEquals(1L, store.snapshotWithRevision().revision)
        store.put(firstRail, first)
        store.remove(secondRail)
        store.replace(setOf(secondRail), emptyMap())
        assertEquals(1L, store.snapshotWithRevision().revision)

        store.replace(setOf(firstRail), mapOf(firstRail to replacement))
        val changed = store.snapshotWithRevision()
        assertEquals(2L, changed.revision)
        assertEquals(replacement, changed.values[firstRail])

        store.remove(firstRail)
        assertEquals(3L, store.snapshotWithRevision().revision)
    }

    @Test
    fun `restore establishes revision zero baseline and snapshots are detached`() {
        val railId = UUID.randomUUID()
        val restored = RailInfrastructureMetadata(
            operating = RailOperatingMetadata(allowedDirection = ViewDirection.AB)
        )
        val store = RailInfrastructureMetadataStore()

        store.restore(mapOf(railId to restored))
        val snapshot = store.snapshotWithRevision()
        assertEquals(0L, snapshot.revision)

        store.put(railId, restored.copy(operating = RailOperatingMetadata(ViewDirection.BA)))
        assertEquals(restored, snapshot.values[railId])
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.values as MutableMap<UUID, RailInfrastructureMetadata>).clear()
        }
    }
}
