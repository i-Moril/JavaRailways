package work.art1st.javarailways.simplesignals.runtime

import work.art1st.javarailways.simplesignals.testing.signalWorldRuntime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyCrossingKind
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
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
import work.art1st.javarailways.simplesignals.region.endpoint
import work.art1st.javarailways.simplesignals.region.topologyState
import work.art1st.javarailways.simplesignals.signal.ConfiguredSignalOutput
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalClearLevel
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalContinuationRequirement
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDependency
import work.art1st.javarailways.simplesignals.signal.fixedBlockSignalDeclarationId
import java.util.UUID

class SectionOccupancyRuntimeTest {
    @Test
    fun `stale topology fails before control publication and definition replacement establishes a fresh baseline`() {
        var reads = 0
        val policy = work.art1st.javarailways.simplesignals.block.FixedBlockDirectionPolicy({ root, rails ->
            reads++
            work.art1st.javarailways.basic.api.route.RailOperatingDirections(root, rails.associateWith { null })
        })
        val world = SimpleSignalsWorldRuntime(worldId, policy)
        world.initializeDefinitions(topology, SimpleSignalsWorldDefinitions(worldId, 1L, emptyList(), emptyList(), listOf(block)))
        val occupancy = world.initializeSectionOccupancy()
        val completedEpochs = arrayListOf<Long>()
        val epochs = SignalEpochRuntime(worldId.toString(), { value ->
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, value.revision)
        }, completeEpoch = { stamp, _ -> completedEpochs += stamp.physicsEpoch })
        epochs.replaceSources(occupancy.sources)
        val section = SectionRef(SectionKind.FIXED_BLOCK, block.blockId)
        epochs.process(emptyFrame(1L, topology, ActiveRailContinuationsSnapshot.empty(topology.revision)))
        assertTrue(occupancy.signalFor(section)!!.state is SectionSignalState.Degraded)
        val baseline = occupancy.presentationSnapshot()

        val replacementTopology = lineTopologyState(topology.revision + 1L, railId)
        assertThrows(IllegalArgumentException::class.java) {
            epochs.process(emptyFrame(2L, replacementTopology,
                ActiveRailContinuationsSnapshot.empty(replacementTopology.revision)))
        }
        assertSame(baseline, occupancy.presentationSnapshot())
        assertEquals(listOf(1L), completedEpochs, "stale topology cannot advance the completed control observation")
        assertEquals(1, reads, "stale geometry cannot be proved by another direction read")

