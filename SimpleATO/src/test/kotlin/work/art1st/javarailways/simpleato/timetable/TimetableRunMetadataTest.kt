package work.art1st.javarailways.simpleato.timetable

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.simpleato.persistence.*
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.simpleato.api.destination.*
import work.art1st.javarailways.simpleato.api.station.StationId

class TimetableRunMetadataTest {
    private fun values(destination: String) = buildJsonObject { put("destination",destination) }
    private fun segment(id: String, start: Int, end: Int) = TimetableRunSegment(id,LineId(id),start,end,
        calls = listOf(TimetableRunCall("$id-origin","C","C",TimetableServiceStopStatus.ORIGIN),
            TimetableRunCall("$id-end","E","E",TimetableServiceStopStatus.TERMINAL)))

    @Test fun `runtime owned lookup preserves held peer cycles and revision observes fresh SET reset and retire`() {
        val segment = segment("run",0,1)
        val service = TimetableService("duty",steps=listOf(TimetableStep.Check("C")),runSegments=listOf(segment))
        val trip = TimetableOperationalTrip("trip",values("E"),listOf(TimetableOperationalTripSegment("duty","run",listOf(1,2))))
        val table = Timetable("table",services=listOf(service),operationalTrips=listOf(trip))
        val runtime = TimetableDisplayRuntime()
        assertEquals(0L,runtime.revision)
        assertNull(runtime.currentSnapshot("U1") { table })
        runtime.enter(table,0,service,segment,listOf("U1","U2"))
        val entered = runtime.revision
        assertTrue(entered > 0)
        runtime.set("table",0,listOf("U1"),values("late SET"))
        assertTrue(runtime.revision > entered)
        val changed = runtime.revision
        assertEquals("late SET",runtime.currentSnapshot("U1") { table }!!.second.values.getValue("destination").jsonPrimitive.content)
        runtime.set("table",0,listOf("U1"),values("late SET"))
        assertEquals(changed,runtime.revision,"equal author values do not wake stationary consumers again")
        runtime.set("table",0,listOf("U1"),null)
        assertTrue(runtime.revision > changed)
        val one = TimetableOperationalTrip("trip",values("new"),listOf(TimetableOperationalTripSegment("duty","run",listOf(1))))
        val next = table.copy(operationalTrips=listOf(one))
        runtime.enter(next,1,service,segment,listOf("U1"))
        assertEquals(1L,runtime.currentSnapshot("U1") { next }!!.second.cycleIndex)
        assertEquals(0L,runtime.currentSnapshot("U2") { next }!!.second.cycleIndex,"held peer keeps its owned cycle")
        assertNull(runtime.currentSnapshot("U1") { null },"missing table cannot borrow another context")
        val beforeRetire = runtime.revision
        runtime.retire("table",1,listOf("U1"))
        assertTrue(runtime.revision > beforeRetire)
        assertFalse(runtime.currentSnapshot("U1") { next }!!.second.active)
        val retired = runtime.revision
        runtime.retire("table",1,listOf("U1"))
        assertEquals(retired,runtime.revision)
        runtime.clear()
        assertTrue(runtime.revision > retired)
        assertNull(runtime.currentSnapshot("U2") { next })
    }

    @Test fun `future proof reach finish and retained cycle removal advance only when state changes`() {
        val first = segment("first",0,1); val next = segment("next",1,2)
        val service = TimetableService("duty",steps=listOf(TimetableStep.Check("C"),TimetableStep.Check("C")),runSegments=listOf(first,next))
        val trip = TimetableOperationalTrip("trip",segments=listOf(TimetableOperationalTripSegment("duty","first",listOf(1)),TimetableOperationalTripSegment("duty","next",listOf(1))))
        val table = Timetable("table",services=listOf(service),operationalTrips=listOf(trip))
        val runtime = TimetableDisplayRuntime()
        runtime.enter(table,0,service,first,listOf("U"))
        var revision = runtime.revision
        runtime.proveFuture(table,0,service,first,listOf("U")) { _,_ -> null }
        assertTrue(runtime.revision > revision); revision = runtime.revision
        runtime.proveFuture(table,0,service,first,listOf("U")) { _,_ -> null }
        assertEquals(revision,runtime.revision)
        runtime.reached("table",0,"duty","first","first-end",listOf("U"))
        assertTrue(runtime.revision > revision); revision = runtime.revision
        runtime.reached("table",0,"duty","first","first-end",listOf("U"))
        assertEquals(revision,runtime.revision)
        runtime.enter(table,0,service,next,listOf("U")); revision = runtime.revision
        runtime.finish(table,0,"duty",listOf("U"))
        assertTrue(runtime.revision > revision); revision = runtime.revision
        runtime.retainCycles(setOf("table" to 0L))
        assertEquals(revision,runtime.revision)
        runtime.retainCycles(emptySet())
        assertTrue(runtime.revision > revision)
    }

