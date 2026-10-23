package work.art1st.javarailways.bukkit.logic

import org.bukkit.World as BukkitWorld
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.api.threading.host.ThreadGuardRegistration
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.bukkit.extension.WorldModelOperations
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture

class WorldManagerUnloadTest {
    private var threadGuardRegistration: ThreadGuardRegistration? = null

    @BeforeEach
    fun installMainThreadBridge() {
        threadGuardRegistration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = true
        })
    }

    @AfterEach
    fun clearMainThreadBridge() {
        threadGuardRegistration?.close()
        threadGuardRegistration = null
    }

    @Test
    fun `cancelled Bukkit unload leaves registered world runtime alive`() {
        val fixture = Fixture()
        try {
            val handler = WorldManager::class.java.getMethod("onWorldUnload", org.bukkit.event.world.WorldUnloadEvent::class.java)
                .getAnnotation(org.bukkit.event.EventHandler::class.java)
            assertEquals(org.bukkit.event.EventPriority.MONITOR, handler.priority)
            assertTrue(handler.ignoreCancelled)
            val plugin = Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin::class.java.classLoader,
                arrayOf(org.bukkit.plugin.Plugin::class.java)
            ) { _, method, _ -> error("Unexpected plugin call: ${method.name}") } as org.bukkit.plugin.Plugin
            val listener = org.bukkit.plugin.RegisteredListener(
                fixture.manager,
                { _, event -> fixture.manager.onWorldUnload(event as org.bukkit.event.world.WorldUnloadEvent) },
                handler.priority,
                plugin,
                handler.ignoreCancelled
            )
            val event = org.bukkit.event.world.WorldUnloadEvent(fixture.oldBukkitWorld).apply { isCancelled = true }
            listener.callEvent(event)
            assertSame(fixture.oldCoreWorld(), fixture.manager.loadedRailWorld(fixture.worldId))
            assertSame(fixture.oldCoreWorld(), fixture.manager.railWorlds[fixture.oldBukkitWorld])
            assertFalse(fixture.pendingModelOperation.isDone)
            assertTrue(fixture.events.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    @OptIn(work.art1st.javarailways.core.api.util.annotation.AsyncEventApi::class)
    fun `failed capture retains world resources until post event and generic callbacks actually exit`() {
        val fixture = Fixture()
        val world = fixture.oldCoreWorld()
        val postEntered = java.util.concurrent.CountDownLatch(1)
        val genericEntered = java.util.concurrent.CountDownLatch(1)
        val postInterrupted = java.util.concurrent.CountDownLatch(1)
        val genericInterrupted = java.util.concurrent.CountDownLatch(1)
        val releasePost = java.util.concurrent.CountDownLatch(1)
        val releaseGeneric = java.util.concurrent.CountDownLatch(1)
        val resourcesClosed = java.util.concurrent.atomic.AtomicBoolean()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val captureFailure = IllegalStateException("capture failed")
        fun waitIgnoringInterrupt(release: java.util.concurrent.CountDownLatch, interrupted: java.util.concurrent.CountDownLatch) {
            while (true) {
                try { release.await(); return } catch (_: InterruptedException) { interrupted.countDown() }
            }
        }
        val listener = object : work.art1st.javarailways.core.api.event.processor.EventListener {
            @work.art1st.javarailways.core.api.event.processor.HandlesEvent
            fun post(event: work.art1st.javarailways.core.api.event.PostTickEvent) {
                if (event.world !== world) return
                postEntered.countDown()
                waitIgnoringInterrupt(releasePost, postInterrupted)
            }
        }
        val registration = work.art1st.javarailways.core.api.event.processor.PhysicsRailEventBus.register(listener)
        try {
            world.tickManager.register(object : work.art1st.javarailways.core.api.util.tick.TickingObject {
                override fun tick(deltaT: Double) {
                    genericEntered.countDown()
                    waitIgnoringInterrupt(releaseGeneric, genericInterrupted)
                }
            })
            world.tickManager.postTick()
            world.tickManager.preTick(System.currentTimeMillis() + 50L)
            world.physicsCoordinator.start()
            val ticket = world.physicsCoordinator.stepSimulation(50.0)
            ticket.await()
            assertTrue(postEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(genericEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val closed = executor.submit<Throwable?> {
                runCatching {
                    fixture.manager.unregisterRailWorld(fixture.oldBukkitWorld,
                        captureAndSave = { throw captureFailure }, clearAbnormalMarkers = {}, closeRender = {},
                        closeModelOperations = { resourcesClosed.set(true) }, closeTick = {})
                }.exceptionOrNull()
            }
            assertTrue(postInterrupted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(resourcesClosed.get())
            assertFalse(closed.isDone)
            releasePost.countDown()
            assertTrue(genericInterrupted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(resourcesClosed.get())
            assertFalse(closed.isDone)
            releaseGeneric.countDown()
            assertSame(captureFailure, closed.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(resourcesClosed.get())
        } finally {
            releasePost.countDown()
            releaseGeneric.countDown()
            registration.close()
            executor.shutdownNow()
            fixture.close()
        }
    }

    @Test
    fun `unavailable physics still retires exact local manual references before teardown`() {
        val released = mutableListOf<CoreWorld>()
        val retired = mutableListOf<CoreWorld>()
        val fixture = Fixture({ released += it }, { retired += it })
        val saveFailure = IllegalStateException("closed world cannot save")
        fixture.manager.rememberServerTickMetrics(fixture.oldBukkitWorld,
            work.art1st.javarailways.bukkit.debug.JavaRailwaysServerTickMetrics(1L, 2L, 3L, 5L, emptyMap()))
        try {
            val oldWorld = fixture.closePhysics()
            val thrown = assertThrows(IllegalStateException::class.java) {
                fixture.manager.unregisterRailWorld(
                    bukkitWorld = fixture.oldBukkitWorld,
                    captureAndSave = { fixture.events += "persist"; throw saveFailure },
                    clearAbnormalMarkers = { fixture.events += "marker" },
                    closeRender = { fixture.events += "render" },
                    closeModelOperations = { fixture.closeModelOperations() },
                    closeTick = { fixture.events += "tick" }
                )
            }
            assertNull(fixture.manager.latestServerTickMetrics())
            assertTrue(thrown.message.orEmpty().contains("closed"))
            assertTrue(thrown.suppressed.contains(saveFailure))
            assertTrue(released.isEmpty(), "unavailable physics must not run physical lifecycle work")
            assertEquals(1, retired.size)
            assertSame(oldWorld, retired.single())
            assertEquals(listOf("persist", "model-operations", "render", "tick", "marker"), fixture.events)
            fixture.assertOldOwnerRemovedAndReloadable()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `persistence failure preserves the primary error and completes every teardown step`() {
        val fixture = Fixture()
        val primary = IllegalStateException("persistence failed")
        val renderFailure = IllegalArgumentException("render failed")
        val tickFailure = IllegalStateException("tick failed")
        val markerFailure = UnsupportedOperationException("marker failed")
        val modelFailure = IllegalStateException("model operations cleanup failed")
        try {
            val thrown = assertThrows(IllegalStateException::class.java) {
                fixture.manager.unregisterRailWorld(
                    bukkitWorld = fixture.oldBukkitWorld,
                    captureAndSave = {
                        fixture.events += "persist"
                        throw primary
                    },
                    clearAbnormalMarkers = {
                        fixture.events += "marker"
                        throw markerFailure
                    },
                    closeRender = {
                        fixture.events += "render"
                        throw renderFailure
                    },
                    closeModelOperations = {
                        fixture.closeModelOperations()
                        throw modelFailure
                    },
                    closeTick = {
                        fixture.events += "tick"
                        throw tickFailure
                    }
                )
            }

            assertSame(primary, thrown)
            assertEquals(listOf(modelFailure, renderFailure, tickFailure, markerFailure), thrown.suppressed.toList())
            assertEquals(listOf("persist", "model-operations", "render", "tick", "marker"), fixture.events)
            assertTrue(fixture.pendingModelOperation.isCompletedExceptionally)
            assertFalse(fixture.pendingModelOperation.complete(Unit))
            fixture.assertOldOwnerRemovedAndReloadable()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `teardown failure continues cleanup without removing a replacement owner`() {
        val fixture = Fixture()
        val renderFailure = IllegalStateException("render failed")
        try {
            val thrown = assertThrows(IllegalStateException::class.java) {
                fixture.manager.unregisterRailWorld(
                    bukkitWorld = fixture.oldBukkitWorld,
                    captureAndSave = { fixture.events += "persist" },
                    clearAbnormalMarkers = { fixture.events += "marker" },
                    closeRender = {
                        fixture.events += "render"
                        throw renderFailure
                    },
                    closeModelOperations = { fixture.closeModelOperations() },
                    closeTick = {
                        fixture.events += "tick"
                        fixture.publishReplacementOnOriginalKey()
                    }
                )
            }

            assertSame(renderFailure, thrown)
            assertEquals(emptyList<Throwable>(), thrown.suppressed.toList())
            assertEquals(listOf("persist", "model-operations", "render", "tick", "marker"), fixture.events)
            assertTrue(fixture.pendingModelOperation.isCompletedExceptionally)
            fixture.assertReplacementPreserved()
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        onRelease: (CoreWorld) -> Unit = {},
        onRetire: (CoreWorld) -> Unit = {}
    ) {
        val worldId: UUID = UUID.randomUUID()
        val oldBukkitWorld: BukkitWorld = bukkitWorld(worldId)
        private val newBukkitWorld: BukkitWorld = bukkitWorld(worldId)
        private val oldWorld: CoreWorld = createTestWorld(work.art1st.javarailways.core.physics.tick.PhysicsCoordinator(1, automaticFallback = false))
        private val newWorld: CoreWorld = createTestWorld(work.art1st.javarailways.core.physics.tick.PhysicsCoordinator(1, automaticFallback = false))
        private val railWorlds = ConcurrentHashMap<BukkitWorld, World>()
        private val registry = LoadedRailWorldRegistry(railWorlds)
        val manager = WorldManager(
            railWorlds, loadedWorlds = registry,
            releaseManualControl = onRelease, retireManualControl = onRetire
        )
        val events = mutableListOf<String>()
        private val modelOperations = WorldModelOperations()
        val pendingModelOperation = modelOperations.track(CompletableFuture<Unit>())

        fun closeModelOperations() {
            events += "model-operations"
            modelOperations.close()
        }

        init {
            registry.publish(oldBukkitWorld, oldWorld)
        }

        fun closePhysics(): CoreWorld = oldWorld.also { it.physicsCoordinator.close() }

        fun oldCoreWorld(): CoreWorld = oldWorld

        fun assertOldOwnerRemovedAndReloadable() {
            assertFalse(manager.railWorlds.containsKey(oldBukkitWorld))
            assertNull(manager.loadedRailWorld(worldId))
            assertEquals(PhysicsCoordinatorStatus.CLOSED, oldWorld.physicsCoordinator.status)
            registry.publish(newBukkitWorld, newWorld)
            assertSame(newWorld, manager.loadedRailWorld(worldId))
            assertSame(newWorld, manager.railWorlds[newBukkitWorld])
        }

        fun publishReplacementOnOriginalKey() {
            registry.publish(oldBukkitWorld, newWorld)
        }

        fun assertReplacementPreserved() {
            assertSame(newWorld, manager.loadedRailWorld(worldId))
            assertSame(newWorld, manager.railWorlds[oldBukkitWorld])
            assertEquals(PhysicsCoordinatorStatus.CLOSED, oldWorld.physicsCoordinator.status)
        }

        fun close() {
            modelOperations.close()
            manager.stop()
            manager.finishStopping()
            closeWorld(oldWorld)
            closeWorld(newWorld)
        }
    }

    companion object {
        private fun bukkitWorld(id: UUID): BukkitWorld {
            lateinit var proxy: BukkitWorld
            proxy = Proxy.newProxyInstance(
                BukkitWorld::class.java.classLoader,
                arrayOf(BukkitWorld::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "getUID" -> id
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "TestWorld($id)"
                    else -> error("Unexpected Bukkit world call: ${method.name}")
                }
            } as BukkitWorld
            return proxy
        }

        private fun closeWorld(world: CoreWorld) {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
        }
    }
}
