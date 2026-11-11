package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.depot.*

class TimetableUnitCirculationTest {
    private val a = DepotId("A"); private val b = DepotId("B")
    private val u = FixedEmuUnit("U", listOf("u"), "emu")
    private val v = FixedEmuUnit("V", listOf("v"), "emu")
    private fun point(depot: DepotId, id: String = "one", length: Double = 50.0) =
        DepotStablingPoint(StablingPointRef(depot, id), "${depot.value}-$id", length)
    private data class CapturedFormation(val trainId: String, val cars: List<String>, val at: StablingPointRef,
        val facts: TrainCandidateFacts)
    private fun formation(id: String, cars: List<String>, at: StablingPointRef, tags: Set<String> = setOf("passenger")) =
        CapturedFormation(id, cars, at, TrainCandidateFacts("emu", tags, 20.0, setOf("electric")))
    private fun ledger(formations: List<CapturedFormation> = listOf(formation("T", u.carIds, StablingPointRef(a, "one"))),
        units: List<FixedEmuUnit> = listOf(u), places: List<DepotStablingPoint> = listOf(point(a), point(b)),
        claims: List<FixedEmuUnitAssignment> = emptyList(), held: List<FixedFormationHeldOutput> = emptyList()): UnitCirculationLedger =
        UnitCirculationLedger(units, places.map(::StablingPointSnapshot), formations.associate { it.trainId to it.cars },
            formations.associate { it.trainId to false }, formations.flatMap { it.cars }.associateWith { 10.0 },
            formations.associate { it.trainId to it.facts }, formations.associate { it.trainId to it.at }, claims, held)
    private fun at(tick: Long, action: TimetableAction) = TimetableStep.At(tick, action)
    private fun trip(id: String, from: DepotId, to: DepotId, selector: DepotTrainSelector = DepotTrainSelector(from),
        takeTick: Long = 0, storeTick: Long = 100) = TimetableService(id, steps = listOf(
            at(takeTick, TimetableAction.TakeFromDepot(selector)), at(storeTick, TimetableAction.StoreAtDepot(to))))
    private fun analyze(vararg duties: TimetableService, ledger: UnitCirculationLedger = ledger()) =
        TimetableUnitCirculation.analyze(Timetable("circulation", services = duties.toList()), ledger)

    @Test fun `cross depot return follows physical placement and proves inventory only`() {
        val result = analyze(trip("out", a, b), trip("back", b, a))
        assertEquals(CirculationProof.PROVEN, result.inventory)
        assertNotNull(result.periodicInventoryCycles)
        assertEquals(CirculationProof.UNPROVEN, result.timing)
        assertEquals(CirculationProof.UNPROVEN, result.capacity)
    }

    @Test fun `spare initial formations cannot cure recurring physical depot deficit`() {
        val spare = formation("V", v.carIds, StablingPointRef(a, "two"))
        val supplied = ledger(listOf(formation("T", u.carIds, StablingPointRef(a, "one")), spare), listOf(u, v),
            listOf(point(a), point(a, "two"), point(b), point(b, "two")))
        val result = analyze(trip("leak", a, b), ledger = supplied)
        assertEquals(CirculationProof.IMPOSSIBLE, result.inventory)
        assertTrue(result.findings.any { it.code == "RECURRING_DEPOT_DEFICIT" && it.blocking })
    }

    @Test fun `balanced flow cannot invent an eligible model formation`() {
        val result = analyze(trip("out", a, b, DepotTrainSelector(a, requiredTags = setOf("freight"))), trip("back", b, a))
        assertEquals(CirculationProof.IMPOSSIBLE, result.inventory)
        assertTrue(result.findings.any { it.code == "NO_COMPATIBLE_FORMATION" })
    }

    @Test fun `earlier next take and cross boundary completion remain timing unproven not fake deadlines`() {
        val result = analyze(trip("out", a, b, storeTick = 23_999), trip("back", b, a, takeTick = 1, storeTick = 23_999))
        assertEquals(CirculationProof.PROVEN, result.inventory)
        assertEquals(CirculationProof.UNPROVEN, result.timing)
        assertEquals(CirculationProof.UNPROVEN, result.capacity)
        assertTrue(result.findings.any { it.code == "COMPLETION_BOUNDS_MISSING" && !it.blocking })
    }

    @Test fun `one parked coupled formation cannot satisfy separate single unit acquisitions`() {
        val together = ledger(listOf(formation("T", u.carIds + v.carIds, StablingPointRef(a, "one"))), listOf(u, v))
        val result = analyze(trip("one", a, b, DepotTrainSelector(a, maximumCars = 1)), trip("back", b, a), ledger = together)
        assertEquals(CirculationProof.IMPOSSIBLE, result.inventory)
        assertTrue(result.findings.any { it.code == "NO_COMPATIBLE_FORMATION" })
    }

