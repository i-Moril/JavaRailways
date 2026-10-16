package work.art1st.javarailways.simplesignals.authoring

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.plugin.Plugin
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.rail.RailTarget
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Real handler with deterministic owner/worker scheduling; this is not a live Folia test. */
class SectionWandHandlerTest {
    private val coreWorlds = mutableListOf<CoreWorld>()
    @AfterEach fun closeWorlds() = coreWorlds.forEach {
        it.physicsCoordinator.close()
        (it.tickManager as? AutoCloseable)?.close()
    }
    @Test fun `permission rejection does not start targeting or worker and command requires permission`() {
        val f = Fixture()
        f.permitted = false
        f.handler.click(f.player, f.tool!!, false)
        f.handler.onCommand(f.player, command, "simplesignals", arrayOf("wand", "block"))
        assertEquals(0, f.targetCalls)
        assertEquals(0, f.given)
        assertTrue(f.workers.isEmpty())
        assertTrue(f.messages.isNotEmpty())
    }

    @Test fun `command supplies distinct create and delete tools without a naming or confirm step`() {
        val f = Fixture()
        f.handler.onCommand(f.player, command, "simplesignals", arrayOf("wand", "region", "delete"))
        assertEquals(SectionWandTool.REGION_DELETE, f.lastGiven)
        assertEquals(1, f.given)
        assertEquals(0, f.targetCalls)
    }

    @Test fun `duplicate clicks coalesce and edits and geometry run only on worker then reply on owner`() {
        val f = Fixture()
        f.handler.click(f.player, f.tool!!, false)
        f.handler.click(f.player, f.tool!!, false)
        assertEquals(1, f.targetCalls)
        f.completeTarget()
        assertEquals(0, f.performs)
        f.drainOwner()
        assertEquals(1, f.workers.size)
        assertEquals(0, f.performs)
        f.drainWorkers()
        assertEquals(1, f.performs)
        assertTrue(f.messages.isEmpty())
        f.drainOwner()
        assertEquals(1, f.messages.size)
    }

    @Test fun `stale publication rejects worker result and clears old selection`() {
        val f = Fixture()
        val first = RailPositionSnapshot(f.railId, ViewDirection.AB, 2.0)
        f.nextOutcome = SectionWandOutcome(WandText.diagnostic("first"), first, f.capture)
        f.clickAndDrain()
        assertEquals(listOf(null), f.firstInputs)

        f.target = CompletableFuture()
        f.handler.click(f.player, f.tool!!, false)
        f.completeTarget(); f.drainOwner()
        f.current = false
        f.drainWorkers(); f.drainOwner()
        assertEquals(1, f.performs)

        f.current = true
        f.advanceCapture()
        f.nextOutcome = SectionWandOutcome(WandText.diagnostic("recovered"))
        f.clickAndDrain()
        assertEquals(listOf(null, null), f.firstInputs)
        assertEquals(2, f.performs)
        assertTrue(f.messages.isNotEmpty())
    }

    @Test fun `world change and returning to same world invalidate a queued click`() {
        val f = Fixture()
        f.handler.click(f.player, f.tool!!, false)
        f.completeTarget(); f.drainOwner()
        f.handler.onWorldChange(PlayerChangedWorldEvent(f.player, f.world))
        f.drainWorkers(); f.drainOwner()
        assertEquals(0, f.performs)
        assertTrue(f.messages.isEmpty())
    }

    @Test fun `tool swap before targeting completion invalidates click`() {
        val f = Fixture()
        f.handler.click(f.player, f.tool!!, false)
        f.tool = SectionWandTool.REGION_DELETE
        f.completeTarget(); f.drainOwner(); f.drainWorkers()
        assertEquals(0, f.performs)
    }

    @Test fun `disable drops queued workers and retired player receives no reply`() {
        val f = Fixture()
        f.handler.click(f.player, f.tool!!, false)
        f.completeTarget(); f.drainOwner()
        f.handler.close()
        f.drainWorkers(); f.drainOwner()
        assertEquals(0, f.performs)
        val next = Fixture()
        next.handler.click(next.player, next.tool!!, false)
        next.completeTarget(); next.drainOwner(); next.drainWorkers()
        next.online = false
        next.drainOwner()
        assertTrue(next.messages.isEmpty())
    }

    @Test fun `failed target releases busy state for retry`() {
        val f = Fixture()
        f.handler.click(f.player, f.tool!!, false)
        f.target.complete(Optional.empty()); f.drainOwner()
        f.target = CompletableFuture()
        f.handler.click(f.player, f.tool!!, false)
        assertEquals(2, f.targetCalls)
    }

