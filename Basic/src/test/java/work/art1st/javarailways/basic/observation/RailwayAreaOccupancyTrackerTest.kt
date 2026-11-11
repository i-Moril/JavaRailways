package work.art1st.javarailways.basic.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayAreaOccupancyEngine
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyAreaDefinition
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyBoundary
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyCrossingKind
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange
import work.art1st.javarailways.basic.infrastructure.lineTopologyState
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import java.util.UUID

class RailwayAreaOccupancyTrackerTest {
    private val firstRail = UUID.nameUUIDFromBytes("area-first-rail".toByteArray())
    private val secondRail = UUID.nameUUIDFromBytes("area-second-rail".toByteArray())
    private val areaId = RailObjectRef("test", "area")
    private val entryId = RailObjectRef("test", "entry")
    private val exitId = RailObjectRef("test", "exit")

    @Test
    fun `removing and readding an area before consume establishes fresh physical baseline`() {
        val definition = area(listOf(RailInterval(firstRail, 10.0, 20.0)), listOf(
            boundary(entryId, firstRail, ViewDirection.AB, 10.0),
            boundary(exitId, firstRail, ViewDirection.BA, 20.0)))
        val old = RailwayAreaOccupancyEngine.create(listOf(definition))
        old.consume(observation(1L, occupied = listOf(RailInterval(firstRail, 15.0, 16.0))))
        val replacement = old.prepareReplacement(emptyList()).prepareReplacement(listOf(definition))
        assertEquals(emptyList<Any>(), replacement.consume(observation(2L,
            tailTraversal = listOf(RailTraversalSegmentSnapshot(firstRail, ViewDirection.AB, 16.0, 22.0))))
            .updates.single().crossings)
    }

    @Test
    fun `prepared replacement receives old epoch clearance and first frame ENTER LEFT for unchanged geometry`() {
        val definition = area(listOf(RailInterval(firstRail, 10.0, 20.0)), listOf(
            boundary(entryId, firstRail, ViewDirection.AB, 10.0),
            boundary(exitId, firstRail, ViewDirection.BA, 20.0)))
        val old = RailwayAreaOccupancyEngine.create(listOf(definition))
        old.consume(observation(1L, occupied = listOf(RailInterval(firstRail, 15.0, 16.0))))
        val newArea = RailwayOccupancyAreaDefinition.create(RailObjectRef("test", "new"), 1L, 7L,
            definition.intervals, definition.boundaries)
        val revised = RailwayOccupancyAreaDefinition.create(areaId, 4L, 7L,
            definition.intervals, definition.boundaries)
        val replacement = old.prepareReplacement(listOf(revised, newArea))
        val latest = replacement.prepareReplacement(listOf(revised, newArea))
        // Old consume may finish after both candidate engines have already compiled.
        assertEquals(RailwayOccupancyCrossingKind.LEFT, old.consume(observation(2L,
            occupied = listOf(RailInterval(firstRail, 21.0, 22.0)),
            tailTraversal = listOf(RailTraversalSegmentSnapshot(firstRail, ViewDirection.AB, 16.0, 22.0))))
            .updates.single().crossings.single().kind)
        val passage = listOf(RailTraversalSegmentSnapshot(firstRail, ViewDirection.AB, 0.0, 30.0))
        val baseline = latest.consume(observation(3L, occupied = listOf(RailInterval(firstRail, 30.0, 31.0)),
            headTraversal = passage, tailTraversal = passage))
        assertEquals(2, baseline.updates.size)
        val continued = baseline.updates.single { it.areaId == areaId }
        assertEquals(4L, continued.definitionRevision)
        assertEquals(listOf(RailwayOccupancyCrossingKind.ENTER, RailwayOccupancyCrossingKind.LEFT),
            continued.crossings.map { it.kind })
        assertEquals(emptyList<Any>(), baseline.updates.single { it.areaId == newArea.areaId }.crossings)
        assertEquals(emptyList<Any>(), latest.consume(observation(4L,
            occupied = listOf(RailInterval(firstRail, 30.0, 31.0)))).updates)
    }

