package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.timetable.TimetableCondition
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall
import work.art1st.javarailways.simpleato.api.timetable.TimetableServiceActivation
import work.art1st.javarailways.simpleato.persistence.SavedTimetableV1
import work.art1st.javarailways.simpleato.persistence.TimetableSnapshotCodec

class FormationEventTest {
    private fun at(action: TimetableAction) = TimetableStep.At(100L, action)
    private fun output(id: String, duty: String? = null) = TimetableFormationOutput(id,
        duty?.let { FormationOutputDisposition.Duty(it) } ?: FormationOutputDisposition.Hold)
    private fun split() = TimetableFormationEvent.Split("divide", "source", 1,
        output("head", "source"), output("tail", "branch"))
    private fun validSplit() = Timetable("fixed", services = listOf(
        TimetableService("source", TrainSelector.TrainId("physical"), listOf(TimetableStep.Check("origin"), at(TimetableAction.Split("divide")))),
        TimetableService("branch", steps = listOf(at(TimetableAction.TakeFromFormationOutput("divide", "tail")), at(TimetableAction.GoTo("end"))))
    ), formationEvents = TimetableFormationCatalog(listOf(split())))

    @Test fun `valid catalog does not invent retained onward routes from pre-split origin`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestSimpleATOWorldDataRegistry.getOrCreate(world)
            try {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(10.0, 1))
                world.basicWorldData.wayPoints.add(WayPoint("origin", "Origin", rail.id, 2.0))
                world.basicWorldData.wayPoints.add(WayPoint("end", "End", rail.id, 8.0))
                val timetable = validSplit()
                assertTrue(FormationEventValidator.validate(timetable).isEmpty())
                val result = TimetableValidator(world).validate(timetable)
                assertTrue(result.errors.any { it.contains("formation output has no retained onward-route proof") })
                assertTrue(result.retainedRoutes.isEmpty())
            } finally { TestSimpleATOWorldDataRegistry.unregister(world) }
        }
    }

    @Test fun `fixed split reciprocal new duty acquisition and continuing source are valid`() {
        assertTrue(FormationEventValidator.validate(validSplit()).isEmpty())
        val held = split().copy(rest = output("tail"))
        assertTrue(FormationEventValidator.validate(validSplit().copy(services = validSplit().services.take(1),
            formationEvents = TimetableFormationCatalog(listOf(held)))).isEmpty())
    }

    @Test fun `catalog copies collection has value equality and snapshot default remains empty`() {
        val source = mutableListOf<TimetableFormationEvent>(split())
        val frozen = TimetableFormationCatalog(source)
        source.clear()
        assertEquals(1, frozen.size)
        assertEquals(TimetableFormationCatalog(listOf(split())), frozen)
        assertEquals(TimetableFormationCatalog(listOf(split())).hashCode(), frozen.hashCode())
        assertEquals(Timetable("empty"), Timetable("empty"))
        val empty = TimetableSnapshotCodec.decode("""{"id":"empty","periodDays":1,"services":[]}""").restore()
        assertEquals(Timetable("empty"), empty)
    }

    @Test fun `new catalog and actions survive current snapshot and diagnostic dump`() {
        val expected = validSplit()
        val saved = SavedTimetableV1.capture(expected)
        assertEquals(expected, TimetableSnapshotCodec.decode(TimetableSnapshotCodec.encode(saved)).restore())
        assertTrue(TimetableDumper.dumpToJson(expected).contains("formationEvents"))
        assertTrue(TimetableDumper.dumpToJson(expected).contains("takeFromFormationOutput"))
    }

    @Test fun `missing duplicate wrong output and duplicate claims reject before runtime`() {
        val valid = validSplit()
        for (bad in listOf(
            valid.copy(services = valid.services.take(1)),
            valid.copy(formationEvents = TimetableFormationCatalog(listOf(split(), split()))),
            valid.copy(services = listOf(valid.services[0], valid.services[1].copy(steps = listOf(at(TimetableAction.TakeFromFormationOutput("divide", "head")))))),
            valid.copy(services = valid.services + valid.services[1].copy(id = "intruder"))
        )) assertFalse(FormationEventValidator.validate(bad).isEmpty())
    }

    @Test fun `split rejects nonpositive partial selector duplicate output and duplicate owner`() {
        assertThrows(IllegalArgumentException::class.java) { split().copy(firstUnitCount = 0) }
        assertThrows(IllegalArgumentException::class.java) { split().copy(rest = output("head")) }
        assertThrows(IllegalArgumentException::class.java) { split().copy(rest = output("tail", "source")) }
        assertThrows(IllegalArgumentException::class.java) { TimetableSnapshotCodec.decode("""
          {"id":"x","periodDays":1,"services":[],"formationEvents":[{"id":"divide","kind":"SPLIT","coordinatorDutyId":"source",
          "partition":{"from":"TAIL","firstUnitCount":1},"outputs":[{"id":"a","group":"FIRST","disposition":{"type":"HOLD"}},
          {"id":"b","group":"REST","disposition":{"type":"HOLD"}}]}]}
        """) }
    }

    @Test fun `coupling requires both distinct participant occurrences and ends consumed duty`() {
        val event = TimetableFormationEvent.Couple("join", "a", "a", "b", FormationStationaryEnd.TAIL,
            FormationRendezvous("approach", "standing"), 0.5, output("combined", "a"))
        val a = TimetableService("a", TrainSelector.TrainId("one"), listOf(at(TimetableAction.Couple("join"))))
        val b = TimetableService("b", TrainSelector.TrainId("two"), listOf(at(TimetableAction.Couple("join"))))
        val valid = Timetable("couple", services = listOf(a,b), formationEvents = TimetableFormationCatalog(listOf(event)))
        assertTrue(FormationEventValidator.validate(valid).isEmpty())
        assertFalse(FormationEventValidator.validate(valid.copy(services = listOf(a,b.copy(steps = emptyList())))).isEmpty())
        val continuedConsumed = valid.copy(services = listOf(a, b.copy(steps = b.steps + at(TimetableAction.GoTo("end")))))
        assertFalse(FormationEventValidator.validate(continuedConsumed).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { event.copy(stationaryDutyId = "a") }
        assertThrows(IllegalArgumentException::class.java) { event.copy(maximumApproachSpeedMetersPerSecond = Double.NaN) }
    }

    @Test fun `structural actions forbidden in runtime branches and conditional participant activation`() {
        val condition = TimetableCondition.Predicate(TimetableExtensionCall("test:condition", 1))
        assertThrows(IllegalArgumentException::class.java) { TimetableAction.Conditional(condition, listOf(TimetableStep.At(0, TimetableAction.Split("divide")))) }
        val valid = validSplit()
        assertFalse(FormationEventValidator.validate(valid.copy(services = listOf(valid.services[0].copy(
            activation = TimetableServiceActivation.Conditional(condition, work.art1st.javarailways.simpleato.api.timetable.TimetableActivationFalseBehavior.SKIP_CYCLE)), valid.services[1]))).isEmpty())
    }

    @Test fun `crossed participant event order rejects deadlock and consistent coupling chain is valid`() {
        fun couple(id: String, stationary: String) = TimetableFormationEvent.Couple(id, "a", "a", stationary,
            FormationStationaryEnd.TAIL, FormationRendezvous("approach", "standing"), 0.5, output("combined", "a"))
        fun duty(id: String, vararg events: String) = TimetableService(id, TrainSelector.TrainId(id),
            events.map { at(TimetableAction.Couple(it)) })
        val crossed = Timetable("crossed", services = listOf(duty("a", "x", "y"), duty("b", "y", "x")),
            formationEvents = TimetableFormationCatalog(listOf(couple("x", "b"), couple("y", "b"))))
        assertTrue(FormationEventValidator.validate(crossed).any { it.contains("event ordering cycle") && it.contains("x") && it.contains("y") })
        val legal = Timetable("chain", services = listOf(duty("a", "x", "y"), duty("b", "x"), duty("c", "y")),
            formationEvents = TimetableFormationCatalog(listOf(couple("x", "b"), couple("y", "c"))))
        assertTrue(FormationEventValidator.validate(legal).isEmpty())
    }

    @Test fun `same-cycle output dependency cycle and action kind mismatch reject`() {
        val a = TimetableFormationEvent.Split("a-event", "a", 1, output("first"), output("rest", "b"))
        val b = TimetableFormationEvent.Split("b-event", "b", 1, output("first"), output("rest", "a"))
        val timetable = Timetable("cycle", services = listOf(
            TimetableService("a", steps = listOf(at(TimetableAction.TakeFromFormationOutput("b-event", "rest")), at(TimetableAction.Split("a-event")))),
            TimetableService("b", steps = listOf(at(TimetableAction.TakeFromFormationOutput("a-event", "rest")), at(TimetableAction.Split("b-event"))))
        ), formationEvents = TimetableFormationCatalog(listOf(a,b)))
        assertTrue(FormationEventValidator.validate(timetable).any { it.contains("cycle") })
        val valid = validSplit()
        assertTrue(FormationEventValidator.validate(valid.copy(services = listOf(valid.services[0].copy(
            steps = listOf(at(TimetableAction.Couple("divide")))), valid.services[1]))).any { it.contains("kind") })
    }
}
