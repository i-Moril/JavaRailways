package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import kotlinx.serialization.json.JsonObject
import work.art1st.javarailways.simpleato.api.timetable.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.api.depot.*
import work.art1st.javarailways.simpleato.api.station.*
import work.art1st.javarailways.simpleato.api.destination.*
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import java.util.concurrent.TimeUnit

class StructuralTimetableValidationTest {
    private fun at(action: TimetableAction, tick: Long = 0) = TimetableStep.At(tick, action)
    private fun output(id: String, duty: String? = null) = TimetableFormationOutput(id,
        duty?.let { FormationOutputDisposition.Duty(it) } ?: FormationOutputDisposition.Hold)
    private fun definition() = TimetableFormationEvent.Split("divide", "source", 1, output("first", "source"), output("rest", "branch"))
    private fun timetable(sourceId: String = "physical", sourceSteps: List<TimetableStep> = listOf(TimetableStep.Check("origin"),
        at(TimetableAction.Split("divide")), at(TimetableAction.GoTo("end"))),
        branchTarget: String = "back", branchVia: List<String> = emptyList()) = Timetable("structural", services = listOf(
        TimetableService("source", TrainSelector.TrainId(sourceId), sourceSteps),
        TimetableService("branch", steps = listOf(at(TimetableAction.TakeFromFormationOutput("divide", "rest")),
            at(TimetableAction.Reverse), at(if (branchVia.isEmpty()) TimetableAction.GoTo(branchTarget) else TimetableAction.GoVia(branchVia + branchTarget))))
    ), formationEvents = TimetableFormationCatalog(listOf(definition())))

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `initial checked split retains both output routes and resolves actual Core halves in opposite directions`(reversed: Boolean) {
        fixture { world, rail, train ->
            if (reversed) world.onOwnerForTest { train.reverse() }
            val initial = train.publishedFrame
            val origin = if (reversed) "reverse-origin" else "origin"
            if (reversed) world.basicWorldData.wayPoints.add(WayPoint(origin, origin, rail.id, initial.head.arcLengthInAbMeters))
            val sourceSteps = listOf(TimetableStep.Check(origin), at(TimetableAction.Split("divide")),
                at(TimetableAction.GoTo(if (reversed) "back" else "end")))
            val result = TimetableValidator(world).validate(timetable(sourceSteps = sourceSteps,
                branchTarget = if (reversed) "end" else "back"))
            assertTrue(result.isSuccess, result.errors.toString())
            assertEquals(2, result.structural.routes.size)
            val ingress = result.structural.sources.values.single()
            assertTrue(ingress.window.acceptsSource(initial, world.topologyState))
            ingress.envelope.requireContains(initial, world.topologyState, ingress.sourceCarIds, ingress.expectedReversed)
            val child = split(world, train, "car-2", "child")
            val first = if (reversed) child else train
            val rest = if (reversed) train else child
            world.onOwnerForTest { rest.reverse() }
            for ((key, evidence) in result.structural.routes) {
                val frame = if (key.outputId == "first") first.publishedFrame else rest.publishedFrame
                val resolved = evidence.proof.resolve(frame, world.topologyState, evidence.outputCarIds, evidence.expectedReversed)
                assertTrue(resolved.admission.totalLengthMeters > resolved.movement.totalLengthMeters)
                assertEquals(1.0, evidence.stoppingOffsetFromRetainedEndMeters)
            }
        }
    }

    @Test fun `named source selector resolves the actual container frame`() {
        fixture { world, _, train ->
            world.onOwnerForTest {
                world.physicsCoordinator.withStructuralMutation { world.renameTrain(train, "scheduled") }
            }
            assertNotEquals("scheduled", train.id)
            assertSame(train, world.getTrain("scheduled"))
            val result = TimetableValidator(world).validate(timetable(sourceId = "scheduled"))
            assertTrue(result.isSuccess, result.errors.toString())
            assertEquals(setOf(train.id), result.structural.sources.keys.map { it.sourceCandidate.initialTrainId }.toSet())
            assertEquals(2, result.structural.routes.size)
        }
    }