    @Test
    fun `replacement first tail exit survives revision growth but not changed boundary identity`() {
        val definition = area(listOf(RailInterval(firstRail, 10.0, 20.0)), listOf(
            boundary(entryId, firstRail, ViewDirection.AB, 10.0),
            boundary(exitId, firstRail, ViewDirection.BA, 20.0)))
        val old = RailwayAreaOccupancyEngine.create(listOf(definition))
        old.consume(observation(1L, occupied = listOf(RailInterval(firstRail, 15.0, 16.0))))
        val crossing = listOf(RailTraversalSegmentSnapshot(firstRail, ViewDirection.AB, 16.0, 22.0))
        val changedBoundary = RailwayOccupancyAreaDefinition.create(areaId, 4L, 7L, definition.intervals,
            listOf(definition.boundaries.first(), boundary(RailObjectRef("test", "different-exit"),
                firstRail, ViewDirection.BA, 20.0)))
        val changed = old.prepareReplacement(listOf(changedBoundary))
        assertEquals(emptyList<Any>(), changed.consume(observation(2L, tailTraversal = crossing))
            .updates.single().crossings)
        val changedTopology = RailwayOccupancyAreaDefinition.create(areaId, 4L, 8L,
            definition.intervals, definition.boundaries)
        val reset = old.prepareReplacement(listOf(changedTopology))
        assertEquals(RailwayOccupancyCrossingKind.LEFT,
            reset.consume(observation(2L, topologyRevision = 8L,
                tailTraversal = crossing)).updates.single().crossings.single().kind)
    }

    @Test
    fun `affected area retains occupancy unavailable while unrelated area clears across topology change`() {
        val first = area(listOf(RailInterval(firstRail, 1.0, 9.0)), listOf(
            boundary(entryId, firstRail, ViewDirection.AB, 1.0),
            boundary(exitId, firstRail, ViewDirection.BA, 9.0)))
        val second = RailwayOccupancyAreaDefinition.create(RailObjectRef("test", "unrelated"), 1L, 7L,
            listOf(RailInterval(secondRail, 1.0, 9.0)), listOf(
                boundary(entryId, secondRail, ViewDirection.AB, 1.0),
                boundary(exitId, secondRail, ViewDirection.BA, 9.0)))
        val engine = RailwayAreaOccupancyEngine.create(listOf(first, second))
        engine.consume(observation(1L, occupied = listOf(
            RailInterval(firstRail, 5.0, 6.0), RailInterval(secondRail, 5.0, 6.0))))
        val change = RailTopologyChange(lineTopologyState(7L, firstRail, secondRail),
            lineTopologyState(8L, secondRail), setOf(firstRail))
        assertEquals(setOf(areaId), engine.invalidateTopology(change))
        val batch = engine.consume(observation(2L, topologyRevision = 8L,
            tailTraversal = listOf(RailTraversalSegmentSnapshot(secondRail, ViewDirection.AB, 6.0, 10.0))))
        val unavailable = batch.updates.single { it.areaId == areaId }
        assertFalse(unavailable.available)
        assertEquals(setOf("train"), unavailable.occupants)
        assertTrue(unavailable.crossings.isEmpty())
        val unrelated = batch.updates.single { it.areaId == second.areaId }
        assertTrue(unrelated.available)
        assertTrue(unrelated.occupants.isEmpty())
        assertEquals(RailwayOccupancyCrossingKind.LEFT, unrelated.crossings.single().kind)
        val repaired = RailwayOccupancyAreaDefinition.create(areaId, 4L, 8L, first.intervals, first.boundaries)
        val replacement = engine.prepareReplacement(listOf(repaired, second))
        assertTrue(replacement.invalidateTopology(change).isEmpty())
        val recovered = replacement.consume(observation(3L, topologyRevision = 8L,
            occupied = listOf(RailInterval(firstRail, 5.0, 6.0)))).updates.single { it.areaId == areaId }
        assertTrue(recovered.available)
        assertEquals(setOf("train"), recovered.occupants)
        assertTrue(recovered.crossings.isEmpty())
    }

