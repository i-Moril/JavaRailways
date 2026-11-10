package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import java.util.UUID

class TimetableDisplayProgressTest {
    private val rail = UUID.randomUUID()
    private fun frame(position: Double = 20.0, speed: Double = 0.0, reversed: Boolean = false, derailed: Boolean = false) =
        TrainFrameSnapshot.create("train","Train",1,1,speed,derailed,reversed,emptyList(),
            RailPositionSnapshot(rail,ViewDirection.AB,position),RailPositionSnapshot(rail,ViewDirection.AB,position-10),
            emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
    private fun call(id: String, status: TimetableServiceStopStatus, destination: String = id) =
        TimetableRunCall(id,destination,destination,status)
    private val origin = call("origin",TimetableServiceStopStatus.ORIGIN,"A")
    private val pass = call("pass",TimetableServiceStopStatus.PASS,"A")
    private val end = call("end",TimetableServiceStopStatus.TERMINAL,"C")
    private fun scenario(): Triple<TimetableDisplayRuntime,Timetable,TimetableService> {
        val first = TimetableRunSegment("first",LineId("line"),0,1,calls=listOf(origin,pass,end))
        val second = TimetableRunSegment("second",LineId("line"),1,2,calls=listOf(
            call("origin2",TimetableServiceStopStatus.ORIGIN,"C"),call("end2",TimetableServiceStopStatus.TERMINAL,"A")))
        val service = TimetableService("service",steps=listOf(TimetableStep.Check("A"),TimetableStep.Check("C")),runSegments=listOf(first,second))
        val trip = TimetableOperationalTrip("trip",segments=listOf(
            TimetableOperationalTripSegment("service","first",listOf(1)),TimetableOperationalTripSegment("service","second",listOf(1))))
        val table = Timetable("table",services=listOf(service),operationalTrips=listOf(trip))
        val runtime = TimetableDisplayRuntime()
        runtime.enter(table,0,service,first,listOf("unit"))
        return Triple(runtime,table,service)
    }
    private fun TimetableDisplayRuntime.reach(run: String, call: String) = reached("table",0,"service",run,call,listOf("unit"))
    private fun TimetableDisplayRuntime.progress(table: Timetable, call: Triple<String,String,String>? = null, moving: Boolean = false) =
        captureProgress(table,0,"unit",call,moving)

    @Test fun `arrival survives prepared next mission and departure remains in transit at zero speed mid-leg`() {
        val position = TimetableDisplayPosition()
        val identity = Triple("service","first","origin")
        assertNull(position.arrivedCall)
        assertFalse(position.inTransit,"stationary alone is not an arrival or a departure")
        assertTrue(position.arrive(identity,frame()))
        assertFalse(position.observe(frame()),"a future submitted mission changes no arrival facts")
        assertEquals(identity,position.arrivedCall)
        assertTrue(position.observe(frame(21.0,1.0)))
        assertNull(position.arrivedCall)
        assertTrue(position.inTransit)
        assertFalse(position.observe(frame(25.0)),"a red-signal wait cannot invent an arrived call")
        assertTrue(position.inTransit)
        assertTrue(position.arrive(Triple("service","first","end"),frame(30.0)))
        assertFalse(position.inTransit)
        position.observe(frame(30.0,derailed=true))
        assertNull(position.arrivedCall)
        assertFalse(position.inTransit)
    }

    @Test fun `committed stopped reversal preserves occurrence but unexpected orientation does not`() {
        val position = TimetableDisplayPosition()
        val identity = Triple("service","first","end")
        position.arrive(identity,frame())
        assertTrue(position.rebaseReversal(frame(reversed=true)))
        assertFalse(position.observe(frame(reversed=true)))
        assertEquals(identity,position.arrivedCall)
        position.observe(frame(reversed=false))
        assertNull(position.arrivedCall)
        assertFalse(position.inTransit)
        assertFalse(position.arrive(identity,frame(speed=0.001)))
    }

    @Test fun `full sequence maps exact PASS and repeated station occurrences and merged boundary aliases`() {
        val (runtime,table,service) = scenario()
        assertEquals(TimetableDisplayPhase.UNKNOWN,runtime.progress(table).phase)
        runtime.proveFuture(table,0,service,service.runSegments.first(),listOf("unit")) { _,_ -> null }
        assertEquals(listOf("A","A","C","A"),runtime.snapshots(table,0,listOf("unit")).single().plannedStops.map { it.call.stationId })
        runtime.reach("first","origin")
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION,0),runtime.progress(table,Triple("service","first","origin")))
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT,1),runtime.progress(table,moving=true))
        runtime.reach("first","pass")
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT,2),runtime.progress(table,moving=true))
        assertEquals(TimetableDisplayPhase.UNKNOWN,runtime.progress(table,Triple("service","first","pass")).phase)
        runtime.reach("first","end")
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION,2),runtime.progress(table,Triple("service","first","end")))
        // The next ORIGIN has not been reached yet; the earlier terminal proof still addresses slot 2.
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION,2),runtime.progress(table,Triple("service","first","end")))
        runtime.enter(table,0,service,service.runSegments.last(),listOf("unit"))
        runtime.reach("second","origin2")
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION,2),runtime.progress(table,Triple("service","second","origin2")))
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT,3),runtime.progress(table,moving=true))
        runtime.reach("second","end2")
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION,3),runtime.progress(table,Triple("service","second","end2")))
        assertEquals(TimetableDisplayPhase.FINISHED,runtime.progress(table,moving=true).phase)
        runtime.retire("table",0,listOf("unit"))
        assertEquals(TimetableDisplayPhase.UNKNOWN,runtime.progress(table,Triple("service","second","end2"),true).phase)
        runtime.clear()
        assertEquals(TimetableDisplayPhase.UNKNOWN,runtime.progress(table).phase)
    }

    @Test fun `unresolved future and insufficient context never fabricate an index`() {
        val (runtime,table,_) = scenario()
        runtime.reach("first","origin"); runtime.reach("first","pass"); runtime.reach("first","end")
        assertEquals(listOf("service/second"),runtime.snapshots(table,0,listOf("unit")).single().unresolvedRuns)
        assertEquals(TimetableDisplayPhase.UNKNOWN,runtime.progress(table,moving=true).phase)
        assertEquals(TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION,2),runtime.progress(table,Triple("service","first","end")))
        assertThrows(IllegalArgumentException::class.java) { TimetableDisplayProgress(TimetableDisplayPhase.IN_TRANSIT) }
        assertThrows(IllegalArgumentException::class.java) { TimetableDisplayProgress(TimetableDisplayPhase.UNKNOWN,0) }
        assertThrows(IllegalArgumentException::class.java) { TimetableDisplayProgress(TimetableDisplayPhase.AT_STATION,-1) }
    }
    @Test fun `ordered movement cursor cannot consume a future PASS at an earlier non-call via occurrence`() {
        val stop = call("stop",TimetableServiceStopStatus.STOP,"C").copy(viaIds=listOf("B"))
        val pass = call("pass2",TimetableServiceStopStatus.PASS,"B").copy(viaIds=listOf("B"))
        val terminal = call("terminal",TimetableServiceStopStatus.TERMINAL,"D")
        val cursor = TimetableRunCallCursor(listOf(origin,stop,pass,terminal))
        assertNull(cursor.reached("B",true),"via B before STOP C is not later PASS B")
        assertEquals(stop,cursor.reached("C",false))
        assertNull(cursor.reached("B",true),"same-ID incoming via before PASS is a distinct occurrence")
        assertEquals(pass,cursor.reached("B",true))
        assertEquals(terminal,cursor.reached("D",false))
        assertTrue(cursor.valid)
        val wrongType = TimetableRunCallCursor(listOf(origin,pass))
        assertNull(wrongType.reached("B",false))
        assertFalse(wrongType.valid,"wrong type/order permanently invalidates this scope's display cursor")
    }

    @Test fun `later CHECK at a repeated origin waypoint cannot rewind arrival to the initial call`() {
        val central = call("central",TimetableServiceStopStatus.STOP,"C")
        val returned = call("returned",TimetableServiceStopStatus.STOP,"A")
        val terminal = call("terminal",TimetableServiceStopStatus.TERMINAL,"D")
        val cursor = TimetableRunCallCursor(listOf(origin,central,returned,terminal))
        val position = TimetableDisplayPosition()
        val first = checkNotNull(cursor.originForCheck("A"))
        position.arrive(Triple("service","run",first.id),frame())
        assertEquals(central,cursor.reached("C",false))
        assertEquals(returned,cursor.reached("A",false))
        position.arrive(Triple("service","run",returned.id),frame(30.0))
        val checkedOrigin = cursor.originForCheck("A")
        assertNull(checkedOrigin,"a successful later CHECK A is not proof of original occurrence A")
        assertEquals(Triple("service","run","returned"),position.arrivedCall)
        assertEquals(terminal,cursor.reached("D",false),"CHECK does not reset ordered movement progress")
    }

    @Test fun `BEGIN before an actual RUN provides values but not physical station progress`() {
        val (runtime,table,service) = scenario()
        // A separate next trip has not entered any actual leg yet.
        val upcoming = TimetableOperationalTrip("upcoming",segments=listOf(TimetableOperationalTripSegment("service","second",listOf(1))))
        val next = table.copy(operationalTrips=listOf(table.operationalTrips.single(),upcoming))
        runtime.begin(next,0,service,TimetableAction.BeginOperationalTrip("upcoming","second",listOf(1)),listOf("unit"))
        assertEquals(TimetableDisplayPhase.UNKNOWN,runtime.progress(next,moving=true).phase)
        assertEquals(TimetableDisplayPhase.UNKNOWN,runtime.progress(next,Triple("service","second","origin2")).phase)
    }

    @Test fun `effective door side survives mission preparation and reversal but clears on departure`() {
        val position = TimetableDisplayPosition()
        val call = Triple("service","first","origin")
        assertNull(position.doorSide,"no physical arrival cannot advertise a platform side")
        assertFalse(position.updateDoorSide(DoorSide.LEFT,frame()))
        position.arrive(call,frame(),DoorSide.LEFT)
        assertEquals(DoorSide.LEFT,position.doorSide)
        assertFalse(position.observe(frame()),"preparing another mission leaves the stopped proof intact")
        assertEquals(DoorSide.LEFT,position.doorSide)
        position.rebaseReversal(frame(reversed=true))
        assertEquals(DoorSide.RIGHT,position.doorSide,"same physical platform is RIGHT in reversed operating basis")
        assertEquals(call,position.arrivedCall)
        assertTrue(position.updateDoorSide(DoorSide.LEFT,frame(reversed=true)),"an explicit override replaces the inherited side")
        assertEquals(DoorSide.LEFT,position.doorSide)
        position.observe(frame(21.0,1.0,reversed=true))
        assertNull(position.doorSide)
        position.observe(frame(25.0,reversed=true))
        assertNull(position.doorSide,"zero speed in transit does not restore a stale opening side")
    }

    @Test fun `known NONE and BOTH remain distinct from unknown and do not flip on reversal`() {
        val call = Triple("service","first","origin")
        for (side in listOf(DoorSide.NONE,DoorSide.BOTH)) {
            val position = TimetableDisplayPosition()
            position.arrive(call,frame(),side)
            position.rebaseReversal(frame(reversed=true))
            assertEquals(side,position.doorSide)
            position.observe(frame(reversed=true,derailed=true))
            assertNull(position.doorSide)
        }
        val unknown = TimetableDisplayPosition()
        unknown.arrive(call,frame())
        unknown.rebaseReversal(frame(reversed=true))
        assertNull(unknown.doorSide)
    }

}
