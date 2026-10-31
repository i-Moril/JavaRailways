package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StationStopProgramRuntimeTest {
    private val end = StationProgramAction.End
    private class Ticket(var result: StationProgramTicketResult = StationProgramTicketResult.Pending) : StationProgramTicket {
        var cancellations = 0
        override fun poll() = result
        override fun cancel(): String? { cancellations++; result = StationProgramTicketResult.Cancelled("cancelled"); return null }
    }
    private fun definition(role: StationStopRole = StationStopRole.STOP,
        arrival: List<StationProgramAction> = listOf(end), preparation: List<StationProgramAction> = listOf(end),
        lead: Long = 0, departure: Long? = 100) = StationStopPrograms("central","call",role,arrival,preparation,StationPreparationLead.constant(lead),departure)

    @Test fun `preparation waits arrival main but overlaps descendants and final joins every descendant`() {
        val starts = mutableListOf<String>()
        val child = Ticket()
        val runtime = StationStopProgramRuntime(definition(arrival=listOf(
            StationProgramAction.Async("broadcast",listOf(StationProgramAction.Extension(work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall("test:action")))), end),
            preparation=listOf(StationProgramAction.CloseDoors,end),lead=20),0,0,false,1000,
            StationProgramActionHost { _,task,_ -> starts += task; if(task=="broadcast")child else null })
        runtime.tick(0,0)
        assertTrue(runtime.arrivalMainComplete)
        runtime.tick(79,79)
        assertEquals(listOf("broadcast"),starts)
        runtime.tick(80,80)
        assertEquals(listOf("broadcast","@preparation"),starts)
        assertFalse(runtime.allTasksComplete)
        assertFalse(runtime.finalGateSatisfied(100,100))
        child.result=StationProgramTicketResult.Succeeded
        runtime.tick(101,101)
        assertTrue(runtime.finalGateSatisfied(101,101))
    }

    @Test fun `origin runs preparation only and terminal runs arrival only even with a time gate`() {
        for(role in listOf(StationStopRole.ORIGIN,StationStopRole.TERMINAL)) {
            val starts=mutableListOf<StationProgramAction>()
            val runtime=StationStopProgramRuntime(definition(role,listOf(StationProgramAction.OpenDoors,end),
                listOf(StationProgramAction.CloseDoors,end),lead=20),0,0,false,1000,
                StationProgramActionHost { action,_,_ -> starts+=action;null })
            runtime.tick(0,0)
            runtime.tick(80,80)
            runtime.tick(100,100)
            assertEquals(if(role==StationStopRole.ORIGIN)listOf(StationProgramAction.CloseDoors) else listOf(StationProgramAction.OpenDoors),starts)
            assertTrue(runtime.finalGateSatisfied(100,100))
        }
    }

    @Test fun `default blocking door tickets and minimum dwell anchor use actual successful opening`() {
        val opened=Ticket()
        val closed=Ticket()
        val runtime=StationStopProgramRuntime(definition(arrival=listOf(StationProgramAction.OpenDoors,end),
            preparation=listOf(StationProgramAction.CloseDoors,end),lead=100),0,30,false,1000,
            StationProgramActionHost { action,_,_ -> if(action==StationProgramAction.OpenDoors)opened else closed })
        runtime.tick(0,0)
        assertFalse(runtime.arrivalMainComplete)
        opened.result=StationProgramTicketResult.Succeeded
        runtime.tick(80,80)
        runtime.tick(81,81)
        closed.result=StationProgramTicketResult.Succeeded
        runtime.tick(82,82)
        assertTrue(runtime.allTasksComplete)
        assertFalse(runtime.finalGateSatisfied(100,100))
        assertTrue(runtime.finalGateSatisfied(110,110))
    }

    @Test fun `reverse preserves the evidence of successfully closed doors`() {
        val closed = Ticket()
        val reversed = Ticket()
        val runtime = StationStopProgramRuntime(definition(StationStopRole.ORIGIN,
            preparation = listOf(StationProgramAction.CloseDoors, StationProgramAction.Reverse, end), lead = 100),
            0, 0, false, 1000, StationProgramActionHost { action, _, _ ->
                if (action == StationProgramAction.CloseDoors) closed else reversed
            })
        runtime.tick(0, 0)
        closed.result = StationProgramTicketResult.Succeeded
        runtime.tick(1, 1)
        reversed.result = StationProgramTicketResult.Succeeded
        runtime.tick(2, 2)
        assertTrue(runtime.doorsSecured)
        assertTrue(runtime.finalGateSatisfied(100, 100))
    }

    @Test fun `nested detached tasks and named waits have independent duration clocks`() {
        val runtime=StationStopProgramRuntime(definition(StationStopRole.TERMINAL,arrival=listOf(
            StationProgramAction.Async("outer",listOf(StationProgramAction.WaitDuration(10),
                StationProgramAction.Async("inner",listOf(StationProgramAction.WaitDuration(20))),StationProgramAction.Await("inner"))),
            StationProgramAction.Await("outer"),end),departure=null),0,0,true,1000,StationProgramActionHost { _,_,_->null })
        for(tick in 0L..29L) { runtime.tick(tick,tick);assertFalse(runtime.allTasksComplete) }
        for(tick in 30L..35L)runtime.tick(tick,tick)
        assertTrue(runtime.finalGateSatisfied(35,35))
    }

    @Test fun `explicit await joins descendants even after named task main body ended`() {
        var starts=0
        val runtime=StationStopProgramRuntime(definition(StationStopRole.TERMINAL,arrival=listOf(
            StationProgramAction.Async("outer",listOf(StationProgramAction.Async("inner",listOf(StationProgramAction.WaitDuration(20))))),
            StationProgramAction.Await("outer"),
            StationProgramAction.Extension(work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall("test:after")),end),departure=null),
            0,0,true,1000,StationProgramActionHost { _,_,_->starts++;null })
        for(tick in 0L..19L) { runtime.tick(tick,tick);assertEquals(0,starts) }
        for(tick in 20L..22L)runtime.tick(tick,tick)
        assertEquals(1,starts)
        assertTrue(runtime.allTasksComplete)
    }

    @Test fun `late absolute waits complete immediately while relative wait starts when reached`() {
        val runtime=StationStopProgramRuntime(definition(StationStopRole.TERMINAL,arrival=listOf(
            StationProgramAction.WaitUntil(10),StationProgramAction.WaitDuration(20),end),departure=null),100,0,true,1000,
            StationProgramActionHost { _,_,_->null })
        runtime.tick(100,100)
        runtime.tick(119,119)
        assertFalse(runtime.allTasksComplete)
        runtime.tick(120,120)
        assertTrue(runtime.allTasksComplete)
    }

    @Test fun `physical-operation conflicts fail and cancel all outstanding tickets`() {
        val open=Ticket()
        val runtime=StationStopProgramRuntime(definition(StationStopRole.TERMINAL,arrival=listOf(
            StationProgramAction.Async("doors",listOf(StationProgramAction.OpenDoors)),StationProgramAction.WaitDuration(1),
            StationProgramAction.CloseDoors,end),departure=null),0,0,false,1000,
            StationProgramActionHost { _,_,_->open })
        runtime.tick(0,0)
        runtime.tick(1,1)
        runtime.tick(2,2)
        assertNotNull(runtime.failure)
        assertFalse(runtime.allTasksComplete)
        assertTrue(open.cancellations>0)
    }

    @Test fun `failure cancellation timeout and thrown callback never release final gate`() {
        for(mode in 0..3) {
            val ticket=Ticket()
            val problem=IllegalStateException("original callback failure")
            val runtime=StationStopProgramRuntime(definition(StationStopRole.TERMINAL,arrival=listOf(StationProgramAction.OpenDoors,end),departure=null),
                0,0,false,10,StationProgramActionHost { _,_,_-> if(mode==3)throw problem else ticket })
            runtime.tick(0,0)
            ticket.result=when(mode) {
                0->StationProgramTicketResult.Failed("failed")
                1->StationProgramTicketResult.Cancelled("cancelled")
                else->StationProgramTicketResult.Pending
            }
            runtime.tick(10,10)
            assertNotNull(runtime.failure)
            if(mode==3)assertSame(problem,runtime.failure?.cause)
            assertFalse(runtime.finalGateSatisfied(100,100))
        }
    }

    @Test fun `shared definition never shares invocation task state`() {
        val shared=definition(StationStopRole.TERMINAL,arrival=listOf(StationProgramAction.Async("child",listOf(
            StationProgramAction.Extension(work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall("test:work")))),end),departure=null)
        val ticket=Ticket()
        val first=StationStopProgramRuntime(shared,0,0,true,1000,StationProgramActionHost { _,_,_->ticket })
        val second=StationStopProgramRuntime(shared,0,0,true,1000,StationProgramActionHost { _,_,_->null })
        first.tick(0,0)
        second.tick(0,0)
        assertFalse(first.allTasksComplete)
        assertTrue(second.allTasksComplete)
        ticket.result=StationProgramTicketResult.Succeeded
        first.tick(1,1)
        assertTrue(first.allTasksComplete)
    }

    @Test fun `doorless defaults require no invented door tickets`() {
        var starts=0
        val runtime=StationStopProgramRuntime(definition(arrival=listOf(StationProgramAction.OpenDoors,end),
            preparation=listOf(StationProgramAction.CloseDoors,end),lead=100),0,0,true,1000,
            StationProgramActionHost { _,_,_->starts++;null })
        runtime.tick(0,0)
        runtime.tick(1,1)
        runtime.tick(100,100)
        assertTrue(runtime.finalGateSatisfied(100,100))
        assertEquals(0,starts)
    }
    private fun dynamicDefinition(role: StationStopRole = StationStopRole.ORIGIN,
        arrival: List<StationProgramAction> = listOf(end)) = StationStopPrograms("central", "call", role,
            arrival, listOf(StationProgramAction.CloseDoors, end),
            StationPreparationLead(kotlinx.serialization.json.Json.parseToJsonElement("""{"doorCloseTicks":true}""")), 100)

    @Test fun `delayed planning query waits without door effects and long closing cannot depart early`() {
        val duration = java.util.concurrent.CompletableFuture<Long>()
        val closed = Ticket()
        var requests = 0
        val starts = mutableListOf<Long>()
        var tick = 0L
        val runtime = StationStopProgramRuntime(dynamicDefinition(), 0, 0, false, 1000,
            StationProgramActionHost { _, _, _ -> starts += tick; closed }, { requests++; duration })
        runtime.tick(0, 0)
        tick = 50; runtime.tick(tick, tick)
        assertEquals(1, requests)
        assertTrue(starts.isEmpty())
        duration.complete(120)
        tick = 51; runtime.tick(tick, tick)
        assertEquals(listOf(51L), starts)
        tick = 100; runtime.tick(tick, tick)
        assertFalse(runtime.finalGateSatisfied(tick, tick))
        tick = 170; runtime.tick(tick, tick)
        assertFalse(runtime.finalGateSatisfied(tick, tick))
        closed.result = StationProgramTicketResult.Succeeded
        tick = 171; runtime.tick(tick, tick)
        assertTrue(runtime.finalGateSatisfied(tick, tick))
    }

    @Test fun `dynamic lead starts at computed deadline but arrival main still gates preparation`() {
        val opened = Ticket()
        var closes = 0
        val runtime = StationStopProgramRuntime(dynamicDefinition(StationStopRole.STOP, listOf(StationProgramAction.OpenDoors, end)),
            0, 0, false, 1000, StationProgramActionHost { action, _, _ ->
                if (action == StationProgramAction.OpenDoors) opened else { closes++; null }
            }, { java.util.concurrent.CompletableFuture.completedFuture(80L) })
        runtime.tick(0, 0)
        runtime.tick(20, 20)
        assertEquals(0, closes)
        opened.result = StationProgramTicketResult.Succeeded
        runtime.tick(30, 30)
        assertEquals(1, closes)
        assertFalse(runtime.finalGateSatisfied(99, 99))
        assertTrue(runtime.finalGateSatisfied(100, 100))
    }

    @Test fun `terminal doorless and fixed timing never request model duration`() {
        for ((definition, doorless) in listOf(dynamicDefinition(StationStopRole.TERMINAL) to false,
            dynamicDefinition() to true, definition(StationStopRole.ORIGIN, preparation = listOf(StationProgramAction.CloseDoors, end)) to false)) {
            val runtime = StationStopProgramRuntime(definition, 0, 0, doorless, 1000,
                StationProgramActionHost { _, _, _ -> null }, { fail("unexpected duration query") })
            runtime.tick(0, 0); runtime.tick(100, 100)
            assertNull(runtime.failure)
            assertTrue(runtime.finalGateSatisfied(100, 100))
        }
    }

    @Test fun `query failure invalid duration timeout and cancellation preserve departure hold`() {
        for (duration in listOf(java.util.concurrent.CompletableFuture.failedFuture<Long>(IllegalStateException("model failed")),
            java.util.concurrent.CompletableFuture.completedFuture(-1L), java.util.concurrent.CompletableFuture<Long>().also { it.cancel(true) })) {
            val runtime = StationStopProgramRuntime(dynamicDefinition(), 0, 0, false, 10,
                StationProgramActionHost { _, _, _ -> fail("must not close") }, { duration })
            runtime.tick(0, 0)
            assertNotNull(runtime.failure)
            assertFalse(runtime.finalGateSatisfied(100, 100))
        }
        val pending = java.util.concurrent.CompletableFuture<Long>()
        val runtime = StationStopProgramRuntime(dynamicDefinition(), 0, 0, false, 10,
            StationProgramActionHost { _, _, _ -> fail("must not close") }, { pending })
        runtime.tick(0, 0); runtime.tick(10, 10)
        assertNotNull(runtime.failure)
        assertTrue(pending.isCancelled)
        assertFalse(runtime.finalGateSatisfied(100, 100))
        val cancelled = java.util.concurrent.CompletableFuture<Long>()
        val stopped = StationStopProgramRuntime(dynamicDefinition(), 0, 0, false, 10,
            StationProgramActionHost { _, _, _ -> null }, { cancelled })
        stopped.tick(0, 0)
        assertNull(stopped.cancelOutstanding())
        assertTrue(cancelled.isCancelled)
    }

}
