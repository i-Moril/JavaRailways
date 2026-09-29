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
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
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

        val occupancy = worldRuntime.initializeSectionOccupancy(emptyList())
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
        val occupancy = worldRuntime.initializeSectionOccupancy(emptyList())
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

    @Test
    fun `configured rules publish arbitrary speed and model outputs through affected-only propagation`() {
        val firstBlock = FixedBlockDefinition(
            "test:first-block",
            1L,
            listOf(FixedBlockTraversal(railId, ViewDirection.AB, 0.0, 4.0))
        )
        val secondBlock = FixedBlockDefinition(
            "test:second-block",
            1L,
            listOf(FixedBlockTraversal(railId, ViewDirection.AB, 6.0, 10.0))
        )
        val firstSection = SectionRef(SectionKind.FIXED_BLOCK, firstBlock.blockId)
        val secondSection = SectionRef(SectionKind.FIXED_BLOCK, secondBlock.blockId)
        val calls = arrayListOf<String>()
        val worldRuntime = SimpleSignalsWorldRuntime(worldId)
        worldRuntime.initializeDefinitions(
            topology,
            SimpleSignalsWorldDefinitions(
                worldId,
                1L,
                emptyList(),
                emptyList(),
                listOf(firstBlock, secondBlock)
            )
        )
        val occupancy = worldRuntime.initializeSectionOccupancy(
            listOf(
                SectionSignalRuleBinding(
                    "test:second-signal",
                    secondSection,
                    emptyList()
                ) { input ->
                    calls += input.signalId
                    if (input.controlledSection.occupants.isEmpty()) {
                        SectionSignalOutput(27.25, "YG")
                    } else {
                        SectionSignalOutput(0.0, "custom-stop")
                    }
                },
                SectionSignalRuleBinding(
                    "test:first-signal",
                    firstSection,
                    listOf(DownstreamSignalDependency("test:second-signal", 6.0))
                ) { input ->
                    calls += input.signalId
                    val downstream = input.downstreamSignals.single().output
                    SectionSignalOutput(
                        downstream.speedLimitMetersPerSecond,
                        "mirror/${downstream.modelVariant}"
                    )
                }
            )
        )
        val epochRuntime = epochRuntime(requireNotNull(occupancy.source))

        epochRuntime.process(frame(1L, listOf(RailInterval(railId, 4.5, 5.5))))
        val baseline = requireNotNull(occupancy.signalSnapshot())
        assertEquals(listOf("test:second-signal", "test:first-signal"), calls)
        assertEquals(
            27.25,
            baseline.availableOutput("test:second-signal").speedLimitMetersPerSecond
        )
        assertEquals("YG", baseline.availableOutput("test:second-signal").modelVariant)
        assertEquals("mirror/YG", baseline.availableOutput("test:first-signal").modelVariant)
        assertEquals(setOf("test:first-signal", "test:second-signal"), baseline.changedSignalIds)

        calls.clear()
        epochRuntime.process(frame(2L, listOf(RailInterval(railId, 4.5, 5.5))))
        assertSame(baseline, occupancy.signalSnapshot())
        assertEquals(emptyList<String>(), calls)

        epochRuntime.process(frame(3L, listOf(RailInterval(railId, 7.0, 8.0))))
        val occupied = requireNotNull(occupancy.signalSnapshot())
        assertEquals(listOf("test:second-signal", "test:first-signal"), calls)
        assertEquals(
            0.0,
            occupied.availableOutput("test:second-signal").speedLimitMetersPerSecond
        )
        assertEquals("custom-stop", occupied.availableOutput("test:second-signal").modelVariant)
        assertEquals("mirror/custom-stop", occupied.availableOutput("test:first-signal").modelVariant)
        assertEquals(setOf("test:first-signal", "test:second-signal"), occupied.changedSignalIds)
        assertThrows(UnsupportedOperationException::class.java) {
            (occupied.states as MutableMap<*, *>).clear()
        }
    }

    @Test
    fun `rule failure replaces stale permissive state and propagates unavailable without invoking consumers`() {
        val section = SectionRef(SectionKind.FIXED_BLOCK, block.blockId)
        var shouldFail = false
        var consumerCalls = 0
        val worldRuntime = SimpleSignalsWorldRuntime(worldId)
        worldRuntime.initializeDefinitions(
            topology,
            SimpleSignalsWorldDefinitions(worldId, 1L, emptyList(), emptyList(), listOf(block))
        )
        val occupancy = worldRuntime.initializeSectionOccupancy(
            listOf(
                SectionSignalRuleBinding("test:downstream-signal", section, emptyList()) { input ->
                    if (shouldFail) error("configured rule failed")
                    SectionSignalOutput(
                        if (input.controlledSection.occupants.isEmpty()) 31.0 else 4.0,
                        "configured-variant"
                    )
                },
                SectionSignalRuleBinding(
                    "test:upstream-signal",
                    section,
                    listOf(DownstreamSignalDependency("test:downstream-signal", 6.0))
                ) { input ->
                    consumerCalls++
                    input.downstreamSignals.single().output
                }
            )
        )
        val epochRuntime = epochRuntime(requireNotNull(occupancy.source))
        epochRuntime.process(frame(1L, listOf(RailInterval(railId, 0.0, 1.0))))
        val baselineSignals = requireNotNull(occupancy.signalSnapshot())
        assertEquals(
            31.0,
            baselineSignals.availableOutput("test:downstream-signal").speedLimitMetersPerSecond
        )
        assertEquals(1, consumerCalls)

        shouldFail = true
        epochRuntime.process(frame(2L, listOf(RailInterval(railId, 3.0, 4.0))))

        val unavailable = requireNotNull(occupancy.signalSnapshot())
        val downstream = unavailable.states.getValue("test:downstream-signal") as
            SectionSignalState.Unavailable
        val upstream = unavailable.states.getValue("test:upstream-signal") as
            SectionSignalState.Unavailable
        assertEquals("configured rule failed", downstream.reason)
        assertEquals(0.0, downstream.effectiveSpeedLimitMetersPerSecond)
        assertEquals(
            "downstream signal test:downstream-signal is unavailable",
            upstream.reason
        )
        assertEquals(0.0, upstream.effectiveSpeedLimitMetersPerSecond)
        assertEquals(1, consumerCalls)
        assertEquals(
            setOf("test:downstream-signal", "test:upstream-signal"),
            unavailable.changedSignalIds
        )
    }

    @Test
    fun `rule outputs reject invalid speed or absent model selection`() {
        assertThrows(IllegalArgumentException::class.java) {
            SectionSignalOutput(-1.0, "negative")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SectionSignalOutput(Double.NaN, "not-a-number")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SectionSignalOutput(0.0, " ")
        }
    }

    private fun epochRuntime(source: work.art1st.javarailways.simpleato.api.signal.SignalEpochSource) =
        SignalEpochRuntime(
            worldId.toString(),
            TrainRestrictionPublisher { snapshot ->
                TrainRestrictionPublicationResult(
                    TrainRestrictionPublicationStatus.ACCEPTED,
                    snapshot.revision
                )
            }
        ).also { it.replaceSources(listOf(source)) }

    private fun SectionSignalSnapshot.availableOutput(signalId: String): SectionSignalOutput =
        (states.getValue(signalId) as SectionSignalState.Available).output

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
            ),
            ActiveRailContinuationsSnapshot.empty(topology.revision)
        )
    }
}
