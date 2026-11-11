package work.art1st.javarailways.bukkit.render.model

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.render.Animation
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.api.util.plugin.Plugin
import work.art1st.javarailways.core.api.util.tick.Synchronizable
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.api.util.tick.TickingObject
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

class AbstractModelAnimationTest {
    @Test
    fun `recursive apply skips missing animations and starts matching children`() {
        val world = FakeWorld()
        val child = TestModel(
            world = world,
            animation = mapOf("doors_l" to doorAnimation())
        )
        val parent = TestModel(
            world = world,
            attachedModels = listOf(child),
            animation = emptyMap()
        )

        assertFalse(parent.applyAnimation("missing"))
        assertEquals(0, world.tickManager.registered.size)

        assertFalse(parent.applyAnimation("doors_l", "missing_scene"))
        assertEquals(0, world.tickManager.registered.size)

        assertTrue(parent.applyAnimation("doors_l", "open"))
        assertEquals(1, world.tickManager.registered.size)
    }

    @Test
    fun `finished animation is held for future model updates`() {
        val world = FakeWorld()
        val model = TestModel(
            world = world,
            animation = mapOf("doors_l" to doorAnimation())
        )

        assertTrue(model.applyAnimation("doors_l", "open"))
        model.updateState(CompactTransform(), CompactTransform(), Vector3d())
        world.tickManager.registered.single().tick(1300.0)
        world.tickManager.commitAll()

        assertEquals(0.625, model.state.rootRelativeTransform.position.z(), 1e-6)
    }

    @Test
    fun `held animation scenes can be collected and restored instantly`() {
        val world = FakeWorld()
        val model = TestModel(
            world = world,
            animation = mapOf("doors_l" to doorAnimation())
        )

        assertTrue(model.setAnimationScene("doors_l", "open"))
        assertEquals(mapOf("doors_l" to "open"), model.collectHeldAnimationScenes())

        model.updateState(CompactTransform(), CompactTransform(), Vector3d())
        assertEquals(0.625, model.state.rootRelativeTransform.position.z(), 1e-6)
        assertTrue(world.tickManager.registered.isEmpty())
    }

    private fun doorAnimation(): Animation {
        return Animation(
            listOf(
                Animation.AnimationCommand(CompactTransform(), 0.0, "closed"),
                Animation.AnimationCommand(
                    CompactTransform(position = Vector3d(0.0, 0.0, 0.625)),
                    1.3,
                    "open"
                )
            )
        )
    }

    private class TestModel(
        world: World,
        attachedModels: Collection<AbstractModel> = emptyList(),
        animation: Map<String, Animation> = emptyMap()
    ) : AbstractModel(
        world = world,
        state = ModelState(CompactTransform()),
        attachedModels = attachedModels,
        animation = animation
    )

    @OptIn(ExtensionData::class)
    private class FakeWorld : World {
        override val executionMode = work.art1st.javarailways.core.api.physics.WorldExecutionMode.LIVE
        override val maximumPhysicsDeltaSeconds: Double = 0.2
        override val tickManager: FakeTickManager = FakeTickManager()
        override val topologyState: TopologyState
            get() = error("Topology is not used by this render test")
        override val allTrains: Collection<Train> = emptyList()
        override fun getTrain(name: String): Train? = null
        override fun getRail(id: UUID): Rail? = null
        override fun removeRail(rail: Rail): java.util.concurrent.CompletionStage<Void> =
            java.util.concurrent.CompletableFuture.completedFuture(null)
        override fun <T : Any> getExtensionDataOrNull(plugin: Plugin): T? = null
        override fun <T : Any> requireExtensionData(plugin: Plugin): T = error("No extensions")
        override fun <T : Any> registerExtensionData(plugin: Plugin, data: T) = Unit
        override fun forEachExtensionData(action: (Any) -> Unit) = Unit
    }

    private class FakeTickManager : TickManager {
        val registered: MutableList<TickingObject> = mutableListOf()
        override fun register(obj: TickingObject) {
            registered.add(obj)
        }
        override fun unregister(obj: TickingObject) {
            registered.remove(obj)
        }
        override fun preTick(currentTimeMs: Long) = Unit
        override fun postTick() = Unit
        fun commitAll() {
            registered.filterIsInstance<Synchronizable>().forEach { it.commit() }
        }
        override fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T> = runTask(task)
        private fun <T> runTask(task: Callable<T>): Future<T> {
            return FutureTask(task).also { it.run() }
        }
    }
}