    @Test fun `entire retained incoming route reaches full eligibility bound before split`() {
        fixture(points = setOf("stop")) { world, _, _ ->
            val steps = listOf(TimetableStep.Check("origin"), at(TimetableAction.GoTo("stop")),
                at(TimetableAction.Split("divide")), at(TimetableAction.GoTo("end")))
            val result = TimetableValidator(world).validate(timetable(sourceSteps = steps))
            assertTrue(result.isSuccess, result.errors.toString())
            val ingress = result.structural.sources.values.single()
            assertEquals(101.0, ingress.window.forwardBoundary.arcLengthInAbMeters)
            assertEquals(101.0, ingress.envelope.retainedPath.segments.last().endArcLengthMeters)
            assertTrue(ingress.envelope.retainedPath.segments.first().startArcLengthMeters > 40.0,
                "Core-certified stop envelope may remove distant historical source body")
            assertEquals(2, result.structural.routes.size)
        }
    }

    @Test fun `producer output proof propagates through new duty movement and a later split`() {
        fixture(8, 140.0, points = setOf("later")) { world, rail, train ->
            val first = TimetableFormationEvent.Split("one", "a", 1, output("first"), output("rest", "b"))
            val second = TimetableFormationEvent.Split("two", "b", 1, output("first"), output("rest", "c"))
            val plan = Timetable("chain", services = listOf(
                TimetableService("a", TrainSelector.TrainId(train.id), listOf(TimetableStep.Check("origin"), at(TimetableAction.Split("one")))),
                TimetableService("b", steps = listOf(at(TimetableAction.TakeFromFormationOutput("one", "rest")),
                    at(TimetableAction.GoTo("later")), at(TimetableAction.Split("two")))),
                TimetableService("c", steps = listOf(at(TimetableAction.TakeFromFormationOutput("two", "rest")),
                    at(TimetableAction.Reverse), at(TimetableAction.GoTo("back"))))
            ), formationEvents = TimetableFormationCatalog(listOf(first,second)))
            val result = TimetableValidator(world).validate(plan)
            assertTrue(result.isSuccess, result.errors.toString())
            assertEquals(2, result.structural.sources.size)
            assertEquals(2, result.structural.routes.size)
            val secondSource = result.structural.sources.entries.single { it.key.eventId == "two" }.value
            val b = split(world, train, "car-2", "b-physical")
            world.onOwnerForTest { b.setLocation(rail.viewAB.getRailLocationAt(180.0)); b.commit() }
            assertTrue(secondSource.window.acceptsSource(b.publishedFrame, world.topologyState))
            secondSource.envelope.requireContains(b.publishedFrame, world.topologyState,
                secondSource.sourceCarIds, secondSource.expectedReversed)
            val c = split(world, b, "car-4", "c-physical")
            world.onOwnerForTest { c.reverse() }
            val departure = result.structural.routes.entries.single { it.key.eventId == "two" }.value
            assertEquals(listOf("car-4","car-5","car-6","car-7"), departure.outputCarIds)
            assertDoesNotThrow { departure.proof.resolve(c.publishedFrame, world.topologyState,
                departure.outputCarIds, departure.expectedReversed) }
        }
    }

    @Test fun `common outbound and return route uses certified stopped body instead of historical origin`() {
        fixture(points = setOf("stop", "later")) { world, rail, train ->
            val steps = listOf(TimetableStep.Check("origin"), at(TimetableAction.GoVia(listOf("stop", "later"))),
                at(TimetableAction.Split("divide")), at(TimetableAction.GoTo("end")))
            val result = TimetableValidator(world).validate(timetable(sourceSteps = steps, branchTarget = "origin", branchVia = listOf("stop")))
            assertTrue(result.isSuccess, result.errors.toString())
            val ingress = result.structural.sources.values.single()
            assertTrue(ingress.envelope.retainedPath.segments.first().startArcLengthMeters > 100.0)
            world.onOwnerForTest { train.setLocation(rail.viewAB.getRailLocationAt(180.0)); train.commit() }
            ingress.envelope.requireContains(train.publishedFrame, world.topologyState,
                ingress.sourceCarIds, ingress.expectedReversed)
            val branch = split(world, train, "car-2", "returning")
            world.onOwnerForTest { branch.reverse() }
            val proof = result.structural.routes.entries.single { it.key.outputId == "rest" }.value
            assertEquals("origin", result.structural.routes.entries.single { it.key.outputId == "rest" }.key.targetId)
            assertDoesNotThrow { proof.proof.resolve(branch.publishedFrame, world.topologyState,
                proof.outputCarIds, proof.expectedReversed) }
        }
    }

