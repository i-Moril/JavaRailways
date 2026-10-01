package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.World as BukkitWorld
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.logic.LoadedRailWorldRegistry
import work.art1st.javarailways.bukkit.logic.WorldManager
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BukkitRailTargetServiceTest {
    @Test
    fun `loaded world lookup returns canonical immutable geometry off the Bukkit owner`() {
        val world = createTestWorld()
        val worldId = UUID.randomUUID()
        val bukkitWorld = bukkitWorld(worldId)
        val railWorlds = ConcurrentHashMap<BukkitWorld, World>()
        val registry = LoadedRailWorldRegistry(railWorlds)
        val manager = WorldManager(railWorlds, loadedWorlds = registry)
        val service = BukkitRailTargetService({ manager }, { error("Geometry must not access a Bukkit scheduler") })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val rail = world.createRailForTest(curve(20.0))
            registry.publish(bukkitWorld, world)
            assertSame(world, manager.railWorlds[bukkitWorld])
            val retained = worker.submit(Callable { service.findCurve(worldId, rail.id).orElseThrow() })
                .get(5, TimeUnit.SECONDS)
            assertSame(rail.curve, retained)
            assertEquals(20.0, retained.totalArcLength)
            assertEquals(3.0, retained.getPointAt(3.0).position.x(), 1e-9)
            assertEquals(1.0, retained.getPointAt(3.0).tangent.x(), 1e-9)
            assertEquals(17.0, retained.reversed.getPointAt(3.0).position.x(), 1e-9)
            assertEquals(-1.0, retained.reversed.getPointAt(3.0).tangent.x(), 1e-9)
            assertTrue(service.findCurve(worldId, UUID.randomUUID()).isEmpty)

            world.removeRail(rail)
            assertTrue(service.findCurve(worldId, rail.id).isEmpty)
            assertEquals(3.0, retained.getPointAt(3.0).position.x(), 1e-9)
        } finally {
            worker.shutdownNow()
            manager.stop()
            manager.finishStopping()
            close(world)
        }
    }

    @Test
    fun `withdrawal rejects new queries and same UUID reload binds only the new world`() {
        val first = createTestWorld()
        val second = createTestWorld()
        val worldId = UUID.randomUUID()
        val oldBukkitWorld = bukkitWorld(worldId)
        val newBukkitWorld = bukkitWorld(worldId)
        val railWorlds = ConcurrentHashMap<BukkitWorld, World>()
        val registry = LoadedRailWorldRegistry(railWorlds)
        val manager = WorldManager(railWorlds, loadedWorlds = registry)
        val service = BukkitRailTargetService({ manager }, { error("No Bukkit access expected") })
        try {
            val oldRail = first.createRailForTest(curve(20.0))
            val newRail = second.createRailForTest(curve(30.0))
            assertThrows(IllegalStateException::class.java) { service.findCurve(worldId, oldRail.id) }
            assertThrows(IllegalStateException::class.java) { service.findCurve(UUID.randomUUID(), oldRail.id) }
            registry.publish(oldBukkitWorld, first)
            val retained = service.findCurve(worldId, oldRail.id).orElseThrow()

            // The production unload path withdraws lookup before awaiting physics or snapshot I/O.
            registry.withdraw(oldBukkitWorld)
            assertSame(first, manager.railWorlds[oldBukkitWorld], "teardown still owns the old world")
            assertThrows(IllegalStateException::class.java) { service.findCurve(worldId, oldRail.id) }
            assertEquals(3.0, retained.getPointAt(3.0).position.x(), 1e-9)

            registry.publish(newBukkitWorld, second)
            registry.withdraw(oldBukkitWorld)
            assertSame(second, manager.loadedRailWorld(worldId), "old cleanup cannot withdraw a new binding")
            assertTrue(service.findCurve(worldId, oldRail.id).isEmpty)
            assertSame(newRail.curve, service.findCurve(worldId, newRail.id).orElseThrow())

            // Failed-load cleanup is harmless before publication and removes a published candidate.
            registry.withdraw(newBukkitWorld)
            registry.withdraw(newBukkitWorld)
            assertThrows(IllegalStateException::class.java) { service.findCurve(worldId, newRail.id) }
            registry.publish(newBukkitWorld, second)
            manager.stop()
            assertThrows(IllegalStateException::class.java) { service.findCurve(worldId, newRail.id) }
            manager.finishStopping()
            assertThrows(IllegalStateException::class.java) { service.findCurve(worldId, newRail.id) }
        } finally {
            manager.stop()
            manager.finishStopping()
            close(first)
            close(second)
        }
    }

    private fun curve(length: Double): PrimeCurve = PrimeCurve.fromPoints(listOf(
        Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0),
        Curve.RawPoint(Vector3d(length, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), length)
    ))

    private fun bukkitWorld(id: UUID): BukkitWorld {
        lateinit var proxy: BukkitWorld
        proxy = Proxy.newProxyInstance(BukkitWorld::class.java.classLoader, arrayOf(BukkitWorld::class.java)) {
            _, method, args -> when (method.name) {
                "getUID" -> id
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "TestWorld($id)"
                else -> error("Unexpected Bukkit world call: ${method.name}")
            }
        } as BukkitWorld
        return proxy
    }

    private fun close(world: CoreWorld) {
        world.physicsCoordinator.close()
        (world.tickManager as AutoCloseable).close()
    }
}
