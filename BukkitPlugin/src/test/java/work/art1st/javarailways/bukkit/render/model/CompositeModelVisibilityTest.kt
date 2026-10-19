package work.art1st.javarailways.bukkit.render.model

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.CompletableFuture
import work.art1st.javarailways.core.api.util.DestructionSteps
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.api.util.plugin.Plugin
import work.art1st.javarailways.core.api.util.tick.TickManager
import work.art1st.javarailways.core.api.util.tick.TickingObject
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

class CompositeModelVisibilityTest {
    @Test
    fun `children spawn before root passenger packet and root hides first`() {
        val calls = mutableListOf<String>()
        val world = FakeWorld()
        val child = RecordingModel(world, calls)
        val root = RecordingVisual(calls)
        val composite = CompositeModel(world, listOf(child), root)
        val player = fakePlayer()

        composite.showTo(player)
        composite.hideFrom(player)

        assertEquals(listOf("child-show", "root-show", "root-hide", "child-hide"), calls)
    }

    @Test
    fun `destruction attempts all children waits for cleanup and retains the same outcome`() {
        val calls = mutableListOf<String>()
        val world = FakeWorld()
        val pending = CompletableFuture<Void>()
        val failedChild = object : AbstractModel(world, ModelState(CompactTransform()), emptyList(), emptyMap()) {
            override fun collectDestruction(steps: DestructionSteps) {
                steps.action { calls += "failed-child"; error("child cleanup failed") }
            }
        }
        val delayedChild = object : AbstractModel(world, ModelState(CompactTransform()), emptyList(), emptyMap()) {
            override fun collectDestruction(steps: DestructionSteps) {
                steps.await { calls += "delayed-child"; pending }
            }
        }
        val composite = CompositeModel(world, listOf(failedChild, delayedChild), RecordingVisual(calls))
        val result = composite.destroy().toCompletableFuture()
        assertEquals(listOf("failed-child", "delayed-child", "root-destroy"), calls)
        assertFalse(result.isDone)
        assertSame(result, composite.destroy())
        pending.complete(null)
        assertTrue(result.isCompletedExceptionally)
        assertSame(result, composite.destroy())
        assertEquals(3, calls.size)
    }

    private class RecordingModel(
        world: World,
        private val calls: MutableList<String>
    ) : AbstractModel(world, ModelState(CompactTransform()), emptyList(), emptyMap()) {
        override fun showVisualsTo(player: Player) {
            calls += "child-show"
        }

        override fun hideVisualsFrom(player: Player) {
            calls += "child-hide"
        }
    }

    private class RecordingVisual(private val calls: MutableList<String>) : DisplayVisual {
        override fun showTo(player: Player) { calls += "root-show" }
        override fun hideFrom(player: Player) { calls += "root-hide" }
        override fun teleport(location: Location) = Unit
        override fun setTransformation(transformation: Transformation) = Unit
        override fun setItemStack(itemStack: ItemStack) = Unit
        override fun setBrightness(blockLight: Int, skyLight: Int) = Unit
        override fun clearBrightness() = Unit
        override fun addPassenger(passenger: DisplayVisual): Boolean = true
        override fun destroy(): java.util.concurrent.CompletionStage<Void> {
            calls += "root-destroy"
            return CompletableFuture.completedFuture(null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fakePlayer(): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java)
    ) { _, method, _ ->
        when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    } as Player

    @OptIn(ExtensionData::class)
    private class FakeWorld : World {
        override val executionMode = work.art1st.javarailways.core.api.physics.WorldExecutionMode.LIVE
        override val tickManager: TickManager = FakeTickManager()
        override val topologyState: TopologyState
            get() = error("Topology is not used by this render test")
        override val allTrains: Collection<Train> = emptyList()
        override fun getTrain(name: String): Train? = null
        override fun getRail(id: UUID): Rail? = null
        override fun removeRail(rail: Rail) = Unit
        override fun <T : Any> getExtensionDataOrNull(plugin: Plugin): T? = null
        override fun <T : Any> requireExtensionData(plugin: Plugin): T = error("No extensions")
        override fun <T : Any> registerExtensionData(plugin: Plugin, data: T) = Unit
        override fun forEachExtensionData(action: (Any) -> Unit) = Unit
    }

    private class FakeTickManager : TickManager {
        override fun register(obj: TickingObject) = Unit
        override fun unregister(obj: TickingObject) = Unit
        override fun preTick(currentTimeMs: Long) = Unit
        override fun postTick() = Unit
        override fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T> = runTask(task)
        private fun <T> runTask(task: Callable<T>): Future<T> = FutureTask(task).also { it.run() }
    }
}