    @Test fun `worker receives first point from previous selection and continued block outcome`() {
        val f = Fixture()
        val first = RailPositionSnapshot(f.railId, ViewDirection.AB, 2.0)
        f.nextOutcome = SectionWandOutcome(WandText.diagnostic("first"), first, f.capture)
        f.clickAndDrain()
        assertNull(f.lastFirst)
        val second = first.copy(arcLengthInAbMeters = 5.0)
        f.nextOutcome = SectionWandOutcome(WandText.diagnostic("created"), second, f.capture)
        f.clickAndDrain()
        assertEquals(first, f.lastFirst)
        f.nextOutcome = SectionWandOutcome(WandText.diagnostic("next"), null)
        f.clickAndDrain()
        assertEquals(second, f.lastFirst)
    }

    @Test fun `perpendicular sight refuses region direction rather than guessing`() {
        val f = Fixture()
        f.tool = SectionWandTool.REGION_CREATE
        f.yaw = 0f // Looking +Z, while the rail tangent points +X.
        f.clickAndDrain()
        assertEquals(0, f.performs)
        assertTrue(f.messages.isNotEmpty())
    }

    @Test fun `obsolete callback cannot remove a new session`() {
        val sessions = SectionWandSessions()
        val player = UUID(0, 1)
        val old = sessions.begin(player, UUID(0, 2), SectionWandTool.BLOCK_CREATE, true)!!
        sessions.invalidate(player)
        val fresh = sessions.begin(player, UUID(0, 2), SectionWandTool.BLOCK_CREATE, true)!!
        sessions.invalidate(player, old)
        assertTrue(sessions.current(player, fresh))
        assertFalse(old.active.get())
    }

    @Test fun `region warnings are worker prepared and owner refresh performs no geometry work`() {
        val f = Fixture()
        f.tool = SectionWandTool.REGION_CREATE
        val cut = work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition("test:warn",
            RailPositionSnapshot(f.railId, ViewDirection.AB, 5.0))
        f.nextOutcome = SectionWandOutcome(WandText.diagnostic("multiple paths"), warnings = listOf(cut), warningCapture = f.capture)
        f.target = CompletableFuture()
        f.handler.click(f.player, f.tool!!, true)
        f.completeTarget(); f.drainOwner(); f.drainWorkers(); f.drainOwner()
        assertEquals(1, f.openRefresh)
        val samples = f.curveCalls
        repeat(3) { f.refresh!!.invoke() }
        assertEquals(3, f.draws)
        assertEquals(samples, f.curveCalls)
        f.current = false
        f.refresh!!.invoke()
        assertEquals(1, f.closedRefresh)
        assertEquals(3, f.draws)
    }

    @Test fun `world swap disable and late warning handle attachment all close display`() {
        val f = Fixture()
        val cut = work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition("test:warn",
            RailPositionSnapshot(f.railId, ViewDirection.AB, 5.0))
        f.nextOutcome = SectionWandOutcome(WandText.diagnostic("warnings"), warnings = listOf(cut))
        f.closeOnAttach = true
        f.clickAndDrain()
        assertEquals(1, f.closedRefresh)
        f.refresh!!.invoke()
        assertEquals(0, f.draws)
        val next = Fixture()
        next.nextOutcome = SectionWandOutcome(WandText.diagnostic("warnings"), warnings = listOf(cut.copy(position = cut.position.copy(railId = next.railId))))
        next.clickAndDrain()
        next.handler.onWorldChange(PlayerChangedWorldEvent(next.player, next.world))
        assertEquals(1, next.closedRefresh)
        next.handler.close(); next.refresh!!.invoke()
        assertEquals(0, next.draws)
    }

    @Test fun `old warning capture changing during own edit does not suppress successful owner reply`() {
        val f = Fixture()
        val cut = work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition("test:warn",
            RailPositionSnapshot(f.railId, ViewDirection.AB, 5.0))
        f.nextOutcome = SectionWandOutcome(WandText.diagnostic("first warning"), warnings = listOf(cut), warningCapture = f.capture)
        f.clickAndDrain()
        f.nextOutcome = SectionWandOutcome(WandText.diagnostic("next edit succeeded"))
        f.target = CompletableFuture()
        f.handler.click(f.player, f.tool!!, false)
        f.completeTarget(); f.drainOwner(); f.drainWorkers()
        f.current = false // The just-completed edit changed publication before the queued player reply.
        f.refresh!!.invoke()
        assertEquals(0, f.closedRefresh)
        f.drainOwner()
        assertTrue(f.messages.last().toString().contains("next edit succeeded"))
        assertEquals(1, f.closedRefresh)
    }

