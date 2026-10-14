package work.art1st.javarailways.core.physics

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.api.util.tick.TickingObject
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.di.WorldContextRegistration
import work.art1st.javarailways.core.di.WorldParentContext
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.SavedTrainV1
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

class CoreWorldRuntimeBindingTest {
    @Test
    fun `runtime binding release is idempotent and does not close caller resources`() {
        val parent = RecordingWorldParentContext()
        val physics = PhysicsCoordinator(1, automaticFallback = false)
        val world = CoreWorld.createEmpty(DummyTickManager, parent, physics)

        world.releaseRuntimeBinding()
        world.releaseRuntimeBinding()

        assertEquals(1, parent.closeCalls)
        assertSame(world, parent.attachedWorld)
        assertEquals(work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus.RUNNING, physics.status)
        physics.close()
    }

    @Test
    fun `constructor rolls back runtime binding when coordinator attachment fails`() {
        val physics = PhysicsCoordinator(1, automaticFallback = false)
        val owner = CoreWorld.createEmpty(DummyTickManager, NoOpWorldParentContext, physics)
        val parent = RecordingWorldParentContext()
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                CoreWorld.createEmpty(DummyTickManager, parent, physics)
            }

            assertEquals("PhysicsCoordinator is already attached to a world", failure.message)
            assertEquals(1, parent.closeCalls)
        } finally {
            owner.releaseRuntimeBinding()
            physics.close()
        }
    }

    @Test
    fun `load failure releases runtime binding and suppresses cleanup failure`() {
        val cleanupFailure = IllegalStateException("binding close failed")
        val parent = RecordingWorldParentContext(cleanupFailure)
        val physics = PhysicsCoordinator(1, automaticFallback = false)
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                IsolatedSimulationThreadMarker.run { CoreWorld.load(
                    emptyInfrastructure(),
                    brokenRuntime(),
                    CoreWorld.SnapshotDependency(DummyTickManager, parent, physics)
                ) }
            }

            assertEquals(1, parent.closeCalls)
            assertEquals(listOf(cleanupFailure), failure.suppressed.toList())
            assertEquals("Failed to restore train 'broken-train' [broken-id]", failure.message)
        } finally {
            physics.close()
        }
    }

    private class RecordingWorldParentContext(
        private val closeFailure: Throwable? = null
    ) : WorldParentContext {
        var attachedWorld: CoreWorld? = null
        var closeCalls: Int = 0

        override fun attachWorld(world: CoreWorld): WorldContextRegistration {
            attachedWorld = world
            val closed = AtomicBoolean(false)
            return WorldContextRegistration {
                if (closed.compareAndSet(false, true)) {
                    closeCalls++
                    closeFailure?.let { throw it }
                }
            }
        }
    }

    private object DummyTickManager : TickManager {
        override fun register(obj: TickingObject) = Unit
        override fun unregister(obj: TickingObject) = Unit
        override fun preTick(currentTimeMs: Long) = Unit
        override fun postTick() = Unit
        override fun <T> scheduleMainThreadAfterCommitTask(task: Callable<T>): Future<T> =
            FutureTask(task).apply { run() }

        override fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T> =
            FutureTask(task).apply { run() }
    }

    private fun emptyInfrastructure() = CoreWorld.SavedInfrastructureV2(
        formatVersion = CoreWorld.INFRASTRUCTURE_SNAPSHOT_FORMAT_VERSION,
        infrastructureRevision = 7L,
        rails = emptyList()
    )

    private fun brokenRuntime() = CoreWorld.SavedRuntimeV1(
        formatVersion = CoreWorld.RUNTIME_SNAPSHOT_FORMAT_VERSION,
        infrastructureRevision = 7L,
        physicsEpoch = 1L,
        trains = listOf(
            SavedTrainV1(
                id = "broken-id",
                name = "broken-train",
                cars = emptyList(),
                reversed = false,
                extension = emptyMap(),
                headLocation = missingRailLocation(),
                tailLocation = missingRailLocation()
            )
        )
    )

    private fun missingRailLocation() = RailLocationWithFacing.SavedRailLocationWithFacingV1(
        railId = "00000000-0000-0000-0000-000000000002",
        arcLengthInCurveAB = 0.0,
        direction = ViewDirection.AB
    )
}
