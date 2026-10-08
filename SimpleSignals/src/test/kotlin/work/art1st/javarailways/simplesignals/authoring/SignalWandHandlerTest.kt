package work.art1st.javarailways.simplesignals.authoring

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.block.Action
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.plugin.Plugin
import org.bukkit.inventory.meta.ItemMeta
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.rail.RailTarget
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.simplesignals.signalpack.AvailableSignalType
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Deterministic owner/worker/UI coverage; live Folia dialog rendering remains a deployment check. */
class SignalWandHandlerTest {
    @Test fun `wand marker and encoded state round trip while corrupt state fails closed`() = Fixture().use { f ->
        val state = SignalWandState(UUID(3, 4), 7L, SignalWandMode.PLACEMENT,
            SignalWandControlKind.FIXED_BLOCK, "automatic:jr-four-light",
            FixedBlockSignalWandBinding(f.worldId, "test:block", FixedBlockEntryDirection.REVERSE))
        val values = mutableMapOf<NamespacedKey, Any>()
        val data = proxy(PersistentDataContainer::class.java) { name, args -> when (name) {
            "set" -> { values[args!![0] as NamespacedKey] = args[2]!!; null }
            "get" -> values[args!![0] as NamespacedKey]
            else -> error("Unexpected PDC method $name")
        } }
        SignalWandHandler.writeTool(f.plugin, data, state)
        assertEquals(state, SignalWandHandler.readTool(f.plugin, data))
        assertEquals(state, SignalWandHandler.decodeState(SignalWandHandler.encodeState(state)))
        val currentEntry = state.copy(controlKind = SignalWandControlKind.PROTECTION_CURRENT_ENTRY,
            binding = ProtectionCurrentEntrySignalWandBinding(f.worldId, "test:entry",
                ExistingProtectionSignal("test:signal", 8)))
        val fixedRoute = state.copy(controlKind = SignalWandControlKind.PROTECTION_FIXED_ROUTE,
            binding = ProtectionFixedRouteSignalWandBinding(f.worldId, "test:entry", "test:route",
                NewProtectionSignal))
        assertEquals(currentEntry, SignalWandHandler.decodeState(SignalWandHandler.encodeState(currentEntry)))
        assertEquals(fixedRoute, SignalWandHandler.decodeState(SignalWandHandler.encodeState(fixedRoute)))
        assertNull(SignalWandHandler.decodeState("not json"))
        assertNull(SignalWandHandler.decodeState(
            """{"itemId":"${state.itemId}","stateRevision":-1,"mode":"PLACEMENT","signalType":"test:a"}"""))
        assertNull(SignalWandHandler.decodeState(
            """{"itemId":"bad","stateRevision":0,"mode":"PLACEMENT","signalType":"test:a"}"""))
        assertNull(SignalWandHandler.decodeState(
            """{"itemId":"${state.itemId}","stateRevision":0,"mode":"PLACEMENT","signalType":"bad"}"""))
        assertNull(SignalWandHandler.decodeState(
            """{"itemId":"${state.itemId}","stateRevision":9223372036854775808,"mode":"PLACEMENT","signalType":"test:a"}"""))
        assertNull(SignalWandHandler.decodeState(
            """{"itemId":"${state.itemId}","stateRevision":0,"mode":"OLD","signalType":"test:a"}"""))
        assertNull(SignalWandHandler.decodeState(
            """{"itemId":"${state.itemId}","stateRevision":0,"mode":"PLACEMENT","signalType":"test:a","binding":{"worldId":"${f.worldId}","blockId":"test:block","entryDirection":"FORWARD"}}"""))
        val mismatched = SignalWandHandler.encodeState(currentEntry).replace(
            "PROTECTION_CURRENT_ENTRY", "FIXED_BLOCK")
        assertNull(SignalWandHandler.decodeState(mismatched))
    }

    @Test fun `state rejects malformed type and negative revision`() {
        assertThrows(IllegalArgumentException::class.java) {
            SignalWandState(UUID.randomUUID(), 0L, SignalWandMode.PLACEMENT,
                SignalWandControlKind.FIXED_BLOCK, "not-namespaced")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalWandState(UUID.randomUUID(), -1L, SignalWandMode.PLACEMENT,
                SignalWandControlKind.FIXED_BLOCK, "test:a")
        }
    }

