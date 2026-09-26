package work.art1st.javarailways.simpleato.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyAreaDefinition
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyBoundary
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyCrossingKind
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionRouteBinding
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.signal.SignalEpochResult
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SignalEpochRuntimeTest {
    private val railId = UUID.nameUUIDFromBytes("signal-epoch-rail".toByteArray())
    private val firstAreaId = RailObjectRef("test", "first-area")
    private val secondAreaId = RailObjectRef("test", "second-area")

    @Test
    fun `empty runtime stays idle until sources are installed`() {
        val invocations = AtomicInteger()
        val runtime = runtime()

        runtime.process(frame(1L, occupied = emptyList()))
        runtime.replaceSources(listOf(source("test:first", area(firstAreaId, 10.0, 20.0)) {
            invocations.incrementAndGet()
            SignalEpochResult.empty()
        }))
        runtime.process(frame(2L, occupied = emptyList()))

        assertEquals(1, invocations.get())
    }

    @Test
    fun `replacement resets occupancy baseline for the next epoch`() {
        val received = mutableListOf<Pair<String, List<String>>>()
        val runtime = runtime(
            listOf(source("test:first", area(firstAreaId, 10.0, 20.0)) { input ->
                received += "first" to input.areaUpdates.single().occupants.toList()
                SignalEpochResult.empty()
            })
        )
        runtime.process(frame(1L, occupied = emptyList()))
        received.clear()

        runtime.replaceSources(
            listOf(source("test:second", area(secondAreaId, 50.0, 60.0)) { input ->
                received += "second" to input.areaUpdates.single().occupants.toList()
                SignalEpochResult.empty()
            })
        )
        runtime.process(frame(2L, occupied = listOf(RailInterval(railId, 55.0, 56.0))))

        assertEquals(listOf("second" to listOf("train")), received)
    }

    @Test
    fun `in progress epoch keeps one source snapshot while replacement affects the next epoch`() {
        val enteredOldHandler = CountDownLatch(1)
        val releaseOldHandler = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val runtime = runtime(
            listOf(source("test:first", area(firstAreaId, 10.0, 20.0)) {
                calls += "old"
                enteredOldHandler.countDown()
                releaseOldHandler.await()
                SignalEpochResult.empty()
            })
        )
        val processing = thread(start = true) {
            runtime.process(frame(1L, occupied = emptyList()))
        }
        enteredOldHandler.await()

        runtime.replaceSources(
            listOf(source("test:second", area(secondAreaId, 50.0, 60.0)) {
                calls += "new"
                SignalEpochResult.empty()
            })
        )
        releaseOldHandler.countDown()
        processing.join()
        runtime.process(frame(2L, occupied = emptyList()))

        assertEquals(listOf("old", "new"), calls)
    }

    @Test
    fun `invalid replacement leaves the installed source state unchanged`() {
        val calls = AtomicInteger()
        val existing = source("test:first", area(firstAreaId, 10.0, 20.0)) {
            calls.incrementAndGet()
            SignalEpochResult.empty()
        }
        val runtime = runtime(listOf(existing))
        runtime.process(frame(1L, occupied = emptyList()))

        assertThrows(IllegalArgumentException::class.java) {
            runtime.replaceSources(listOf(existing, existing))
        }
        runtime.process(frame(2L, occupied = listOf(RailInterval(railId, 12.0, 13.0))))

        assertEquals(2, calls.get())
    }

    @Test
    fun `baseline invokes source once and unchanged area leaves handler idle`() {
        val invocations = AtomicInteger()
        val runtime = runtime(
            listOf(source("test:first", area(firstAreaId, 10.0, 20.0)) {
                invocations.incrementAndGet()
                SignalEpochResult.empty()
            })
        )

        runtime.process(frame(1L, occupied = listOf(RailInterval(railId, 0.0, 5.0))))
        runtime.process(frame(2L, occupied = listOf(RailInterval(railId, 0.0, 5.0))))

        assertEquals(1, invocations.get())
    }

    @Test
    fun `same epoch ENTER and LEFT reach owning source together once`() {
        val received = mutableListOf<List<RailwayOccupancyCrossingKind>>()
        val runtime = runtime(
            listOf(source("test:first", area(firstAreaId, 10.0, 20.0)) { input ->
                received += input.areaUpdates.single().crossings.map { it.kind }
                SignalEpochResult.empty()
            })
        )
        runtime.process(frame(1L, occupied = listOf(RailInterval(railId, 0.0, 5.0))))
        received.clear()

        val traversal = listOf(RailTraversalSegmentSnapshot(railId, ViewDirection.AB, 0.0, 30.0))
        runtime.process(
            frame(
                epoch = 2L,
                occupied = listOf(RailInterval(railId, 25.0, 30.0)),
                headTraversal = traversal,
                tailTraversal = traversal
            )
        )

        assertEquals(
            listOf(listOf(RailwayOccupancyCrossingKind.ENTER, RailwayOccupancyCrossingKind.LEFT)),
            received
        )
    }

    @Test
    fun `only source owning changed area runs after baseline`() {
        val firstCalls = AtomicInteger()
        val secondCalls = AtomicInteger()
        val runtime = runtime(
            listOf(
                source("test:first", area(firstAreaId, 10.0, 20.0)) {
                    firstCalls.incrementAndGet()
                    SignalEpochResult.empty()
                },
                source("test:second", area(secondAreaId, 50.0, 60.0)) {
                    secondCalls.incrementAndGet()
                    SignalEpochResult.empty()
                }
            )
        )
        runtime.process(frame(1L, occupied = listOf(RailInterval(railId, 0.0, 5.0))))
        firstCalls.set(0)
        secondCalls.set(0)

        val traversal = listOf(RailTraversalSegmentSnapshot(railId, ViewDirection.AB, 0.0, 30.0))
        runtime.process(
            frame(
                2L,
                occupied = listOf(RailInterval(railId, 25.0, 30.0)),
                headTraversal = traversal,
                tailTraversal = traversal
            )
        )

        assertEquals(1, firstCalls.get())
        assertEquals(0, secondCalls.get())
    }

    @Test
    fun `all handler output is validated before first publication`() {
        val published = mutableListOf<TrainRestrictionSnapshot>()
        lateinit var currentFrame: RailwayWorldFrameSnapshot
        val runtime = SignalEpochRuntime(
            worldId = "world",
            publisher = TrainRestrictionPublisher { snapshot ->
                published += snapshot
                accepted(snapshot)
            }
        ).also { runtime ->
            runtime.replaceSources(listOf(
                source("test:first", area(firstAreaId, 10.0, 20.0)) {
                    SignalEpochResult(listOf(restriction("test:first", currentFrame)))
                },
                source("test:second", area(secondAreaId, 50.0, 60.0)) {
                    SignalEpochResult(listOf(restriction("test:wrong", currentFrame)))
                }
            ))
        }
        currentFrame = frame(1L, occupied = listOf(RailInterval(railId, 0.0, 5.0)))

        assertThrows(IllegalArgumentException::class.java) { runtime.process(currentFrame) }
        assertEquals(emptyList<TrainRestrictionSnapshot>(), published)
    }

    @Test
    fun `wrong observation and missing train are rejected before publication`() {
        val currentFrame = frame(1L, occupied = listOf(RailInterval(railId, 0.0, 5.0)))
        val invalidSnapshots = listOf(
            restriction(
                "test:first",
                currentFrame,
                observationStamp = RailwayStateStamp("world", 2L, currentFrame.topologyRevision)
            ),
            restriction("test:first", currentFrame, trainId = "missing")
        )

        invalidSnapshots.forEach { invalidSnapshot ->
            val published = mutableListOf<TrainRestrictionSnapshot>()
            val runtime = SignalEpochRuntime(
                worldId = "world",
                publisher = TrainRestrictionPublisher { snapshot ->
                    published += snapshot
                    accepted(snapshot)
                }
            ).also { runtime ->
                runtime.replaceSources(listOf(
                    source("test:first", area(firstAreaId, 10.0, 20.0)) {
                        SignalEpochResult(listOf(invalidSnapshot))
                    }
                ))
            }

            assertThrows(IllegalArgumentException::class.java) { runtime.process(currentFrame) }
            assertEquals(emptyList<TrainRestrictionSnapshot>(), published)
        }
    }

    @Test
    fun `duplicate train output is rejected before publication`() {
        val currentFrame = frame(1L, occupied = listOf(RailInterval(railId, 0.0, 5.0)))
        val published = mutableListOf<TrainRestrictionSnapshot>()
        val runtime = SignalEpochRuntime(
            worldId = "world",
            publisher = TrainRestrictionPublisher { snapshot ->
                published += snapshot
                accepted(snapshot)
            }
        ).also { runtime ->
            runtime.replaceSources(listOf(
                source("test:first", area(firstAreaId, 10.0, 20.0)) {
                    SignalEpochResult(
                        listOf(
                            restriction("test:first", currentFrame, revision = 1L),
                            restriction("test:first", currentFrame, revision = 2L)
                        )
                    )
                }
            ))
        }

        assertThrows(IllegalArgumentException::class.java) { runtime.process(currentFrame) }
        assertEquals(emptyList<TrainRestrictionSnapshot>(), published)
    }

    @Test
    fun `publications use stable source order`() {
        lateinit var currentFrame: RailwayWorldFrameSnapshot
        val published = mutableListOf<String>()
        val runtime = SignalEpochRuntime(
            worldId = "world",
            publisher = TrainRestrictionPublisher { snapshot ->
                published += snapshot.sourceId
                accepted(snapshot)
            }
        ).also { runtime ->
            runtime.replaceSources(listOf(
                source("test:second", area(secondAreaId, 50.0, 60.0)) {
                    SignalEpochResult(listOf(restriction("test:second", currentFrame)))
                },
                source("test:first", area(firstAreaId, 10.0, 20.0)) {
                    SignalEpochResult(listOf(restriction("test:first", currentFrame)))
                }
            ))
        }
        currentFrame = frame(1L, occupied = listOf(RailInterval(railId, 0.0, 5.0)))

        runtime.process(currentFrame)

        assertEquals(listOf("test:first", "test:second"), published)
    }

    @Test
    fun `publisher rejection fails the epoch callback`() {
        lateinit var currentFrame: RailwayWorldFrameSnapshot
        val runtime = SignalEpochRuntime(
            worldId = "world",
            publisher = TrainRestrictionPublisher {
                TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.STALE_REVISION, 9L)
            }
        ).also { runtime ->
            runtime.replaceSources(listOf(
                source("test:first", area(firstAreaId, 10.0, 20.0)) {
                    SignalEpochResult(listOf(restriction("test:first", currentFrame)))
                }
            ))
        }
        currentFrame = frame(1L, occupied = listOf(RailInterval(railId, 0.0, 5.0)))

        assertThrows(IllegalStateException::class.java) { runtime.process(currentFrame) }
    }

    @Test
    fun `publisher exception escapes for Core to stall fail closed`() {
        lateinit var currentFrame: RailwayWorldFrameSnapshot
        val runtime = SignalEpochRuntime(
            worldId = "world",
            publisher = TrainRestrictionPublisher { error("publisher failed") }
        ).also { runtime ->
            runtime.replaceSources(listOf(
                source("test:first", area(firstAreaId, 10.0, 20.0)) {
                    SignalEpochResult(listOf(restriction("test:first", currentFrame)))
                }
            ))
        }
        currentFrame = frame(1L, occupied = listOf(RailInterval(railId, 0.0, 5.0)))

        val failure = assertThrows(IllegalStateException::class.java) { runtime.process(currentFrame) }
        assertEquals("publisher failed", failure.message)
    }

    @Test
    fun `handler exception escapes for Core to stall fail closed`() {
        val runtime = runtime(
            listOf(source("test:first", area(firstAreaId, 10.0, 20.0)) {
                error("signal implementation failed")
            })
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            runtime.process(frame(1L, occupied = emptyList()))
        }
        assertEquals("signal implementation failed", failure.message)
    }

    @Test
    fun `public source result and callback collections are defensive`() {
        val definitions = mutableListOf(area(firstAreaId, 10.0, 20.0))
        val currentFrame = frame(1L, occupied = emptyList())
        val snapshots = mutableListOf(restriction("test:first", currentFrame))
        var callbackListWasImmutable = false
        val source = SignalEpochSource("test:first", definitions) { input ->
            assertThrows(UnsupportedOperationException::class.java) {
                @Suppress("UNCHECKED_CAST")
                (input.areaUpdates as MutableList).clear()
            }
            callbackListWasImmutable = true
            SignalEpochResult.empty()
        }
        definitions.clear()
        val result = SignalEpochResult(snapshots)
        snapshots.clear()

        assertEquals(1, source.areas.size)
        assertEquals(1, result.publications.size)
        runtime(listOf(source)).process(currentFrame)
        assertEquals(true, callbackListWasImmutable)
    }

    @Test
    fun `live train retention runs only after a successful epoch`() {
        val retained = mutableListOf<Set<String>>()
        val runtime = SignalEpochRuntime(
            "world",
            TrainRestrictionPublisher(::accepted),
            retained::add
        )
        runtime.replaceSources(listOf(source("test:first", area(firstAreaId, 10.0, 20.0)) {
            SignalEpochResult.empty()
        }))
        runtime.process(frame(1L, occupied = emptyList()))
        runtime.replaceSources(listOf(source("test:first", area(firstAreaId, 10.0, 20.0)) {
            error("signal failure")
        }))

        assertThrows(IllegalStateException::class.java) {
            runtime.process(frame(2L, occupied = emptyList()))
        }

        assertEquals(listOf(setOf("train")), retained)
    }

    private fun runtime(sources: Collection<SignalEpochSource> = emptyList()): SignalEpochRuntime = SignalEpochRuntime(
        "world",
        TrainRestrictionPublisher(::accepted)
    ).also { it.replaceSources(sources) }

    private fun source(
        sourceId: String,
        area: RailwayOccupancyAreaDefinition,
        update: (work.art1st.javarailways.simpleato.api.signal.SignalEpochInput) -> SignalEpochResult
    ): SignalEpochSource = SignalEpochSource(sourceId, listOf(area), update)

    private fun area(id: RailObjectRef, start: Double, end: Double): RailwayOccupancyAreaDefinition =
        RailwayOccupancyAreaDefinition.create(
            areaId = id,
            definitionRevision = 1L,
            topologyRevision = 7L,
            intervals = listOf(RailInterval(railId, start, end)),
            boundaries = listOf(
                RailwayOccupancyBoundary(
                    RailObjectRef("test", "${id.id}-entry"),
                    RailPositionSnapshot(railId, ViewDirection.AB, start)
                ),
                RailwayOccupancyBoundary(
                    RailObjectRef("test", "${id.id}-exit"),
                    RailPositionSnapshot(railId, ViewDirection.BA, end)
                )
            )
        )

    private fun frame(
        epoch: Long,
        occupied: List<RailInterval>,
        headTraversal: List<RailTraversalSegmentSnapshot> = emptyList(),
        tailTraversal: List<RailTraversalSegmentSnapshot> = emptyList()
    ): RailwayWorldFrameSnapshot {
        val position = occupied.firstOrNull()?.let {
            RailPositionSnapshot(it.railId, ViewDirection.AB, it.maxArcLengthInAbMeters)
        } ?: RailPositionSnapshot(railId, ViewDirection.AB, 0.0)
        return RailwayWorldFrameSnapshot.create(
            epoch,
            7L,
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

    private fun restriction(
        sourceId: String,
        frame: RailwayWorldFrameSnapshot,
        revision: Long = 1L,
        trainId: String = "train",
        observationStamp: RailwayStateStamp = RailwayStateStamp(
            "world",
            frame.physicsEpoch,
            frame.topologyRevision
        )
    ): TrainRestrictionSnapshot =
        TrainRestrictionSnapshot(
            sourceId = sourceId,
            revision = revision,
            trainId = trainId,
            observationStamp = observationStamp,
            route = TrainRestrictionRouteBinding("route", 1L, frame.topologyRevision, 100.0),
            restrictions = emptyList()
        )

    private fun accepted(snapshot: TrainRestrictionSnapshot): TrainRestrictionPublicationResult =
        TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, snapshot.revision)
}
