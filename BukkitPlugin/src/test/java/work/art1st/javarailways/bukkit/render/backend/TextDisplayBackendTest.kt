package work.art1st.javarailways.bukkit.render.backend

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import me.tofaa.entitylib.EntityLib
import me.tofaa.entitylib.EntityLibAPI
import me.tofaa.entitylib.meta.Metadata
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta
import me.tofaa.entitylib.meta.display.BlockDisplayMeta
import me.tofaa.entitylib.meta.display.ItemDisplayMeta
import me.tofaa.entitylib.meta.display.TextDisplayMeta
import me.tofaa.entitylib.wrapper.WrapperEntity
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.util.EntityCleanupDispatcher
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.function.Consumer
import sun.misc.Unsafe

class TextDisplayBackendTest {
    @Test
    fun `native factory creates transparent fixed centered unwrapped text with common display settings`() = withHost { host ->
        val state = linkedMapOf<String, Any?>()
        val display = proxy(TextDisplay::class.java) { name, args ->
            when (name) {
                "getEntityId" -> 42
                "text", "setPersistent", "setVisibleByDefault", "setInterpolationDelay", "setInterpolationDuration",
                "setTeleportDuration", "setViewRange", "setTransformation", "setBillboard", "setAlignment",
                "setLineWidth", "setTextOpacity", "setShadowed", "setSeeThrough", "setDefaultBackground", "setBackgroundColor" -> {
                    state[name] = args!![0]; null
                }
                "remove" -> null
                else -> error("Unexpected native display call: $name")
            }
        }
        val world = proxy(World::class.java) { name, args ->
            check(name == "spawn") { "Unexpected world call: $name" }
            assertEquals(TextDisplay::class.java, args!![1])
            @Suppress("UNCHECKED_CAST")
            (args[2] as Consumer<TextDisplay>).accept(display)
            display
        }
        val text = styledText()
        val transformation = transformation(2f)
        val visual = NativeDisplayRenderBackend(host).create(world, DisplayVisualDescriptor(
            Location(world, 1.0, 2.0, 3.0), DisplayVisualContent.Text(text), transformation, 4f, 5, 6))
        try {
            assertSame(text, state["text"])
            assertEquals(false, state["setPersistent"])
            assertEquals(false, state["setVisibleByDefault"])
            assertEquals(0, state["setInterpolationDelay"])
            assertEquals(5, state["setInterpolationDuration"])
            assertEquals(6, state["setTeleportDuration"])
            assertEquals(4f, state["setViewRange"])
            assertSame(transformation, state["setTransformation"])
            assertEquals(Display.Billboard.FIXED, state["setBillboard"])
            assertEquals(TextDisplay.TextAlignment.CENTER, state["setAlignment"])
            assertEquals(Int.MAX_VALUE, state["setLineWidth"])
            assertEquals((-1).toByte(), state["setTextOpacity"])
            assertEquals(false, state["setShadowed"])
            assertEquals(false, state["setSeeThrough"])
            assertEquals(false, state["setDefaultBackground"])
            assertEquals(Color.fromARGB(0), state["setBackgroundColor"])
        } finally { visual.destroy().toCompletableFuture().join() }
    }

