package work.art1st.javarailways.simpleato.signal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyAreaDefinition
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyBoundary
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyCrossingKind
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionSnapshot
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionState
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionUpdate
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.signal.SignalEpochResult
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SignalEpochRuntimeTest {
    @Test
    fun `direction changes coalesce and wake only sources watching actually changed rails`() {
        val other = UUID.randomUUID()
        val received = linkedMapOf<String, MutableList<Set<UUID>>>()
        fun source(id: String, rail: UUID) = SignalEpochSource(id, emptyList(), emptyList(), emptyList(), listOf(rail)) {
            received.getOrPut(id, ::arrayListOf).add(it.changedOperatingDirectionRailIds)
            assertThrows(UnsupportedOperationException::class.java) { (it.changedOperatingDirectionRailIds as MutableSet).clear() }
            SignalEpochResult.empty()
        }
        val runtime = runtime(listOf(source("test:first", railId), source("test:other", other)))
        val store = work.art1st.javarailways.simpleato.routing.OperatingDirectionStore(runtime::publishOperatingDirectionChanges)
        runtime.process(frame(1L, emptyList()))
        store.put(railId, ViewDirection.AB)
        store.put(railId, ViewDirection.BA)
        runtime.process(frame(2L, emptyList()))
        assertEquals(listOf(emptySet<UUID>(), setOf(railId)), received["test:first"])
        assertEquals(1, received.getValue("test:other").size)
        store.put(railId, ViewDirection.BA)
        store.put(UUID.randomUUID(), ViewDirection.AB)
        runtime.process(frame(3L, emptyList()))
        assertEquals(2, received.getValue("test:first").size)
        assertEquals(1, received.getValue("test:other").size)
    }

    private val railId = UUID.nameUUIDFromBytes("signal-epoch-rail".toByteArray())
    private val firstAreaId = RailObjectRef("test", "first-area")
    private val secondAreaId = RailObjectRef("test", "second-area")

    @Test
    fun `replacement baseline includes unchanged area crossings and full new area baseline then idles`() {
        val received = mutableListOf<work.art1st.javarailways.simpleato.api.signal.SignalEpochInput>()
        val definition = area(firstAreaId, 10.0, 20.0)
        val runtime = runtime(listOf(source("test:first", definition) {
            received += it
            SignalEpochResult.empty()
        }))
        runtime.process(frame(1L, listOf(RailInterval(railId, 15.0, 16.0))))
        val replacement = SignalEpochSource("test:first", listOf(definition, area(secondAreaId, 10.0, 20.0))) {
            received += it
            SignalEpochResult.empty()
        }
        runtime.replaceSources(listOf(replacement))
        runtime.replaceSources(listOf(replacement))
        runtime.process(frame(2L, listOf(RailInterval(railId, 21.0, 22.0)),
            tailTraversal = listOf(RailTraversalSegmentSnapshot(railId, ViewDirection.AB, 16.0, 22.0))))
        val baseline = received.last()
        assertEquals(true, baseline.topologyBaseline)
        assertEquals(2, baseline.areaUpdates.size)
        assertEquals(listOf(RailwayOccupancyCrossingKind.LEFT),
            baseline.areaUpdates.single { it.areaId == firstAreaId }.crossings.map { it.kind })
        assertEquals(emptyList<Any>(), baseline.areaUpdates.single { it.areaId == secondAreaId }.crossings)
        runtime.process(frame(3L, listOf(RailInterval(railId, 21.0, 22.0))))
        assertEquals(2, received.size)
    }

    @Test
    fun `admission only source receives baseline latest owner change and old route removal`() {
        val otherRail = UUID.nameUUIDFromBytes("other-admission-rail".toByteArray())
        val received = linkedMapOf<String, MutableList<List<ActiveRouteAdmissionUpdate>>>()
        fun watching(id: String, rail: UUID) = SignalEpochSource(id, emptyList(), emptyList(), listOf(rail)) { input ->
            assertThrows(UnsupportedOperationException::class.java) { (input.admissionUpdates as MutableList).clear() }
            received.getOrPut(id, ::arrayListOf) += input.admissionUpdates
            SignalEpochResult.empty()
        }
        val first = watching("test:first", railId)
        val second = watching("test:second", otherRail)
        val runtime = runtime(listOf(first, second))
        runtime.process(frame(1L, emptyList()))
        assertEquals(1, received.getValue("test:first").size)
        assertEquals(1, received.getValue("test:second").size)
        val owner = InfrastructureOwner("test:admission", 1L)
        val route = RouteSnapshot("test:route", 1L, 7L, listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 100.0)))
        fun admission(route: RouteSnapshot, end: Double) = ActiveRouteAdmissionSnapshot(owner, "train", route,
            ActiveRouteAdmissionState.GRANTED, RouteBoundary(end), RouteBoundary(end))
        runtime.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(route, 20.0)))
        runtime.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(route, 40.0)))
        runtime.process(frame(2L, emptyList()))
        assertEquals(2, received.getValue("test:first").size)
        assertEquals(RouteBoundary(40.0), received.getValue("test:first").last().single().current?.committedAuthority)
        assertEquals(1, received.getValue("test:second").size)
        runtime.process(frame(3L, emptyList()))
        assertEquals(2, received.getValue("test:first").size)
        val rebased = RouteSnapshot("test:route", 2L, 7L, listOf(RouteSegmentSnapshot(otherRail, ViewDirection.BA, 0.0, 100.0)))
        runtime.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(rebased, 30.0)))
        runtime.process(frame(4L, emptyList()))
        assertEquals(3, received.getValue("test:first").size)
        assertEquals(2, received.getValue("test:second").size)
        runtime.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, null))
        runtime.process(frame(5L, emptyList()))
        assertEquals(3, received.getValue("test:first").size)
        assertEquals(null, received.getValue("test:second").last().single().current)
        runtime.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(route, 50.0)))
        runtime.replaceSources(listOf(first))
        runtime.process(frame(6L, emptyList()))
        assertEquals(RouteBoundary(50.0), received.getValue("test:first").last().single().current?.committedAuthority)
    }

    @Test
    fun `replacement baseline retains first watched route for coalesced change and removal`() {
        val otherRail = UUID.nameUUIDFromBytes("replacement-admission-rail".toByteArray())
        val received = arrayListOf<List<ActiveRouteAdmissionUpdate>>()
        val source = SignalEpochSource("test:replacement", emptyList(), emptyList(), listOf(railId)) {
            received += it.admissionUpdates
            SignalEpochResult.empty()
        }
        val runtime = runtime(listOf(source))
        val owner = InfrastructureOwner("test:admission", 1L)
        fun admission(rail: UUID) = ActiveRouteAdmissionSnapshot(owner, "train",
            RouteSnapshot("test:route", 1L, 7L,
                listOf(RouteSegmentSnapshot(rail, ViewDirection.AB, 0.0, 100.0))),
            ActiveRouteAdmissionState.GRANTED, RouteBoundary(20.0), RouteBoundary(20.0))
        runtime.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(railId)))
        runtime.process(frame(1L, emptyList()))
        runtime.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(otherRail)))
        runtime.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, null))
        runtime.replaceSources(listOf(source))
        runtime.process(frame(2L, emptyList()))
        assertEquals(listOf(ActiveRouteAdmissionUpdate(owner, null)), received.last())
        runtime.process(frame(3L, emptyList()))
        assertEquals(2, received.size)
    }

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
    fun `atomic replacement validates before callback and callback failure retains old state`() {
        val calls = mutableListOf<String>()
        val existing = source("test:first", area(firstAreaId, 10.0, 20.0)) {
            calls += "old"
            SignalEpochResult.empty()
        }
        val replacement = source("test:second", area(secondAreaId, 50.0, 60.0)) {
            calls += "new"
            SignalEpochResult.empty()
        }
        val runtime = runtime(listOf(existing))
        var invalidCallbackCalls = 0

        assertThrows(IllegalArgumentException::class.java) {
            runtime.replaceSourcesAtomically(listOf(existing, existing)) {
                invalidCallbackCalls++
            }
        }
        assertEquals(0, invalidCallbackCalls)
        assertThrows(IllegalStateException::class.java) {
            runtime.replaceSourcesAtomically(listOf(replacement)) {
                throw IllegalStateException("commit failed")
            }
        }

        runtime.process(frame(1L, occupied = emptyList()))
        assertEquals(listOf("old"), calls)
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
    fun `continuation revision wakes only sources watching an actually changed entry`() {
        val firstEntry = RailEndpointRef(railId, RailEndpoint.B)
        val secondRail = UUID.nameUUIDFromBytes("second-watched-rail".toByteArray())
        val secondEntry = RailEndpointRef(secondRail, RailEndpoint.B)
        val firstExit = RailEndpointRef(secondRail, RailEndpoint.A)
        val alternateExit = RailEndpointRef(
            UUID.nameUUIDFromBytes("alternate-exit-rail".toByteArray()),
            RailEndpoint.A
        )
        val calls = mutableListOf<Pair<String, List<Pair<RailEndpointRef, RailEndpointRef?>>>>()
        val runtime = runtime(
            listOf(
                SignalEpochSource("test:first", emptyList(), listOf(firstEntry)) { input ->
                    calls += "first" to input.continuationUpdates.map {
                        it.entry to it.selectedExit
                    }
                    SignalEpochResult.empty()
                },
                SignalEpochSource("test:second", emptyList(), listOf(secondEntry)) { input ->
                    calls += "second" to input.continuationUpdates.map {
                        it.entry to it.selectedExit
                    }
                    SignalEpochResult.empty()
                }
            )
        )

        runtime.process(
            frame(
                1L,
                emptyList(),
                activeContinuations = continuations(
                    1L,
                    mapOf(firstEntry to firstExit, secondEntry to firstEntry)
                )
            )
        )
        assertEquals(
            listOf(
                "first" to listOf(firstEntry to firstExit),
                "second" to listOf(secondEntry to firstEntry)
            ),
            calls
        )

        calls.clear()
        runtime.process(
            frame(
                2L,
                emptyList(),
                activeContinuations = continuations(
                    1L,
                    mapOf(firstEntry to firstExit, secondEntry to firstEntry)
                )
            )
        )
        assertEquals(emptyList<Pair<String, List<Pair<RailEndpointRef, RailEndpointRef?>>>>(), calls)

        runtime.process(
            frame(
                3L,
                emptyList(),
                activeContinuations = continuations(
                    2L,
                    mapOf(firstEntry to alternateExit, secondEntry to firstEntry)
                )
            )
        )
        assertEquals(listOf("first" to listOf(firstEntry to alternateExit)), calls)

        calls.clear()
        val unwatchedEntry = RailEndpointRef(
            UUID.nameUUIDFromBytes("unwatched-rail".toByteArray()),
            RailEndpoint.B
        )
        runtime.process(
            frame(
                4L,
                emptyList(),
                activeContinuations = continuations(
                    3L,
                    mapOf(
                        firstEntry to alternateExit,
                        secondEntry to firstEntry,
                        unwatchedEntry to firstExit
                    )
                )
            )
        )
        assertEquals(emptyList<Pair<String, List<Pair<RailEndpointRef, RailEndpointRef?>>>>(), calls)
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
        val invalidated = mutableListOf<String>()
        lateinit var currentFrame: RailwayWorldFrameSnapshot
        val runtime = SignalEpochRuntime(
            worldId = "world",
            publisher = TrainRestrictionPublisher { snapshot ->
                published += snapshot
                accepted(snapshot)
            },
            invalidateInspectionDeclarations = { sourceId, _ -> invalidated += sourceId }
        ).also { runtime ->
            runtime.replaceSources(listOf(
                source("test:first", area(firstAreaId, 10.0, 20.0)) {
                    SignalEpochResult(
                        listOf(restriction("test:first", currentFrame)),
                        listOf("test:first-signal")
                    )
                },
                source("test:second", area(secondAreaId, 50.0, 60.0)) {
                    SignalEpochResult(listOf(restriction("test:wrong", currentFrame)))
                }
            ))
        }
        currentFrame = frame(1L, occupied = listOf(RailInterval(railId, 0.0, 5.0)))

        assertThrows(IllegalArgumentException::class.java) { runtime.process(currentFrame) }
        assertEquals(emptyList<TrainRestrictionSnapshot>(), published)
        assertEquals(emptyList<String>(), invalidated)
    }

    @Test
    fun `inspection invalidations publish in stable source order after validation`() {
        val invalidated = mutableListOf<Pair<String, Set<String>>>()
        val runtime = SignalEpochRuntime(
            worldId = "world",
            publisher = TrainRestrictionPublisher(::accepted),
            invalidateInspectionDeclarations = { sourceId, declarationIds ->
                invalidated += sourceId to declarationIds
            }
        ).also { runtime ->
            runtime.replaceSources(listOf(
                source("test:second", area(secondAreaId, 50.0, 60.0)) {
                    SignalEpochResult(emptyList(), listOf("test:second-signal"))
                },
                source("test:first", area(firstAreaId, 10.0, 20.0)) {
                    SignalEpochResult(emptyList(), listOf("test:first-b", "test:first-a"))
                }
            ))
        }

        runtime.process(frame(1L, occupied = emptyList()))

        assertEquals(
            listOf(
                "test:first" to setOf("test:first-b", "test:first-a"),
                "test:second" to setOf("test:second-signal")
            ),
            invalidated
        )
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
        val declarationIds = mutableListOf("test:signal")
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
        val result = SignalEpochResult(snapshots, declarationIds)
        snapshots.clear()
        declarationIds.clear()

        assertEquals(1, source.areas.size)
        assertEquals(1, result.publications.size)
        assertEquals(setOf("test:signal"), result.invalidatedInspectionDeclarationIds)
        runtime(listOf(source)).process(currentFrame)
        assertEquals(true, callbackListWasImmutable)
    }

    @Test
    fun `live train retention runs only after a successful epoch`() {
        val retained = mutableListOf<Set<String>>()
        val runtime = SignalEpochRuntime(
            "world",
            TrainRestrictionPublisher(::accepted),
            { _, liveTrainIds -> retained += liveTrainIds }
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

    @Test
    fun `empty source state still completes the observation boundary`() {
        val completed = mutableListOf<Pair<RailwayStateStamp, Set<String>>>()
        val runtime = SignalEpochRuntime(
            "world",
            TrainRestrictionPublisher(::accepted),
            { stamp, liveTrainIds -> completed += stamp to liveTrainIds }
        )

        runtime.process(frame(3L, occupied = emptyList()))

        assertEquals(
            listOf(RailwayStateStamp("world", 3L, 7L) to setOf("train")),
            completed
        )
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
        tailTraversal: List<RailTraversalSegmentSnapshot> = emptyList(),
        activeContinuations: ActiveRailContinuationsSnapshot =
            ActiveRailContinuationsSnapshot.empty(7L)
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
            ),
            activeContinuations
        )
    }

    private fun continuations(
        selectionRevision: Long,
        selections: Map<RailEndpointRef, RailEndpointRef>
    ): ActiveRailContinuationsSnapshot = ActiveRailContinuationsSnapshot.create(
        7L,
        selectionRevision,
        selections
    )

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
            route = FrozenRouteBinding("route", 1L, frame.topologyRevision, 100.0),
            restrictions = emptyList()
        )

    private fun accepted(snapshot: TrainRestrictionSnapshot): TrainRestrictionPublicationResult =
        TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, snapshot.revision)
}
