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
            index[key(rail)] = owner
            try {
                val clearing = work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run {
                    RailVisualBootstrap.unregisterRail(rail.world, rail.id).toCompletableFuture()
                }
                assertFalse(index.containsKey(key(rail)))
                assertFalse(clearing.isDone)
                val retiring = owner.destroy().toCompletableFuture()
                assertFalse(retiring.isDone)
                pending.complete(null)
                assertTrue(clearing.isDone)
                assertTrue(retiring.isDone)
                assertFalse(retiring.isCompletedExceptionally)
            } finally {
                index.remove(key(rail))
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

    @Test
    fun `retire world releases only its index and waits for its pending entity cleanup`() {
        TestWorldFixture.create().use { first ->
            TestWorldFixture.create().use { second ->
                val railA = createStraightRailFromSegments(first.world, PrimeCurveGenerator.buildStraight(10.0, 1))
                val railB = work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run {
                    second.world.railTopologyTransaction {
                        railA.save().loadDisconnected(CoreRail.SavedRailV1.Dependency(second.world))
                    }
                }
                // A copied rail ID must coexist in two exact runtime scopes.
                val copiedId = railA.id
                val keyA = RailVisualBootstrap.RailVisualKey(first.world.runtimeId, copiedId)
                val keyB = RailVisualBootstrap.RailVisualKey(second.world.runtimeId, copiedId)
                val ownerA = RailVisualBootstrap.RailVisualLifetime(railA)
                val ownerB = RailVisualBootstrap.RailVisualLifetime(railB)
                val pending = CompletableFuture<Void>()
                ownerA.segments = listOf(segment(railA, pending))
                index()[keyA] = ownerA
                index()[keyB] = ownerB
                try {
                    val retiring = RailVisualBootstrap.retireWorld(first.world).toCompletableFuture()
                    assertFalse(index().containsKey(keyA))
                    assertSame(ownerB, index()[keyB])
                    assertFalse(retiring.isDone)
                    pending.complete(null)
                    retiring.join()
                    assertSame(ownerB, index()[keyB])
                    work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run {
                        RailVisualBootstrap.unregisterRail(first.world, copiedId).toCompletableFuture().join()
                        assertSame(ownerB, index()[keyB])
                        RailVisualBootstrap.unregisterRail(second.world, copiedId).toCompletableFuture().join()
                    }
                    assertFalse(index().containsKey(keyB))
                } finally {
                    index().remove(keyA)
                    index().remove(keyB)
                    pending.complete(null)
                }
            }
        }
    }

    private fun key(rail: CoreRail) = RailVisualBootstrap.RailVisualKey(rail.world.runtimeId, rail.id)

    @Test
    fun `construction rollback retires index before asynchronous cleanup finishes`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world, PrimeCurveGenerator.buildStraight(10.0, 1))
            val owner = RailVisualBootstrap.RailVisualLifetime(rail)
            val pending = CompletableFuture<Void>()
            owner.segments = listOf(segment(rail, pending))
            index()[key(rail)] = owner
            val resources = WorldConstructionResources()
            var renderClosed = false
            var observedCleanupFailure: Throwable? = null
            resources.own { renderClosed = true }
            resources.own {
                RailVisualBootstrap.retireWorld(fixture.world).whenComplete { _, failure ->
                    observedCleanupFailure = failure
                }
            }
            try {
                val primary = IllegalArgumentException("later render metadata restore failed")
                assertSame(primary, assertThrows(IllegalArgumentException::class.java) { resources.fail(primary) })
                assertFalse(index().containsKey(key(rail)))
                assertTrue(renderClosed)
                assertFalse(pending.isDone, "rollback must not wait on a region cleanup task")
                val cleanupFailure = IllegalStateException("entity removal failed")
                pending.completeExceptionally(cleanupFailure)
                assertNotNull(observedCleanupFailure, "asynchronous failure must remain observable")
            } finally {
                index().remove(key(rail))
                pending.complete(null)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun index(): MutableMap<RailVisualBootstrap.RailVisualKey, RailVisualBootstrap.RailVisualLifetime> =
        RailVisualBootstrap::class.java.getDeclaredField("railVisuals").let {
            it.isAccessible = true
            it.get(RailVisualBootstrap) as MutableMap<RailVisualBootstrap.RailVisualKey, RailVisualBootstrap.RailVisualLifetime>
        }
}