    @Test
    fun `native text update clears content and retains transform visibility brightness and retirement contracts`() {
        val calls = mutableListOf<String>()
        var current: Component = Component.empty()
        val display = proxy(TextDisplay::class.java) { name, args -> when (name) {
            "getEntityId" -> 42
            "text" -> { current = args!![0] as Component; calls += name; null }
            "setTransformation", "setBrightness", "remove" -> { calls += name; null }
            "teleport" -> { calls += name; true }
            else -> error("Unexpected native call: $name")
        } }
        val player = proxy(Player::class.java) { name, _ ->
            check(name in setOf("showEntity", "hideEntity")); calls += name; null
        }
        val dispatcher = EntityCleanupDispatcher()
        val visual = NativeDisplayVisual(plugin(), display, dispatcher)
        val styled = styledText()
        visual.setText(styled)
        assertSame(styled, current)
        visual.setTransformation(transformation(3f))
        visual.setBrightness(7, 12)
        visual.clearBrightness()
        visual.teleport(Location(null, 1.0, 2.0, 3.0))
        visual.showTo(player); visual.hideFrom(player)
        visual.setText(Component.empty())
        assertEquals(Component.empty(), current)
        val destruction = visual.destroy()
        val beforeRetiredUpdates = calls.toList()
        visual.setText(Component.text("must not reappear"))
        visual.setTransformation(transformation(4f))
        visual.showTo(player); visual.hideFrom(player)
        assertEquals(beforeRetiredUpdates, calls)
        assertFalse(destruction.toCompletableFuture().isDone)
        assertSame(destruction, visual.destroy())
        dispatcher.action!!.invoke()
        destruction.toCompletableFuture().join()
        assertEquals(listOf("text", "setTransformation", "setBrightness", "setBrightness", "teleport", "showEntity", "hideEntity", "text", "remove"), calls)
    }

    @Test
    fun `native nontext visuals reject text while live and ignore it after destruction`() {
        for (type in listOf(ItemDisplay::class.java, BlockDisplay::class.java)) {
            val entity = proxy(type) { name, _ -> when (name) { "getEntityId" -> 42; "remove" -> null; else -> error(name) } }
            val visual = NativeDisplayVisual(plugin(), entity,
                EntityCleanupDispatcher().apply { immediate = true })
            val failure = assertThrows(IllegalArgumentException::class.java) { visual.setText(Component.empty()) }
            assertTrue(failure.message!!.contains("text display"))
            visual.destroy().toCompletableFuture().join()
            assertDoesNotThrow { visual.setText(Component.empty()) }
        }
    }

    @Test
    fun `real EntityLib text metadata uses identical explicit display defaults and styled component`() = withEntityLibMetadata {
        val meta = TextDisplayMeta(42, Metadata(42))
        configurePacketText(meta, styledText())
        assertEquals(styledText(), meta.text)
        assertEquals(AbstractDisplayMeta.BillboardConstraints.FIXED, meta.billboardConstraints)
        assertEquals(Int.MAX_VALUE, meta.lineWidth)
        assertEquals(0, meta.backgroundColor)
        assertEquals((-1).toByte(), meta.textOpacity)
        assertFalse(meta.isShadow)
        assertFalse(meta.isSeeThrough)
        assertFalse(meta.isUseDefaultBackground)
        assertFalse(meta.isAlignLeft)
        assertFalse(meta.isAlignRight)
    }

    @Test
    fun `packet text queues actual immutable metadata snapshots in existing frame order and ignores retired updates`() = withEntityLibMetadata {
        val sent = mutableListOf<Pair<UUID, List<PacketWrapper<*>>>>()
        val bundles = TrainPacketBundleCoordinator { viewer, packets -> sent += viewer to packets }
        withHost(bundles) {
            val meta = TextDisplayMeta(42, Metadata(42))
            configurePacketText(meta, Component.text("initial"))
            val wrapper = WrapperEntity(42, UUID.randomUUID(), EntityTypes.TEXT_DISPLAY, meta)
            val viewer = UUID.randomUUID()
            wrapper.addViewerSilently(viewer)
            val visual = packetVisual(wrapper)
            val styled = styledText()
            bundles.frame {
                visual.setText(styled)
                visual.setTransformation(transformation(3f))
                visual.setBrightness(7, 12)
                visual.clearBrightness()
                visual.setText(Component.empty())
                assertTrue(sent.isEmpty(), "text metadata must remain in the current shared frame")
            }
            assertEquals(1, sent.size)
            assertEquals(viewer, sent.single().first)
            val packets = sent.single().second.map { it as WrapperPlayServerEntityMetadata }
            assertEquals(5, packets.size)
            assertTrue(packets.all { it.entityId == 42 })
            assertTrue(packets.first().entityMetadata.any { it.value == styled })
            assertTrue(packets.last().entityMetadata.any { it.value == Component.empty() })
            assertEquals(Component.empty(), meta.text)
            assertEquals(3f, meta.translation.x)
            assertEquals(-1, meta.brightnessOverride)
            assertEquals(0, meta.backgroundColor)
            assertFalse(meta.isShadow)
            val destruction = visual.destroy()
            destruction.toCompletableFuture().join() // Fixture wrapper was never spawned into a container.
            assertSame(destruction, visual.destroy())
            visual.setText(Component.text("retired"))
            visual.setTransformation(transformation(8f))
            assertEquals(1, sent.size)
            assertEquals(Component.empty(), meta.text)
            assertEquals(3f, meta.translation.x)
        }
    }