    @Test fun `item presentation follows binding with lore and glint`() = Fixture().use { f ->
        var lore = emptyList<Component>()
        var glint: Boolean? = null
        val meta = proxy(ItemMeta::class.java) { name, args -> when (name) {
            "lore" -> {
                @Suppress("UNCHECKED_CAST")
                val updated = args!![0] as List<Component>
                lore = updated
                null
            }
            "setEnchantmentGlintOverride" -> { glint = args!![0] as Boolean; null }
            else -> error("Unexpected item meta method $name")
        } }

        SignalWandHandler.decorate(meta, f.tool)
        assertEquals(true, glint)
        assertTrue(lore.last().toString().contains("test:block"))

        SignalWandHandler.decorate(meta, f.tool.copy(binding = null))
        assertEquals(false, glint)
        assertTrue(lore.last().toString().contains("none"))

        val protection = f.tool.copy(controlKind = SignalWandControlKind.PROTECTION_CURRENT_ENTRY,
            binding = ProtectionCurrentEntrySignalWandBinding(f.worldId, "test:entry", NewProtectionSignal))
        SignalWandHandler.decorate(meta, protection)
        assertEquals(true, glint)
        assertTrue(lore.any { it.toString().contains("protection current-entry") })
        assertTrue(lore.last().toString().contains("test:entry"))
    }

    @Test fun `command has no type argument rejects unavailable catalogs and chooses sorted default`() = Fixture().use { f ->
        f.permitted = false
        f.command("wand", "signal")
        assertTrue(f.given.isEmpty())
        f.permitted = true
        f.available = null
        f.command("wand", "signal")
        assertTrue(f.messages.last().toString().contains("not ready"))
        f.available = emptyList()
        f.command("wand", "signal")
        assertTrue(f.messages.last().toString().contains("No signal types"))
        f.available = listOf(AvailableSignalType("test:z", "Z"), AvailableSignalType("test:a", "A"))
        f.command("wand", "signal", "test:z")
        assertTrue(f.given.isEmpty())
        f.command("wand", "signal")
        assertEquals("test:a", f.given.single().signalType)
        assertEquals(SignalWandMode.PLACEMENT, f.given.single().mode)
        assertEquals(SignalWandControlKind.FIXED_BLOCK, f.given.single().controlKind)
        assertNull(f.given.single().binding)
        assertEquals(0L, f.given.single().stateRevision)
        assertEquals(f.newItemId, f.given.single().itemId)
    }

    @Test fun `settings are sorted marked and valid callback persists with incremented revision`() = Fixture().use { f ->
        f.available = listOf(AvailableSignalType("test:z", "Z"), AvailableSignalType("test:a", "A"))
        f.tool = f.tool.copy(signalType = "test:z")
        val opening = f.tool
        f.handler.openSettings(f.player, opening)
        assertEquals(listOf("Mode: placement", "Mode: deletion", "Control: fixed-block primary",
            "Control: protection current-entry primary", "Control: protection fixed-route primary",
            "test:a — A", "test:z — Z"),
            f.settings.map { it.label })
        assertEquals(listOf(true, false, true, false, false, false, true), f.settings.map { it.selected })
        f.choose!!(SignalWandSettingAction.Type("test:a"))
        f.drainOwners()
        assertEquals(opening.itemId, f.tool.itemId)
        assertEquals(1L, f.tool.stateRevision)
        assertEquals("test:a", f.tool.signalType)
        assertTrue(f.messages.last().toString().contains("selected"))
    }

    @Test fun `settings callback rejects swapped revised removed unauthorized disabled and overflow state`() = Fixture().use { f ->
        fun openAndChoose(opening: SignalWandState = f.tool, type: String = "test:b") {
            f.handler.openSettings(f.player, opening)
            f.choose!!(SignalWandSettingAction.Type(type))
            f.drainOwners()
        }

        val first = f.tool
        f.handler.openSettings(f.player, first)
        f.tool = SignalWandState(UUID(9, 9), 0, SignalWandMode.PLACEMENT,
            SignalWandControlKind.FIXED_BLOCK, "test:a")
        f.choose!!(SignalWandSettingAction.Type("test:b")); f.drainOwners()
        assertEquals(0L, f.tool.stateRevision)

        f.tool = first.copy(stateRevision = 1)
        openAndChoose(first)
        assertEquals(1L, f.tool.stateRevision)

        f.tool = first
        f.handler.openSettings(f.player, first)
        f.available = listOf(AvailableSignalType("test:a", "A"))
        f.choose!!(SignalWandSettingAction.Type("test:b")); f.drainOwners()
        assertEquals(first, f.tool)

        f.available = Fixture.DEFAULT_TYPES
        f.handler.openSettings(f.player, first)
        f.permitted = false
        f.choose!!(SignalWandSettingAction.Type("test:b")); f.drainOwners()
        assertEquals(first, f.tool)

        f.permitted = true
        f.handler.openSettings(f.player, first)
        f.pluginEnabled = false
        f.choose!!(SignalWandSettingAction.Type("test:b")); f.drainOwners()
        assertEquals(first, f.tool)

        f.pluginEnabled = true
        f.tool = first.copy(stateRevision = Long.MAX_VALUE)
        openAndChoose(f.tool)
        assertEquals(Long.MAX_VALUE, f.tool.stateRevision)
    }

