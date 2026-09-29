package work.art1st.javarailways.basic.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
        val wrongTopology = RailwayAreaOccupancyTracker(listOf(definition))
        assertThrows(IllegalArgumentException::class.java) {
            wrongTopology.consume(observation(1L, topologyRevision = 8L, occupied = emptyList()))
        }
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
        includeTrain: Boolean = true
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
            "world",
            RailwayWorldFrameSnapshot.create(
                epoch,
                topologyRevision,
                frames,
                ActiveRailContinuationsSnapshot.empty(topologyRevision)
            )
        )
    }
}
