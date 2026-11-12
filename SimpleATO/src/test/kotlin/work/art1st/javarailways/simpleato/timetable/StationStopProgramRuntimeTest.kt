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

    @Test fun `post commit evidence distinguishes exact deadline from one tick late without opening gates`() {
        for (completion in listOf(2400L, 2401L)) {
            val ticket = Ticket()
            val runtime = StationStopProgramRuntime(definition(preparation = listOf(StationProgramAction.CloseDoors, end),
                lead = 38, departure = 400), 2000, 400, false, 1000, StationProgramActionHost { _, _, _ -> ticket })
            runtime.tick(362, 2362)
            runtime.tick(completion - 2000, completion)
            ticket.result = StationProgramTicketResult.Succeeded // Actual effect commit, after Dispatcher.
            runtime.recordCommittedTicketCompletions(completion)
            runtime.recordCommittedTicketCompletions(completion + 1) // First observation must win.
            assertFalse(runtime.allTasksComplete)
            assertFalse(runtime.doorsSecured)
            assertFalse(runtime.finalGateSatisfied(completion - 2000, completion))
            runtime.tick(completion + 1 - 2000, completion + 1)
            assertEquals(completion, runtime.allTasksCompletedAtDispatcherTick)
            assertEquals(completion == 2400L, runtime.allTasksCompletedAtDispatcherTick!! <= 2400)
            assertTrue(runtime.finalGateSatisfied(completion + 1 - 2000, completion + 1))
        }
    }

    @Test fun `completion observation does not backdate follow on wait or synchronous work`() {
        val display = StationProgramAction.Extension(work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall("test:display"))
        for ((following, expected) in listOf(
            StationProgramAction.WaitDuration(5) to 406L,
            StationProgramAction.WaitUntil(390) to 401L,
            StationProgramAction.Async("following", listOf(display)) to 401L,
            display to 401L)) {
            val ticket = Ticket()
            val starts = mutableListOf<StationProgramAction>()
            val runtime = StationStopProgramRuntime(definition(preparation = listOf(StationProgramAction.CloseDoors, following, end),
                lead = 38, departure = 400), 0, 400, false, 1000, StationProgramActionHost { action, _, _ ->
                starts += action
                if (action == StationProgramAction.CloseDoors) ticket else null
            })
            runtime.tick(362, 362)
            runtime.tick(400, 400)
            ticket.result = StationProgramTicketResult.Succeeded
            runtime.recordCommittedTicketCompletions(400)
            assertEquals(listOf(StationProgramAction.CloseDoors), starts)
            for (tick in 401..expected) runtime.tick(tick, tick)
            assertEquals(expected, runtime.allTasksCompletedAtDispatcherTick)
        }
    }

    @Test fun `pure joins retain real child completion but later children remain required`() {
        val child = Ticket()
        val closed = Ticket()
        val runtime = StationStopProgramRuntime(definition(arrival = listOf(StationProgramAction.Async("child", listOf(
            StationProgramAction.Extension(work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall("test:child")))), end),
            preparation = listOf(StationProgramAction.CloseDoors, StationProgramAction.Await("child"), end),
            lead = 38, departure = 400), 0, 400, false, 1000,
            StationProgramActionHost { _, task, _ -> if (task == "child") child else closed })
        runtime.tick(0, 0); runtime.tick(362, 362); runtime.tick(400, 400)
        closed.result = StationProgramTicketResult.Succeeded
        runtime.recordCommittedTicketCompletions(400)
        runtime.tick(401, 401)
        assertFalse(runtime.allTasksComplete)
        child.result = StationProgramTicketResult.Succeeded
        runtime.recordCommittedTicketCompletions(402)
        runtime.tick(403, 403); runtime.tick(404, 404)
        assertEquals(402L, runtime.allTasksCompletedAtDispatcherTick)
    }

    @Test fun `pure join cannot replace the parents later completed wait with an earlier child tick`() {
        val child = Ticket()
        val runtime = StationStopProgramRuntime(definition(StationStopRole.TERMINAL, arrival = listOf(
            StationProgramAction.Async("child", listOf(StationProgramAction.Extension(
                work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall("test:child")))),
            StationProgramAction.WaitDuration(405), StationProgramAction.Await("child"), end), departure = null),
            0, 0, true, 1000, StationProgramActionHost { _, _, _ -> child })
        runtime.tick(0, 0); runtime.tick(400, 400)
        child.result = StationProgramTicketResult.Succeeded
        runtime.recordCommittedTicketCompletions(400)
        runtime.tick(401, 401)
        assertFalse(runtime.allTasksComplete)
        runtime.tick(405, 405)
        assertEquals(405L, runtime.allTasksCompletedAtDispatcherTick)
    }

    @Test fun `sampling failures or absent sampling never invents a completion tick`() {
        for (result in listOf(StationProgramTicketResult.Succeeded, StationProgramTicketResult.Failed("broken"),
            StationProgramTicketResult.Cancelled("cancelled"))) {
            val ticket = Ticket()
            val runtime = StationStopProgramRuntime(definition(preparation = listOf(StationProgramAction.CloseDoors, end),
                lead = 38, departure = 400), 0, 400, false, 1000, StationProgramActionHost { _, _, _ -> ticket })
            runtime.tick(362, 362)
            runtime.recordCommittedTicketCompletions(400) // Still pending.
            ticket.result = result
            runtime.tick(401, 401)
            if (result == StationProgramTicketResult.Succeeded) assertEquals(401L, runtime.allTasksCompletedAtDispatcherTick)
            else { assertNotNull(runtime.failure); assertNull(runtime.allTasksCompletedAtDispatcherTick) }
        }
    }

    @Test fun `recorded success cannot override subsequent invalid terminal state`() {
        for (failure in listOf(StationProgramTicketResult.Failed("broken"), StationProgramTicketResult.Cancelled("cancelled"))) {
            val ticket = Ticket()
            val runtime = StationStopProgramRuntime(definition(preparation = listOf(StationProgramAction.CloseDoors, end),
                lead = 38, departure = 400), 0, 400, false, 1000, StationProgramActionHost { _, _, _ -> ticket })
            runtime.tick(362, 362)
            ticket.result = StationProgramTicketResult.Succeeded
            runtime.recordCommittedTicketCompletions(400)
            ticket.result = failure // Broken adapter must still fail, never use stale success as permission.
            runtime.tick(401, 401)
            assertNotNull(runtime.failure)
            assertNull(runtime.allTasksCompletedAtDispatcherTick)
            assertFalse(runtime.doorsSecured)
        }
    }

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
                listOf(StationProgramAction.CloseDoors,end),lead=20),0,10000,false,1000,
                StationProgramActionHost { action,_,_ -> starts+=action;null })
            runtime.tick(0,0)
            runtime.tick(80,80)
            runtime.tick(100,100)
            assertEquals(if(role==StationStopRole.ORIGIN)listOf(StationProgramAction.CloseDoors) else listOf(StationProgramAction.OpenDoors),starts)
            assertTrue(runtime.finalGateSatisfied(100,100))
        }
    }

    @Test fun `opening completion does not restart the actual arrival minimum`() {
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
        assertTrue(runtime.finalGateSatisfied(100,100))
    }

    @Test fun `late arrival includes open and close within one minimum across different clock epochs`() {
        var now = 1001L // Real arrival was 1000; the program was created the following tick.
        val starts = mutableListOf<Pair<StationProgramAction, Long>>()
        val opened = Ticket()
        val closed = Ticket()
        val runtime = StationStopProgramRuntime(definition(arrival = listOf(StationProgramAction.OpenDoors, end),
            preparation = listOf(StationProgramAction.CloseDoors, end), lead = 20, departure = 50),
            1000, 100, false, 1000, StationProgramActionHost { action, _, _ ->
                starts += action to now
                if (action == StationProgramAction.OpenDoors) opened else closed
            })
        fun tick(value: Long) { now = value; runtime.tick(value - 900, value) }
        tick(1001)
        opened.result = StationProgramTicketResult.Succeeded
        tick(1030); tick(1079)
        assertEquals(listOf(StationProgramAction.OpenDoors to 1001L), starts)
        tick(1080)
        assertEquals(StationProgramAction.CloseDoors to 1080L, starts.last())
        tick(1099)
        assertFalse(runtime.finalGateSatisfied(199, 1099))
        closed.result = StationProgramTicketResult.Succeeded
        tick(1100)
        assertTrue(runtime.finalGateSatisfied(200, 1100))
        assertNull(runtime.failure)
    }

    @Test fun `custom lead may close early but departure still waits original arrival minimum`() {
        val starts = mutableListOf<Pair<StationProgramAction, Long>>()
        var now = 1000L
        val closed = Ticket()
        val sideAction = StationProgramAction.Extension(work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall("test:display"))
        val runtime = StationStopProgramRuntime(definition(arrival = listOf(
            StationProgramAction.Async("display", listOf(StationProgramAction.WaitDuration(10), sideAction)), end),
            preparation = listOf(StationProgramAction.CloseDoors, end), lead = 200, departure = 900),
            1000, 100, false, 1000, StationProgramActionHost { action, _, _ ->
                starts += action to now
                if (action == StationProgramAction.CloseDoors) closed else null
            })
        for (tick in 1000L..1019L) { now = tick; runtime.tick(tick, tick) }
        assertEquals(listOf(StationProgramAction.CloseDoors to 1000L, sideAction to 1010L), starts)
        closed.result = StationProgramTicketResult.Succeeded
        now = 1020; runtime.tick(now, now)
        assertTrue(runtime.allTasksComplete)
        assertTrue(runtime.doorsSecured)
        assertFalse(runtime.finalGateSatisfied(now, now))
        now = 1099; runtime.tick(now, now)
        assertFalse(runtime.finalGateSatisfied(now, now))
        now = 1100; runtime.tick(now, now)
        assertTrue(runtime.finalGateSatisfied(now, now))
    }

    @Test fun `preparation query timeout starts at request not original arrival`() {
        val pending = java.util.concurrent.CompletableFuture<Long>()
        val runtime = StationStopProgramRuntime(dynamicDefinition(), 0, 0, false, 10,
            StationProgramActionHost { _, _, _ -> fail("must not close") }, { pending })
        runtime.tick(980, 980)
        assertNull(runtime.failure)
        runtime.tick(989, 989)
        assertNull(runtime.failure)
        runtime.tick(990, 990)
        assertNotNull(runtime.failure)
        assertTrue(pending.isCancelled)
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
        var queries = 0
        val runtime = StationStopProgramRuntime(dynamicDefinition(StationStopRole.STOP, listOf(StationProgramAction.OpenDoors, end)),
            0, 0, false, 1000, StationProgramActionHost { action, _, _ ->
                if (action == StationProgramAction.OpenDoors) opened else { closes++; null }
            }, { queries++; java.util.concurrent.CompletableFuture.completedFuture(80L) })
        runtime.tick(0, 0)
        runtime.tick(20, 20)
        assertEquals(0, closes)
        assertEquals(0, queries)
        opened.result = StationProgramTicketResult.Succeeded
        runtime.tick(30, 30)
        assertEquals(1, closes)
        assertEquals(1, queries)
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