        val bidirectional = FixedBlockDefinition(block.blockId, block.revision + 1L, block.traversals, true)
        val prepared = world.prepareDefinitions(replacementTopology,
            SimpleSignalsWorldDefinitions(worldId, 2L, emptyList(), emptyList(), listOf(bidirectional)))
            as SimpleSignalsDefinitionPreparationResult.Prepared
        val replacement = SectionOccupancyRuntime.from(prepared.value.candidatePublication)
        epochs.replaceSourcesAtomically(replacement.sources) {
            assertEquals(SimpleSignalsDefinitionCommitStatus.COMMITTED, world.commitPrepared(prepared.value, replacement))
        }
        assertNull(replacement.signalFor(section), "old degraded or unavailable control is not a new physical baseline")
        epochs.process(emptyFrame(3L, replacementTopology,
            ActiveRailContinuationsSnapshot.empty(replacementTopology.revision)))
        assertTrue(replacement.presentationSnapshot()!!.blockControls.isEmpty(), "the new bidirectional definition is enabled")
        assertEquals(2, reads)
    }

    @Test
    fun `ordinary physical updates reuse the immutable disabled block controls without policy reads`() {
        var reads = 0
        val policy = work.art1st.javarailways.simplesignals.block.FixedBlockDirectionPolicy({ root, rails ->
            reads++
            work.art1st.javarailways.basic.api.route.RailOperatingDirections(root, rails.associateWith { null })
        })
        val world = SimpleSignalsWorldRuntime(worldId, policy)
        world.initializeDefinitions(topology, SimpleSignalsWorldDefinitions(worldId, 1L, emptyList(), emptyList(), listOf(block)))
        val occupancy = world.initializeSectionOccupancy()
        val epochs = epochRuntime(checkNotNull(occupancy.source))
        epochs.process(emptyFrame(1L, topology, ActiveRailContinuationsSnapshot.empty(topology.revision)))
        val baseline = checkNotNull(occupancy.presentationSnapshot())
        assertTrue(baseline.blockControls.getValue(block.blockId) is SectionSignalState.Degraded)
        epochs.process(frame(2L, listOf(RailInterval(railId, 4.0, 5.0))))
        val updated = checkNotNull(occupancy.presentationSnapshot())
        assertNotSame(baseline.occupancy, updated.occupancy)
        assertSame(baseline.blockControls, updated.blockControls)
        assertEquals(1, reads)
    }

    @Test
    fun `direction wave publishes known degraded speed downstream and leaves unrelated or no-op writes idle`() {
        val second = UUID.randomUUID()
        val connected = lineTopologyState(topology.revision, railId, second)
        val downstreamBlock = FixedBlockDefinition("test:downstream", 1L,
            listOf(FixedBlockTraversal(second, ViewDirection.AB, 2.0, 8.0)))
        val downstream = terminalSignal("test:downstream-signal", downstreamBlock.blockId, 30.0, "clear")
        val upstream = configuredSignal("test:upstream-signal", block.blockId, listOf(FixedBlockSignalDependency(downstream.signalId)))
        var epochs: SignalEpochRuntime? = null
        val directions = work.art1st.javarailways.basic.rail.OperatingDirectionStore { epochs?.publishOperatingDirectionChanges(it) }
        directions.put(railId, ViewDirection.AB)
        val subsets = arrayListOf<Set<UUID>>()
        var unavailable = false
        val policy = work.art1st.javarailways.simplesignals.block.FixedBlockDirectionPolicy({ root, rails ->
            subsets += rails.toSet()
            if (unavailable) error("policy unavailable")
            directions.snapshot(root, rails)
        })
        val world = SimpleSignalsWorldRuntime(worldId, policy)
        world.initializeDefinitions(connected, SimpleSignalsWorldDefinitions(worldId, 1L, emptyList(), emptyList(),
            listOf(block, downstreamBlock), listOf(upstream, downstream)))
        val occupancy = world.initializeSectionOccupancy()
        epochs = epochRuntime(checkNotNull(occupancy.source))
        fun nextFrame(epoch: Long) = emptyFrame(epoch, connected, ActiveRailContinuationsSnapshot.empty(connected.revision))
        epochs.process(nextFrame(1L))
        val baseline = checkNotNull(occupancy.signalSnapshot())
        assertEquals(20.0 / 3.6, baseline.states.getValue(downstream.signalId).effectiveSpeedLimitMetersPerSecond)
        assertTrue(baseline.states.getValue(downstream.signalId) is SectionSignalState.Degraded)
        assertEquals(8.0, baseline.states.getValue(upstream.signalId).effectiveSpeedLimitMetersPerSecond,
            "upstream consumes the known limit and remains usable")
        assertEquals(listOf(setOf(railId, second)), subsets)
        assertEquals(baseline.stamp, occupancy.presentationSnapshot()!!.occupancy.stamp)
        directions.put(UUID.randomUUID(), ViewDirection.AB)
        directions.put(railId, ViewDirection.AB)
        epochs.process(nextFrame(2L))
        assertSame(baseline, occupancy.signalSnapshot())
        assertEquals(1, subsets.size)
        directions.put(second, ViewDirection.AB)
        epochs.process(nextFrame(3L))
        val enabled = checkNotNull(occupancy.signalSnapshot())
        assertEquals(30.0, enabled.states.getValue(upstream.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals(setOf(second), subsets.last(), "only related block paths are read after a direction change")
        unavailable = true
        directions.put(second, ViewDirection.BA)
        epochs.process(nextFrame(4L))
        val unknown = checkNotNull(occupancy.signalSnapshot())
        assertEquals(0.0, unknown.states.getValue(downstream.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals(0.0, unknown.states.getValue(upstream.signalId).effectiveSpeedLimitMetersPerSecond)
        assertTrue(unknown.states.getValue(downstream.signalId) is SectionSignalState.Unavailable)
    }

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
        val worldRuntime = signalWorldRuntime(worldId)
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
    fun `world definitions install concrete fixed-block rules without injected bindings`() {
        val upstreamBlock = FixedBlockDefinition(
            "test:upstream-block",
            1L,
            listOf(FixedBlockTraversal(railId, ViewDirection.AB, 0.0, 4.0))
        )
        val downstreamBlock = FixedBlockDefinition(
            "test:downstream-block",
            1L,
            listOf(FixedBlockTraversal(railId, ViewDirection.AB, 6.0, 10.0))
        )
        val downstreamSignal = configuredSignal(
            "test:downstream-signal",
            downstreamBlock.blockId
        )
        val upstreamSignal = configuredSignal(
            "test:upstream-signal",
            upstreamBlock.blockId,
            listOf(FixedBlockSignalDependency(downstreamSignal.signalId))
        )
        val worldRuntime = signalWorldRuntime(worldId)
        worldRuntime.initializeDefinitions(
            topology,
            SimpleSignalsWorldDefinitions(
                worldId,
                1L,
                emptyList(),
                emptyList(),
                listOf(upstreamBlock, downstreamBlock),
                listOf(upstreamSignal, downstreamSignal)
            )
        )
        val occupancy = worldRuntime.initializeSectionOccupancy()
        val epochRuntime = epochRuntime(requireNotNull(occupancy.source))

        epochRuntime.process(frame(1L, listOf(RailInterval(railId, 4.5, 5.5))))
        val clear = requireNotNull(occupancy.signalSnapshot())
        assertEquals(30.0, clear.availableOutput(downstreamSignal.signalId).speedLimitMetersPerSecond)
        assertEquals("private/open", clear.availableOutput(upstreamSignal.signalId).modelVariant)

        epochRuntime.process(frame(2L, listOf(RailInterval(railId, 7.0, 8.0))))
        val occupied = requireNotNull(occupancy.signalSnapshot())
        assertEquals("private/occupied", occupied.availableOutput(downstreamSignal.signalId).modelVariant)
        assertEquals(8.0, occupied.availableOutput(upstreamSignal.signalId).speedLimitMetersPerSecond)
        assertEquals("private/approach", occupied.availableOutput(upstreamSignal.signalId).modelVariant)
    }

    @Test
    fun `turnout-only selection change evaluates the configured physical branch`() {
        val trunk = UUID.nameUUIDFromBytes("signal-turnout-trunk".toByteArray())
        val firstBranch = UUID.nameUUIDFromBytes("signal-turnout-first".toByteArray())
        val secondBranch = UUID.nameUUIDFromBytes("signal-turnout-second".toByteArray())
        val turnoutTopology = topologyState(
            9L,
            listOf(trunk, firstBranch, secondBranch),
            listOf(
                endpoint(trunk, RailEndpoint.B) to endpoint(firstBranch, RailEndpoint.A),
                endpoint(trunk, RailEndpoint.B) to endpoint(secondBranch, RailEndpoint.A)
            )
        )
        val upstreamBlock = fixedBlock("test:turnout-upstream", trunk)
        val firstBranchBlock = fixedBlock("test:turnout-first-block", firstBranch)
        val secondBranchBlock = fixedBlock("test:turnout-second-block", secondBranch)
        val firstSignal = terminalSignal(
            "test:turnout-first-signal",
            firstBranchBlock.blockId,
            30.0,
            "private/first-clear"
        )
        val secondSignal = terminalSignal(
            "test:turnout-second-signal",
            secondBranchBlock.blockId,
            5.0,
            "private/second-restricted"
        )
        val upstreamSignal = configuredSignal(
            "test:turnout-upstream-signal",
            upstreamBlock.blockId,
            listOf(
                FixedBlockSignalDependency(
                    firstSignal.signalId,
                    listOf(
                        FixedBlockSignalContinuationRequirement(
                            endpoint(trunk, RailEndpoint.B),
                            endpoint(firstBranch, RailEndpoint.A)
                        )
                    )
                ),
                FixedBlockSignalDependency(
                    secondSignal.signalId,
                    listOf(
                        FixedBlockSignalContinuationRequirement(
                            endpoint(trunk, RailEndpoint.B),
                            endpoint(secondBranch, RailEndpoint.A)
                        )
                    )
                )
            )
        )
        val worldRuntime = signalWorldRuntime(worldId)
        worldRuntime.initializeDefinitions(
            turnoutTopology,
            SimpleSignalsWorldDefinitions(
                worldId,
                1L,
                emptyList(),
                emptyList(),
                listOf(upstreamBlock, firstBranchBlock, secondBranchBlock),
                listOf(upstreamSignal, firstSignal, secondSignal)
            )
        )
        val occupancy = worldRuntime.initializeSectionOccupancy()
        val source = requireNotNull(occupancy.source)
        val watchedEntry = endpoint(trunk, RailEndpoint.B)
        assertEquals(setOf(watchedEntry), source.watchedContinuationEntries)
        val invalidations = arrayListOf<Pair<String, Set<String>>>()
        val epochRuntime = epochRuntime(source, invalidations)

        epochRuntime.process(
            emptyFrame(
                1L,
                turnoutTopology,
                activeContinuations(turnoutTopology, 1L, watchedEntry, endpoint(firstBranch, RailEndpoint.A))
            )
        )
        assertEquals(
            "private/open",
            requireNotNull(occupancy.signalSnapshot())
                .availableOutput(upstreamSignal.signalId).modelVariant
        )

        invalidations.clear()
        epochRuntime.process(
            emptyFrame(
                2L,
                turnoutTopology,
                activeContinuations(turnoutTopology, 2L, watchedEntry, endpoint(secondBranch, RailEndpoint.A))
            )
        )
        val switched = requireNotNull(occupancy.signalSnapshot())
        assertEquals("private/approach", switched.availableOutput(upstreamSignal.signalId).modelVariant)
        assertEquals(setOf(upstreamSignal.signalId), switched.changedSignalIds)
        assertEquals(
            listOf(SECTION_OCCUPANCY_SOURCE_ID to setOf(fixedBlockSignalDeclarationId(upstreamBlock.blockId))),
            invalidations
        )

        epochRuntime.process(
            emptyFrame(
                3L,
                turnoutTopology,
                ActiveRailContinuationsSnapshot.create(
                    turnoutTopology.revision,
                    3L,
                    emptyMap()
                )
            )
        )
        val missingSelection = requireNotNull(occupancy.signalSnapshot())
        assertEquals(
            0.0,
            missingSelection.states.getValue(upstreamSignal.signalId)
                .effectiveSpeedLimitMetersPerSecond
        )
        assertEquals(
            "no configured downstream path is active",
            (missingSelection.states.getValue(upstreamSignal.signalId) as
                SectionSignalState.Unavailable).reason
        )
    }

    @Test
    fun `physical baseline change enter and leave publish immutable section snapshots while idle stays idle`() {
        val worldRuntime = signalWorldRuntime(worldId)
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
        val worldRuntime = signalWorldRuntime(worldId)
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
                    val downstream = checkNotNull(input.downstreamSignals.single().output)
                    SectionSignalOutput(
                        downstream.speedLimitMetersPerSecond,
                        "mirror/${downstream.modelVariant}"
                    )
                }
            )
        )
        val invalidations = arrayListOf<Pair<String, Set<String>>>()
        val epochRuntime = epochRuntime(requireNotNull(occupancy.source), invalidations)

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
        assertEquals(
            listOf(
                SECTION_OCCUPANCY_SOURCE_ID to
                    setOf(fixedBlockSignalDeclarationId(firstBlock.blockId), fixedBlockSignalDeclarationId(secondBlock.blockId))
            ),
            invalidations
        )

        calls.clear()
        epochRuntime.process(frame(2L, listOf(RailInterval(railId, 4.5, 5.5))))
        assertSame(baseline, occupancy.signalSnapshot())
        assertEquals(emptyList<String>(), calls)
        assertEquals(1, invalidations.size)

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
        assertEquals(
            SECTION_OCCUPANCY_SOURCE_ID to setOf(fixedBlockSignalDeclarationId(firstBlock.blockId), fixedBlockSignalDeclarationId(secondBlock.blockId)),
            invalidations.last()
        )
        assertEquals(2, invalidations.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (occupied.states as MutableMap<*, *>).clear()
        }
    }

    @Test
    fun `one fixed block accepts only one controlling signal rule`() {
        val section = SectionRef(SectionKind.FIXED_BLOCK, block.blockId)

        assertThrows(IllegalArgumentException::class.java) {
            SectionSignalRuleRuntime.compile(
                listOf(section),
                listOf(
                    SectionSignalRuleBinding("test:first-signal", section, emptyList()) {
                        SectionSignalOutput(10.0, "first")
                    },
                    SectionSignalRuleBinding("test:second-signal", section, emptyList()) {
                        SectionSignalOutput(20.0, "second")
                    }
                )
            )
        }
    }

    @Test
    fun `rule failure replaces stale permissive state and propagates unavailable without invoking consumers`() {
        val upstreamBlock = FixedBlockDefinition(
            "test:upstream-block",
            1L,
            listOf(FixedBlockTraversal(railId, ViewDirection.AB, 0.0, 4.0))
        )
        val downstreamBlock = FixedBlockDefinition(
            "test:downstream-block",
            1L,
            listOf(FixedBlockTraversal(railId, ViewDirection.AB, 6.0, 10.0))
        )
        val upstreamSection = SectionRef(SectionKind.FIXED_BLOCK, upstreamBlock.blockId)
        val downstreamSection = SectionRef(SectionKind.FIXED_BLOCK, downstreamBlock.blockId)
        var shouldFail = false
        var consumerCalls = 0
        val worldRuntime = signalWorldRuntime(worldId)
        worldRuntime.initializeDefinitions(
            topology,
            SimpleSignalsWorldDefinitions(
                worldId,
                1L,
                emptyList(),
                emptyList(),
                listOf(upstreamBlock, downstreamBlock)
            )
        )
        val occupancy = worldRuntime.initializeSectionOccupancy(
            listOf(
                SectionSignalRuleBinding(
                    "test:downstream-signal",
                    downstreamSection,
                    emptyList()
                ) { input ->
                    if (shouldFail) error("configured rule failed")
                    SectionSignalOutput(
                        if (input.controlledSection.occupants.isEmpty()) 31.0 else 4.0,
                        "configured-variant"
                    )
                },
                SectionSignalRuleBinding(
                    "test:upstream-signal",
                    upstreamSection,
                    listOf(DownstreamSignalDependency("test:downstream-signal", 6.0))
                ) { input ->
                    consumerCalls++
                    checkNotNull(input.downstreamSignals.single().output)
                }
            )
        )
        val epochRuntime = epochRuntime(requireNotNull(occupancy.source))
        epochRuntime.process(frame(1L, listOf(RailInterval(railId, 4.5, 5.5))))
        val baselineSignals = requireNotNull(occupancy.signalSnapshot())
        assertEquals(
            31.0,
            baselineSignals.availableOutput("test:downstream-signal").speedLimitMetersPerSecond
        )
        assertEquals(1, consumerCalls)

        shouldFail = true
        epochRuntime.process(frame(2L, listOf(RailInterval(railId, 7.0, 8.0))))

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

    private fun epochRuntime(
        source: work.art1st.javarailways.simpleato.api.signal.SignalEpochSource,
        invalidations: MutableList<Pair<String, Set<String>>> = arrayListOf()
    ) =
        SignalEpochRuntime(
            worldId = worldId.toString(),
            publisher = TrainRestrictionPublisher { snapshot ->
                TrainRestrictionPublicationResult(
                    TrainRestrictionPublicationStatus.ACCEPTED,
                    snapshot.revision
                )
            },
            invalidateInspectionDeclarations = { sourceId, declarationIds ->
                invalidations += sourceId to declarationIds
            }
        ).also { it.replaceSources(listOf(source)) }

    private fun SectionSignalSnapshot.availableOutput(signalId: String): SectionSignalOutput =
        (states.getValue(signalId) as SectionSignalState.Available).output

    private fun configuredSignal(
        signalId: String,
        blockId: String,
        downstream: List<FixedBlockSignalDependency> = emptyList()
    ) = FixedBlockSignalDefinition(
        signalId,
        1L,
        blockId,
        200.0,
        ConfiguredSignalOutput(0.0, "private/occupied"),
        listOf(
            FixedBlockSignalClearLevel(
                0.0,
                ConfiguredSignalOutput(8.0, "private/approach")
            ),
            FixedBlockSignalClearLevel(
                20.0,
                ConfiguredSignalOutput(30.0, "private/open")
            )
        ),
        downstream
    )

    private fun terminalSignal(
        signalId: String,
        blockId: String,
        speedMetersPerSecond: Double,
        modelVariant: String
    ) = FixedBlockSignalDefinition(
        signalId,
        1L,
        blockId,
        200.0,
        ConfiguredSignalOutput(0.0, "private/occupied"),
        listOf(
            FixedBlockSignalClearLevel(
                0.0,
                ConfiguredSignalOutput(speedMetersPerSecond, modelVariant)
            )
        )
    )

    private fun fixedBlock(blockId: String, blockRailId: UUID) = FixedBlockDefinition(
        blockId,
        1L,
        listOf(FixedBlockTraversal(blockRailId, ViewDirection.AB, 0.0, 10.0))
    )

    private fun activeContinuations(
        topology: TopologyState,
        selectionRevision: Long,
        entry: RailEndpointRef,
        exit: RailEndpointRef
    ) = ActiveRailContinuationsSnapshot.create(
        topology.revision,
        selectionRevision,
        mapOf(entry to exit)
    )

    private fun emptyFrame(
        epoch: Long,
        topology: TopologyState,
        activeContinuations: ActiveRailContinuationsSnapshot
    ) = RailwayWorldFrameSnapshot.create(
        epoch,
        topology.revision,
        emptyList(),
        activeContinuations
    )

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