    @Test
    fun `current segment cap survives station program gap and only changes at next scope`() {
        val service = TimetableService("duty",lineId=LineId("unused"),runSegments=listOf(segment("main",1,3),segment("branch",5,7)))
        val table = Timetable("table",services=listOf(service),lineSpeedLimits=listOf(
            TimetableLineSpeedLimit(LineId("main"),22.0),TimetableLineSpeedLimit(LineId("branch"),8.0)))
        val resolver = LineMaximumSpeedResolver(table)
        assertEquals(22.0,resolver.resolve(service,0))
        assertEquals(22.0,resolver.resolve(service,4))
        assertEquals(8.0,resolver.resolve(service,5))
        assertEquals(8.0,resolver.resolve(service,20))
    }

    @Test
    fun `segments trips and native display actions round trip without aliasing caller JSON`() {
        val mutable = linkedMapOf<String,JsonElement>("destination" to JsonPrimitive("D"))
        val defaults = JsonObject(mutable)
        val trip = TimetableOperationalTrip("trip",defaults,listOf(TimetableOperationalTripSegment("duty","main",listOf(1))))
        val table = Timetable("table",services=listOf(TimetableService("duty",steps=listOf(
            TimetableStep.Check("C"),TimetableStep.At(1,TimetableAction.SetDisplay(listOf(1),defaults,listOf("old"))),
            TimetableStep.At(2,TimetableAction.ResetDisplay(listOf(1)))),runSegments=listOf(segment("main",1,3)))),operationalTrips=listOf(trip))
        mutable["destination"] = JsonPrimitive("mutated")
        assertEquals("D",trip.defaults["destination"]!!.jsonPrimitive.content)
        val restored = SavedTimetableV1.capture(table).restore()
        assertEquals(table.operationalTrips,restored.operationalTrips)
        assertEquals(table.services.single().runSegments,restored.services.single().runSegments)
        assertEquals(table.services.single().steps,restored.services.single().steps)
    }

    @Test
    fun `separate registered units preserve display state through slot change and reset only next trip`() {
        val first = segment("joint",0,1)
        val next = segment("child",2,3)
        val service = TimetableService("duty",steps=listOf(TimetableStep.Check("C"),TimetableStep.At(0,TimetableAction.Reverse),TimetableStep.At(1,TimetableAction.GoTo("E"))),runSegments=listOf(first,next))
        val tripD = TimetableOperationalTrip("D",values("D"),listOf(
            TimetableOperationalTripSegment("duty","joint",listOf(1)),TimetableOperationalTripSegment("duty","child",listOf(2))))
        val tripE = TimetableOperationalTrip("E",values("E"),listOf(
            TimetableOperationalTripSegment("duty","joint",listOf(2)),TimetableOperationalTripSegment("duty","child",listOf(1))))
        val table = Timetable("table",services=listOf(service),operationalTrips=listOf(tripD,tripE))
        val runtime = TimetableDisplayRuntime()
        runtime.enter(table,0,service,first,listOf("U1","U2"))
        runtime.set("table",0,listOf("U1"),values("D express"))
        runtime.enter(table,0,service,next,listOf("U2","U1"))
        assertEquals(listOf("E","D express"),runtime.snapshots(table,0,listOf("U2","U1")).map { it.values["destination"]!!.jsonPrimitive.content })
        runtime.set("table",0,listOf("U1"),null)
        assertEquals(listOf("D","E"),runtime.snapshots(table,0,listOf("U1","U2")).map { it.values["destination"]!!.jsonPrimitive.content })
        runtime.enter(table,1,service,first,listOf("U1","U2"))
        assertEquals(listOf("D","E"),runtime.snapshots(table,1,listOf("U1","U2")).map { it.values["destination"]!!.jsonPrimitive.content })
        assertThrows(IllegalArgumentException::class.java) { runtime.set("table",0,listOf("unregistered"),values("bad")) }
    }