    @Test
    fun `packet nontext visuals reject text without touching metadata or packet queues`() = withEntityLibMetadata {
        for ((type, meta) in listOf(EntityTypes.ITEM_DISPLAY to ItemDisplayMeta(42, Metadata(42)),
            EntityTypes.BLOCK_DISPLAY to BlockDisplayMeta(42, Metadata(42)))) {
            val visual = packetVisual(WrapperEntity(42, UUID.randomUUID(), type, meta))
            assertThrows(IllegalArgumentException::class.java) { visual.setText(Component.empty()) }
            visual.destroy().toCompletableFuture().join()
            assertDoesNotThrow { visual.setText(Component.empty()) }
        }
    }

    @Test
    fun `packet late passenger refresh uses captured IDs and child retirement also clears actual wrapper passengers`() = withEntityLibMetadata {
        val sent = mutableListOf<WrapperPlayServerSetPassengers>()
        withHost(TrainPacketBundleCoordinator { _, packets -> sent += packets.map { it as WrapperPlayServerSetPassengers } }) {
            val parentWrapper = WrapperEntity(42, UUID.randomUUID(), EntityTypes.ITEM_DISPLAY, ItemDisplayMeta(42, Metadata(42)))
            val childWrapper = WrapperEntity(43, UUID.randomUUID(), EntityTypes.TEXT_DISPLAY, TextDisplayMeta(43, Metadata(43)))
            val parent = packetVisual(parentWrapper)
            val child = packetVisual(childWrapper)
            val player = proxy(Player::class.java) { name, _ -> if (name == "getUniqueId") UUID(0, 1) else error(name) }
            assertTrue(parent.addPassenger(child))
            assertTrue(parentWrapper.passengers.contains(43))
            parent.refreshPassengersFor(player)
            assertEquals(42, sent.single().entityId)
            assertArrayEquals(intArrayOf(43), sent.single().passengers)
            child.destroy().toCompletableFuture().join()
            assertFalse(parentWrapper.passengers.contains(43), "cached packet IDs alone do not repair EntityLib's own parent state")
            parent.refreshPassengersFor(player)
            assertArrayEquals(intArrayOf(), sent.last().passengers)
            parent.destroy().toCompletableFuture().join()
        }
    }