    @Test fun `duplicate clicks coalesce and worker geometry replies only on player owner`() = Fixture().use { f ->
        f.click(); f.click()
        assertEquals(1, f.targetCalls)
        f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0)))
        f.drainOwners()
        assertEquals(1, f.workers.size)
        assertEquals(0, f.performs)
        f.drainWorkers()
        assertEquals(1, f.performs)
        assertTrue(f.messages.isEmpty())
        f.drainOwners()
        assertTrue(f.messages.single().toString().contains("updated"))
    }

    @Test fun `left click binds entrance into item state and increments revision`() = Fixture().use { f ->
        f.tool = f.tool.copy(binding = null)
        val original = f.tool
        f.click(Action.LEFT_CLICK_AIR)
        f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0)))
        f.drainOwners(); f.drainWorkers(); f.drainOwners()

        assertEquals(original.itemId, f.tool.itemId)
        assertEquals(original.stateRevision + 1, f.tool.stateRevision)
        assertEquals(f.binding, f.tool.binding)
        assertTrue(f.messages.last().toString().contains("Bound"))
    }

    @Test fun `mode setting clears binding and deletion uses left click`() = Fixture().use { f ->
        val opening = f.tool
        f.handler.openSettings(f.player, opening)
        f.choose!!(SignalWandSettingAction.Mode(SignalWandMode.DELETION))
        f.drainOwners()
        assertEquals(SignalWandMode.DELETION, f.tool.mode)
        assertNull(f.tool.binding)

        f.target = CompletableFuture()
        f.click(Action.LEFT_CLICK_AIR)
        f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0)))
        f.drainOwners(); f.drainWorkers(); f.drainOwners()
        assertEquals(1, f.deletes)
    }

    @Test fun `control kind clears binding while signal type retains it`() = Fixture().use { f ->
        val opening = f.tool
        f.handler.openSettings(f.player, opening)
        f.choose!!(SignalWandSettingAction.Type("test:b")); f.drainOwners()
        assertEquals(f.binding, f.tool.binding)

        val typed = f.tool
        f.handler.openSettings(f.player, typed)
        f.choose!!(SignalWandSettingAction.ControlKind(SignalWandControlKind.PROTECTION_CURRENT_ENTRY))
        f.drainOwners()
        assertEquals(SignalWandControlKind.PROTECTION_CURRENT_ENTRY, f.tool.controlKind)
        assertNull(f.tool.binding)
    }

    @Test fun `protection entrance chooser persists exact selected identity and stale callback cannot replace item`() = Fixture().use { f ->
        val selected = ProtectionCurrentEntrySignalWandBinding(f.worldId, "test:entry",
            ExistingProtectionSignal("test:second", 7))
        f.resolvedChoices = listOf(
            SignalWandChoice(ProtectionCurrentEntrySignalWandBinding(f.worldId, "test:entry", NewProtectionSignal), "new"),
            SignalWandChoice(selected, "existing"))
        f.tool = f.tool.copy(controlKind = SignalWandControlKind.PROTECTION_CURRENT_ENTRY, binding = null)
        f.click(Action.LEFT_CLICK_AIR)
        f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0)))
        f.drainOwners(); f.drainWorkers(); f.drainOwners()
        assertEquals(listOf("new", "existing"), f.choices.map { it.label })
        f.chooseChoice!!(f.choices.last()); f.drainOwners(); f.drainWorkers(); f.drainOwners()
        assertEquals(selected, f.tool.binding)
        assertEquals(1L, f.tool.stateRevision)

        f.target = CompletableFuture()
        f.tool = f.tool.copy(binding = null)
        f.click(Action.LEFT_CLICK_AIR)
        f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0)))
        f.drainOwners(); f.drainWorkers(); f.drainOwners()
        val staleChoice = f.chooseChoice!!
        f.tool = f.tool.copy(stateRevision = f.tool.stateRevision + 1)
        staleChoice(f.choices.first()); f.drainOwners(); f.drainWorkers(); f.drainOwners()
        assertNull(f.tool.binding)
    }

    @Test fun `stale capture world permission and complete item state invalidate queued callbacks`() = Fixture().use { f ->
        val staleAtStart = f.tool
        f.tool = f.tool.copy(stateRevision = 1)
        f.handler.click(f.player, staleAtStart, Action.RIGHT_CLICK_AIR)
        assertEquals(0, f.targetCalls)

        f.click()
        f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0)))
        f.drainOwners()
        f.current = false
        f.drainWorkers(); f.drainOwners()
        assertEquals(0, f.performs)

        f.current = true
        f.target = CompletableFuture()
        f.click()
        f.handler.onWorldChange(PlayerChangedWorldEvent(f.player, f.world))
        f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0)))
        f.drainOwners(); f.drainWorkers()
        assertEquals(0, f.performs)

        f.target = CompletableFuture()
        f.click()
        f.permitted = false
        f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0)))
        f.drainOwners(); f.drainWorkers()
        assertEquals(0, f.performs)

        f.permitted = true
        f.target = CompletableFuture()
        f.click()
        f.tool = f.tool.copy(stateRevision = f.tool.stateRevision + 2)
        f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0)))
        f.drainOwners(); f.drainWorkers()
        assertEquals(0, f.performs)
    }

    @Test fun `status reports current and missing type uses one owner task and cancels by lifecycle`() = Fixture().use { f ->
        f.handler.refreshStatus(f.player)
        f.handler.refreshStatus(f.player)
        assertEquals(1, f.statusStarts)
        val firstRetired = f.statusRetired!!
        assertTrue(f.actionBars.last().toString().contains("test:a"))
        assertTrue(f.actionBars.last().toString().contains("placement"))
        assertTrue(f.actionBars.last().toString().contains("fixed-block primary"))
        assertTrue(f.actionBars.last().toString().contains("test:block"))
        assertFalse(f.actionBars.last().toString().contains("unavailable"))

        f.available = listOf(AvailableSignalType("test:b", "B"))
        f.statusTick!!.invoke()
        assertTrue(f.actionBars.last().toString().contains("unavailable"))

        f.toolPresent = false
        f.statusTick!!.invoke()
        assertEquals(1, f.statusCancels)

        f.toolPresent = true
        f.handler.refreshStatus(f.player)
        assertEquals(2, f.statusStarts)
        firstRetired()
        f.handler.refreshStatus(f.player)
        assertEquals(2, f.statusStarts)

        f.statusRetired!!.invoke()
        f.handler.refreshStatus(f.player)
        assertEquals(3, f.statusStarts)

        f.handler.onQuit(PlayerQuitEvent(f.player, "quit"))
        assertEquals(2, f.statusCancels)
        f.handler.refreshStatus(f.player)
        assertEquals(4, f.statusStarts)
        f.handler.close()
        assertEquals(3, f.statusCancels)
        f.handler.refreshStatus(f.player)
        assertEquals(4, f.statusStarts)
    }

    private class Fixture : AutoCloseable {
        val worldId = UUID(0, 1)
        val playerId = UUID(0, 2)
        val newItemId = UUID(0, 3)
        var permitted = true
        var online = true
        var pluginEnabled = true
        var ownerThread = true
        var current = true
        var toolPresent = true
        val binding = FixedBlockSignalWandBinding(worldId, "test:block", FixedBlockEntryDirection.FORWARD)
        var tool = SignalWandState(UUID(0, 4), 0L, SignalWandMode.PLACEMENT,
            SignalWandControlKind.FIXED_BLOCK, "test:a", binding)
        var target = CompletableFuture<Optional<RailTarget>>()
        var targetCalls = 0
        var performs = 0
        var deletes = 0
        var available: List<AvailableSignalType>? = DEFAULT_TYPES
        val messages = mutableListOf<Component>()
        val actionBars = mutableListOf<Component>()
        val given = mutableListOf<SignalWandState>()
        val workers = ArrayDeque<() -> Unit>()
        val owners = ArrayDeque<() -> Unit>()
        var settings = emptyList<SignalWandSetting>()
        var choose: ((SignalWandSettingAction) -> Unit)? = null
        var choices = emptyList<SignalWandChoice>()
        var chooseChoice: ((SignalWandChoice) -> Unit)? = null
        var resolvedChoices: List<SignalWandChoice> = listOf(SignalWandChoice(binding, "fixed"))
        var statusTick: (() -> Unit)? = null
        var statusRetired: (() -> Unit)? = null
        var statusStarts = 0
        var statusCancels = 0
        val capture = object : SignalWandCapture { override val worldId = this@Fixture.worldId }
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
                "getEyeLocation" -> Location(world, 0.0, 0.0, 0.0, -90f, 0f)
                "sendMessage" -> { messages += args!![0] as Component; null }
                "sendActionBar" -> { actionBars += args!![0] as Component; null }
                else -> error("Unexpected player method $name")
            }
        }
        val plugin: Plugin = proxy(Plugin::class.java) { name, _ -> when (name) {
            "isEnabled" -> pluginEnabled
            "getName" -> "SimpleSignals"
            "namespace" -> "simplesignals"
            else -> error("Unexpected plugin method $name")
        } }
        private val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(10.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 10.0, Curve.SegmentType.LINE_END)))
        private val coreWorld: CoreWorld = createTestWorld()
        val rail = coreWorld.createRailForTest(curve)
        private val targets = object : RailTargetService {
            override fun findLookedAtRail(player: Player, maxDistance: Double, hitRadius: Double) = target.also { targetCalls++ }
            override fun findCurve(worldId: UUID, railId: UUID): Optional<PrimeCurve> {
                check(!ownerThread)
                return Optional.of(curve)
            }
        }
        private val backend = object : SignalWandBackend {
            override fun availableSignalTypes() = available
            override fun capture(worldId: UUID, signalType: String): SignalWandCapture {
                require(available?.any { it.signalType == signalType } == true)
                return capture
            }
            override fun current(capture: SignalWandCapture) = current
            override fun resolveEntrance(capture: SignalWandCapture, controlKind: SignalWandControlKind,
                mode: SignalWandMode, point: RailPositionSnapshot): List<SignalWandChoice> {
                check(!ownerThread)
                return resolvedChoices
            }
            override fun place(capture: SignalWandCapture, binding: SignalWandBinding, point: RailPositionSnapshot, active: () -> Boolean): SignalWandOutcome {
                check(!ownerThread)
                assertTrue(active())
                performs++
                return SignalWandOutcome("updated")
            }
            override fun delete(capture: SignalWandCapture, binding: SignalWandBinding, active: () -> Boolean): SignalWandOutcome {
                check(!ownerThread)
                assertTrue(active())
                deletes++
                return SignalWandOutcome("deleted")
            }
        }
        val handler = SignalWandHandler(plugin, backend, targets, enabled = { pluginEnabled },
            worker = { workers += it }, entity = { _, action -> owners += action },
            toolInHand = { check(ownerThread); tool.takeIf { toolPresent } },
            giveTool = { _, value -> given += value },
            replaceToolState = { _, expected, replacement ->
                if (!toolPresent || tool != expected) false else { tool = replacement; true }
            },
            showSettings = { _, values, callback -> settings = values; choose = callback },
            showChoices = { _, values, callback -> choices = values; chooseChoice = callback },
            scheduleStatus = { _, tick, retired ->
                statusStarts++
                statusTick = tick
                statusRetired = retired
                SignalWandStatusTask { statusCancels++ }
            },
            newItemId = { newItemId },
            findTarget = { targetCalls++; target })

        fun command(vararg args: String) = handler.onCommand(player, command, "simplesignals", args)
        fun click(action: Action = Action.RIGHT_CLICK_AIR) = handler.click(player, tool, action)
        fun drainOwners() { ownerThread = true; while (owners.isNotEmpty()) owners.removeFirst().invoke() }
        fun drainWorkers() {
            ownerThread = false
            try { while (workers.isNotEmpty()) workers.removeFirst().invoke() } finally { ownerThread = true }
        }
        override fun close() {
            handler.close()
            coreWorld.physicsCoordinator.close()
            (coreWorld.tickManager as? AutoCloseable)?.close()
        }

        companion object {
            val DEFAULT_TYPES = listOf(
                AvailableSignalType("test:a", "A"), AvailableSignalType("test:b", "B"))
        }
    }

    companion object {
        private val command = object : Command("simplesignals") {
            override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<out String>) = true
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