    @Test
    fun `trip cannot silently migrate to unrelated physical unit in later leg`() {
        val first=segment("first",0,1); val second=segment("second",1,2)
        val service=TimetableService("duty",steps=listOf(TimetableStep.Check("C"),TimetableStep.Check("C")),runSegments=listOf(first,second))
        val trip=TimetableOperationalTrip("trip",values("E"),listOf(TimetableOperationalTripSegment("duty","first",listOf(1)),TimetableOperationalTripSegment("duty","second",listOf(1))))
        val table=Timetable("t",services=listOf(service),operationalTrips=listOf(trip))
        val runtime=TimetableDisplayRuntime()
        runtime.enter(table,0,service,first,listOf("U1"))
        assertThrows(IllegalArgumentException::class.java) { runtime.enter(table,0,service,second,listOf("U2")) }
        assertEquals("U1",runtime.snapshots(table,0,listOf("U1")).single().unitId)
    }

    @Test
    fun `future stops only follow proven selected unit and preserve repeated station occurrences`() {
        val first=segment("first",0,1); val second=segment("second",1,2)
        val service=TimetableService("duty",steps=listOf(TimetableStep.Check("C"),TimetableStep.Check("C")),runSegments=listOf(first,second))
        val trip=TimetableOperationalTrip("trip",values("E"),listOf(TimetableOperationalTripSegment("duty","first",listOf(1)),TimetableOperationalTripSegment("duty","second",listOf(1))))
        val table=Timetable("t",services=listOf(service),operationalTrips=listOf(trip))
        val runtime=TimetableDisplayRuntime()
        runtime.enter(table,0,service,first,listOf("U1"))
        assertEquals(listOf("duty/second"),runtime.snapshots(table,0,listOf("U1")).single().unresolvedRuns)
        runtime.proveFuture(table,0,service,first,listOf("U1")) { _,_-> null }
        val before=runtime.snapshots(table,0,listOf("U1")).single()
        assertTrue(before.unresolvedRuns.isEmpty())
        assertEquals(4,before.plannedStops.size,"same station revisited later must not be globally deduplicated")
        runtime.reached("t",0,"duty","first","first-end",listOf("U1"))
        assertEquals(1,runtime.snapshots(table,0,listOf("U1")).single().completedStops.size)
        assertTrue(before.completedStops.isEmpty(),"published snapshot is immutable")
    }
    @Test
    fun `explicit trip boundary preserves station program changes and resets only selected unit`() {
        val first=segment("first",0,1); val next=segment("next",3,4)
        val service=TimetableService("duty",steps=listOf(TimetableStep.Check("C"),TimetableStep.At(0,TimetableAction.BeginOperationalTrip("new","next",listOf(1))),TimetableStep.At(0,TimetableAction.SetDisplay(listOf(1),values("custom"))),TimetableStep.At(1,TimetableAction.GoTo("E"))),runSegments=listOf(first,next))
        val old=TimetableOperationalTrip("old",values("old"),listOf(TimetableOperationalTripSegment("duty","first",listOf(1))))
        val peer=TimetableOperationalTrip("peer",values("peer"),listOf(TimetableOperationalTripSegment("duty","first",listOf(2)),TimetableOperationalTripSegment("duty","next",listOf(2))))
        val nextTrip=TimetableOperationalTrip("new",values("new"),listOf(TimetableOperationalTripSegment("duty","next",listOf(1))))
        val table=Timetable("table",services=listOf(service),operationalTrips=listOf(old,peer,nextTrip))
        val runtime=TimetableDisplayRuntime()
        runtime.enter(table,0,service,first,listOf("U1","U2"))
        runtime.set("table",0,listOf("U2"),values("peer changed"))
        runtime.begin(table,0,service,TimetableAction.BeginOperationalTrip("new","next",listOf(1)),listOf("U1","U2"))
        runtime.set("table",0,listOf("U1"),values("custom"))
        runtime.enter(table,0,service,next,listOf("U1","U2"))
        assertEquals(listOf("custom","peer changed"),runtime.snapshots(table,0,listOf("U1","U2")).map { it.values["destination"]!!.jsonPrimitive.content })
        runtime.enter(table,1,service,first,listOf("U1","U2"))
        assertTrue(runtime.snapshots(table,0,listOf("U1","U2")).isEmpty(),"new cycle does not retain a mutable old context")
    }

