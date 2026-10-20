package work.art1st.javarailways.core.physics.tick

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.testing.IsolateGlobalHalt
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class RailwaySimulationHaltTest {
    @ParameterizedTest
    @ValueSource(strings = ["first fault", "independent invocation"])
    @IsolateGlobalHalt
    fun `terminal gate affects existing and later coordinators without production reset`(message: String) {
        assertFalse(RailwaySimulationHalt.isHalted, "each invocation must start in a fresh process")
        val cause = IllegalStateException(message)
        PhysicsCoordinator(1, automaticFallback = false).use { first ->
            PhysicsCoordinator(1, automaticFallback = false).use { second ->
                val firstPending = first.submitBetweenFrames { error("halted work must not execute") }
                val secondPending = second.submitBetweenFrames { error("halted work must not execute") }
                val failure = RailwaySimulationHalt.halt(message, cause)
                assertSame(cause, failure.cause)
                assertSame(failure, RailwaySimulationHalt.halt("later failure"))
                assertEquals(PhysicsCoordinatorStatus.STALLED, first.status)
                assertEquals(PhysicsCoordinatorStatus.STALLED, second.status)
                listOf(firstPending, secondPending).forEach { pending ->
                    val rejected = assertThrows(ExecutionException::class.java) {
                        pending.get(5, TimeUnit.SECONDS)
                    }
                    assertSame(failure, rejected.cause)
                }
            }
        }
        assertTrue(RailwaySimulationHalt.isHalted, "closing every coordinator must not reset production state")
        PhysicsCoordinator(1, automaticFallback = false).use { later ->
            assertEquals(PhysicsCoordinatorStatus.STALLED, later.status)
            assertThrows(PhysicsStalledException::class.java) { later.start() }
            assertTrue(later.submitBetweenFrames { "not admitted" }.isCompletedExceptionally)
        }
    }
}
