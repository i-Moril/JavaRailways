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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `placement input edits rail offsets or absolute ground position only after confirmation`(ground: Boolean) = Fixture().use { f ->
        val rotation = Quaterniond().rotateXYZ(0.4, Math.PI / 2, 0.3)
        val originalPose = ModelPose(1.0, 2.0, 3.0, rotation.x, rotation.y, rotation.z, rotation.w)
        val original = if (ground) ModelPlacement.Ground(originalPose) else ModelPlacement.RailAttached(
            RailPositionSnapshot(f.rail.id, work.art1st.javarailways.core.api.physics.rail.ViewDirection.BA, 4.0), originalPose)
        f.placedDevice = f.placedDevice.copy(placement = original)
        val originalTool = f.tool
        f.handler.selectSetting(f.player, f.tool, SignalWandSettingAction.EditPlacement); f.drain()
        f.dialogOptions.first().second(); f.drain()
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
        f.handler.selectSetting(f.player, f.tool, SignalWandSettingAction.EditPlacement); f.drain()
        f.dialogOptions.first().second(); f.drain()
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
        f.handler.selectSetting(f.player, f.tool, SignalWandSettingAction.EditPlacement); f.drain()
        f.dialogOptions.first().second(); f.drain()
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

    @Test fun `indicator settings edit can choose new entry leave route unmapped and keep placement and tool unchanged`() = Fixture().use { f ->
        val original = f.tool
        f.handler.selectSetting(f.player, original, SignalWandSettingAction.EditIndicator); f.drain()
        f.dialogOptions.first().second(); f.drain()
        f.dialogOptions.single { it.first == "test:new-entry" }.second(); f.drain()
        f.dialogOptions.single { it.first == "dark" }.second(); f.drain()
        assertTrue(f.dialogOptions.first().first.contains("UNMAPPED"))
        f.dialogOptions.single { it.first.startsWith("Review") }.second(); f.drain()
        assertEquals(0, f.indicatorSaves)
        f.dialogOptions.first().second(); f.drain()
        assertEquals(1, f.indicatorSaves)
        assertEquals("test:new-entry", f.indicatorSavedEntry)
        assertTrue(f.indicatorSavedMappings.isEmpty())
        assertTrue(f.placements.isEmpty())
        assertEquals(original, f.tool)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["cancel", "tool", "permission", "catalog", "settings"])
    fun `indicator final confirmation cannot save after cancellation or stale continuation`(reason: String) = Fixture().use { f ->
        f.handler.selectSetting(f.player, f.tool, SignalWandSettingAction.EditIndicator); f.drain()
        f.dialogOptions.first().second(); f.drain()
        f.dialogOptions.first().second(); f.drain()
        f.dialogOptions.first().second(); f.drain()
        f.dialogOptions.single { it.first.startsWith("Review") }.second(); f.drain()
        val confirm = f.dialogOptions.first().second
        when (reason) {
            "cancel" -> { f.dialogOptions.last().second(); f.drain() }
            "tool" -> f.tool = f.tool.copy(stateRevision = 1)
            "permission" -> f.permitted = false
            "catalog" -> f.current = false
            "settings" -> {
                f.handler.openSettings(f.player, f.tool)
                f.handler.selectSetting(f.player, f.tool, SignalWandSettingAction.EditIndicator); f.drain()
            }
        }
        confirm(); f.drain()
        assertEquals(0, f.indicatorSaves)
    }

    @Test fun `rebind dialog includes unbound device waits for confirmation and ignores repeated old callbacks`() = Fixture().use { f ->
        val original = f.tool
        f.handler.selectSetting(f.player, original, SignalWandSettingAction.RebindProtection); f.drain()
        assertTrue(f.dialogOptions.single().first.contains("unbound"))
        val old = f.dialogOptions.single().second
        // Escape is callback-free; reopening must still work and retires the old page.
        f.handler.openSettings(f.player, f.tool)
        f.handler.selectSetting(f.player, f.tool, SignalWandSettingAction.RebindProtection); f.drain()
        old(); f.drain()
        assertTrue(f.dialogOptions.single().first.contains("unbound"))
        val device = f.dialogOptions.single().second
        device(); device(); f.drain()
        assertTrue(f.dialogOptions.single().first.contains("Entry test:entry"))
        f.dialogOptions.single().second(); f.drain()
        assertEquals(0, f.rebindings)
        val confirm = f.dialogOptions.first().second
        confirm(); confirm(); f.drain()
        assertEquals(1, f.rebindings)
        assertEquals(original, f.tool)
        assertEquals(0, f.performs)
        assertTrue(f.placements.isEmpty())
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["cancel", "tool", "permission", "settings", "catalog"])
    fun `rebind final confirmation rejects cancelled or stale continuation`(reason: String) = Fixture().use { f ->
        f.handler.selectSetting(f.player, f.tool, SignalWandSettingAction.RebindProtection); f.drain()
        f.dialogOptions.single().second(); f.drain()
        f.dialogOptions.single().second(); f.drain()
        val confirm = f.dialogOptions.first().second
        when (reason) {
            "cancel" -> { f.dialogOptions.last().second(); f.drain() }
            "tool" -> f.tool = f.tool.copy(stateRevision = 1)
            "permission" -> f.permitted = false
            "settings" -> f.handler.openSettings(f.player, f.tool)
            "catalog" -> f.current = false
        }
        confirm(); f.drain()
        assertEquals(0, f.rebindings)
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
        assertEquals("test:model", f.given.single().presentationModelId)
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
            "Control: attached signal", "Control: route indicator",
            "test:a — A", "test:z — Z", "Presentation model: test:model", "Rail placement: unadjusted",
            "Rebind existing protection signal", "Edit route indicator mapping / entry", "Edit existing device placement"),
            f.settings.map { it.label })
        assertEquals(listOf(true, false, true, false, false, false, false, false, true, false, true, false, false, false),
            f.settings.map { it.selected })
        f.choose!!(SignalWandSettingAction.Type("test:a"))
        f.drainOwners()
        assertEquals(opening.itemId, f.tool.itemId)
        assertEquals(1L, f.tool.stateRevision)
        assertEquals("test:a", f.tool.signalType)
        assertTrue(f.messages.last().toString().contains("selected"))
        val modelOpening = f.tool
        f.handler.openSettings(f.player, modelOpening)
        f.choose!!(SignalWandSettingAction.Model("test:model"))
        f.drainOwners()
        assertEquals(2L, f.tool.stateRevision)
        assertEquals("test:model", f.tool.presentationModelId)
    }

    @Test fun `rail preset settings copy sorted immutable values without editing devices and can explicitly reapply or clear`() = Fixture().use { f ->
        val offset = ModelPose(0.0, 1.0, 2.0, 0.0, 0.0, 0.0, 1.0)
        val source = linkedMapOf("right" to RailPlacementPreset(offset),
            "left" to RailPlacementPreset(ModelPose.IDENTITY, false))
        f.models = mapOf("test:primary" to railModel("test:primary", source, followRailTilt = true))
        val capturedProjection = f.models.getValue("test:primary")
        source.clear()
        assertEquals(listOf("left", "right"), capturedProjection.presets.keys.toList())
        assertThrows(UnsupportedOperationException::class.java) {
            (capturedProjection.presets as MutableMap<String, RailPlacementPreset>).clear()
        }
        f.handler.openSettings(f.player, f.tool)
        assertEquals(listOf("Rail placement: unadjusted", "Rail placement: left", "Rail placement: right"),
            f.settings.filter { it.action is SignalWandSettingAction.RailPreset ||
                it.action === SignalWandSettingAction.ClearRailPreset }.map { it.label })
        f.choose!!(SignalWandSettingAction.RailPreset("test:primary", "right")); f.drainOwners()
        assertEquals(SignalWandRailPreset("test:primary", "right", offset, true), f.tool.railPreset)
        assertEquals(f.binding, f.tool.binding)
        assertEquals(1L, f.tool.stateRevision)
        assertEquals(0, f.performs)
        assertTrue(f.workers.isEmpty())
        assertTrue(SignalWandHandler.statusText(f.tool, f.available, f.modelIds).toString().contains("rail-only preset: right"))
        f.handler.openSettings(f.player, f.tool)
        assertTrue(f.settings.single { it.action == SignalWandSettingAction.RailPreset("test:primary", "right") }.selected)

        val replacement = RailPlacementPreset(ModelPose.IDENTITY, false)
        f.models = mapOf("test:primary" to railModel("test:primary", mapOf("right" to replacement)))
        assertEquals(offset, f.tool.railPreset!!.adjustment)
        f.choose!!(SignalWandSettingAction.RailPreset("test:primary", "right")); f.drainOwners()
        assertEquals(SignalWandRailPreset("test:primary", "right", ModelPose.IDENTITY, false), f.tool.railPreset)
        f.handler.openSettings(f.player, f.tool)
        f.choose!!(SignalWandSettingAction.ClearRailPreset); f.drainOwners()
        assertNull(f.tool.railPreset)
        assertEquals(f.binding, f.tool.binding)
        assertEquals(0, f.performs)
        f.click()
        f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0)))
        f.drainOwners(); f.drainWorkers(); f.drainOwners()
        val placement = f.placements.single() as ModelPlacement.RailAttached
        assertEquals(ModelPose.IDENTITY, placement.adjustment)
        assertFalse(placement.followRailTilt)
    }

    @Test fun `preset callbacks reject stale items missing presets and changed effective model`() = Fixture().use { f ->
        fun withPreset() = railModel("test:primary", mapOf("right" to RailPlacementPreset(ModelPose.IDENTITY)))
        f.models = mapOf("test:primary" to withPreset())
        val original = f.tool
        f.handler.openSettings(f.player, original)
        val firstCallback = f.choose!!
        f.tool = original.copy(stateRevision = 1)
        firstCallback(SignalWandSettingAction.RailPreset("test:primary", "right")); f.drainOwners()
        assertNull(f.tool.railPreset)
        f.tool = original
        f.handler.openSettings(f.player, f.tool)
        f.models = mapOf("test:primary" to railModel("test:primary", emptyMap()))
        f.choose!!(SignalWandSettingAction.RailPreset("test:primary", "right")); f.drainOwners()
        assertEquals(original, f.tool)
        assertTrue(f.messages.last().toString().contains("no longer available"))
        f.models = mapOf("test:primary" to withPreset())
        f.handler.openSettings(f.player, f.tool)
        f.primaryModels = mapOf("test:a" to "test:other")
        f.choose!!(SignalWandSettingAction.RailPreset("test:primary", "right")); f.drainOwners()
        assertEquals(original, f.tool)
    }

    @Test fun `effective model switches clear preferences but unrelated settings retain them`() = Fixture().use { f ->
        val copied = SignalWandRailPreset("test:primary", "right", ModelPose.IDENTITY, false)
        f.tool = f.tool.copy(railPreset = copied)
        fun select(action: SignalWandSettingAction) {
            f.handler.openSettings(f.player, f.tool)
            f.choose!!(action); f.drainOwners()
        }
        select(SignalWandSettingAction.Model("test:model"))
        assertEquals(copied, f.tool.railPreset) // primary model is selected by signal type
        select(SignalWandSettingAction.Mode(SignalWandMode.DELETION))
        assertEquals(copied, f.tool.railPreset)
        f.primaryModels = mapOf("test:a" to "test:primary", "test:b" to "test:primary")
        select(SignalWandSettingAction.Type("test:b"))
        assertEquals(copied, f.tool.railPreset)
        f.primaryModels = mapOf("test:a" to "test:other", "test:b" to "test:primary")
        select(SignalWandSettingAction.Type("test:a"))
        assertNull(f.tool.railPreset)
        f.tool = f.tool.copy(railPreset = copied)
        select(SignalWandSettingAction.ControlKind(SignalWandControlKind.ATTACHED_SIGNAL))
        assertNull(f.tool.railPreset) // model-only namespace is not the signal-pack namespace
        f.tool = f.tool.copy(railPreset = copied.copy(modelId = "test:model"))
        select(SignalWandSettingAction.Type("test:b"))
        assertEquals("test:model", f.tool.railPreset!!.modelId)
        f.modelIds = listOf("test:model", "test:other")
        select(SignalWandSettingAction.Model("test:other"))
        assertNull(f.tool.railPreset)
        assertEquals(0, f.performs)
    }

    @Test fun `rail placement uses copied preference after source removal while ground placement ignores it`() = Fixture().use { f ->
        val adjustment = ModelPose(0.0, 1.0, 2.0, 0.0, 0.0, 1.0, 0.0)
        f.models = mapOf("test:primary" to railModel("test:primary", mapOf("right" to RailPlacementPreset(adjustment, true))))
        f.handler.openSettings(f.player, f.tool)
        f.choose!!(SignalWandSettingAction.RailPreset("test:primary", "right")); f.drainOwners()
        f.models = mapOf("test:primary" to railModel("test:primary", emptyMap()))
        f.click()
        f.target.complete(Optional.of(RailTarget(f.rail, 2.0, 0.0)))
        f.drainOwners(); f.drainWorkers(); f.drainOwners()
        val railPlacement = f.placements.single() as ModelPlacement.RailAttached
        assertEquals(adjustment, railPlacement.adjustment)
        assertTrue(railPlacement.followRailTilt)
        assertEquals(2.0, railPlacement.position.arcLengthInAbMeters)
        val ground = SignalWandHandler.groundPlacement(10, 63, -4, BlockFace.EAST, 0f)
        f.handler.click(f.player, f.tool, Action.RIGHT_CLICK_BLOCK, ground)
        f.drainWorkers(); f.drainOwners()
        assertEquals(ground, f.placements.last())
        assertEquals(1, f.targetCalls)
        f.primaryModels = mapOf("test:a" to "test:other")
        f.target = CompletableFuture()
        f.click()
        f.target.complete(Optional.of(RailTarget(f.rail, 3.0, 0.0)))
        f.drainOwners(); f.drainWorkers(); f.drainOwners()
        assertEquals(2, f.performs)
        assertTrue(f.messages.last().toString().contains("model changed"))
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
        assertEquals(ModelPlacement.RailAttached(
            RailPositionSnapshot(f.rail.id, work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB, 2.0)),
            f.placements.single())
    }

    @Test fun `ground placement uses face centre and upright player facing without rail lookup`() = Fixture().use { f ->
        val placement = SignalWandHandler.groundPlacement(10, 63, -4, BlockFace.EAST, 0f)
        val pose = placement.pose
        assertEquals(11.0, pose.x(), 1e-12)
        assertEquals(63.5, pose.y(), 1e-12)
        assertEquals(-3.5, pose.z(), 1e-12)
        assertEquals(0.0, pose.rotationX(), 1e-12)
        assertEquals(-sqrt(0.5), pose.rotationY(), 1e-12)
        assertEquals(0.0, pose.rotationZ(), 1e-12)
        assertEquals(sqrt(0.5), pose.rotationW(), 1e-12)

        f.handler.click(f.player, f.tool, Action.RIGHT_CLICK_BLOCK, placement)
        assertEquals(0, f.targetCalls)
        assertEquals(1, f.workers.size)
        f.drainWorkers()
        assertEquals(listOf(placement), f.placements)
        assertEquals(1, f.performs)
        f.drainOwners()
        assertTrue(f.messages.single().toString().contains("updated"))
    }

    @Test fun `block event captures ground placement while shift right click keeps settings priority`() = Fixture().use { f ->
        val block = proxy(Block::class.java) { name, _ -> when (name) {
            "getX" -> 10
            "getY" -> 63
            "getZ" -> -4
            else -> error("Unexpected block method $name")
        } }
        val place = PlayerInteractEvent(f.player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.EAST,
            EquipmentSlot.HAND)

        f.handler.onInteract(place)

        assertEquals(Event.Result.DENY, place.useItemInHand())
        assertEquals(0, f.targetCalls)
        f.drainWorkers()
        assertEquals(SignalWandHandler.groundPlacement(10, 63, -4, BlockFace.EAST, 90f), f.placements.single())
        f.drainOwners()

        f.sneaking = true
        val settings = PlayerInteractEvent(f.player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP,
            EquipmentSlot.HAND)
        f.handler.onInteract(settings)

        assertEquals(Event.Result.DENY, settings.useItemInHand())
        assertTrue(f.settings.isNotEmpty())
        assertEquals(1, f.performs)
        assertEquals(0, f.targetCalls)
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
                "isSneaking" -> sneaking
                "getLocation" -> Location(world, 0.0, 0.0, 0.0, 90f, 0f)
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
            override fun availableModelIds() = modelIds
            override fun railPlacementModel(controlKind: SignalWandControlKind, signalType: String,
                modelId: String?): RailPlacementModel? = models[when (controlKind) {
                SignalWandControlKind.ATTACHED_SIGNAL, SignalWandControlKind.ROUTE_INDICATOR -> modelId
                else -> primaryModels[signalType]
            }]
            override fun capture(worldId: UUID, signalType: String, modelId: String?): SignalWandCapture {
                require(available?.any { it.signalType == signalType } == true)
                require(modelId == null || modelIds?.contains(modelId) == true)
                return capture
            }
            override fun current(capture: SignalWandCapture) = current
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
                return RouteIndicatorWandOptions(listOf("test:route"), listOf("dark", "straight"), emptyMap())
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
            override fun rebindDevices(capture: SignalWandCapture): List<ProtectionSignalRebindChoice> {
                check(!ownerThread)
                return listOf(ProtectionSignalRebindChoice(ProtectionSignalDefinition("test:unbound", 2,
                    ProtectionSignalKind.FIXED_ROUTE, null, 0.0, "test:a")))
            }
            override fun rebindTargets(capture: SignalWandCapture, device: ProtectionSignalRebindChoice): List<ProtectionSignalRebindTarget> {
                check(!ownerThread)
                return listOf(ProtectionSignalRebindTarget("test:entry", "test:route"))
            }
            override fun rebind(capture: SignalWandCapture, device: ProtectionSignalRebindChoice,
                target: ProtectionSignalRebindTarget, active: () -> Boolean): SignalWandOutcome {
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
            showAuthoringDialog = { _, _, options -> check(ownerThread); dialogOptions = options },
            showPlacementInput = { _, _, initial, submit -> check(ownerThread); placementInput = initial; submitPlacement = submit },
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