    @Test
    fun `new cycle cleanup preserves independent held unit and unassigned run retires display`() {
        val first=segment("first",0,1); val blank=segment("blank",1,2)
        val service=TimetableService("duty",steps=listOf(TimetableStep.Check("C"),TimetableStep.Check("C")),runSegments=listOf(first,blank))
        val trip=TimetableOperationalTrip("trip",values("E"),listOf(TimetableOperationalTripSegment("duty","first",listOf(1,2))))
        val table=Timetable("table",services=listOf(service),operationalTrips=listOf(trip))
        val runtime=TimetableDisplayRuntime()
        runtime.enter(table,0,service,first,listOf("U1","U2"))
        val oneOnly=TimetableOperationalTrip("trip",values("E"),listOf(TimetableOperationalTripSegment("duty","first",listOf(1))))
        val nextTable=table.copy(operationalTrips=listOf(oneOnly))
        runtime.enter(nextTable,1,service,first,listOf("U1"))
        assertTrue(runtime.snapshots(table,0,listOf("U1")).isEmpty())
        assertEquals("U2",runtime.snapshots(table,0,listOf("U2")).single().unitId)
        runtime.enter(nextTable,1,service,blank,listOf("U1"))
        assertFalse(runtime.snapshots(nextTable,1,listOf("U1")).single().active)
        assertThrows(IllegalArgumentException::class.java) { runtime.set("table",1,listOf("U1"),values("bad")) }
    }

    @Test
    fun `merged interchange retains arrival and next departure without removing later visits`() {
        val first=TimetableRunSegment("main",LineId("main"),0,1,calls=listOf(
            TimetableRunCall("a","A","A@1",TimetableServiceStopStatus.ORIGIN,departureTick=0),
            TimetableRunCall("c-in","C","C@1",TimetableServiceStopStatus.TERMINAL,arrivalTick=100)))
        val next=TimetableRunSegment("branch",LineId("branch"),1,2,calls=listOf(
            TimetableRunCall("c-out","C","C@1",TimetableServiceStopStatus.ORIGIN,departureTick=150),
            TimetableRunCall("a-back","A","A@1",TimetableServiceStopStatus.TERMINAL,arrivalTick=250)))
        val service=TimetableService("d",steps=listOf(TimetableStep.Check("A@1"),TimetableStep.Check("C@1")),runSegments=listOf(first,next))
        val trip=TimetableOperationalTrip("trip",segments=listOf(TimetableOperationalTripSegment("d","main",listOf(1)),TimetableOperationalTripSegment("d","branch",listOf(1))))
        val table=Timetable("t",services=listOf(service),operationalTrips=listOf(trip))
        val runtime=TimetableDisplayRuntime()
        runtime.enter(table,0,service,first,listOf("U"))
        runtime.proveFuture(table,0,service,first,listOf("U")) { _,_->null }
        val stops=runtime.snapshots(table,0,listOf("U")).single().plannedStops
        assertEquals(listOf("A","C","A"),stops.map { it.call.stationId })
        assertEquals(TimetableServiceStopStatus.STOP,stops[1].call.status)
        assertEquals(100L,stops[1].call.arrivalTick)
        assertEquals(150L,stops[1].call.departureTick)
    }

    @Test
    fun `fixed split projects each units own onward stations before physical separation`() {
        fun scope(id:String,start:String,end:String,from:Int,to:Int)=TimetableRunSegment(id,LineId("L"),from,to,calls=listOf(
            TimetableRunCall("$id-origin",start,start,TimetableServiceStopStatus.ORIGIN),
            TimetableRunCall("$id-end",end,end,TimetableServiceStopStatus.TERMINAL)))
        val joint=scope("joint","A","C",0,2)
        val source=TimetableService("source",steps=listOf(TimetableStep.Check("A"),TimetableStep.At(0,TimetableAction.GoTo("C")),TimetableStep.At(1,TimetableAction.Split("split"))),runSegments=listOf(joint))
        fun child(id:String,end:String)=TimetableService(id,steps=listOf(TimetableStep.At(1,TimetableAction.TakeFromFormationOutput("split",id)),TimetableStep.At(2,TimetableAction.GoTo(end))),runSegments=listOf(scope(id,"C",end,1,2)))
        val left=child("left","D");val right=child("right","E")
        val split=TimetableFormationEvent.Split("split","source",1,TimetableFormationOutput("left",FormationOutputDisposition.Duty("left")),TimetableFormationOutput("right",FormationOutputDisposition.Duty("right")))
        val trips=listOf(TimetableOperationalTrip("D",values("D"),listOf(TimetableOperationalTripSegment("source","joint",listOf(1)),TimetableOperationalTripSegment("left","left",listOf(1)))),
            TimetableOperationalTrip("E",values("E"),listOf(TimetableOperationalTripSegment("source","joint",listOf(2)),TimetableOperationalTripSegment("right","right",listOf(1)))))
        val table=Timetable("t",services=listOf(source,left,right),formationEvents=TimetableFormationCatalog(listOf(split)),operationalTrips=trips)
        val runtime=TimetableDisplayRuntime()
        runtime.enter(table,0,source,joint,listOf("U1","U2"))
        runtime.proveFuture(table,0,source,joint,listOf("U1","U2")) { _,_->null }
        val displays=runtime.snapshots(table,0,listOf("U1","U2"))
        assertTrue(displays.all { it.unresolvedRuns.isEmpty() })
        assertEquals(listOf("A","C","D"),displays[0].plannedStops.map { it.call.stationId })
        assertEquals(listOf("A","C","E"),displays[1].plannedStops.map { it.call.stationId })
    }