    @Test fun `missing finite origin witness and target inside uncertainty reject explicitly`() {
        fixture(points = setOf("inside")) { world, _, _ ->
            val missing = TimetableValidator(world).validate(timetable(sourceId = "missing"))
            assertFalse(missing.isSuccess)
            assertTrue(missing.errors.any { it.contains("initial source frame is missing") })
            val inside = TimetableValidator(world).validate(timetable(branchTarget = "inside"))
            assertFalse(inside.isSuccess)
            assertTrue(inside.errors.any { it.contains("no retained onward-route proof") })
        }
    }

    @Test fun `every finite acquisition origin must have a full body witness`() {
        fixture { world, rail, train ->
            world.basicWorldData.wayPoints.add(WayPoint("other", "Other", rail.id, 120.0))
            fun plan(ids: List<String>) = Timetable("origins", services = listOf(TimetableService("source", steps = listOf(
                at(TimetableAction.TakeFromWaypoint(ids)), at(TimetableAction.Split("divide")), at(TimetableAction.GoVia(listOf("other", "end")))))),
                formationEvents = TimetableFormationCatalog(listOf(TimetableFormationEvent.Split("divide", "source", 1,
                    output("first", "source"), output("rest")))))
            val complete = TimetableValidator(world).validate(plan(listOf("origin")))
            assertTrue(complete.isSuccess, complete.errors.toString())
            val partial = TimetableValidator(world).validate(plan(listOf("origin", "other")))
            assertFalse(partial.isSuccess)
            assertTrue(partial.errors.any { it.contains("other") && it.contains("full initial-body witness") })
            assertEquals(1, world.allTrains.size)
            assertEquals(4, train.cars.size)
        }
    }

    @Test fun `operating direction and structural marker window remain binding`() {
        fixture { world, _, train ->
            world.basicWorldData.operatingDirections.put(train.publishedFrame.head.railId, ViewDirection.AB)
            val forbidden = TimetableValidator(world).validate(timetable())
            assertFalse(forbidden.isSuccess)
            val marker = StructuralEventWindow.at(train.publishedFrame.head, world.topologyState, 0.1)
            assertTrue(marker.acceptsSource(train.publishedFrame, world.topologyState))
            assertFalse(marker.contains(train.publishedFrame.head.copy(arcLengthInAbMeters = 80.2), world.topologyState))
            assertFalse(marker.contains(train.publishedFrame.head.copy(facing = ViewDirection.BA), world.topologyState))
        }
    }

    private fun registerSource(world: CoreWorld, train: CoreTrain) {
        world.simpleAtoWorldData.depots.registerRollingStock(RollingStockRecord(train.id, DepotId("home"),
            "metro/model", setOf("metro"), train.cars.size, train.cars.sumOf { it.length }, 22.0, setOf("doors")),
            StablingPointRef(DepotId("home"), "point"))
    }
    private fun storagePoint(world: CoreWorld, id: String, target: String, length: Double? = 30.0,
        tags: Set<String> = setOf("metro")): StablingPointRef {
        val depot = DepotId("storage")
        val ref = StablingPointRef(depot, id)
        world.simpleAtoWorldData.depots.addStablingPoint(depot, "Storage", DepotStablingPoint(ref, target, length, tags))
        return ref
    }
    private fun storagePlan(points: Set<String> = emptySet(), newDuty: Boolean = false): Timetable {
        val event = TimetableFormationEvent.Split("divide", "source", 1,
            output("first", if (newDuty) null else "source"), output("rest", if (newDuty) "branch" else null))
        val store = at(TimetableAction.StoreAtDepot(DepotId("storage"), points))
        val steps = listOf(TimetableStep.Check("origin"), at(TimetableAction.Split("divide"))) +
            if (newDuty) emptyList() else listOf(store)
        val services = listOf(TimetableService("source", TrainSelector.TrainId("physical"), steps)) +
            if (newDuty) listOf(TimetableService("branch", steps = listOf(
                at(TimetableAction.TakeFromFormationOutput("divide", "rest")), at(TimetableAction.Reverse), store))) else emptyList()
        return Timetable("storage", services = services, formationEvents = TimetableFormationCatalog(listOf(event)))
    }