    @Test
    fun `candidate availability is independent of predecessor after candidate incorporated transaction`() {
        val first = area(listOf(RailInterval(firstRail, 1.0, 9.0)), listOf(
            boundary(entryId, firstRail, ViewDirection.AB, 1.0)))
        val old = RailwayAreaOccupancyEngine.create(listOf(first))
        old.consume(observation(1L, occupied = listOf(RailInterval(firstRail, 5.0, 6.0))))
        val fresh = RailwayOccupancyAreaDefinition.create(areaId, 4L, 8L, first.intervals, first.boundaries)
        val candidate = old.prepareReplacement(listOf(fresh))
        val change = RailTopologyChange(lineTopologyState(7L, firstRail), lineTopologyState(8L, firstRail), setOf(firstRail))
        old.invalidateTopology(change)
        assertTrue(candidate.invalidateTopology(change).isEmpty())
        assertFalse(old.consume(observation(2L, topologyRevision = 8L)).updates.single().available)
        assertTrue(candidate.consume(observation(3L, topologyRevision = 8L)).updates.single().available)
    }

    @Test
    fun `replacement rejects retained epoch gap and world mismatch without advancing continuity`() {
        val definition = area(listOf(RailInterval(firstRail, 10.0, 20.0)), listOf(
            boundary(entryId, firstRail, ViewDirection.AB, 10.0)))
        val old = RailwayAreaOccupancyEngine.create(listOf(definition))
        old.consume(observation(1L))
        val replacement = old.prepareReplacement(listOf(definition))
        assertThrows(IllegalArgumentException::class.java) { replacement.consume(observation(3L)) }
        assertThrows(IllegalArgumentException::class.java) { replacement.consume(observation(2L, worldId = "other")) }
        assertEquals(1, replacement.consume(observation(2L)).updates.size)
    }

    @Test
    fun `complete multi-rail passage emits ENTER then LEFT with rail-local directions`() {
        val tracker = RailwayAreaOccupancyTracker(
            listOf(
                area(
                    intervals = listOf(
                        RailInterval(firstRail, 10.0, 100.0),
                        RailInterval(secondRail, 10.0, 90.0)
                    ),
                    boundaries = listOf(
                        boundary(entryId, firstRail, ViewDirection.AB, 10.0),
                        // The train leaves this rail in BA. Inward is therefore the opposite AB.
                        boundary(exitId, secondRail, ViewDirection.AB, 10.0)
                    )
                )
            )
        )
        tracker.consume(observation(1L, occupied = listOf(RailInterval(firstRail, 0.0, 5.0))))

        val completePass = listOf(
            RailTraversalSegmentSnapshot(firstRail, ViewDirection.AB, 0.0, 100.0),
            RailTraversalSegmentSnapshot(secondRail, ViewDirection.BA, 100.0, 0.0)
        )
        val update = tracker.consume(
            observation(
                epoch = 2L,
                occupied = listOf(RailInterval(secondRail, 0.0, 5.0)),
                headTraversal = completePass,
                tailTraversal = completePass
            )
        ).updates.single()

        assertEquals(emptySet<String>(), update.occupants)
        assertEquals(
            listOf(
                entryId to RailwayOccupancyCrossingKind.ENTER,
                exitId to RailwayOccupancyCrossingKind.LEFT
            ),
            update.crossings.map { it.boundaryId to it.kind }
        )
        assertEquals(listOf(areaId, areaId), update.crossings.map { it.areaId })
    }

    @Test
    fun `head reaching inward boundary enters while rear must move beyond outward boundary`() {
        val tracker = RailwayAreaOccupancyTracker(
            listOf(
                area(
                    intervals = listOf(RailInterval(firstRail, 10.0, 90.0)),
                    boundaries = listOf(
                        boundary(entryId, firstRail, ViewDirection.AB, 10.0),
                        boundary(exitId, firstRail, ViewDirection.BA, 90.0)
                    )
                )
            )
        )
        tracker.consume(observation(1L, occupied = listOf(RailInterval(firstRail, 0.0, 5.0))))

        val entered = tracker.consume(
            observation(
                epoch = 2L,
                occupied = listOf(RailInterval(firstRail, 10.0, 10.0)),
                headTraversal = listOf(
                    RailTraversalSegmentSnapshot(firstRail, ViewDirection.AB, 5.0, 10.0)
                )
            )
        ).updates.single()

        assertEquals(setOf("train"), entered.occupants)
        assertEquals(RailwayOccupancyCrossingKind.ENTER, entered.crossings.single().kind)
    }