    private inner class Fixture {
        val worldId = UUID(0, 1)
        val playerId = UUID(0, 2)
        val railId get() = rail.id
        var permitted = true
        var online = true
        var ownerThread = true
        var current = true
        var yaw = -90f
        var tool: SectionWandTool? = SectionWandTool.BLOCK_CREATE
        var target = CompletableFuture<Optional<RailTarget>>()
        var targetCalls = 0
        var curveCalls = 0
        var performs = 0
        var given = 0
        var lastGiven: SectionWandTool? = null
        var lastFirst: RailPositionSnapshot? = null
        val firstInputs = mutableListOf<RailPositionSnapshot?>()
        var refresh: (() -> Unit)? = null
        var openRefresh = 0
        var closedRefresh = 0
        var draws = 0
        var closeOnAttach = false
        val messages = mutableListOf<Component>()
        val workers = ArrayDeque<() -> Unit>()
        val owners = ArrayDeque<() -> Unit>()
        var capture: SectionWandCapture = newCapture()
        var nextOutcome = SectionWandOutcome(WandText.diagnostic("edited"))
        val world: World = proxy(World::class.java) { name, _ -> when (name) {
            "getUID" -> worldId
            else -> error("Unexpected world method $name")
        } }
        val player: Player = proxy(Player::class.java) { name, args ->
            check(ownerThread) { "Player access on worker: $name" }
            when (name) {
                "getUniqueId" -> playerId
                "hasPermission" -> permitted
                "getWorld" -> world
                "isOnline" -> online
                "isSneaking" -> false
                "getEyeLocation" -> Location(world, 0.0, 0.0, 0.0, yaw, 0f)
                "locale" -> java.util.Locale.US
                "sendMessage" -> { messages += args!![0] as Component; null }
                else -> error("Unexpected player method $name")
            }
        }
        private val plugin = proxy(Plugin::class.java) { name, _ -> when (name) {
            "isEnabled" -> true
            "getName" -> "SimpleSignals"
            else -> error("Unexpected plugin method $name")
        } }
        private val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(10.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 10.0, Curve.SegmentType.LINE_END)))
        private val coreWorld = createTestWorld().also(coreWorlds::add)
        private val rail = coreWorld.createRailForTest(curve)
        private val targets = object : RailTargetService {
            override fun findLookedAtRail(player: Player, maxDistance: Double, hitRadius: Double) = target
            override fun findCurve(worldId: UUID, railId: UUID): Optional<PrimeCurve> {
                check(!ownerThread)
                curveCalls++
                return Optional.of(curve)
            }
        }
        private val backend = object : SectionWandBackend {
            override fun capture(worldId: UUID) = capture
            override fun current(capture: SectionWandCapture): Boolean =
                current && capture === this@Fixture.capture
            override fun perform(capture: SectionWandCapture, tool: SectionWandTool, leftClick: Boolean,
                firstPoint: RailPositionSnapshot?, point: RailPositionSnapshot, active: () -> Boolean): SectionWandOutcome {
                check(!ownerThread)
                assertTrue(active())
                performs++
                lastFirst = firstPoint
                firstInputs += firstPoint
                return nextOutcome
            }
        }
        val handler: SectionWandHandler = SectionWandHandler(plugin, backend, targets, enabled = { true },
            worker = { workers += it }, entity = { _, action -> owners += action },
            toolInHand = { check(ownerThread); tool }, giveTool = { _, value -> check(ownerThread); given++; lastGiven = value },
            findTarget = { check(ownerThread); targetCalls++; target },
            repeatDisplay = { _, action, _ ->
                check(ownerThread); refresh = action; openRefresh++
                if (closeOnAttach) handler.close()
                var closed = false
                AutoCloseable { if (!closed) { closed = true; closedRefresh++ } }
            }, render = { _, drawing, offset ->
                check(ownerThread); assertTrue(drawing.batch(offset).size <= 512); draws++
            })
        fun completeTarget() { target.complete(Optional.of(RailTarget(rail, 5.0, 0.0))) }
        fun advanceCapture() { capture = newCapture() }
        fun drainOwner() { ownerThread = true; while (owners.isNotEmpty()) owners.removeFirst().invoke() }
        fun drainWorkers() {
            ownerThread = false
            try { while (workers.isNotEmpty()) workers.removeFirst().invoke() } finally { ownerThread = true }
        }
        fun clickAndDrain() {
            target = CompletableFuture()
            handler.click(player, tool!!, false)
            completeTarget(); drainOwner(); drainWorkers(); drainOwner()
        }

        private fun newCapture() = object : SectionWandCapture {
            override val worldId = this@Fixture.worldId
        }
    }

    companion object {
        private val command = object : Command("simplesignals") {
            override fun execute(sender: org.bukkit.command.CommandSender, commandLabel: String, args: Array<out String>) = true
        }
        private fun <T> proxy(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args -> when (method.name) {
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.get(0)
                "toString" -> type.simpleName
                else -> handler(method.name, args)
            } })
    }
}