    @Test fun `output storage proves every compatible finite target including currently occupied alternatives`() {
        fixture { world, rail, train ->
            registerSource(world, train)
            val end = storagePoint(world, "end", "end")
            world.basicWorldData.wayPoints.add(WayPoint("later", "Later", rail.id, 200.0))
            val later = storagePoint(world, "later", "later")
            world.simpleAtoWorldData.depots.registerRollingStock(RollingStockRecord("parked", DepotId("storage"),
                "metro/model", setOf("metro"), 2, 20.0, 22.0, emptySet()), later)
            val before = world.simpleAtoWorldData.depots.stablingPoints(DepotId("storage"))
            val result = TimetableValidator(world).validate(storagePlan())
            assertTrue(result.isSuccess, result.errors.toString())
            assertEquals(setOf(end, later), result.structural.storageRoutes.keys.map { it.stablingPointRef }.toSet())
            assertEquals(2, result.structural.routes.size)
            val child = split(world, train, "car-2", "rest")
            assertEquals(2, child.cars.size)
            result.structural.storageRoutes.forEach { (key, storage) ->
                assertEquals("first", key.outputId)
                assertEquals(2, key.stepIndex)
                assertEquals(20.0, storage.metadata.lengthMeters)
                assertEquals(setOf("doors"), storage.metadata.capabilities)
                assertEquals("metro/model", storage.metadata.toRecord(train.id).modelPackageId)
                assertDoesNotThrow { storage.movement.proof.resolve(train.publishedFrame, world.topologyState,
                    storage.movement.outputCarIds, storage.movement.expectedReversed) }
            }
            assertEquals(before, world.simpleAtoWorldData.depots.stablingPoints(DepotId("storage")))
        }
    }

    @Test fun `output storage filters actual output length and classification before proving all admissible points`() {
        fixture(points = setOf("inside")) { world, _, train ->
            registerSource(world, train)
            val eligible = storagePoint(world, "fits", "end", 20.0)
            storagePoint(world, "short", "inside", 19.99)
            storagePoint(world, "wrong-tag", "inside", tags = setOf("freight"))
            val result = TimetableValidator(world).validate(storagePlan())
            assertTrue(result.isSuccess, result.errors.toString())
            assertEquals(listOf(eligible), result.structural.storageRoutes.keys.map { it.stablingPointRef })
            val none = TimetableValidator(world).validate(storagePlan(setOf("short", "wrong-tag")))
            assertFalse(none.isSuccess)
            assertTrue(none.errors.any { it.contains("classification/length-compatible") })
        }
    }

    @Test fun `one unsafe compatible storage alternative rejects the entire finite candidate set`() {
        fixture(points = setOf("inside")) { world, _, train ->
            registerSource(world, train)
            storagePoint(world, "safe", "end")
            storagePoint(world, "unsafe", "inside")
            val result = TimetableValidator(world).validate(storagePlan())
            assertFalse(result.isSuccess)
            assertTrue(result.errors.any { it.contains("no retained onward-route proof") })
            val selected = TimetableValidator(world).validate(storagePlan(setOf("safe")))
            assertTrue(selected.isSuccess, selected.errors.toString())
            assertEquals(1, selected.structural.storageRoutes.size)
        }
    }

    @Test fun `storage rejects missing declared point or source classification rather than physical-only fallback`() {
        fixture { world, _, train ->
            storagePoint(world, "safe", "end")
            val unregistered = TimetableValidator(world).validate(storagePlan())
            assertFalse(unregistered.isSuccess)
            assertTrue(unregistered.errors.any { it.contains("registered source classification") })
            registerSource(world, train)
            val missing = TimetableValidator(world).validate(storagePlan(setOf("missing")))
            assertFalse(missing.isSuccess)
            assertTrue(missing.errors.any { it.contains("StablingPoint") && it.contains("missing") })
        }
    }

