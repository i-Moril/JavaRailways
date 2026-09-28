package work.art1st.javarailways.simplesignals.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyCrossingKind
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
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
        val first = FixedBlockDefinition(
            "test:a-block",
            1L,
            listOf(FixedBlockTraversal(railId, ViewDirection.AB, 0.0, 2.0))
        )
        val second = FixedBlockDefinition(
            "test:z-block",
            1L,
            listOf(FixedBlockTraversal(railId, ViewDirection.AB, 8.0, 10.0))
        )
        val worldRuntime = SimpleSignalsWorldRuntime(worldId)
        worldRuntime.initializeDefinitions(
            topology,
            SimpleSignalsWorldDefinitions(
                worldId,
                1L,
                emptyList(),
                emptyList(),
                listOf(second, first)
            )
        )

        val occupancy = worldRuntime.initializeSectionOccupancy()
        assertEquals(
            listOf("test:a-block", "test:z-block"),
            requireNotNull(occupancy.source)
                .areas.map { it.areaId.toString() }
        )
        val epochRuntime = SignalEpochRuntime(
            worldId.toString(),
            TrainRestrictionPublisher { snapshot ->
                TrainRestrictionPublicationResult(
                    TrainRestrictionPublicationStatus.ACCEPTED,
                    snapshot.revision
                )
            }
        )
        epochRuntime.replaceSources(listOf(requireNotNull(occupancy.source)))
        epochRuntime.process(frame(1L, listOf(RailInterval(railId, 4.0, 6.0))))
        val baseline = requireNotNull(occupancy.snapshot())
        assertEquals(
            listOf("test:a-block", "test:z-block"),
            baseline.sections.keys.map(SectionRef::id)
        )
        assertThrows(UnsupportedOperationException::class.java) {
            (baseline.sections as MutableMap<*, *>).clear()
        }
    }

    @Test
    fun `physical baseline change enter and leave publish immutable section snapshots while idle stays idle`() {
        val worldRuntime = SimpleSignalsWorldRuntime(worldId)
        assertEquals(
            SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
            worldRuntime.initializeDefinitions(
                topology,
                SimpleSignalsWorldDefinitions(worldId, 1L, emptyList(), emptyList(), listOf(block))
            ).status
        )
        val occupancy = worldRuntime.initializeSectionOccupancy()
        assertNull(occupancy.snapshot())
        val source = requireNotNull(occupancy.source)
        val epochRuntime = SignalEpochRuntime(
            worldId.toString(),
            TrainRestrictionPublisher { snapshot ->
                TrainRestrictionPublicationResult(
                    TrainRestrictionPublicationStatus.ACCEPTED,
                    snapshot.revision
                )
            }
        )
        epochRuntime.replaceSources(listOf(source))

        epochRuntime.process(frame(1L, listOf(RailInterval(railId, 0.0, 1.0))))
        val baseline = requireNotNull(occupancy.snapshot())
        val section = SectionRef(SectionKind.FIXED_BLOCK, block.blockId)
        assertEquals(setOf(section), baseline.changedSections)
        assertEquals(emptySet<String>(), baseline.sections.getValue(section).occupants)
        assertEquals(6.0, baseline.sections.getValue(section).lengthMeters, 0.0)

        epochRuntime.process(frame(2L, listOf(RailInterval(railId, 0.0, 1.0))))
        assertSame(baseline, occupancy.snapshot())

        epochRuntime.process(
            frame(
                3L,
                listOf(RailInterval(railId, 3.0, 4.0)),
                headTraversal = listOf(
                    RailTraversalSegmentSnapshot(railId, ViewDirection.AB, 0.0, 4.0)
                )
            )
        )
        val entered = requireNotNull(occupancy.snapshot())
        assertNotSame(baseline, entered)
        assertEquals(setOf("train"), entered.sections.getValue(section).occupants)
        assertEquals(
            listOf(RailwayOccupancyCrossingKind.ENTER),
            entered.crossingsBySection.getValue(section).map { it.kind }
        )
        assertThrows(UnsupportedOperationException::class.java) {
            (entered.crossingsBySection.getValue(section) as MutableList<*>).clear()
        }

        epochRuntime.process(
            frame(
                4L,
                listOf(RailInterval(railId, 9.0, 10.0)),
                tailTraversal = listOf(
                    RailTraversalSegmentSnapshot(railId, ViewDirection.AB, 4.0, 10.0)
                )
            )
        )
        val left = requireNotNull(occupancy.snapshot())
        assertEquals(emptySet<String>(), left.sections.getValue(section).occupants)
        assertEquals(
            listOf(RailwayOccupancyCrossingKind.LEFT),
            left.crossingsBySection.getValue(section).map { it.kind }
        )
    }

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
        return RailwayWorldFrameSnapshot.create(
            epoch,
            topology.revision,
            listOf(
                TrainFrameSnapshot.create(
                    trainId = "train",
                    trainName = "Train",
                    revision = epoch,
                    physicsEpoch = epoch,
                    speedMetersPerSecond = 0.0,
                    derailed = false,
                    reversed = false,
                    cars = emptyList(),
                    head = head,
                    tail = tail,
                    occupiedIntervals = occupied,
                    bodyTraversal = emptyList(),
                    sweptSpans = occupied.map {
                        SweptRailSpanSnapshot(
                            it.railId,
                            it.minArcLengthInAbMeters,
                            it.maxArcLengthInAbMeters
                        )
                    },
                    headTraversal = headTraversal,
                    tailTraversal = tailTraversal
                )
            )
        )
    }
}