    @Test
    fun `rear resting on boundary remains occupied until later outward movement`() {
        val tracker = RailwayAreaOccupancyTracker(
            listOf(
                area(
                    intervals = listOf(RailInterval(firstRail, 10.0, 90.0)),
                    boundaries = listOf(
                        boundary(entryId, firstRail, ViewDirection.AB, 10.0),
                        boundary(exitId, firstRail, ViewDirection.BA, 90.0)
                    )
                )
            )
        )
        tracker.consume(observation(1L, occupied = listOf(RailInterval(firstRail, 20.0, 30.0))))

        val restingAtBoundary = tracker.consume(
            observation(
                epoch = 2L,
                occupied = listOf(RailInterval(firstRail, 90.0, 95.0)),
                tailTraversal = listOf(
                    RailTraversalSegmentSnapshot(firstRail, ViewDirection.AB, 30.0, 90.0)
                )
            )
        )
        assertEquals(emptyList<Any>(), restingAtBoundary.updates)

        val cleared = tracker.consume(
            observation(
                epoch = 3L,
                occupied = listOf(RailInterval(firstRail, 91.0, 95.0)),
                tailTraversal = listOf(
                    RailTraversalSegmentSnapshot(firstRail, ViewDirection.AB, 90.0, 95.0)
                )
            )
        ).updates.single()

        assertEquals(emptySet<String>(), cleared.occupants)
        assertEquals(exitId, cleared.crossings.single().boundaryId)
        assertEquals(RailwayOccupancyCrossingKind.LEFT, cleared.crossings.single().kind)
    }

    @Test
    fun `final placement not swept envelope determines occupants`() {
        val tracker = RailwayAreaOccupancyTracker(
            listOf(
                area(
                    intervals = listOf(RailInterval(firstRail, 10.0, 90.0)),
                    boundaries = listOf(
                        boundary(entryId, firstRail, ViewDirection.AB, 10.0),
                        boundary(exitId, firstRail, ViewDirection.BA, 90.0)
                    )
                )
            )
        )

        val baseline = tracker.consume(
            observation(
                epoch = 1L,
                occupied = listOf(RailInterval(firstRail, 0.0, 5.0)),
                swept = listOf(SweptRailSpanSnapshot(firstRail, 0.0, 100.0))
            )
        ).updates.single()

        assertEquals(emptySet<String>(), baseline.occupants)
    }

    @Test
    fun `administrative placement change updates snapshot without invented crossing`() {
        val tracker = RailwayAreaOccupancyTracker(
            listOf(
                area(
                    intervals = listOf(RailInterval(firstRail, 10.0, 90.0)),
                    boundaries = listOf(
                        boundary(entryId, firstRail, ViewDirection.AB, 10.0),
                        boundary(exitId, firstRail, ViewDirection.BA, 90.0)
                    )
                )
            )
        )
        tracker.consume(observation(1L, occupied = listOf(RailInterval(firstRail, 0.0, 5.0))))

        val relocated = tracker.consume(
            observation(2L, occupied = listOf(RailInterval(firstRail, 20.0, 30.0)))
        ).updates.single()

        assertEquals(setOf("train"), relocated.occupants)
        assertEquals(emptyList<Any>(), relocated.crossings)

        val removed = tracker.consume(observation(3L, includeTrain = false)).updates.single()
        assertEquals(emptySet<String>(), removed.occupants)
        assertEquals(emptyList<Any>(), removed.crossings)
    }

