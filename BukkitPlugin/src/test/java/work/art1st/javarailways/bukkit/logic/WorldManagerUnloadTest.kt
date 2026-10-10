package work.art1st.javarailways.bukkit.logic

import org.bukkit.World as BukkitWorld
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.api.threading.host.ThreadGuardRegistration
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    fun `persistence failure preserves the primary error and completes every teardown step`() {
        val fixture = Fixture()
        val primary = IllegalStateException("persistence failed")
        val renderFailure = IllegalArgumentException("render failed")
        val bindingFailure = IllegalStateException("binding failed")
        val tickFailure = IllegalStateException("tick failed")
        val markerFailure = UnsupportedOperationException("marker failed")
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
                    releaseRuntimeBinding = {
                        fixture.events += "binding"
                        throw bindingFailure
                    },
                    closeTick = {
                        fixture.events += "tick"
                        throw tickFailure
                    }
                )
            }

            assertSame(primary, thrown)
            assertEquals(listOf(renderFailure, bindingFailure, tickFailure, markerFailure), thrown.suppressed.toList())
            assertEquals(listOf("persist", "render", "binding", "tick", "marker"), fixture.events)
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
                    releaseRuntimeBinding = { fixture.events += "binding" },
                    closeTick = {
                        fixture.events += "tick"
                        fixture.publishReplacementOnOriginalKey()
                    }
                )
            }

            assertSame(renderFailure, thrown)
            assertEquals(emptyList<Throwable>(), thrown.suppressed.toList())
            assertEquals(listOf("persist", "render", "binding", "tick", "marker"), fixture.events)
            fixture.assertReplacementPreserved()
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        val worldId: UUID = UUID.randomUUID()
        val oldBukkitWorld: BukkitWorld = bukkitWorld(worldId)
        private val newBukkitWorld: BukkitWorld = bukkitWorld(worldId)
        private val oldWorld: CoreWorld = createTestWorld()
        private val newWorld: CoreWorld = createTestWorld()
        private val railWorlds = ConcurrentHashMap<BukkitWorld, World>()
        private val registry = LoadedRailWorldRegistry(railWorlds)
        val manager = WorldManager(railWorlds, loadedWorlds = registry)
        val events = mutableListOf<String>()

        init {
            registry.publish(oldBukkitWorld, oldWorld)
        }

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