    @Test
    fun `native late passenger refresh reads no entity getters from viewer owner and forgets destroyed child IDs`() = withPacketVersion {
        val sent = mutableListOf<WrapperPlayServerSetPassengers>()
        withHost(TrainPacketBundleCoordinator { _, packets -> sent += packets.map { it as WrapperPlayServerSetPassengers } }) { host ->
            var ownerReadsAllowed = true
            fun entity(id: Int): TextDisplay = proxy(TextDisplay::class.java) { name, _ -> when (name) {
                "getEntityId" -> { check(ownerReadsAllowed); id }
                "getVehicle" -> { check(ownerReadsAllowed); null }
                "addPassenger" -> { check(ownerReadsAllowed); true }
                "remove" -> null
                else -> error("Unexpected entity call: $name")
            } }
            val dispatcher = EntityCleanupDispatcher().apply { immediate = true }
            val parent = NativeDisplayVisual(host, entity(42), dispatcher)
            val child = NativeDisplayVisual(host, entity(43), dispatcher)
            assertTrue(parent.addPassenger(child))
            ownerReadsAllowed = false
            val player = proxy(Player::class.java) { name, _ -> if (name == "getUniqueId") UUID(0, 1) else error(name) }
            parent.refreshPassengersFor(player)
            assertArrayEquals(intArrayOf(43), sent.single().passengers)
            child.destroy().toCompletableFuture().join()
            parent.refreshPassengersFor(player)
            assertArrayEquals(intArrayOf(), sent.last().passengers)
            parent.destroy().toCompletableFuture().join()
        }
    }

    private fun withEntityLibMetadata(block: () -> Unit) = withPacketVersion {
        synchronized(EntityLib::class.java) {
            val field = EntityLib::class.java.getDeclaredField("api").also { it.isAccessible = true }
            val previous = field.get(null)
            val settings = me.tofaa.entitylib.APIConfig(com.github.retrooper.packetevents.PacketEvents.getAPI())
            // Supply the existing PacketEvents version binding and no spawned entities. This fixture
            // has no container or live entities and never starts the EntityLib platform/network.
            val api = proxy(EntityLibAPI::class.java) { name, _ ->
                when (name) {
                    "getPacketEvents" -> com.github.retrooper.packetevents.PacketEvents.getAPI()
                    "getSettings" -> settings
                    "getEntity" -> null
                    else -> error("Unexpected EntityLib API call: $name")
                }
            }
            field.set(null, api)
            try { block() } finally { field.set(null, previous) }
        }
    }

    private fun configurePacketText(meta: TextDisplayMeta, text: Component) {
        EntityLibDisplayRenderBackend::class.java.getDeclaredMethod("configureText", TextDisplayMeta::class.java, Component::class.java)
            .also { it.isAccessible = true }.invoke(EntityLibDisplayRenderBackend(PacketDisplayChunks()), meta, text)
    }

    private fun packetVisual(wrapper: WrapperEntity): DisplayVisual = EntityLibDisplayVisual(wrapper, PacketDisplayChunks())

    private fun styledText() = Component.text("下一站", NamedTextColor.AQUA)
        .font(Key.key("example:display"))
        .decorate(TextDecoration.BOLD)
        .append(Component.newline()).append(Component.text("Central"))

    private fun transformation(x: Float) = Transformation(Vector3f(x, 2f, 1f), Quaternionf(), Vector3f(1f), Quaternionf())

    private fun plugin() = proxy(Plugin::class.java) { name, _ -> error("Unexpected plugin call: $name") }

    /** Only supplies the existing global backend dependencies; no plugin/server lifecycle is simulated. */
    private fun withHost(bundles: TrainPacketBundleCoordinator = TrainPacketBundleCoordinator { _, _ -> }, block: (BukkitPlugin) -> Unit) =
        synchronized(BukkitPlugin::class.java) {
            val field = BukkitPlugin::class.java.getDeclaredField("INSTANCE").also { it.isAccessible = true }
            val previous = field.get(null)
            val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").also { it.isAccessible = true }.get(null) as Unsafe
            val host = (unsafe.allocateInstance(BukkitPlugin::class.java) as BukkitPlugin).also {
                it.platformDispatcher = EntityCleanupDispatcher().apply { immediate = true }
                it.trainPacketBundles = bundles
            }
            field.set(null, host)
            try { block(host) } finally { field.set(null, previous) }
        }

    private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args -> when (method.name) {
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === args?.get(0)
            "toString" -> type.simpleName + " fixture"
            else -> call(method.name, args)
        } })
}
