package work.art1st.javarailways.simplesignals.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyCrossingKind
import work.art1st.javarailways.basic.api.route.RailOperatingDirections
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockDirectionPolicy
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.lineTopologyState
import java.util.UUID

class SectionOccupancyRuntimeTest {
    private val worldId = UUID.nameUUIDFromBytes("section-world".toByteArray())
    private val railId = UUID.nameUUIDFromBytes("section-rail".toByteArray())
    private val topology = lineTopologyState(7L, railId)
    private val block = FixedBlockDefinition(
        "test:block",
        3L,
        listOf(FixedBlockTraversal(railId, ViewDirection.AB, 2.0, 8.0))
    )

    @Test
    fun `source areas have stable identity order independent of definition input order`() {
        val first = FixedBlockDefinition("test:a-block", 1L,
            listOf(FixedBlockTraversal(railId, ViewDirection.AB, 0.0, 2.0)))
        val second = FixedBlockDefinition("test:z-block", 1L,
            listOf(FixedBlockTraversal(railId, ViewDirection.AB, 8.0, 10.0)))
        val world = SimpleSignalsWorldRuntime(worldId)
        world.initializeDefinitions(topology, SimpleSignalsWorldDefinitions(
            worldId, 1L, emptyList(), emptyList(), listOf(second, first)))

        val occupancy = world.initializeSectionOccupancy()
        assertEquals(listOf("test:a-block", "test:z-block"),
            requireNotNull(occupancy.source).areas.map { it.areaId.toString() })
        val epochs = epochRuntime(requireNotNull(occupancy.source))
        epochs.process(frame(1L, listOf(RailInterval(railId, 4.0, 6.0))))
        val baseline = requireNotNull(occupancy.snapshot())
        assertEquals(listOf("test:a-block", "test:z-block"), baseline.sections.keys.map(SectionRef::id))
        assertThrows(UnsupportedOperationException::class.java) {
            (baseline.sections as MutableMap<*, *>).clear()
        }
    }

    @Test
    fun `physical baseline enter and leave publish immutable section snapshots while idle stays idle`() {
        val world = SimpleSignalsWorldRuntime(worldId)
        world.initializeDefinitions(topology, SimpleSignalsWorldDefinitions(
            worldId, 1L, emptyList(), emptyList(), listOf(block)))
        val occupancy = world.initializeSectionOccupancy()
        val epochs = epochRuntime(requireNotNull(occupancy.source))
        val section = SectionRef(SectionKind.FIXED_BLOCK, block.blockId)

        epochs.process(frame(1L, listOf(RailInterval(railId, 0.0, 1.0))))
        val baseline = requireNotNull(occupancy.snapshot())
        assertEquals(setOf(section), baseline.changedSections)
        assertTrue(baseline.sections.getValue(section).occupants.isEmpty())
        assertEquals(6.0, baseline.sections.getValue(section).lengthMeters, 0.0)

        epochs.process(frame(2L, listOf(RailInterval(railId, 0.0, 1.0))))
        assertSame(baseline, occupancy.snapshot())

        epochs.process(frame(3L, listOf(RailInterval(railId, 3.0, 4.0)),
            headTraversal = listOf(RailTraversalSegmentSnapshot(railId, ViewDirection.AB, 0.0, 4.0))))
        val entered = requireNotNull(occupancy.snapshot())
        assertNotSame(baseline, entered)
        assertEquals(setOf("train"), entered.sections.getValue(section).occupants)
        assertEquals(listOf(RailwayOccupancyCrossingKind.ENTER),
            entered.crossingsBySection.getValue(section).map { it.kind })
        assertThrows(UnsupportedOperationException::class.java) {
            (entered.crossingsBySection.getValue(section) as MutableList<*>).clear()
        }

        epochs.process(frame(4L, listOf(RailInterval(railId, 9.0, 10.0)),
            tailTraversal = listOf(RailTraversalSegmentSnapshot(railId, ViewDirection.AB, 4.0, 10.0))))
        val left = requireNotNull(occupancy.snapshot())
        assertTrue(left.sections.getValue(section).occupants.isEmpty())
        assertEquals(listOf(RailwayOccupancyCrossingKind.LEFT),
            left.crossingsBySection.getValue(section).map { it.kind })
    }

    @Test
    fun `ordinary physical updates reuse immutable direction controls without policy rereads`() {
        var reads = 0
        val policy = FixedBlockDirectionPolicy { root, rails ->
            reads++
            RailOperatingDirections(root, rails.associateWith { null })
        }
        val world = SimpleSignalsWorldRuntime(worldId, policy)
        world.initializeDefinitions(topology, SimpleSignalsWorldDefinitions(
            worldId, 1L, emptyList(), emptyList(), listOf(block)))
        val occupancy = world.initializeSectionOccupancy()
        val epochs = epochRuntime(requireNotNull(occupancy.source))

        epochs.process(frame(1L, listOf(RailInterval(railId, 0.0, 1.0))))
        val baseline = requireNotNull(occupancy.presentationSnapshot())
        assertTrue(baseline.blockControls.getValue(block.blockId) is SectionSignalState.Unavailable)
        epochs.process(frame(2L, listOf(RailInterval(railId, 4.0, 5.0))))
        val updated = requireNotNull(occupancy.presentationSnapshot())
        assertNotSame(baseline.occupancy, updated.occupancy)
        assertSame(baseline.blockControls, updated.blockControls)
        assertEquals(1, reads)
    }

    private fun epochRuntime(source: work.art1st.javarailways.simpleato.api.signal.SignalEpochSource) =
        SignalEpochRuntime(worldId.toString(), { snapshot ->
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, snapshot.revision)
        }).also { it.replaceSources(listOf(source)) }

    private fun frame(
        epoch: Long,
        occupied: List<RailInterval>,
        headTraversal: List<RailTraversalSegmentSnapshot> = emptyList(),
        tailTraversal: List<RailTraversalSegmentSnapshot> = emptyList()
    ): RailwayWorldFrameSnapshot {
        val head = occupied.last().let {
            RailPositionSnapshot(it.railId, ViewDirection.AB, it.maxArcLengthInAbMeters)
        }
        val tail = occupied.first().let {
            RailPositionSnapshot(it.railId, ViewDirection.AB, it.minArcLengthInAbMeters)
        }
        return RailwayWorldFrameSnapshot.create(epoch, topology.revision, listOf(TrainFrameSnapshot.create(
            "train", "Train", epoch, epoch, 0.0, false, false, emptyList(), head, tail, occupied,
            emptyList(), occupied.map { SweptRailSpanSnapshot(it.railId, it.minArcLengthInAbMeters,
                it.maxArcLengthInAbMeters) }, headTraversal, tailTraversal)),
            ActiveRailContinuationsSnapshot.empty(topology.revision))
    }
}
