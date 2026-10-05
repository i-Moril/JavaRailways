package work.art1st.javarailways.simplesignals.authoring

import net.kyori.adventure.text.Component
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import org.joml.Vector3d
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.rail.RailTarget
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.*
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.runtime.*
import java.util.ArrayDeque
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Production handler/session with controlled owner+worker queues, not a live Folia renderer. */
class RoutePreviewWandHandlerTest {
    private val worlds = mutableListOf<CoreWorld>()
    @AfterEach fun closeWorlds() = worlds.forEach { it.physicsCoordinator.close(); (it.tickManager as? AutoCloseable)?.close() }

    @Test fun `permission and missing entry reject without targeting and command supplies preview tool`() {
        val f = Fixture()
        f.permitted = false
        f.handler.click(f.player, true)
        f.handler.onCommand(f.player, command, "simplesignals", arrayOf("wand", "route"))
        assertEquals(0, f.targets)
        assertEquals(0, f.given)
        f.permitted = true
        f.handler.click(f.player, false)
        assertEquals(0, f.targets)
        f.handler.onCommand(f.player, command, "simplesignals", arrayOf("wand", "route"))
        assertEquals(1, f.given)
    }

    @Test fun `entry exit gestures use worker and player display refresh does no geometry computation`() {
        val f = Fixture()
        f.click(true)
        assertEquals(0, f.computes)
        f.click(false)
        assertEquals(1, f.computes)
        assertEquals(1, f.openRefresh)
        val computes = f.computes
        repeat(5) { f.refresh!!.invoke() }
        assertEquals(computes, f.computes)
        assertEquals(5, f.draws)
        assertTrue(f.messages.isNotEmpty())
    }

    @Test fun `new entry selection closes current preview`() {
        val f = Fixture(); f.click(true); f.click(false)
        f.handler.click(f.player, true)
        assertEquals(3, f.closedSubscriptions)
        assertEquals(1, f.closedRefresh)
        f.completeTarget(); f.drain()
        val oldDraws = f.draws
        f.refresh?.invoke()
        assertEquals(oldDraws, f.draws)
    }

    @Test fun `queued old owner result cannot publish after a new entry choice`() {
        val f = Fixture(); f.click(true)
        f.target = CompletableFuture()
        f.handler.click(f.player, false); f.completeTarget(); f.drainOwners()
        f.drainWorkers(); f.drainOwners() // Attach the old preview, but do not start its worker yet.
        f.drainWorkers() // Compute and queue the old result for its player owner.
        assertEquals(1, f.computes)
        assertTrue(f.owners.isNotEmpty())
        val messages = f.messages.size
        f.target = CompletableFuture()
        f.handler.click(f.player, true) // Invalidates the old session before its owner result runs.
        f.drainOwners()
        assertEquals(messages, f.messages.size)
        assertEquals(3, f.closedSubscriptions)
        assertEquals(1, f.closedRefresh)
        f.completeTarget(); f.drain()
        assertEquals(1, f.computes)
        assertEquals(messages + 1, f.messages.size)
    }

    @Test fun `sneak cancel tool swap and world round trip discard outstanding target and worker`() {
        val f = Fixture()
        f.handler.click(f.player, true)
        f.sneaking = true
        f.handler.click(f.player, true)
        f.sneaking = false
        f.completeTarget(); f.drain()
        assertEquals(0, f.selections)
        f.target = CompletableFuture()
        f.handler.click(f.player, true)
        f.inHand = false
        f.completeTarget(); f.drain()
        assertEquals(0, f.selections)
        f.inHand = true; f.target = CompletableFuture()
        f.handler.click(f.player, true)
        f.completeTarget(); f.drainOwners()
        f.handler.onWorldChange(PlayerChangedWorldEvent(f.player, f.world))
        f.drain()
        assertEquals(0, f.selections)
    }

    @Test fun `disable and retired display close all subscriptions and no queued work recreates them`() {
        val f = Fixture(); f.click(true); f.click(false)
        f.handler.close()
        assertEquals(3, f.closedSubscriptions)
        assertEquals(1, f.closedRefresh)
        f.refresh!!.invoke(); f.drain()
        assertEquals(0, f.draws)
        val next = Fixture(); next.click(true); next.click(false)
        next.retired!!.invoke()
        assertEquals(3, next.closedSubscriptions)
        assertEquals(1, next.closedRefresh)
    }

    @Test fun `world unload during refresh attachment disposes late handle`() {
        val f = Fixture(); f.click(true)
        f.closeDuringAttach = true
        f.click(false)
        assertEquals(1, f.closedRefresh)
        assertEquals(0, f.subscriptions)
        f.refresh!!.invoke(); f.drain()
        assertEquals(0, f.draws)
    }

    @Test fun `quit closes an active session and queued refresh cannot recreate it`() {
        val f = Fixture(); f.click(true); f.click(false)
        f.handler.onQuit(PlayerQuitEvent(f.player, Component.empty()))
        assertEquals(3, f.closedSubscriptions)
        assertEquals(1, f.closedRefresh)
        f.refresh!!.invoke(); f.drain()
        assertEquals(0, f.draws)
        assertEquals(1, f.subscriptions)
    }

    @Test fun `duplicate exit clicks coalesce and failed target remains retryable`() {
        val f = Fixture(); f.click(true)
        f.target = CompletableFuture()
        f.handler.click(f.player, false); f.handler.click(f.player, false)
        assertEquals(2, f.targets)
        f.target.complete(Optional.empty()); f.drain()
        f.click(false)
        assertEquals(1, f.computes)
    }