    @Test fun `coupled group occupies one real point and may circulate intact`() {
        val together = ledger(listOf(formation("T", u.carIds + v.carIds, StablingPointRef(a, "one"))), listOf(u, v))
        assertEquals(CirculationProof.PROVEN, analyze(trip("out", a, b), trip("back", b, a), ledger = together).inventory)
    }

    @Test fun `physically undersized return point is an independent capacity contradiction`() {
        val supplied = ledger(places = listOf(point(a), point(b, length = 5.0)))
        val result = analyze(trip("out", a, b), trip("back", b, a), ledger = supplied)
        assertEquals(CirculationProof.IMPOSSIBLE, result.capacity)
        assertTrue(result.findings.any { it.code == "NO_COMPATIBLE_STORAGE_POINT" })
    }

    @Test fun `held pending or active cross cycle owners are never fabricated into available supply`() {
        val owner = FixedFormationOwner("T", "pending", u.carIds)
        val result = analyze(trip("out", a, b), trip("back", b, a),
            ledger = ledger(held = listOf(FixedFormationHeldOutput(owner, FixedFormationDisposition.CONTINUE_DUTY))))
        assertEquals(CirculationProof.UNPROVEN, result.inventory)
        assertTrue(result.findings.any { it.code == "OUTSTANDING_OWNERS" })
    }

    @Test fun `finite state ceiling explicitly leaves inventory unproven`() {
        val result = TimetableUnitCirculation.analyze(Timetable("bounded", services = listOf(trip("out", a, b), trip("back", b, a))),
            ledger(), CirculationLimits(maximumStates = 1))
        assertEquals(CirculationProof.UNPROVEN, result.inventory)
        assertTrue(result.findings.any { it.code == "SEARCH_LIMIT" })
    }

    @Test fun `registered permanent cars cannot overlap or split internally in source evidence`() {
        val overlapping = FixedEmuUnit("bad", u.carIds, "emu")
        val result = analyze(trip("out", a, b), ledger = ledger(units = listOf(u, overlapping)))
        assertEquals(CirculationProof.IMPOSSIBLE, result.inventory)
        assertTrue(result.findings.any { it.code == "INVALID_LEDGER" })
    }

    @Test fun `balanced depot totals cannot hide forced recurring class loss`() {
        val supplied = ledger(listOf(formation("T", u.carIds, StablingPointRef(a, "one")),
            formation("V", v.carIds, StablingPointRef(b, "one"), setOf("freight"))), listOf(u, v))
        val result = analyze(trip("passenger", a, b, DepotTrainSelector(a, requiredTags = setOf("passenger"))),
            trip("freight", b, a, DepotTrainSelector(b, requiredTags = setOf("freight"))), ledger = supplied)
        assertEquals(CirculationProof.IMPOSSIBLE, result.inventory)
        assertTrue(result.findings.any { it.code == "RECURRING_CLASS_DEFICIT" })
    }

    @Test fun `interrupted owner may name retired source without becoming an impossible ledger`() {
        val interrupted = UnitCirculationLedger(listOf(u), emptyList(), mapOf("published-output" to u.carIds),
            mapOf("published-output" to false), mapOf("u" to 10.0), emptyMap(), emptyMap(),
            listOf(FixedEmuUnitAssignment(u.id, "retired-source", "duty")))
        val result = analyze(trip("out", a, b), ledger = interrupted)
        assertEquals(CirculationProof.UNPROVEN, result.inventory)
        assertTrue(result.findings.any { it.code == "OUTSTANDING_OWNERS" })
    }

    @Test fun `unclassified physical permanent unit is missing evidence rather than imaginary available stock`() {
        val incomplete = UnitCirculationLedger(listOf(u, v), listOf(StablingPointSnapshot(point(a))),
            mapOf("T" to u.carIds, "V" to v.carIds), mapOf("T" to false, "V" to false), mapOf("u" to 10.0, "v" to 10.0),
            mapOf("T" to formation("T", u.carIds, StablingPointRef(a, "one")).facts), mapOf("T" to StablingPointRef(a, "one")))
        val result = analyze(trip("out", a, b), ledger = incomplete)
        assertEquals(CirculationProof.UNPROVEN, result.inventory)
        assertTrue(result.findings.any { it.code == "UNIT_EVIDENCE_MISSING" })
    }

