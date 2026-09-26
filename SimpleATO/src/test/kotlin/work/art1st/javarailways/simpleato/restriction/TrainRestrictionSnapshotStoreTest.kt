package work.art1st.javarailways.simpleato.restriction

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.simpleato.api.restriction.CurrentSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionRouteBinding
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot

class TrainRestrictionSnapshotStoreTest {
    @Test
    fun `publication replaces one source and leaves previously read snapshot immutable`() {
        val store = TrainRestrictionSnapshotStore("world")
        assertEquals(TrainRestrictionPublicationStatus.ACCEPTED, store.publish(snapshot("test:b", 0L)).status)
        assertEquals(TrainRestrictionPublicationStatus.ACCEPTED, store.publish(snapshot("test:a", 0L)).status)
        val before = store.snapshotsFor("train")

        assertEquals(TrainRestrictionPublicationStatus.ACCEPTED, store.publish(snapshot("test:a", 1L)).status)

        assertEquals(listOf("test:a", "test:b"), before.map { it.sourceId })
        assertEquals(listOf(0L, 0L), before.map { it.revision })
        assertEquals(listOf(1L, 0L), store.snapshotsFor("train").map { it.revision })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (before as MutableList<TrainRestrictionSnapshot>).clear()
        }
    }

    @Test
    fun `stale publication keeps the latest immutable value`() {
        val store = TrainRestrictionSnapshotStore("world")
        store.publish(snapshot("test:signal", 2L))

        val result = store.publish(snapshot("test:signal", 1L))

        assertEquals(TrainRestrictionPublicationStatus.STALE_REVISION, result.status)
        assertEquals(2L, result.acceptedRevision)
        assertEquals(2L, store.snapshotsFor("train").single().revision)
    }

    @Test
    fun `publication from another world is rejected without mutation`() {
        val store = TrainRestrictionSnapshotStore("world")

        assertThrows(IllegalArgumentException::class.java) {
            store.publish(snapshot("test:signal", 0L, worldId = "other"))
        }
        assertEquals(emptyList<TrainRestrictionSnapshot>(), store.snapshotsFor("train"))
    }

    private fun snapshot(
        sourceId: String,
        revision: Long,
        worldId: String = "world"
    ): TrainRestrictionSnapshot = TrainRestrictionSnapshot(
        sourceId = sourceId,
        revision = revision,
        trainId = "train",
        observationStamp = RailwayStateStamp(worldId, 1L, 2L),
        route = TrainRestrictionRouteBinding("route", 3L, 2L, 100.0),
        restrictions = listOf(CurrentSpeedRestriction("test:speed", 5.0))
    )
}