    @Test
    fun `native display values obey the same finite JSON limits as authoring`() {
        assertThrows(IllegalArgumentException::class.java) { TimetableAction.SetDisplay(listOf(1),buildJsonObject { put("n",Long.MAX_VALUE) }) }
        assertThrows(IllegalArgumentException::class.java) { TimetableAction.SetDisplay(listOf(1),values("x".repeat(8193))) }
    }

    @Test
    fun `planned calls must match canonical ownership actual pass movements and trip order`() {
        TestWorldFixture.create().use { fixture ->
            val world=fixture.world
            TestSimpleATOWorldDataRegistry.getOrCreate(world)
            try {
                val rail=createStraightRailFromSegments(world,PrimeCurveGenerator.buildStraight(200.0,1))
                listOf("A","C","E").forEachIndexed { index,id -> OperationalDestinationService(world).register(
                    OperationalDestination("$id@1",rail.id,20.0+index*60.0,ViewDirection.AB,
                        DestinationRole.StationBerthRole(StationId(id),berthId="1",doorSide=work.art1st.javarailways.basic.api.operation.DoorSide.NONE,usableLengthMeters=30.0))) }
                fun call(id:String,status:TimetableServiceStopStatus)=TimetableRunCall(id,id,"$id@1",status)
                val calls=listOf(call("A",TimetableServiceStopStatus.ORIGIN),call("C",TimetableServiceStopStatus.PASS),call("E",TimetableServiceStopStatus.TERMINAL))
                val first=TimetableRunSegment("first",LineId("L"),1,2,calls=calls)
                val next=TimetableRunSegment("next",LineId("L"),2,3)
                val service=TimetableService("d",steps=listOf(TimetableStep.Check("A@1"),TimetableStep.At(0,TimetableAction.GoVia(listOf("C@1","E@1"))),TimetableStep.At(1,TimetableAction.WaitUntil)),runSegments=listOf(first,next))
                val table=Timetable("t",services=listOf(service))
                assertTrue(TimetableMetadataValidator.validate(world,table).isEmpty())
                val viaCalls = listOf(calls.first(), calls.last().copy(viaIds = listOf("C@1")))
                val viaSegment = TimetableRunSegment("first", LineId("L"), 1, 2, calls = viaCalls)
                val viaTable = table.copy(services = listOf(service.copy(runSegments = listOf(viaSegment, next))))
                assertTrue(TimetableMetadataValidator.validate(world,viaTable).isEmpty())
                assertEquals(viaCalls, SavedTimetableV1.capture(viaTable).restore().services.single().runSegments.first().calls)
                val missingVia = TimetableRunSegment("first", LineId("L"), 1, 2, calls = listOf(calls.first(),calls.last()))
                assertTrue(TimetableMetadataValidator.validate(world,table.copy(services=listOf(service.copy(runSegments=listOf(missingVia,next))))).any { "ordered via" in it })
                val wrong=TimetableRunSegment("first",LineId("L"),1,2,calls=calls.map { if(it.stationId=="C")it.copy(stationId="E",status=TimetableServiceStopStatus.STOP) else it })
                val errors=TimetableMetadataValidator.validate(world,table.copy(services=listOf(service.copy(runSegments=listOf(wrong,next)))))
                assertTrue(errors.any { "does not own canonical berth" in it },errors.toString())
                assertTrue(errors.any { "PASS/STOP" in it },errors.toString())
                val backwards=TimetableOperationalTrip("backwards",segments=listOf(TimetableOperationalTripSegment("d","next",listOf(1)),TimetableOperationalTripSegment("d","first",listOf(1))))
                assertTrue(TimetableMetadataValidator.validate(world,table.copy(operationalTrips=listOf(backwards))).any { "physical duty order" in it })
            } finally { TestSimpleATOWorldDataRegistry.unregister(world) }
        }
    }

}
