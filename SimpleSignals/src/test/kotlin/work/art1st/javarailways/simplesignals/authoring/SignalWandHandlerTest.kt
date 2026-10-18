package work.art1st.javarailways.simplesignals.authoring

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.plugin.Plugin
import org.bukkit.inventory.meta.ItemMeta
import org.joml.Vector3d
import org.joml.Quaterniond
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.rail.RailTarget
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.bukkit.api.model.ModelDefinition
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.simplesignals.presentation.LoadedPackageModel
import work.art1st.javarailways.simplesignals.presentation.RailPlacementModel
import work.art1st.javarailways.simplesignals.presentation.RailPlacementPreset
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.simplesignals.signalpack.AvailableSignalType
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind
import work.art1st.javarailways.simplesignals.signal.RouteIndicatorDefinition
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.sqrt

/** Deterministic owner/worker/UI coverage; live Folia dialog rendering remains a deployment check. */
class SignalWandHandlerTest {
    @Test fun `giving signal wand and opening settings activate the shared section overlay`() = Fixture().use { f ->
        f.command("wand", "signal")
        assertEquals(1, f.overlayChecks)
        f.handler.openSettings(f.player, f.tool)
        assertEquals(2, f.overlayChecks)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `placement input edits rail offsets or absolute ground position only after confirmation`(ground: Boolean) = Fixture().use { f ->
        val rotation = Quaterniond().rotateXYZ(0.4, Math.PI / 2, 0.3)
        val originalPose = ModelPose(1.0, 2.0, 3.0, rotation.x, rotation.y, rotation.z, rotation.w)
        val original = if (ground) ModelPlacement.Ground(originalPose) else ModelPlacement.RailAttached(
            RailPositionSnapshot(f.rail.id, work.art1st.javarailways.core.api.physics.rail.ViewDirection.BA, 4.0), originalPose)
        f.placedDevice = f.placedDevice.copy(placement = original)
        val originalTool = f.tool
        f.handler.openPlacementEditor(f.player, f.tool, f.placedDevice); f.drain()
        val initial = checkNotNull(f.placementInput)
        assertEquals(if (ground) null else false, initial.followRailTilt)
        assertEquals("1.0", initial.fields["x"])
        val submitted = initial.copy(fields = initial.fields + ("x" to "7.25"), followRailTilt = if (ground) null else true)
        f.submitPlacement!!(submitted); f.drain()
        assertTrue(f.placements.isEmpty())
        val confirm = f.dialogOptions.first().second
        confirm(); confirm(); f.drain()
        val saved = f.placements.single()
        val pose = when (saved) {
            is ModelPlacement.RailAttached -> {
                assertEquals((original as ModelPlacement.RailAttached).position, saved.position)
                assertTrue(saved.followRailTilt)
                saved.adjustment
            }
            is ModelPlacement.Ground -> saved.pose
        }
        assertEquals(ModelPose(7.25, 2.0, 3.0, rotation.x, rotation.y, rotation.z, rotation.w), pose)
        assertEquals(originalTool, f.tool)
        assertEquals(0, f.performs)
    }

    @Test fun `placement input uses intrinsic XYZ degrees without rotating its offsets`() = Fixture().use { f ->
        f.handler.openPlacementEditor(f.player, f.tool, f.placedDevice); f.drain()
        val initial = checkNotNull(f.placementInput)
        f.submitPlacement!!(initial.copy(fields = initial.fields + mapOf("rx" to "30", "ry" to "60", "rz" to "90")))
        f.drain(); f.dialogOptions.first().second(); f.drain()
        val pose = (f.placements.single() as ModelPlacement.Ground).pose
        assertEquals(1.0, pose.x())
        assertEquals(2.0, pose.y())
        assertEquals(3.0, pose.z())
        assertEquals(0.5, pose.rotationX(), 1e-12)
        assertEquals((sqrt(3.0) - 1.0) / 4.0, pose.rotationY(), 1e-12)
        assertEquals((sqrt(3.0) + 1.0) / 4.0, pose.rotationZ(), 1e-12)
        assertEquals(0.5, pose.rotationW(), 1e-12)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["NaN", "Infinity", "invalid", "cancel", "tool", "permission", "catalog", "settings"])
    fun `invalid input or stale placement continuation never saves`(reason: String) = Fixture().use { f ->
        f.handler.openPlacementEditor(f.player, f.tool, f.placedDevice); f.drain()
        val initial = checkNotNull(f.placementInput)
        if (reason in listOf("NaN", "Infinity", "invalid", "cancel")) {
            f.submitPlacement!!(if (reason == "cancel") null else initial.copy(fields = initial.fields + ("x" to reason)))
            f.drain()
        } else {
            f.submitPlacement!!(initial); f.drain()
            val confirm = f.dialogOptions.first().second
            when (reason) {
                "tool" -> f.tool = f.tool.copy(stateRevision = f.tool.stateRevision + 1)
                "permission" -> f.permitted = false
                "catalog" -> f.current = false
                "settings" -> f.handler.openSettings(f.player, f.tool)
            }
            confirm(); f.drain()
        }
        assertTrue(f.placements.isEmpty())
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `new indicator captures rail or ground pose and saves only after explicit mapping confirmation`(ground: Boolean) = Fixture().use { f ->
        f.tool = f.tool.copy(controlKind = SignalWandControlKind.ROUTE_INDICATOR,
            presentationModelId = "test:model", binding = RouteIndicatorSignalWandBinding(f.worldId, "test:entry"))
        if (ground) f.handler.click(f.player, f.tool, Action.RIGHT_CLICK_BLOCK,
            ModelPlacement.Ground(ModelPose(1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 1.0)))
        else {
            f.click()
            f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0)))
        }
        f.drain()
        assertEquals(0, f.indicatorSaves)
        assertEquals(0, f.performs)
        f.dialogOptions.single { it.first == "dark" }.second(); f.drain()
        assertTrue(f.dialogOptions.first().first.contains("UNMAPPED"))
        f.dialogOptions.first().second(); f.drain()
        f.dialogOptions.single { it.first == "straight" }.second(); f.drain()
        assertTrue(f.dialogOptions.first().first.contains("straight"))
        f.dialogOptions.single { it.first.startsWith("Review") }.second(); f.drain()
        assertEquals(0, f.indicatorSaves)
        val confirm = f.dialogOptions.first().second
        confirm(); confirm(); f.drain()
        assertEquals(1, f.indicatorSaves)
        assertEquals("dark", f.indicatorSavedNoRoute)
        assertEquals(mapOf("test:route" to "straight"), f.indicatorSavedMappings)
        assertEquals(ground, f.placements.single() is ModelPlacement.Ground)
        assertEquals(0, f.performs)
    }





    @Test fun `duplicate endpoint captions keep distinct route callbacks and explicit appearance mappings`() = Fixture().use { f ->
        f.indicatorRoutes = listOf("test:first", "test:second")
        f.indicatorRouteLabels = f.indicatorRoutes.associateWith { WandText.diagnostic("西口 → 一站台") }
        f.tool = f.tool.copy(controlKind = SignalWandControlKind.ROUTE_INDICATOR,
            presentationModelId = "test:model", binding = RouteIndicatorSignalWandBinding(f.worldId, "test:entry"))
        f.click(); f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0))); f.drain()
        f.dialogOptions.single { it.first == "dark" }.second(); f.drain()
        assertTrue(f.dialogOptions[0].first.contains("西口 → 一站台"))
        assertTrue(f.dialogOptions[1].first.contains("西口 → 一站台"))
        assertFalse(f.dialogOptions[0].first == f.dialogOptions[1].first)
        f.dialogOptions[1].second(); f.drain()
        f.dialogOptions.single { it.first == "straight" }.second(); f.drain()
        f.dialogOptions.single { it.first.startsWith("Review") }.second(); f.drain()
        f.dialogOptions.first().second(); f.drain()
        assertEquals(mapOf("test:second" to "straight"), f.indicatorSavedMappings)
    }

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
        val attached = state.copy(controlKind = SignalWandControlKind.ATTACHED_SIGNAL,
            binding = AttachedSignalWandBinding(f.worldId, "test:primary", 2,
                ExistingAttachedSignal("test:attached", 3)), presentationModelId = "test:model")
        val indicator = state.copy(controlKind = SignalWandControlKind.ROUTE_INDICATOR,
            binding = RouteIndicatorSignalWandBinding(f.worldId, "test:entry", "test:indicator", 4))
        assertEquals(attached, SignalWandHandler.decodeState(SignalWandHandler.encodeState(attached)))
        assertEquals(indicator, SignalWandHandler.decodeState(SignalWandHandler.encodeState(indicator)))
        val newIndicator = indicator.copy(binding = RouteIndicatorSignalWandBinding(f.worldId, "test:entry"))
        val encodedNewIndicator = SignalWandHandler.encodeState(newIndicator)
        assertTrue(encodedNewIndicator.contains("\"indicatorId\":null"))
        assertTrue(encodedNewIndicator.contains("\"indicatorRevision\":null"))
        assertEquals(newIndicator, SignalWandHandler.decodeState(encodedNewIndicator))
        assertNull(SignalWandHandler.decodeState(encodedNewIndicator
            .replace(",\"indicatorId\":null", "").replace(",\"indicatorRevision\":null", "")))
        val encodedIndicator = SignalWandHandler.encodeState(indicator)
        assertNull(SignalWandHandler.decodeState(encodedIndicator.replace("\"indicatorRevision\":4", "\"indicatorRevision\":null")))
        assertNull(SignalWandHandler.decodeState(encodedIndicator.replace("\"indicatorId\":\"test:indicator\"", "\"indicatorId\":null")))
        val preset = state.copy(railPreset = SignalWandRailPreset("test:primary", "right",
            ModelPose(1.0, 2.0, 3.0, 0.0, 0.0, 1.0, 0.0), true))
        val encodedPreset = SignalWandHandler.encodeState(preset)
        assertEquals(preset, SignalWandHandler.decodeState(encodedPreset))
        assertNull(SignalWandHandler.decodeState(encodedPreset.replace("\"rotationZ\":1.0", "\"rotationZ\":2.0")))
        assertNull(SignalWandHandler.decodeState(encodedPreset.replace("\"name\":\"right\"", "\"name\":\" \"")))
        assertNull(SignalWandHandler.decodeState("not json"))
        assertNull(SignalWandHandler.decodeState(
            """{"itemId":"${state.itemId}","stateRevision":-1,"mode":"PLACEMENT","signalType":"test:a"}"""))
        assertNull(SignalWandHandler.decodeState(
            """{"itemId":"bad","stateRevision":0,"mode":"PLACEMENT","signalType":"test:a"}"""))
        assertNull(SignalWandHandler.decodeState(
            """{"itemId":"${state.itemId}","stateRevision":0,"mode":"PLACEMENT","signalType":"bad"}"""))
        assertNull(SignalWandHandler.decodeState(
            """{"itemId":"${state.itemId}","stateRevision":0,"mode":"PLACEMENT","controlKind":"FIXED_BLOCK","signalType":"test:a","presentationModelId":"bad"}"""))
        assertNull(SignalWandHandler.decodeState(
            """{"itemId":"${state.itemId}","stateRevision":9223372036854775808,"mode":"PLACEMENT","signalType":"test:a"}"""))
        assertNull(SignalWandHandler.decodeState(
            """{"itemId":"${state.itemId}","stateRevision":0,"mode":"OLD","signalType":"test:a"}"""))
        assertNull(SignalWandHandler.decodeState(
            """{"itemId":"${state.itemId}","stateRevision":0,"mode":"PLACEMENT","signalType":"test:a","binding":{"worldId":"${f.worldId}","blockId":"test:block","entryDirection":"FORWARD"}}"""))
        val mismatched = SignalWandHandler.encodeState(currentEntry).replace(
            "PROTECTION_CURRENT_ENTRY", "FIXED_BLOCK")
        assertNull(SignalWandHandler.decodeState(mismatched))
        val partialAttachedIdentity = SignalWandHandler.encodeState(attached)
            .replace(",\"existingSignalRevision\":3", "")
        assertNull(SignalWandHandler.decodeState(partialAttachedIdentity))
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
            "displayName" -> null
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
        assertFalse(lore.any { it.toString().contains("test:block") })

        SignalWandHandler.decorate(meta, f.tool.copy(binding = null))
        assertEquals(false, glint)
        assertTrue(lore.last().toString().contains("origin"))

        val protection = f.tool.copy(controlKind = SignalWandControlKind.PROTECTION_CURRENT_ENTRY,
            binding = ProtectionCurrentEntrySignalWandBinding(f.worldId, "test:entry", NewProtectionSignal))
        SignalWandHandler.decorate(meta, protection)
        assertEquals(true, glint)
        assertFalse(lore.any { it.toString().contains("test:entry") })

        val generated = "simplesignals:12345678-1234-1234-1234-123456789abc"
        val bindings = listOf(
            SignalWandControlKind.FIXED_BLOCK to FixedBlockSignalWandBinding(f.worldId, generated, FixedBlockEntryDirection.FORWARD),
            SignalWandControlKind.PROTECTION_CURRENT_ENTRY to ProtectionCurrentEntrySignalWandBinding(f.worldId, generated, ExistingProtectionSignal(generated, 0)),
            SignalWandControlKind.PROTECTION_FIXED_ROUTE to ProtectionFixedRouteSignalWandBinding(f.worldId, generated, generated, ExistingProtectionSignal(generated, 0)),
            SignalWandControlKind.ATTACHED_SIGNAL to AttachedSignalWandBinding(f.worldId, generated, 0, ExistingAttachedSignal(generated, 0)),
            SignalWandControlKind.ROUTE_INDICATOR to RouteIndicatorSignalWandBinding(f.worldId, generated, generated, 0)
        )
        bindings.forEach { (kind, binding) ->
            val state = f.tool.copy(controlKind = kind, binding = binding)
            SignalWandHandler.decorate(meta, state)
            assertFalse(lore.any { it.toString().contains("12345678") })
            assertFalse(SignalWandHandler.statusText(state, f.available, f.modelIds).toString().contains("12345678"))
            assertEquals(binding, state.binding)
        }
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
        assertEquals("test:model", f.given.single().presentationModelId)
        assertNull(f.given.single().binding)
        assertEquals(0L, f.given.single().stateRevision)
        assertEquals(f.newItemId, f.given.single().itemId)
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
        assertEquals(ModelPlacement.RailAttached(
            RailPositionSnapshot(f.rail.id, work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB, 2.0)),
            f.placements.single())
    }

    @Test fun `ground placement maps model Z to player facing at cardinal and oblique headings`() {
        for (yawDegrees in listOf(0f, 90f, -90f, 180f, 37f)) {
            val pose = SignalWandHandler.groundPlacement(0, 0, 0, BlockFace.UP, yawDegrees).pose
            val rotation = Quaterniond(pose.rotationX(), pose.rotationY(), pose.rotationZ(), pose.rotationW())
            val yaw = Math.toRadians(yawDegrees.toDouble())
            val forward = rotation.transform(org.joml.Vector3d(0.0, 0.0, 1.0))
            val up = rotation.transform(org.joml.Vector3d(0.0, 1.0, 0.0))
            val width = rotation.transform(org.joml.Vector3d(1.0, 0.0, 0.0))
            assertEquals(-kotlin.math.sin(yaw), forward.x, 1e-12)
            assertEquals(0.0, forward.y, 1e-12)
            assertEquals(kotlin.math.cos(yaw), forward.z, 1e-12)
            assertEquals(0.0, up.distance(org.joml.Vector3d(0.0, 1.0, 0.0)), 1e-12)
            assertEquals(0.0, width.distance(org.joml.Vector3d(up).cross(forward)), 1e-12)
        }
    }

    @Test fun `ground placement uses face centre and upright player facing without rail lookup`() = Fixture().use { f ->
        val placement = SignalWandHandler.groundPlacement(10, 63, -4, BlockFace.EAST, 0f)
        val pose = placement.pose
        assertEquals(11.0, pose.x(), 1e-12)
        assertEquals(63.5, pose.y(), 1e-12)
        assertEquals(-3.5, pose.z(), 1e-12)
        assertEquals(0.0, pose.rotationX(), 1e-12)
        assertEquals(0.0, pose.rotationY(), 1e-12)
        assertEquals(0.0, pose.rotationZ(), 1e-12)
        assertEquals(1.0, pose.rotationW(), 1e-12)

        f.handler.click(f.player, f.tool, Action.RIGHT_CLICK_BLOCK, placement)
        assertEquals(0, f.targetCalls)
        assertEquals(1, f.workers.size)
        f.drainWorkers()
        assertEquals(listOf(placement), f.placements)
        assertEquals(1, f.performs)
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



    @Test fun `protection entrance chooser persists exact selected identity and stale callback cannot replace item`() = Fixture().use { f ->
        val selected = ProtectionCurrentEntrySignalWandBinding(f.worldId, "test:entry",
            ExistingProtectionSignal("test:second", 7))
        f.resolvedChoices = listOf(
            SignalWandChoice(ProtectionCurrentEntrySignalWandBinding(f.worldId, "test:entry", NewProtectionSignal), WandText.diagnostic("new")),
            SignalWandChoice(selected, WandText.diagnostic("existing")))
        f.tool = f.tool.copy(controlKind = SignalWandControlKind.PROTECTION_CURRENT_ENTRY, binding = null)
        f.click(Action.LEFT_CLICK_AIR)
        f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0)))
        f.drainOwners(); f.drainWorkers(); f.drainOwners()
        assertEquals(listOf("1. new", "2. existing"), f.choices.map { it.label.render(java.util.Locale.US) })
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
        assertTrue(f.actionBars.last().toString().contains("none"))
        assertTrue(f.actionBars.last().toString().contains("placement"))
        assertFalse(f.actionBars.last().toString().contains("test:block"))
        assertFalse(f.actionBars.last().toString().contains("unavailable"))

        f.available = listOf(AvailableSignalType("test:b", "B"))
        f.statusTick!!.invoke()
        assertTrue(f.actionBars.last().toString().contains("none"))

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

    @Test fun `settings collect one draft preserve preset across default pages and reject stale callbacks`() = Fixture().use { f ->
        f.tool = f.tool.copy(binding = null)
        f.models = f.models + ("test:primary" to railModel("test:primary", mapOf("right" to RailPlacementPreset(ModelPose.IDENTITY, false))))
        val original = f.tool
        f.handler.openSettings(f.player, original)
        assertEquals(2, f.page!!.columns)
        assertEquals(4, f.page!!.options.size)
        f.page!!.options[1].second(); f.drain()
        f.page!!.options[0].second(); f.drain()
        f.page!!.options.first { it.first == "B" }.second(); f.drain()
        assertEquals(original, f.tool)
        assertTrue(f.page!!.options[1].first.contains("right"))
        f.page!!.options.last().second(); f.drain()
        assertEquals(original, f.tool)
        f.handler.openSettings(f.player, original)
        f.page!!.options[0].second(); f.drain()
        f.page!!.options.first { it.first == "A" }.second(); f.drain()
        val apply = f.page!!.options[2].second
        apply(); apply(); f.drain()
        assertEquals("test:a", f.tool.blockDefaultType)
        assertEquals(1, f.tool.stateRevision)
        assertEquals(f.tool, SignalWandHandler.decodeState(SignalWandHandler.encodeState(f.tool)))
        f.handler.openSettings(f.player, f.tool)
        val staleApply = f.page!!.options[2].second
        f.handler.switchMode(f.player)
        staleApply(); f.drain()
        assertEquals(SignalWandMode.DELETION, f.tool.mode)
        assertEquals("test:a", f.tool.blockDefaultType)
    }

    @Test fun `picked device opens local diagnostics and type changes require one guarded confirmation`() = Fixture().use { f ->
        f.tool = f.tool.copy(binding = null)
        val original = f.placedDevice
        f.pickedObjectId = original.renderObjectId()
        f.rightClick(); f.drain()
        assertEquals(0, f.targetCalls)
        assertTrue(f.page!!.body.any { it.contains("first rule result") })
        f.page!!.options.first { it.first == "Refresh diagnostics" }.second(); f.drain()
        assertEquals(0, f.typeChanges)
        f.page!!.options.first { it.first.contains("signal type") }.second(); f.drain()
        f.page!!.options.first { it.first == "B" }.second(); f.drain()
        assertEquals(0, f.typeChanges)
        val confirm = f.page!!.options.first().second
        confirm(); confirm(); f.drain()
        assertEquals(1, f.typeChanges)
        assertEquals(original, f.placedDevice)
        assertTrue(f.placements.isEmpty())
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `route indicator creation without models shows an empty explanation and can cancel`(unavailable: Boolean) = Fixture().use { f ->
        f.tool = f.tool.copy(binding = null)
        f.protectionEntrance = true
        f.modelIds = if (unavailable) null else emptyList()
        f.rightClick(); f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0))); f.drain()
        f.page!!.options.single { it.first == "route indicator" }.second(); f.drain()
        assertTrue(f.page!!.options.isEmpty())
        assertEquals(listOf("No presentation models are currently available"), f.page!!.body)
        assertEquals("Cancel creation", f.page!!.exit!!.first)
        f.page!!.exit!!.second(); f.drain()
        assertEquals(0, f.performs)
        assertEquals(0, f.indicatorSaves)
        assertTrue(f.messages.last().toString().contains("closed"))
    }

    @Test fun `target creation uses optional block default and protection purpose always has separate cancel`() = Fixture().use { f ->
        f.tool = f.tool.copy(binding = null)
        f.rightClick(); f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0))); f.drain()
        assertEquals(listOf("A", "B"), f.page!!.options.map { it.first })
        assertEquals("Cancel creation", f.page!!.exit!!.first)
        f.page!!.options.first().second(); f.drain()
        assertEquals(1, f.performs)
        assertNull(f.tool.binding)
        f.tool = f.tool.copy(blockDefaultType = "test:a")
        f.target = CompletableFuture()
        f.rightClick(); f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0))); f.drain()
        assertEquals(2, f.performs)
        f.occupiedPrimary = true
        f.target = CompletableFuture()
        f.rightClick(); f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0))); f.drain()
        assertEquals(2, f.performs)
        assertTrue(f.messages.last().toString().contains("already has"))
        f.protectionEntrance = true
        f.target = CompletableFuture()
        f.rightClick(); f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0))); f.drain()
        assertEquals(1, f.page!!.columns)
        assertEquals(3, f.page!!.options.size)
        assertEquals("Cancel creation", f.page!!.exit!!.first)
        f.page!!.exit!!.second(); f.drain()
        assertEquals(2, f.performs)
    }

    @Test fun `local rebind waits for a clicked entrance and confirmation and late clicks cannot replace settings`() = Fixture().use { f ->
        f.tool = f.tool.copy(binding = null)
        f.handler.openLocalEditor(f.player, f.tool, f.placedDevice); f.drain()
        f.page!!.options.first { it.first == "Rebind existing primary signal" }.second(); f.drain()
        f.rightClick()
        f.handler.openSettings(f.player, f.tool)
        val settings = f.page
        f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0))); f.drain()
        assertEquals(settings, f.page)
        assertEquals(0, f.rebindings)
        f.handler.openLocalEditor(f.player, f.tool, f.placedDevice); f.drain()
        f.page!!.options.first { it.first == "Rebind existing primary signal" }.second(); f.drain()
        f.target = CompletableFuture()
        f.rightClick(); f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0))); f.drain()
        assertEquals(0, f.rebindings)
        f.page!!.options.first().second(); f.drain()
        assertEquals(1, f.rebindings)
        assertTrue(f.placements.isEmpty())
    }

    private class Fixture : AutoCloseable {
        val worldId = UUID(0, 1)
        val playerId = UUID(0, 2)
        val newItemId = UUID(0, 3)
        var permitted = true
        var online = true
        var sneaking = false
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
        var rebindings = 0
        var indicatorRoutes = listOf("test:route")
        var indicatorRouteLabels = emptyMap<String, WandText>()
        var indicatorSaves = 0
        var indicatorSavedEntry: String? = null
        var indicatorSavedMappings = emptyMap<String, String>()
        var indicatorSavedNoRoute: String? = null
        var dialogOptions = emptyList<Pair<String, () -> Unit>>()
        var placementInput: PlacementWandInput? = null
        var submitPlacement: ((PlacementWandInput?) -> Unit)? = null
        var placedDevice = PlacedSignalChoice(PlacedSignalKind.FIXED_BLOCK, "test:device", 2,
            ModelPlacement.Ground(ModelPose(1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 1.0)))
        val placements = mutableListOf<ModelPlacement>()
        var available: List<AvailableSignalType>? = DEFAULT_TYPES
        var modelIds: List<String>? = listOf("test:model")
        var primaryModels = mapOf("test:a" to "test:primary", "test:b" to "test:other")
        var models = listOf("test:primary", "test:other", "test:model").associateWith { railModel(it, emptyMap()) }
        val messages = mutableListOf<Component>()
        val actionBars = mutableListOf<Component>()
        val given = mutableListOf<SignalWandState>()
        val workers = ArrayDeque<() -> Unit>()
        val owners = ArrayDeque<() -> Unit>()
        var page: SignalWandDialog? = null
        var pickedObjectId: String? = null
        var protectionEntrance = false
        var occupiedPrimary = false
        var typeChanges = 0
        var choices = emptyList<SignalWandChoice>()
        var chooseChoice: ((SignalWandChoice) -> Unit)? = null
        var resolvedChoices: List<SignalWandChoice> = listOf(SignalWandChoice(binding, WandText.diagnostic("fixed")))
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
                "isSneaking" -> sneaking
                "getLocation" -> Location(world, 0.0, 0.0, 0.0, 90f, 0f)
                "getEyeLocation" -> Location(world, 0.0, 0.0, 0.0, -90f, 0f)
                "locale" -> java.util.Locale.US
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
            override fun availableModelIds() = modelIds
            override fun railPlacementModel(controlKind: SignalWandControlKind, signalType: String,
                modelId: String?): RailPlacementModel? = models[when (controlKind) {
                SignalWandControlKind.ATTACHED_SIGNAL, SignalWandControlKind.ROUTE_INDICATOR -> modelId
                else -> primaryModels[signalType]
            }]
            override fun capture(worldId: UUID, signalType: String?, modelId: String?): SignalWandCapture {
                require(available != null && (signalType == null || available?.any { it.signalType == signalType } == true))
                require(modelId == null || modelIds?.contains(modelId) == true)
                return capture
            }
            override fun current(capture: SignalWandCapture) = current
            override fun protectionEntrance(capture: SignalWandCapture, point: RailPositionSnapshot) = protectionEntrance
            override fun occupiedPrimary(capture: SignalWandCapture, binding: FixedBlockSignalWandBinding) = occupiedPrimary
            override fun deviceConfiguration(capture: SignalWandCapture, device: PlacedSignalChoice) = SignalDeviceConfiguration("test:a", "test:primary")
            override fun diagnostics(capture: SignalWandCapture, device: PlacedSignalChoice): work.art1st.javarailways.simplesignals.runtime.SignalDeviceDiagnostics? = null
            override fun changeType(capture: SignalWandCapture, device: PlacedSignalChoice,
                signalType: String?, modelId: String?, active: () -> Boolean): SignalWandOutcome {
                check(!ownerThread); require(active() && current); typeChanges++
                return SignalWandOutcome("type changed")
            }
            override fun deletePlaced(capture: SignalWandCapture, device: PlacedSignalChoice, active: () -> Boolean): SignalWandOutcome {
                check(!ownerThread); require(active() && current); deletes++
                return SignalWandOutcome("deleted")
            }
            override fun placedDevices(capture: SignalWandCapture): List<PlacedSignalChoice> {
                check(!ownerThread)
                require(current)
                return listOf(placedDevice)
            }
            override fun savePlacement(capture: SignalWandCapture, device: PlacedSignalChoice,
                placement: ModelPlacement, active: () -> Boolean): SignalWandOutcome {
                check(!ownerThread)
                assertTrue(active())
                require(current)
                assertEquals(placedDevice, device)
                placements += placement
                return SignalWandOutcome("placement saved")
            }
            override fun indicatorDevices(capture: SignalWandCapture): List<RouteIndicatorDefinition> {
                check(!ownerThread)
                return listOf(RouteIndicatorDefinition("test:indicator", 2, "test:old-entry", "dark", emptyMap()))
            }
            override fun indicatorEntries(capture: SignalWandCapture): List<String> {
                check(!ownerThread)
                return listOf("test:new-entry")
            }
            override fun indicatorOptions(capture: SignalWandCapture, binding: RouteIndicatorSignalWandBinding,
                entryBoundaryId: String): RouteIndicatorWandOptions {
                check(!ownerThread)
                require(current)
                return RouteIndicatorWandOptions(indicatorRoutes, listOf("dark", "straight"), emptyMap(), indicatorRouteLabels)
            }
            override fun saveIndicator(capture: SignalWandCapture, binding: RouteIndicatorSignalWandBinding,
                entryBoundaryId: String, noRouteVariant: String, mappings: Map<String, String>,
                placement: ModelPlacement?, active: () -> Boolean): SignalWandOutcome {
                check(!ownerThread)
                assertTrue(active())
                require(current)
                indicatorSaves++
                indicatorSavedEntry = entryBoundaryId
                indicatorSavedNoRoute = noRouteVariant
                indicatorSavedMappings = mappings
                placement?.let(placements::add)
                return SignalWandOutcome("indicator saved")
            }
            override fun rebindDevices(capture: SignalWandCapture): List<SignalRebindChoice> {
                check(!ownerThread)
                return listOf(FixedBlockSignalRebindChoice(work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition(
                    placedDevice.id, placedDevice.revision, null, 0.0, "test:a")))
            }
            override fun rebindTargets(capture: SignalWandCapture, device: SignalRebindChoice): List<SignalRebindTarget> {
                check(!ownerThread)
                return listOf(FixedBlockSignalRebindTarget(work.art1st.javarailways.simplesignals.signal.FixedBlockSignalEntry("test:block", FixedBlockEntryDirection.FORWARD)))
            }
            override fun rebind(capture: SignalWandCapture, device: SignalRebindChoice,
                target: SignalRebindTarget, active: () -> Boolean): SignalWandOutcome {
                check(!ownerThread)
                assertTrue(active())
                require(current) { "Signal selection expired" }
                rebindings++
                return SignalWandOutcome("rebound")
            }
            override fun resolveEntrance(capture: SignalWandCapture, controlKind: SignalWandControlKind,
                mode: SignalWandMode, point: RailPositionSnapshot): List<SignalWandChoice> {
                check(!ownerThread)
                return resolvedChoices
            }
            override fun place(capture: SignalWandCapture, binding: SignalWandBinding,
                placement: ModelPlacement, active: () -> Boolean): SignalWandOutcome {
                check(!ownerThread)
                assertTrue(active())
                placements += placement
                performs++
                return SignalWandOutcome(WandText.diagnostic("updated"), applied = true)
            }
            override fun delete(capture: SignalWandCapture, binding: SignalWandBinding, active: () -> Boolean): SignalWandOutcome {
                check(!ownerThread)
                assertTrue(active())
                deletes++
                return SignalWandOutcome("deleted")
            }
        }
        var overlayChecks = 0
        val handler = SignalWandHandler(plugin, backend, targets, enabled = { pluginEnabled },
            checkOverlay = { check(ownerThread); overlayChecks++ },
            showAuthoringDialog = { _, _, options -> check(ownerThread); dialogOptions = options },
            showPlacementInput = { _, _, initial, submit -> check(ownerThread); placementInput = initial; submitPlacement = submit },
            worker = { workers += it }, entity = { _, action -> owners += action },
            toolInHand = { check(ownerThread); tool.takeIf { toolPresent } },
            giveTool = { _, value -> given += value },
            replaceToolState = { _, expected, replacement ->
                if (!toolPresent || tool != expected) false else { tool = replacement; true }
            },
            showPage = { _, value -> check(ownerThread); page = value; dialogOptions = value.options },
            findDevice = { check(ownerThread); CompletableFuture.completedFuture(Optional.ofNullable(pickedObjectId)) },
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
        fun rightClick() = handler.onInteract(PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, null, null, BlockFace.SELF, EquipmentSlot.HAND))
        fun drainOwners() { ownerThread = true; while (owners.isNotEmpty()) owners.removeFirst().invoke() }
        fun drain() { while (owners.isNotEmpty() || workers.isNotEmpty()) { drainOwners(); drainWorkers() } }
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
        private fun railModel(id: String, presets: Map<String, RailPlacementPreset>, followRailTilt: Boolean = false) =
            RailPlacementModel.from(LoadedPackageModel(id, ModelDefinition(emptyList()), emptyMap(), emptyMap(),
                followRailTilt, presets))

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