    @Test
    fun `definitions are copied and consecutive coherent epochs are required`() {
        val intervals = mutableListOf(RailInterval(firstRail, 10.0, 90.0))
        val boundaries = mutableListOf(
            boundary(entryId, firstRail, ViewDirection.AB, 10.0),
            boundary(exitId, firstRail, ViewDirection.BA, 90.0)
        )
        val definition = area(intervals, boundaries)
        intervals.clear()
        boundaries.clear()

        assertEquals(1, definition.intervals.size)
        assertEquals(2, definition.boundaries.size)

        val tracker = RailwayAreaOccupancyTracker(listOf(definition))
        tracker.consume(observation(1L, occupied = emptyList()))
        assertThrows(IllegalArgumentException::class.java) {
            tracker.consume(observation(3L, occupied = emptyList()))
        }
        val newerTopology = RailwayAreaOccupancyTracker(listOf(definition))
        assertThrows(IllegalArgumentException::class.java) {
            newerTopology.consume(observation(1L, topologyRevision = 8L))
        }
        newerTopology.invalidateTopology(RailTopologyChange(lineTopologyState(7L, secondRail),
            lineTopologyState(8L, secondRail), setOf(secondRail)))
        assertTrue(newerTopology.consume(observation(1L, topologyRevision = 8L)).updates.single().available)
    }

    @Test
    fun `public engine factory exposes baseline and defensively copied updates`() {
        val engine = RailwayAreaOccupancyEngine.create(
            listOf(
                area(
                    intervals = listOf(RailInterval(firstRail, 10.0, 90.0)),
                    boundaries = listOf(
                        boundary(entryId, firstRail, ViewDirection.AB, 10.0),
                        boundary(exitId, firstRail, ViewDirection.BA, 90.0)
                    )
                )
            )
        )

        val batch = engine.consume(
            observation(1L, occupied = listOf(RailInterval(firstRail, 20.0, 30.0)))
        )
        assertEquals(setOf("train"), batch.updates.single().occupants)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (batch.updates as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (batch.updates.single().occupants as MutableSet).clear()
        }
    }

    private fun area(
        intervals: Collection<RailInterval>,
        boundaries: Collection<RailwayOccupancyBoundary>
    ): RailwayOccupancyAreaDefinition = RailwayOccupancyAreaDefinition.create(
        areaId = areaId,
        definitionRevision = 3L,
        topologyRevision = 7L,
        intervals = intervals,
        boundaries = boundaries
    )

    private fun boundary(
        id: RailObjectRef,
        railId: UUID,
        inwardFacing: ViewDirection,
        arc: Double
    ) = RailwayOccupancyBoundary(id, RailPositionSnapshot(railId, inwardFacing, arc))

    private fun observation(
        epoch: Long,
        topologyRevision: Long = 7L,
        occupied: List<RailInterval> = emptyList(),
        swept: List<SweptRailSpanSnapshot> = occupied.map {
            SweptRailSpanSnapshot(it.railId, it.minArcLengthInAbMeters, it.maxArcLengthInAbMeters)
        },
        headTraversal: List<RailTraversalSegmentSnapshot> = emptyList(),
        tailTraversal: List<RailTraversalSegmentSnapshot> = emptyList(),
        includeTrain: Boolean = true,
        worldId: String = "world"
    ): RailwayObservation {
        val frames = if (includeTrain) {
            val position = occupied.firstOrNull()?.let {
                RailPositionSnapshot(it.railId, ViewDirection.AB, it.maxArcLengthInAbMeters)
            } ?: RailPositionSnapshot(firstRail, ViewDirection.AB, 0.0)
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
                    head = position,
                    tail = position,
                    occupiedIntervals = occupied,
                    bodyTraversal = emptyList(),
                    sweptSpans = swept,
                    headTraversal = headTraversal,
                    tailTraversal = tailTraversal
                )
            )
        } else {
            emptyList()
        }
        return RailwayObservation.fromCore(
            worldId,
            RailwayWorldFrameSnapshot.create(
                epoch,
                topologyRevision,
                frames,
                ActiveRailContinuationsSnapshot.empty(topologyRevision)
            )
        )
    }
}