    @Test fun `metadata absent from physical publication cannot prove inventory impossible`() {
        for (registered in listOf(emptyList(), listOf(u))) {
            val unavailable = UnitCirculationLedger(registered, listOf(StablingPointSnapshot(point(a))),
                emptyMap(), emptyMap(), emptyMap(), mapOf("T" to formation("T", u.carIds, StablingPointRef(a, "one")).facts),
                mapOf("T" to StablingPointRef(a, "one")))
            val result = analyze(trip("out", a, b), ledger = unavailable)
            assertEquals(CirculationProof.UNPROVEN, result.inventory)
            assertTrue(result.findings.any { it.code == "UNIT_EVIDENCE_MISSING" })
            assertFalse(result.findings.any { it.blocking })
        }
    }

    @Test fun `unmodeled physical formation is not a free point or absent supply proof`() {
        val open = UnitCirculationLedger(listOf(u), listOf(StablingPointSnapshot(point(a)), StablingPointSnapshot(point(b))),
            mapOf("T" to u.carIds, "unclassified" to listOf("foreign-car")), mapOf("T" to false, "unclassified" to false),
            mapOf("u" to 10.0, "foreign-car" to 10.0), mapOf("T" to formation("T", u.carIds, StablingPointRef(a, "one")).facts),
            mapOf("T" to StablingPointRef(a, "one"), "unclassified" to StablingPointRef(b, "one")))
        assertEquals(CirculationProof.UNPROVEN, analyze(trip("out", a, b), ledger = open).inventory)
    }

    @Test fun `deep finite action graph reaches explicit ceiling without overflowing recursion`() {
        val many = trip("out", a, b).copy(steps = listOf(at(0, TimetableAction.TakeFromDepot(DepotTrainSelector(a)))) +
            List(300) { at(1, TimetableAction.WaitUntil) } + at(2, TimetableAction.StoreAtDepot(b)))
        val result = analyze(many, trip("back", b, a))
        assertEquals(CirculationProof.UNPROVEN, result.inventory)
        assertTrue(result.findings.any { it.code == "SEARCH_LIMIT" })
    }

    @Test fun `captured physical membership is immutable against caller list changes`() {
        val cars = arrayListOf("u")
        val captured = ledger(listOf(formation("T", cars, StablingPointRef(a, "one"))))
        cars[0] = "invented"
        assertEquals(CirculationProof.PROVEN, analyze(trip("out", a, b), trip("back", b, a), ledger = captured).inventory)
    }

    @Test fun `fixed split and join conserve both whole units through repeated storage`() {
        val split = TimetableFormationEvent.Split("split", "in", 1,
            TimetableFormationOutput("first", FormationOutputDisposition.Duty("left")),
            TimetableFormationOutput("rest", FormationOutputDisposition.Duty("right")))
        val couple = TimetableFormationEvent.Couple("join", "left", "left", "right", FormationStationaryEnd.TAIL,
            FormationRendezvous("approach", "stationary"), 0.2,
            TimetableFormationOutput("whole", FormationOutputDisposition.Duty("left")))
        val incoming = TimetableService("in", steps = listOf(at(0, TimetableAction.TakeFromDepot(DepotTrainSelector(a))), at(1, TimetableAction.Split("split"))))
        val left = TimetableService("left", steps = listOf(at(1, TimetableAction.TakeFromFormationOutput("split", "first")),
            at(2, TimetableAction.Couple("join")), at(3, TimetableAction.StoreAtDepot(a))))
        val right = TimetableService("right", steps = listOf(at(1, TimetableAction.TakeFromFormationOutput("split", "rest")), at(2, TimetableAction.Couple("join"))))
        val supplied = ledger(listOf(formation("T", u.carIds + v.carIds, StablingPointRef(a, "one"))), listOf(u, v))
        val result = TimetableUnitCirculation.analyze(Timetable("split-join", services = listOf(incoming, left, right),
            formationEvents = TimetableFormationCatalog(listOf(split, couple))), supplied)
        assertEquals(CirculationProof.PROVEN, result.inventory, result.toString())
        assertEquals(CirculationProof.UNPROVEN, result.timing)
        assertEquals(2, result.periodicInventoryCycles, "compatible units may permute over more than one period")
    }
    @Test fun `unknown speed capability cannot prove a speed restricted acquisition impossible`() {
        val base = formation("T", u.carIds, StablingPointRef(a, "one"))
        val unknown = base.copy(facts = TrainCandidateFacts("emu", setOf("passenger"), null, setOf("electric")))
        val result = analyze(trip("out", a, b, DepotTrainSelector(a, minimumSpeedMetersPerSecond = 10.0)),
            ledger = ledger(listOf(unknown)))
        assertEquals(CirculationProof.UNPROVEN, result.inventory)
        assertFalse(result.findings.any { it.blocking })
    }

}