    @Test fun `new output duty retains reversed storage proof with exact inherited classification`() {
        fixture { world, _, train ->
            registerSource(world, train)
            val point = storagePoint(world, "back", "back")
            val result = TimetableValidator(world).validate(storagePlan(newDuty = true))
            assertTrue(result.isSuccess, result.errors.toString())
            val (key, storage) = result.structural.storageRoutes.entries.single()
            assertEquals("branch", key.serviceId)
            assertEquals("rest", key.outputId)
            assertEquals(point, key.stablingPointRef)
            val child = split(world, train, "car-2", "rest")
            world.onOwnerForTest { child.reverse() }
            assertDoesNotThrow { storage.movement.proof.resolve(child.publishedFrame, world.topologyState,
                storage.movement.outputCarIds, storage.movement.expectedReversed) }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `initial coupling certifies both bodies and rejects changed contact geometry`(stationaryReversed: Boolean) {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestSimpleATOWorldDataRegistry.getOrCreate(world)
            try {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(220.0, 1))
                fun train(id: String, head: Double) = world.onOwnerForTest {
                    val anchor = work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(rail.viewAB, head)
                    val car = CoreCar.create("$id-car", CoreBogie.create(anchor), CoreBogie.create(anchor),
                        length = 10.0, bogieAOffset = 3.0, bogieBOffset = -3.0,
                        connectorLengthAtA = 0.3, connectorLengthAtB = 0.3, world = world)
                    CoreTrain.create(id, listOf(car), world).also { it.setLocation(anchor); it.commitMaintenancePlacement() }
                }
                val moving = train("moving", 99.0)
                val stationary = train("stationary", if (stationaryReversed) 120.5 else 110.5)
                if (stationaryReversed) world.onOwnerForTest { stationary.reverse() }
                val home = DepotId("home")
                val store = world.simpleAtoWorldData.depots
                store.addStablingPoint(home, "Home", DepotStablingPoint(StablingPointRef(home,"p"), "approach"))
                listOf(moving to "approach", stationary to "meeting").forEach { (train, point) ->
                    world.basicWorldData.wayPoints.add(WayPoint(point, point, rail.id, train.publishedFrame.head.arcLengthInAbMeters))
                    val ids = train.cars.map { it.id }
                    store.registerFixedUnit(FixedEmuUnit("unit-${train.id}", home, ids, "emu"), ids)
                }
                val event = TimetableFormationEvent.Couple("join", "a", "a", "b",
                    if (stationaryReversed) FormationStationaryEnd.HEAD else FormationStationaryEnd.TAIL,
                    FormationRendezvous("approach", "meeting"), 0.4, output("joined"))
                val plan = Timetable("coupling", services = listOf(
                    TimetableService("a", TrainSelector.TrainId(moving.id), listOf(TimetableStep.Check("approach"), at(TimetableAction.Couple("join")))),
                    TimetableService("b", TrainSelector.TrainId(stationary.id), listOf(TimetableStep.Check("meeting"), at(TimetableAction.Couple("join"))))
                ), formationEvents = TimetableFormationCatalog(listOf(event)))
                val result = world.onOwnerForTest { TimetableValidator(world).validate(plan) }
                assertTrue(result.isSuccess, result.errors.toString())
                val pair = result.structural.couplings.values.single()
                assertEquals(2, result.structural.sources.size)
                assertEquals(listOf("stationary-car", "moving-car"), pair.outputCarIds)
                assertNotNull(pair.outputCandidateKey.parents)
                world.onOwnerForTest {
                    fun proof() = (world.inspectCouplingApproach(work.art1st.javarailways.core.api.physics.train.CouplingApproachRequest(
                        moving.id, work.art1st.javarailways.core.api.physics.train.TrainEndSide.A,
                        stationary.id, work.art1st.javarailways.core.api.physics.train.TrainEndSide.B)) as
                        work.art1st.javarailways.core.api.physics.train.CouplingApproachInspection.Accepted).proof
                    assertDoesNotThrow { pair.contactFeasibility.requireContact(proof(), world.topologyState) }
                    stationary.setLocation(rail.viewAB.getRailLocationAt(if (stationaryReversed) 120.4 else 110.6))
                    stationary.commitMaintenancePlacement()
                    if (stationaryReversed) stationary.reverse()
                    assertThrows(IllegalArgumentException::class.java) {
                        pair.contactFeasibility.requireContact(proof(), world.topologyState)
                    }
                    run {
                        val futureA = if (stationaryReversed) 102.0 else 140.0
                        val futureB = if (stationaryReversed) 107.0 else 160.5
                        listOf("future-a" to futureA, "future-b" to futureB).forEach { (id, arc) ->
                            world.basicWorldData.wayPoints.add(WayPoint(id, id, rail.id, arc)) }
                        fun explicitMovement(origin: String, target: String): TimetableAction {
                            val start = world.basicWorldData.wayPoints[origin]!!.arcLength
                            val end = world.basicWorldData.wayPoints[target]!!.arcLength
                            val via = world.basicWorldData.wayPoints.all().filter { point ->
                                point.railId == rail.id && point.arcLength > minOf(start,end) + 1e-7 && point.arcLength < maxOf(start,end) - 1e-7
                            }.sortedBy { if (end >= start) it.arcLength else -it.arcLength }.map { it.id }
                            return if (via.isEmpty()) TimetableAction.GoTo(target) else TimetableAction.GoVia(via + target)
                        }
                        val future = plan.copy(services = plan.services.map { service -> service.copy(steps =
                            listOf(service.steps.first(), at(explicitMovement((service.steps.first() as TimetableStep.Check).wayPointId, if (service.id == "a") "future-a" else "future-b")),
                                service.steps.last())) }, formationEvents = TimetableFormationCatalog(listOf(event.copy(
                                    rendezvous = FormationRendezvous("future-a", "future-b")))))
                        val futureResult = TimetableValidator(world).validate(future)
                        assertTrue(futureResult.isSuccess, futureResult.errors.toString())
                        val futurePair = futureResult.structural.couplings.values.single()
                        val unsafeArc = if (stationaryReversed) 106.4 else 149.9
                        world.basicWorldData.wayPoints.add(WayPoint("unsafe-a", "unsafe-a", rail.id, unsafeArc))
                        val unsafe = future.copy(services = future.services.map { service -> service.copy(steps = listOf(service.steps.first(),
                            at(explicitMovement((service.steps.first() as TimetableStep.Check).wayPointId, if(service.id == "a") "unsafe-a" else "future-b")), service.steps.last())) },
                            formationEvents = TimetableFormationCatalog(listOf(event.copy(rendezvous = FormationRendezvous("unsafe-a", "future-b")))))
                        val unsafeResult = TimetableValidator(world).validate(unsafe)
                        assertFalse(unsafeResult.isSuccess)
                        assertTrue(unsafeResult.errors.any { it.contains("not every future stopped-window pair") }, unsafeResult.errors.toString())
                        for (aHead in listOf(futureA - 1.0, futureA + 1.0)) for (bHead in listOf(futureB - 1.0, futureB + 1.0)) {
                            moving.setLocation(rail.viewAB.getRailLocationAt(aHead)); moving.commitMaintenancePlacement()
                            stationary.setLocation(rail.viewAB.getRailLocationAt(bHead + if (stationaryReversed) 10.0 else 0.0))
                            stationary.commitMaintenancePlacement()
                            if (stationaryReversed) stationary.reverse()
                            assertDoesNotThrow { futurePair.contactFeasibility.requireContact(proof(), world.topologyState) }
                        }
                    }
                }
            } finally { TestSimpleATOWorldDataRegistry.unregister(world) }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `runtime conditional structural sources require planned branch compilation`(takeThen: Boolean) {
        fixture(points = setOf("stop", "later")) { world, _, _ ->
            val choice = TimetableAction.Conditional(TimetableCondition.Predicate(TimetableExtensionCall("test:choice")),
                listOf(at(TimetableAction.GoTo(if(takeThen) "stop" else "later"))), emptyList())
            val result = TimetableValidator(world).validate(timetable(sourceSteps = listOf(TimetableStep.Check("origin"), at(choice),
                at(TimetableAction.Split("divide")), at(TimetableAction.GoTo("end")))))
            assertFalse(result.isSuccess)
            assertTrue(result.errors.any { "editable-plan deterministic" in it })
            assertTrue(result.retainedRoutes.isEmpty())
        }
    }

    @Test fun `structural via markers preserve ordered occurrences on one resolved physical route`() {
        fixture(points = setOf("stop", "later")) { world, _, train ->
            val result = TimetableValidator(world).validate(timetable(sourceSteps = listOf(TimetableStep.Check("origin"),
                at(TimetableAction.Split("divide")), at(TimetableAction.GoVia(listOf("stop", "later", "end"))))))
            assertTrue(result.isSuccess, result.errors.toString())
            val evidence = result.structural.routes.entries.single { it.key.outputId == "first" }.value
            assertEquals(listOf("stop", "later"), evidence.intermediateMarkers.map { it.targetId })
            split(world, train, "car-2", "via-child")
            val resolved = evidence.proof.resolve(train.publishedFrame, world.topologyState, evidence.outputCarIds, evidence.expectedReversed)
            val cuts = evidence.intermediateMarkers.map { resolved.movement.totalLengthMeters - it.distanceBeforeRetainedEndMeters }
            assertEquals(listOf(20.0, 100.0), cuts)
            assertTrue(cuts.zipWithNext().all { (a, b) -> a < b })
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `selected berth history qualifies structural source and output continuation`(beforeSplit: Boolean) {
        fixture { world, rail, train ->
            val station = StationId("choice")
            for ((id, arc) in listOf("near" to 100.0, "far" to 100.0)) {
                OperationalDestinationService(world).register(OperationalDestination("choice@$id", id, rail.id, arc,
                    ViewDirection.AB, DestinationRole.StationBerthRole(station, berthId = id,
                        doorSide = DoorSide.NONE, usableLengthMeters = 80.0)))
            }
            val selection = TimetableAction.SelectStop(StationStopSelection(station,
                listOf(StationStopCandidate("near", 0), StationStopCandidate("far", 1)),
                StationStopSelectionPolicy.AVAILABLE_PRIORITY))
            val sequence = if (beforeSplit) listOf(at(selection), at(TimetableAction.Split("divide")))
                else listOf(at(TimetableAction.Split("divide")), at(selection))
            val result = TimetableValidator(world).validate(timetable(sourceSteps =
                listOf(TimetableStep.Check("origin")) + sequence + at(TimetableAction.GoTo("end"))))
            assertTrue(result.isSuccess, result.errors.toString())
            val trace = StructuralBranchTrace.EMPTY.recordStop("source", TimetableStepAddress(if (beforeSplit) 1 else 2), "near")
            if (beforeSplit) {
                assertEquals(2, result.structural.sources.size)
                world.onOwnerForTest { train.setLocation(rail.viewAB.getRailLocationAt(100.0)); train.commit() }
                val source = result.structural.sources.entries.single { it.key.branchTrace == trace }.value
                source.envelope.requireContains(train.publishedFrame, world.topologyState, source.sourceCarIds, source.expectedReversed)
            }
            split(world, train, "car-2", "selected-child")
            if (!beforeSplit) {
                val incoming = result.structural.routes.entries.single { it.key.branchTrace == trace && it.key.targetId == "choice@near" }.value
                assertDoesNotThrow { incoming.proof.resolve(train.publishedFrame, world.topologyState, incoming.outputCarIds, incoming.expectedReversed) }
                world.onOwnerForTest { train.setLocation(rail.viewAB.getRailLocationAt(100.0)); train.commit() }
            }
            val onward = result.structural.routes.entries.single { it.key.branchTrace == trace && it.key.targetId == "end" }.value
            assertDoesNotThrow { onward.proof.resolve(train.publishedFrame, world.topologyState, onward.outputCarIds, onward.expectedReversed) }
        }
    }

    @Test fun `interior structural station call proves its own stop without changing the final destination offset`() {
        fixture(points = setOf("stop", "later")) { world, rail, train ->
            val station = StationId("interior")
            for ((id, arc) in listOf("middle" to 130.0, "behind" to 75.0)) {
                OperationalDestinationService(world).register(OperationalDestination("interior@$id", id, rail.id, arc,
                    ViewDirection.AB, DestinationRole.StationBerthRole(station, berthId = id,
                        doorSide = DoorSide.NONE, usableLengthMeters = 80.0, stoppingOffsetMetersBefore = 2.0)))
            }
            fun plan(berth: String) = timetable(sourceSteps = listOf(TimetableStep.Check("origin"),
                at(TimetableAction.Split("divide")), at(TimetableAction.CallStation("interior", berth)),
                at(TimetableAction.GoVia(listOf("stop", "interior@middle", "later", "end")))))
            val valid = TimetableValidator(world).validate(plan("middle"))
            assertTrue(valid.isSuccess, valid.errors.toString())
            val (key, evidence) = valid.structural.routes.entries.single { it.key.outputId == "first" }
            assertEquals(0.0, key.stoppingOffsetMeters)
            assertEquals(listOf("stop", "interior@middle", "later"), evidence.intermediateMarkers.map { it.targetId })
            val invalid = TimetableValidator(world).validate(plan("behind"))
            assertFalse(invalid.isSuccess)
            assertTrue(invalid.errors.any { it.contains("station stopping window is not beyond") }, invalid.errors.toString())
            split(world, train, "car-2", "interior-child")
            assertDoesNotThrow { evidence.proof.resolve(train.publishedFrame, world.topologyState, evidence.outputCarIds, evidence.expectedReversed) }
        }
    }

    @Test fun `retained structural endpoint and station bindings reject metadata drift without topology changes`() {
        fixture(points = setOf("stop", "later")) { world, rail, _ ->
            val plan = timetable(sourceSteps = listOf(TimetableStep.Check("origin"), at(TimetableAction.Split("divide")),
                at(TimetableAction.GoVia(listOf("stop", "later", "end")))))
            val result = TimetableValidator(world).validate(plan)
            assertTrue(result.isSuccess, result.errors.toString())
            val route = result.structural.routes.entries.single { it.key.outputId == "first" }.value
            val topology = world.topologyState
            assertDoesNotThrow { route.requireCurrent(world) }
            for ((target, oldArc) in listOf("stop" to 100.0, "later" to 180.0, "end" to 200.0)) {
                world.basicWorldData.wayPoints.remove(target)
                world.basicWorldData.wayPoints.add(WayPoint(target, target, rail.id, oldArc + 0.5))
                assertSame(topology, world.topologyState)
                assertThrows(IllegalArgumentException::class.java) { route.requireCurrent(world) }
                world.basicWorldData.wayPoints.remove(target)
                world.basicWorldData.wayPoints.add(WayPoint(target, target, rail.id, oldArc))
                assertDoesNotThrow { route.requireCurrent(world) }
            }
            val station = StationId("binding")
            val service = OperationalDestinationService(world)
            val destination = OperationalDestination("binding@one", "One", rail.id, 130.0, ViewDirection.AB,
                DestinationRole.StationBerthRole(station, berthId = "one", doorSide = DoorSide.NONE,
                    usableLengthMeters = 80.0))
            service.register(destination)
            val stationPlan = timetable(sourceSteps = listOf(TimetableStep.Check("origin"), at(TimetableAction.Split("divide")),
                at(TimetableAction.CallStation("binding", "one")), at(TimetableAction.GoVia(listOf("stop", "binding@one", "later", "end")))))
            val stationResult = TimetableValidator(world).validate(stationPlan)
            assertTrue(stationResult.isSuccess, stationResult.errors.toString())
            val stationRoute = stationResult.structural.routes.entries.single { it.key.outputId == "first" }.value
            assertDoesNotThrow { stationRoute.requireCurrent(world) }
            service.remove(destination.id)
            service.register(destination.copy(role = (destination.role as DestinationRole.StationBerthRole).copy(stoppingAreaId = "changed-area")))
            assertSame(topology, world.topologyState)
            assertThrows(IllegalArgumentException::class.java) { stationRoute.requireCurrent(world) }
        }
    }

    private fun split(world: CoreWorld, train: CoreTrain, before: String, id: String): CoreTrain {
        val future = train.splitBefore(before, id)
        world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
        return future.get(5, TimeUnit.SECONDS) as CoreTrain
    }
    private fun fixture(cars: Int = 4, head: Double = 80.0, points: Set<String> = emptySet(), body: (CoreWorld, CoreRail, CoreTrain) -> Unit) {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestSimpleATOWorldDataRegistry.getOrCreate(world)
            try {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(220.0, 1))
                val train = createTrainWithConfig("physical", rail, world, List(cars) { 10.0 }, connectorLength = 0.25)
                world.onOwnerForTest { train.setLocation(rail.viewAB.getRailLocationAt(head)); train.commit() }
                listOf("origin" to head, "stop" to 100.0, "end" to 200.0, "later" to 180.0,
                    "back" to 20.0, "inside" to 60.0).filter { it.first in setOf("origin", "end", "back") + points }.forEach { (id, arc) -> world.basicWorldData.wayPoints.add(WayPoint(id,id,rail.id,arc)) }
                val home = DepotId("home")
                val store = world.simpleAtoWorldData.depots
                store.addStablingPoint(home, "Home", DepotStablingPoint(StablingPointRef(home,"point"), "origin"))
                val carIds = train.cars.map { it.id }
                carIds.chunked(2).forEachIndexed { index, ids -> store.registerFixedUnit(FixedEmuUnit("unit-$index", home, ids, "emu"), carIds) }
                body(world, rail, train)
            } finally { TestSimpleATOWorldDataRegistry.unregister(world) }
        }
    }
}
