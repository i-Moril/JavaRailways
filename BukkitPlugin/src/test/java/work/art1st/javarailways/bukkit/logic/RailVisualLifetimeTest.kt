package work.art1st.javarailways.bukkit.logic

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.extension.rail.RailArcVisualSegment
import work.art1st.javarailways.core.api.render.RenderableExtension
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.api.util.plugin.Plugin
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

@OptIn(Heavy::class)
class RailVisualLifetimeTest {
    @Test
    fun `unregister releases global ownership but rail lifetime awaits earlier cleanup`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world, PrimeCurveGenerator.buildStraight(10.0, 1))
            val owner = RailVisualBootstrap.RailVisualLifetime(rail)
            val pending = CompletableFuture<Void>()
            owner.segments = listOf(segment(rail, pending))
            val index = index()
            index[rail.id] = owner
            try {
                val clearing = work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run {
                    RailVisualBootstrap.unregisterRail(rail.id).toCompletableFuture()
                }
                assertFalse(index.containsKey(rail.id))
                assertFalse(clearing.isDone)
                val retiring = owner.destroy().toCompletableFuture()
                assertFalse(retiring.isDone)
                pending.complete(null)
                assertTrue(clearing.isDone)
                assertTrue(retiring.isDone)
                assertFalse(retiring.isCompletedExceptionally)
            } finally {
                index.remove(rail.id)
            }
        }
    }

    @Test
    fun `failed earlier cleanup survives later empty clear and retirement`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world, PrimeCurveGenerator.buildStraight(10.0, 1))
            val owner = RailVisualBootstrap.RailVisualLifetime(rail)
            val pending = CompletableFuture<Void>()
            owner.segments = listOf(segment(rail, pending))
            owner.clear()
            pending.completeExceptionally(IllegalStateException("entity removal failed"))
            assertThrows(CompletionException::class.java) { owner.clear().toCompletableFuture().join() }
            assertThrows(CompletionException::class.java) { owner.destroy().toCompletableFuture().join() }
        }
    }

    @Test
    fun `uninitialized segment has no indexed or platform resources to clean`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world, PrimeCurveGenerator.buildStraight(10.0, 1))
            val owner = RailVisualBootstrap.RailVisualLifetime(rail)
            owner.segments = listOf(RailArcVisualSegment(rail, 0.0, 1.0))
            assertDoesNotThrow { owner.destroy().toCompletableFuture().join() }
        }
    }

    private fun segment(rail: CoreRail,
        completion: CompletableFuture<Void>): RailArcVisualSegment = RailArcVisualSegment(rail, 0.0, 1.0).also {
        it.registerExtensionData(object : Plugin { override var identifier = 0 },
            object : RenderableExtension<RailArcVisualSegment> {
                override fun initialize(host: RailArcVisualSegment, savedExtensionData: Map<String, SerializableExtensionData>) = Unit
                override fun save(): Pair<String, SerializableExtensionData>? = null
                override fun commit() = Unit
                override fun destroy() = completion
            })
    }

    @Suppress("UNCHECKED_CAST")
    private fun index(): MutableMap<UUID, RailVisualBootstrap.RailVisualLifetime> =
        RailVisualBootstrap::class.java.getDeclaredField("railVisuals").let {
            it.isAccessible = true
            it.get(RailVisualBootstrap) as MutableMap<UUID, RailVisualBootstrap.RailVisualLifetime>
        }
}