    private inner class Fixture {
        var owner = true
        var permitted = true
        var online = true
        var inHand = true
        var sneaking = false
        var closeDuringAttach = false
        var targets = 0
        var given = 0
        var selections = 0
        var computes = 0
        var subscriptions = 0
        var closedSubscriptions = 0
        var openRefresh = 0
        var closedRefresh = 0
        var draws = 0
        var refresh: (() -> Unit)? = null
        var retired: (() -> Unit)? = null
        var target = CompletableFuture<Optional<RailTarget>>()
        val workers = ArrayDeque<() -> Unit>()
        val owners = ArrayDeque<() -> Unit>()
        val messages = mutableListOf<Component>()
        val worldId = UUID(1, 1)
        val world = RuntimeRoutePreviewBackendTest.proxy(World::class.java) { name, _ -> when (name) {
            "getUID" -> worldId
            else -> error("Unexpected world call: $name")
        } }
        val player = RuntimeRoutePreviewBackendTest.proxy(Player::class.java) { name, args ->
            check(owner) { "Bukkit player access on worker: $name" }
            when (name) {
                "getUniqueId" -> UUID(1, 2)
                "hasPermission" -> permitted
                "isOnline" -> online
                "isSneaking" -> sneaking
                "getWorld" -> world
                "sendMessage" -> { messages += args!![0] as Component; null }
                else -> error("Unexpected player call: $name")
            }
        }
        val plugin = RuntimeRoutePreviewBackendTest.proxy(Plugin::class.java) { name, _ -> when (name) {
            "isEnabled" -> true
            else -> error("Unexpected plugin call $name")
        } }
        private val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(10.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 10.0, Curve.SegmentType.LINE_END)))
        private val rail = createTestWorld().also(worlds::add).createRailForTest(curve)
        val entry = ProtectionBoundaryDefinition("test:entry", RailPositionSnapshot(rail.id, ViewDirection.AB, 2.0))
        val exit = ProtectionBoundaryDefinition("test:exit", RailPositionSnapshot(rail.id, ViewDirection.BA, 8.0))
        val region = ProtectionRegionDefinition("test:region", 0, listOf(entry, exit))
        val runtime = SimpleSignalsWorldRuntime(worldId)
        private val selection = RoutePreviewSelection(worldId, runtime, region.regionId, entry, regionRailIds = setOf(rail.id))
        private val backend = object : RoutePreviewBackend {
            override fun select(worldId: UUID, point: RailPositionSnapshot, entry: RoutePreviewSelection?): RoutePreviewSelection {
                check(!owner); selections++
                return if (entry == null) selection else entry.copy(exit = exit)
            }
            override fun compute(selection: RoutePreviewSelection): RoutePreviewComputation {
                check(!owner); computes++
                val path = CurrentRoutePath(entry, exit, listOf(ProtectionRouteTraversal(rail.id, ViewDirection.AB, 2.0, 8.0)),
                    exit.position.copy(facing = ViewDirection.AB), CurrentRouteEnd.REACHED_EXIT, "connected")
                return RoutePreviewComputation(path, PreviewRegionState(region, false), setOf(rail.id), RoutePreviewDrawing.prepare(path) {
                    check(!owner); curve
                })
            }
            override fun current(selection: RoutePreviewSelection, computation: RoutePreviewComputation): Boolean { check(!owner); return true }
            override fun subscribe(selection: RoutePreviewSelection, observation: (ActiveRailContinuationsSnapshot) -> Unit,
                topology: (RailTopologyChange) -> Unit, region: (PreviewRegionState) -> Unit): List<AutoCloseable> {
                check(!owner); subscriptions++
                return List(3) { var closed = false; AutoCloseable { if (!closed) { closed = true; closedSubscriptions++ } } }
            }
        }
        val handler: RoutePreviewWandHandler = RoutePreviewWandHandler(plugin, backend,
            RuntimeRoutePreviewBackendTest.proxy(RailTargetService::class.java) { name, _ -> error("Unexpected target call $name") },
            worker = { workers += it }, entity = { _, action -> owners += action },
            repeatDisplay = { _, action, retire ->
                check(owner); refresh = action; retired = retire; openRefresh++
                if (closeDuringAttach) handler.onWorldUnload(WorldUnloadEvent(world))
                var closed = false
                AutoCloseable { if (!closed) { closed = true; closedRefresh++ } }
            }, inHand = { check(owner); inHand }, giveTool = { check(owner); given++ },
            findTarget = { check(owner); targets++; target }, render = { _, drawing, offset ->
                check(owner); assertTrue(drawing.batch(offset).size <= 512); draws++
            })
        fun completeTarget() { target.complete(Optional.of(RailTarget(rail, 5.0, 0.0))) }
        fun drainOwners() { owner = true; while (owners.isNotEmpty()) owners.removeFirst().invoke() }
        fun drainWorkers() {
            owner = false
            try { while (workers.isNotEmpty()) workers.removeFirst().invoke() } finally { owner = true }
        }
        fun drain() {
            while (owners.isNotEmpty() || workers.isNotEmpty()) {
                drainOwners(); drainWorkers()
            }
        }
        fun click(left: Boolean) { target = CompletableFuture(); handler.click(player, left); completeTarget(); drain() }
    }
    private val command = object : Command("simplesignals") {
        override fun execute(sender: org.bukkit.command.CommandSender, commandLabel: String, args: Array<out String>) = true
    }
}
