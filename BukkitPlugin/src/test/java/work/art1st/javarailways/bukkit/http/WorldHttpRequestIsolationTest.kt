package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.logic.WorldTickRunner
import work.art1st.javarailways.bukkit.logic.WorldTickWake
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorldHttpRequestIsolationTest {
    @Test
    fun `HTTP waiting for world A leaves global wakeup and world B physics available`() {
        val guard = work.art1st.javarailways.core.api.threading.host.ThreadGuardHost.registerPlatformBridge(BukkitThreadGuardBridge)
        val first = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        val second = createTestWorld(PhysicsCoordinator(1, automaticFallback = false))
        val global = Executors.newSingleThreadExecutor()
        val asynchronous = Executors.newCachedThreadPool()
        val requests = Executors.newSingleThreadExecutor()
        val httpEntered = CountDownLatch(1)
        val secondPublished = CompletableFuture<Long>()
        val dispatcher = Proxy.newProxyInstance(PlatformDispatcher::class.java.classLoader,
            arrayOf(PlatformDispatcher::class.java)) { _, method, args ->
            @Suppress("UNCHECKED_CAST")
            val action = args?.firstOrNull() as? (() -> Unit)
            when (method.name) {
                "runGlobal" -> { global.execute { requireNotNull(action).invoke() }; Unit }
                "runAsync" -> { asynchronous.execute { requireNotNull(action).invoke() }; Unit }
                else -> error("unexpected platform dispatch ${method.name}")
            }
        } as PlatformDispatcher
        val runner = WorldTickRunner(second.runtimeId, {
            BukkitThreadGuardBridge.runInSimulationContext(second) {
                val ticket = second.physicsCoordinator.stepSimulation(50.0)
                ticket.await()
                second.physicsCoordinator.processBetweenFrames {
                    secondPublished.complete(second.latestWorldFrame().physicsEpoch)
                }
            }
        }, secondPublished::completeExceptionally)
        try {
            second.physicsCoordinator.start()
            lateinit var response: java.util.concurrent.Future<String>
            BukkitThreadGuardBridge.runInSimulationContext(first) {
                response = requests.submit<String> {
                    awaitDomainHttpRequest(dispatcher, 5_000L, { error("request unexpectedly expired") }) {
                        httpEntered.countDown()
                        BukkitThreadGuardBridge.runInSimulationContext(first) {
                            first.physicsCoordinator.withStructuralMutation { "captured" }
                        }
                    }
                }
                assertTrue(httpEntered.await(5, TimeUnit.SECONDS))
                dispatcher.runGlobal { runner.offer(WorldTickWake(1L, 50L)) }
                assertEquals(1L, secondPublished.get(5, TimeUnit.SECONDS))
                assertFalse(response.isDone)
            }
            assertEquals("captured", response.get(5, TimeUnit.SECONDS))
        } finally {
            runner.close()
            requests.shutdownNow()
            global.shutdownNow()
            asynchronous.shutdownNow()
            listOf(first, second).forEach {
                it.physicsCoordinator.close()
                it.releaseRuntimeBinding()
                (it.tickManager as AutoCloseable).close()
            }
            guard.close()
        }
    }
}
