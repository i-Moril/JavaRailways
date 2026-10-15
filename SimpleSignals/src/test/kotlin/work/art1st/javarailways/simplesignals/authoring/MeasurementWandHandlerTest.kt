package work.art1st.javarailways.simplesignals.authoring

import net.kyori.adventure.text.Component
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.rail.RailTarget
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.simplesignals.authoring.RuntimeRoutePreviewBackendTest.Companion.proxy
import work.art1st.javarailways.simplesignals.region.lineTopologyState
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MeasurementWandHandlerTest {
    private val worlds = arrayListOf<CoreWorld>()
    @AfterEach fun cleanup() = worlds.forEach { it.physicsCoordinator.close(); (it.tickManager as? AutoCloseable)?.close() }

    @Test fun `command issues independent tool and two picks report both distances then start fresh`() {
        val f = Fixture()
        f.handler.onCommand(f.player, command, "simplesignals", arrayOf("wand", "measure"))
        assertEquals(1, f.given)
        f.messages.clear()
        f.pick(2.0); assertTrue(f.messages.last().toString().contains("First measurement point"))
        f.pick(8.0)
        assertTrue(f.messages.last().toString().contains("Straight-line: 6.000 m; shortest track: 6.000 m"))
        f.pick(4.0)
        assertTrue(f.messages.last().toString().contains("First measurement point"))
        assertEquals(3, f.geometryLookups)
    }

    @Test fun `permission and held tool are checked before targeting and before replying`() {
        val f = Fixture()
        f.permitted = false
        f.handler.onCommand(f.player, command, "simplesignals", arrayOf("wand", "measure"))
        f.handler.click(f.player)
        assertEquals(0, f.given); assertEquals(0, f.targetCalls)
        f.permitted = true; f.held = false
        f.handler.click(f.player); assertEquals(0, f.targetCalls)
        f.held = true; f.messages.clear()
        f.begin(); f.complete(2.0); f.drainOwner(); f.drainWorker()
        f.permitted = false; f.drainOwner()
        assertTrue(f.messages.isEmpty())
    }

    @Test fun `left click sneak held change and disable cancel late targeting and prepared replies`() {
        for (cancel in listOf<(Fixture) -> Unit>(
            { it.handler.click(it.player, true) },
            { it.sneaking = true; it.handler.click(it.player) },
            { it.handler.onHeld(PlayerItemHeldEvent(it.player, 0, 1)) },
            { it.handler.onUnload(WorldUnloadEvent(it.world)) },
            { it.handler.close() },
            { it.online = false }
        )) {
            val f = Fixture(); f.begin()
            cancel(f)
            val messages = f.messages.size
            f.complete(2.0); f.drainOwner(); f.drainWorker(); f.drainOwner()
            assertEquals(messages, f.messages.size)
            assertEquals(0, f.geometryLookups)
        }
        val f = Fixture()
        f.begin(); f.complete(2.0); f.drainOwner(); f.drainWorker()
        f.handler.close(); f.drainOwner()
        assertTrue(f.messages.isEmpty())
    }

    @Test fun `failed rail target permits retry and topology changes require new first point`() {
        val f = Fixture(); f.begin()
        f.target.complete(Optional.empty()); f.drainOwner()
        f.pick(2.0)
        f.root = lineTopologyState(2, f.rail.id)
        f.pick(8.0)
        assertTrue(f.messages.last().toString().contains("select both points again"))
        f.pick(5.0)
        assertTrue(f.messages.last().toString().contains("First measurement point"))
        f.begin(); f.complete(9.0); f.drainOwner(); f.drainWorker()
        f.root = lineTopologyState(3, f.rail.id)
        f.drainOwner()
        assertTrue(f.messages.last().toString().contains("select both points again"))
        f.begin(); f.complete(3.0); f.drainOwner(); f.drainWorker()
        f.worldAvailable = false
        f.drainOwner()
        assertTrue(f.messages.last().toString().contains("Measurement unavailable"))
        f.worldAvailable = true
        f.pick(7.0)
        assertTrue(f.messages.last().toString().contains("First measurement point"))
    }

    @Test fun `old player callbacks cannot remove sameUUID replacement or revive old world selection`() {
        val f = Fixture(); f.pick(2.0)
        val oldPlayer = f.player
        val replacement = f.newPlayer()
        f.player = replacement
        f.pick(4.0)
        assertTrue(f.messages.last().toString().contains("First measurement point"))
        f.handler.onQuit(PlayerQuitEvent(oldPlayer, Component.empty()))
        f.handler.onHeld(PlayerItemHeldEvent(oldPlayer, 0, 1))
        f.pick(8.0)
        assertTrue(f.messages.last().toString().contains("4.000 m"))
        f.pick(1.0)
        val oldWorld = f.world
        f.world = f.newWorld()
        f.handler.onWorld(PlayerChangedWorldEvent(f.player, oldWorld))
        f.pick(9.0)
        assertTrue(f.messages.last().toString().contains("First measurement point"))
    }

    private inner class Fixture {
        var ownerThread = true
        var permitted = true
        var held = true
        var online = true
        var sneaking = false
        var given = 0
        var targetCalls = 0
        var geometryLookups = 0
        var worldAvailable = true
        var world = newWorld()
        var player = newPlayer()
        val core = createTestWorld().also(worlds::add)
        val curve = SectionOverlayDrawingTest.curve()
        val rail = core.createRailForTest(curve)
        var root = lineTopologyState(1, rail.id)
        var target = CompletableFuture<Optional<RailTarget>>()
        val messages = arrayListOf<Component>()
        val owners = ArrayDeque<() -> Unit>()
        val workers = ArrayDeque<() -> Unit>()
        private val plugin = proxy(Plugin::class.java) { name, _ -> error("Unexpected plugin call $name") }
        private val infrastructure = proxy(InfrastructureResourceService::class.java) { name, _ ->
            check(name == "currentTopology") { "Measurement accessed mutable/operating state: $name" }
            check(worldAvailable) { "Railway world is not registered" }
            root
        }
        private val targets = proxy(RailTargetService::class.java) { name, _ ->
            check(!ownerThread); check(name == "findCurve"); geometryLookups++; Optional.of(curve)
        }
        val handler = MeasurementWandHandler(plugin, RailMeasurementBackend(infrastructure, targets), targets,
            enabled = { check(ownerThread); true }, worker = { workers += it },
            owner = { _, action, _ -> owners += action }, held = { check(ownerThread); held },
            give = { check(ownerThread); given++ }, target = { check(ownerThread); targetCalls++; target })
        fun newWorld() = proxy(World::class.java) { name, _ ->
            check(ownerThread); if (name == "getUID") UUID(0, 1) else error(name)
        }
        fun newPlayer() = proxy(Player::class.java) { name, args ->
            check(ownerThread) { "Player accessed by worker: $name" }
            when (name) {
                "getUniqueId" -> UUID(0, 2)
                "getWorld" -> world
                "isOnline" -> online
                "hasPermission" -> permitted
                "isSneaking" -> sneaking
                "sendMessage" -> { messages += args!![0] as Component; null }
                else -> error(name)
            }
        }
        fun begin() { target = CompletableFuture(); handler.click(player) }
        fun complete(ab: Double) { target.complete(Optional.of(RailTarget(rail, ab, 0.0))) }
        fun drainOwner() { ownerThread = true; while (owners.isNotEmpty()) owners.removeFirst().invoke() }
        fun drainWorker() {
            ownerThread = false
            try { while (workers.isNotEmpty()) workers.removeFirst().invoke() } finally { ownerThread = true }
        }
        fun pick(ab: Double) { begin(); complete(ab); drainOwner(); drainWorker(); drainOwner() }
    }
    companion object {
        private val command = object : Command("simplesignals") {
            override fun execute(sender: org.bukkit.command.CommandSender, commandLabel: String, args: Array<out String>) = true
        }
    }
}
