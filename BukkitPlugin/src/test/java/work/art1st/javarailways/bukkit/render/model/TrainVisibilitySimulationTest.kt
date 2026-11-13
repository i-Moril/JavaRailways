package work.art1st.javarailways.bukkit.render.model

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.PacketEventsAPI
import com.github.retrooper.packetevents.injector.ChannelInjector
import com.github.retrooper.packetevents.manager.player.PlayerManager
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager
import com.github.retrooper.packetevents.manager.server.ServerManager
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.netty.NettyManager
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityType
import com.github.retrooper.packetevents.protocol.world.Location as PacketLocation
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import me.tofaa.entitylib.APIConfig
import me.tofaa.entitylib.EntityLib
import me.tofaa.entitylib.EntityLibAPI
import me.tofaa.entitylib.Platform
import me.tofaa.entitylib.UserLocaleProvider
import me.tofaa.entitylib.container.EntityContainer
import me.tofaa.entitylib.event.EventHandler
import me.tofaa.entitylib.wrapper.WrapperEntity
import me.tofaa.entitylib.meta.display.AbstractDisplayMeta
import me.tofaa.entitylib.meta.display.TextDisplayMeta
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.translation.GlobalTranslator
import net.kyori.adventure.translation.TranslationStore
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.joml.Vector3d
import org.joml.Vector3f
import org.joml.Quaternionf
import org.bukkit.util.Transformation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
import work.art1st.javarailways.bukkit.render.backend.EntityLibDisplayVisual
import work.art1st.javarailways.bukkit.render.backend.PacketDisplayChunks
import work.art1st.javarailways.bukkit.render.backend.TrainPacketBundleCoordinator
import work.art1st.javarailways.bukkit.render.backend.PassengerManagedDisplayEntity
import work.art1st.javarailways.bukkit.BukkitPlugin
import sun.misc.Unsafe
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import org.bukkit.Chunk
import org.bukkit.Location
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import work.art1st.javarailways.bukkit.api.model.ModelDefinition
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.render.chunk.OrdinaryModelRenderable
import work.art1st.javarailways.bukkit.render.chunk.RenderChunkManager
import work.art1st.javarailways.bukkit.render.chunk.ViewDistanceSettings
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import java.util.concurrent.CompletableFuture
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.api.util.plugin.Plugin
import work.art1st.javarailways.core.api.util.tick.TickManager
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.UUID
import java.util.logging.Logger

/**
 * Characterization tests for the reported symptom, not a Minecraft client emulator.
 * Production CompositeModel, DisplayModel, EntityLibDisplayVisual and WrapperEntity are used.
 * Only the network exit and a minimal client entity table are substituted. Clearing/dropping
 * client entities is explicit fault injection; these tests do not explain why a real client lost them.
 */
class TrainVisibilitySimulationTest {
    private val packets = mutableMapOf<UUID, MutableList<PacketWrapper<*>>>()
    private val clients = mutableMapOf<UUID, ClientEntityTable>()
    private val entities = mutableMapOf<Int, WrapperEntity>()
    private val container = EntityContainer.basic()
    private val chunks = PacketDisplayChunks()
    private val unsentChunks = mutableMapOf<UUID, MutableSet<Long>>()
    private val locales = mutableMapOf<UUID, Locale>()
    private val worldId = UUID.randomUUID()
    private val bukkitWorld = proxy<org.bukkit.World> { name, _ ->
        when (name) {
            "getUID" -> worldId
            else -> error("Unexpected Bukkit world call: $name")
        }
    }
    private var previousPacketApi: PacketEventsAPI<*>? = null
    private var previousEntityApi: Any? = null
    private var previousPlatform: Any? = null
    private var previousHost: Any? = null
    private lateinit var settings: APIConfig
    private val bundles = TrainPacketBundleCoordinator { viewer, batch -> batch.forEach { deliver(viewer, it) } }

    @BeforeEach
    fun setUp() {
        previousPacketApi = PacketEvents.getAPI()
        previousEntityApi = entityLibField("api").get(null)
        previousPlatform = entityLibField("platform").get(null)
        val hostField = BukkitPlugin::class.java.getDeclaredField("currentInstance").also { it.isAccessible = true }
        previousHost = hostField.get(null)
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").also { it.isAccessible = true }
        val host = (unsafeField.get(null) as Unsafe).allocateInstance(BukkitPlugin::class.java) as BukkitPlugin
        BukkitPlugin::class.java.getDeclaredField("trainPacketBundles").also { it.isAccessible = true }.set(host, bundles)
        hostField.set(null, host)
        val protocol = proxy<ProtocolManager> { name, args ->
            when (name) {
                "getChannel" -> args[0]
                "sendPacket" -> {
                    val viewer = args[0] as UUID
                    val packet = args[1] as PacketWrapper<*>
                    deliver(viewer, packet)
                    null
                }
                else -> error("Unexpected protocol call: $name")
            }
        }
        val packetApi = TestPacketApi(protocol)
        PacketEvents.setAPI(packetApi)
        settings = APIConfig(packetApi).disableBStats()
        val api = proxy<EntityLibAPI<Any>> { name, args ->
            when (name) {
                "getSettings" -> settings
                "getPacketEvents" -> packetApi
                "getEntity" -> entities[args[0]]
                "getDefaultContainer" -> container
                "getUserLocaleProvider" -> UserLocaleProvider { locales[it] ?: Locale.US }
                else -> error("Unexpected EntityLib API call: $name")
            }
        }
        val events = EventHandler.create()
        val platform = proxy<Platform<Any>> { name, _ ->
            when (name) {
                "setupApi" -> null
                "getAPI" -> api
                "getLogger" -> Logger.getLogger(javaClass.name)
                "getEventHandler" -> events
                else -> error("Unexpected platform call: $name")
            }
        }
        EntityLib.init(platform, settings)
    }

    @AfterEach
    fun tearDown() {
        chunks.close()
        entityLibField("api").set(null, previousEntityApi)
        entityLibField("platform").set(null, previousPlatform)
        PacketEvents.setAPI(previousPacketApi)
        BukkitPlugin::class.java.getDeclaredField("currentInstance").also { it.isAccessible = true }.set(null, previousHost)
    }

    @Test
    fun `unspawned roots receive no passenger packets and repeated audience refresh is idempotent`() {
        val train = train()
        val player = player()
        unsentChunks.getOrPut(player.uniqueId, ::mutableSetOf).add(Chunk.getChunkKey(0, 0))
        repeat(100) {
            train.model.showTo(player)
            train.rootVisual.refreshPassengersFor(player)
        }
        assertEquals(0, client(player).unknownVehicles, "The client warning means the vehicle itself is absent")
        assertTrue(mounts(player).isEmpty())

        unsentChunks.getValue(player.uniqueId).clear()
        train.model.showTo(player)
        train.rootVisual.refreshPassengersFor(player)
        assertEquals(listOf(CHILD), client(player).passengers[ROOT])
        assertEquals(1, mounts(player).size)
        repeat(100) {
            train.model.showTo(player)
            train.rootVisual.refreshPassengersFor(player)
        }
        assertEquals(1, mounts(player).size, "An unchanged list must not eject and reattach every client passenger")
    }

    @Test
    fun `dynamic screen grow shrink and EMPTY publish one complete visible passenger list per replacement`() {
        settings.forceBundles()
        val player = player()
        val observer = player()
        val root = visual(entity(ROOT))
        root.showTo(player)
        root.showTo(observer)
        var nextId = 200
        var current = emptyList<DisplayVisual>()
        for (count in listOf(90, 146, 90, 0)) {
            val before = mounts(player).size
            val observerBefore = packets[observer.uniqueId].orEmpty().size
            bundles.frame {
                val next = List(count) { visual(entity(nextId++)).also { assertTrue(root.addPassenger(it)) } }
                current.forEach { it.destroy().toCompletableFuture().join() }
                current = next
                next.forEach { it.showTo(player) }
                root.refreshPassengersFor(player)
                root.refreshPassengersFor(observer)
            }
            assertEquals(before + 1, mounts(player).size, "Replace a screen as a batch, without per-glyph mount broadcasts")
            assertEquals(count, client(player).passengers[ROOT]?.size)
            assertEquals(0, client(player).unknownVehicles)
            assertEquals(0, client(player).unknownPassengers, "The complete list must follow all child spawns")
            assertTrue(mounts(observer).isEmpty(), "Hidden interior leaves must never enter another viewer's mount list")
            assertEquals(observerBefore, packets[observer.uniqueId].orEmpty().size,
                "Suppress null mount broadcasts even when EntityLib forceBundles is enabled")
            assertEquals(setOf(ROOT), client(observer).entities)
        }
        root.destroy().toCompletableFuture().join()
    }

    @Test
    fun `hide or destroy in an existing packet frame cannot flush passenger packets after the root is gone`() {
        for (destroy in listOf(false, true)) {
            val train = train()
            val player = player()
            bundles.frame {
                train.model.showTo(player)
                train.rootVisual.refreshPassengersFor(player)
                if (destroy) train.model.destroy().toCompletableFuture().join() else train.model.hideFrom(player)
            }
            assertTrue(client(player).entities.isEmpty())
            assertEquals(0, client(player).unknownVehicles)
            train.rootVisual.refreshPassengersFor(player)
            assertEquals(0, client(player).unknownVehicles)
        }
    }

    @Test
    fun `child visibility changes and reparenting update exact viewer lists and real riding bookkeeping`() {
        val player = player()
        val observer = player()
        val firstWrapper = entity(ROOT)
        val secondWrapper = entity(ROOT + 2)
        val childWrapper = entity(CHILD)
        val first = visual(firstWrapper)
        val second = visual(secondWrapper)
        val child = visual(childWrapper)
        assertTrue(first.addPassenger(child))
        child.showTo(player)
        first.showTo(player)
        first.showTo(observer)
        assertEquals(listOf(CHILD), client(player).passengers[ROOT])
        assertTrue(mounts(observer).isEmpty())

        child.hideFrom(player)
        first.refreshPassengersFor(player)
        assertEquals(emptyList<Int>(), client(player).passengers[ROOT])
        child.showTo(player)
        first.refreshPassengersFor(player)
        assertEquals(listOf(CHILD), client(player).passengers[ROOT])

        bundles.frame {
            assertTrue(second.addPassenger(child))
            second.showTo(player)
            first.refreshPassengersFor(player)
        }
        assertTrue(firstWrapper.passengers.isEmpty())
        assertEquals(setOf(CHILD), secondWrapper.passengers)
        assertEquals(ROOT + 2, childWrapper.ridingId)
        assertEquals(emptyList<Int>(), client(player).passengers[ROOT])
        assertEquals(listOf(CHILD), client(player).passengers[ROOT + 2])
        child.destroy().toCompletableFuture().join()
        second.refreshPassengersFor(player)
        assertTrue(secondWrapper.passengers.isEmpty())
        assertEquals(emptyList<Int>(), client(player).passengers[ROOT + 2])
        assertEquals(0, client(player).unknownVehicles)
        assertEquals(0, client(player).unknownPassengers)
        first.destroy().toCompletableFuture().join()
        second.destroy().toCompletableFuture().join()
    }

    @Test
    fun `display lifecycle retains per-viewer initial and broadcast metadata translation without changing source`() {
        val english = player()
        val chinese = player()
        locales[chinese.uniqueId] = Locale.SIMPLIFIED_CHINESE
        val store = TranslationStore.component(Key.key("javarailways:display-passenger-test"))
        store.register("test.display.passenger", Locale.US, Component.text("English"))
        store.register("test.display.passenger", Locale.SIMPLIFIED_CHINESE, Component.text("中文"))
        GlobalTranslator.translator().addSource(store)
        val wrapper = entity(ROOT, EntityTypes.TEXT_DISPLAY)
        val visual = visual(wrapper)
        val meta = wrapper.getEntityMeta(TextDisplayMeta::class.java)
        meta.text = Component.translatable("test.display.passenger")
        fun expect(viewer: Player, expected: String) {
            val metadata = packets[viewer.uniqueId].orEmpty().filterIsInstance<WrapperPlayServerEntityMetadata>()
            assertTrue(metadata.last().entityMetadata.any { it.value == Component.text(expected) })
        }
        try {
            bundles.frame {
                visual.showTo(english)
                visual.showTo(chinese)
            }
            expect(english, "English")
            expect(chinese, "中文")
            assertTrue(meta.text is TranslatableComponent)
            bundles.frame { wrapper.refresh() }
            expect(english, "English")
            expect(chinese, "中文")
            assertTrue(meta.text is TranslatableComponent)
        } finally {
            visual.destroy().toCompletableFuture().join()
            GlobalTranslator.translator().removeSource(store)
        }
    }

    @Test
    fun `new and reshown viewers receive complete latest metadata after visible and hidden deltas`() {
        val first = player()
        val late = player()
        val wrapper = entity(ROOT, EntityTypes.TEXT_DISPLAY)
        val meta = wrapper.getEntityMeta(TextDisplayMeta::class.java)
        meta.setLineWidth(Int.MAX_VALUE)
        meta.setBackgroundColor(0)
        meta.setTextOpacity((-1).toByte())
        meta.setShadow(false)
        meta.setBillboardConstraints(AbstractDisplayMeta.BillboardConstraints.FIXED)
        meta.setTransformationInterpolationDuration(3)
        meta.setPositionRotationInterpolationDuration(3)
        meta.setViewRange(4f)
        val visual = visual(wrapper)
        fun pose(x: Float) = Transformation(Vector3f(x, 2f, 3f), Quaternionf().rotateY(x / 10f),
            Vector3f(1f), Quaternionf())
        fun metadata(viewer: Player) = packets[viewer.uniqueId].orEmpty()
            .filterIsInstance<WrapperPlayServerEntityMetadata>()
        fun expectComplete(viewer: Player, text: Component, x: Float, brightness: Int) {
            val entries = metadata(viewer).last().entityMetadata.associateBy { it.index }
            assertEquals(meta.entityData().map { it.index }.toSet(), entries.keys)
            assertEquals(text, entries.getValue(TextDisplayMeta.OFFSET.toInt()).value)
            assertEquals(brightness, entries.getValue(AbstractDisplayMeta.OFFSET + 8).value)
            assertEquals(com.github.retrooper.packetevents.util.Vector3f(x, 2f, 3f),
                entries.getValue(AbstractDisplayMeta.OFFSET + 3).value)
            assertEquals(Int.MAX_VALUE, entries.getValue(TextDisplayMeta.OFFSET + 1).value)
            assertEquals(0, entries.getValue(TextDisplayMeta.OFFSET + 2).value)
            assertEquals((-1).toByte(), entries.getValue(TextDisplayMeta.OFFSET + 3).value)
            assertEquals(3, entries.getValue(AbstractDisplayMeta.OFFSET + 1).value)
            assertEquals(3, entries.getValue(AbstractDisplayMeta.OFFSET + 2).value)
            assertEquals(4f, entries.getValue(AbstractDisplayMeta.OFFSET + 9).value)
        }
        try {
            bundles.frame {
                visual.setText(Component.text("before first show"))
                visual.setTransformation(pose(1f))
                visual.setBrightness(0, 0)
            }
            assertTrue(packets.isEmpty(), "updates while hidden only change stored metadata")
            bundles.frame { visual.showTo(first) }
            expectComplete(first, Component.text("before first show"), 1f, 0)

            bundles.frame {
                visual.setText(Component.text("after curve"))
                visual.setTransformation(pose(2f))
                visual.setBrightness(7, 12)
            }
            assertEquals(1, metadata(first)[1].entityMetadata.size, "visible text updates remain incremental")
            assertEquals(1, metadata(first).last().entityMetadata.size, "visible brightness updates remain incremental")
            bundles.frame { visual.showTo(late) }
            expectComplete(late, Component.text("after curve"), 2f, (7 shl 4) or (12 shl 20))

            bundles.frame { visual.hideFrom(first); visual.hideFrom(late) }
            val hiddenPacketCount = packets.values.sumOf { it.size }
            bundles.frame {
                visual.setText(Component.empty())
                visual.setTransformation(pose(0f))
                visual.clearBrightness()
            }
            assertEquals(hiddenPacketCount, packets.values.sumOf { it.size })
            bundles.frame { visual.showTo(first); visual.showTo(late) }
            expectComplete(first, Component.empty(), 0f, -1)
            expectComplete(late, Component.empty(), 0f, -1)
            assertEquals(2, spawns(first).size)
            assertEquals(2, spawns(late).size)
        } finally {
            visual.destroy().toCompletableFuture().join()
        }
    }

    @Test
    fun `static signal model deferred before chunk send is shown by the audience drain`() {
        val player = player()
        unsentChunks.getOrPut(player.uniqueId, ::mutableSetOf).add(Chunk.getChunkKey(0, 0))
        withStaticAudience(player) { manager, drain ->
            drain()
            assertEquals(1, manager.audienceMetrics().visibleEntries)
            assertTrue(spawns(player).isEmpty())

            unsentChunks.getValue(player.uniqueId).clear()
            val event = PlayerChunkLoadEvent(chunk(0, 0), player)
            chunks.onChunkLoad(event)
            manager.onPlayerChunkLoad(event)
            drain()

            assertEquals(setOf(CHILD, ROOT), client(player).entities,
                "An already-indexed static signal must retry after the client chunk arrives")
            assertEquals(listOf(CHILD), client(player).passengers[ROOT])
        }
    }

    @Test
    fun `static signal chunk reload hides and respawns without player movement or a scene update`() {
        val player = player()
        withStaticAudience(player) { manager, drain ->
            drain()
            assertEquals(setOf(CHILD, ROOT), client(player).entities)
            unsentChunks.getOrPut(player.uniqueId, ::mutableSetOf).add(Chunk.getChunkKey(0, 0))
            val unload = PlayerChunkUnloadEvent(chunk(0, 0), player)
            chunks.onChunkUnload(unload)
            manager.onPlayerChunkUnload(unload)
            drain()
            assertTrue(client(player).entities.isEmpty())

            unsentChunks.getValue(player.uniqueId).clear()
            val load = PlayerChunkLoadEvent(chunk(0, 0), player)
            chunks.onChunkLoad(load)
            manager.onPlayerChunkLoad(load)
            drain()
            assertEquals(setOf(CHILD, ROOT), client(player).entities)
            assertEquals(listOf(CHILD), client(player).passengers[ROOT])
            assertEquals(4, spawns(player).size)
        }
    }

    @Test
    fun `static signal refreshes for unrelated chunk sends do not recreate packet entities`() {
        val player = player()
        withStaticAudience(player) { manager, drain ->
            drain()
            repeat(20) { x ->
                val event = PlayerChunkLoadEvent(chunk(x + 1, 0), player)
                chunks.onChunkLoad(event)
                manager.onPlayerChunkLoad(event)
            }
            drain()
            assertEquals(2, spawns(player).size)
            assertEquals(setOf(CHILD, ROOT), client(player).entities)
        }
    }

    private fun withStaticAudience(player: Player, test: (RenderChunkManager, () -> Unit) -> Unit) {
        val pending = ArrayDeque<() -> Unit>()
        val dispatcher = proxy<PlatformDispatcher> { name, args ->
            when (name) {
                "runForEntity" -> { @Suppress("UNCHECKED_CAST") pending.addLast(args[1] as () -> Unit); true }
                "runForEntityLater" -> {
                    @Suppress("UNCHECKED_CAST") pending.addLast(args[2] as () -> Unit)
                    proxy<ScheduledTask> { method, _ -> error("Unexpected scheduled task call: $method") }
                }
                else -> error("Unexpected dispatcher call: $name")
            }
        }
        val train = train()
        val ordinary = OrdinaryModel(train.model, emptyList(), OrdinarySceneIndex(ModelDefinition(emptyList())), emptyList())
        val renderable = OrdinaryModelRenderable(UnusedWorld()) { CompletableFuture.completedFuture(null) }
        renderable.publish(ModelPose(0.0, 64.0, 0.0, 0.0, 0.0, 0.0, 1.0), ordinary)
        val manager = RenderChunkManager(
            bukkitWorld, dispatcher = dispatcher, playerLookup = { if (it == player.uniqueId) player else null },
            settingsFor = { ViewDistanceSettings(64.0, 16.0) }, registerListener = {}, unregisterListener = {},
            initialPlayers = listOf(player)
        )
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().invoke() }
        try {
            drain()
            manager.update(renderable, null, renderable.position)
            test(manager, ::drain)
        } finally {
            manager.close()
            drain()
        }
    }

    @Test
    fun `normal approach spawns child before root and attaches it`() {
        val train = train()
        val player = player()

        train.model.showTo(player)

        assertEquals(listOf(CHILD, ROOT), spawns(player).map { it.entityId })
        assertEquals(setOf(CHILD, ROOT), client(player).entities)
        assertEquals(listOf(CHILD), client(player).passengers[ROOT])
    }

    @Test
    fun `lost first spawn stays invisible on refresh but leaving and returning restores it`() {
        val train = train()
        val player = player()
        // An existing observer keeps the model alive during the affected player's round trip.
        val observer = player()
        train.model.showTo(observer)
        client(player).dropSpawns = true
        train.model.showTo(player)
        assertTrue(client(player).entities.isEmpty())
        assertTrue(train.root.hasViewer(player.uniqueId))
        assertTrue(train.child.hasViewer(player.uniqueId))
        val initialSpawnCount = spawns(player).size

        client(player).dropSpawns = false
        repeat(20) {
            train.model.showTo(player)
            train.model.updatePlayerVisibility(player)
        }
        assertEquals(initialSpawnCount, spawns(player).size, "Ordinary refresh sends no replacement spawn")
        assertTrue(client(player).entities.isEmpty(), "The injected loss persists while still in range")

        train.model.hideFrom(player)
        train.model.showTo(player)

        assertEquals(initialSpawnCount + 2, spawns(player).size)
        assertEquals(setOf(CHILD, ROOT), client(player).entities)
        assertEquals(listOf(CHILD), client(player).passengers[ROOT])
        assertEquals(setOf(CHILD, ROOT), client(observer).entities)
    }

    @Test
    fun `client entity reset while in range is not repaired until audience reentry`() {
        val train = train()
        val player = player()
        train.model.showTo(player)
        client(player).entities.clear()
        client(player).passengers.clear()

        repeat(20) { train.model.showTo(player) }
        assertTrue(client(player).entities.isEmpty())
        assertEquals(2, spawns(player).size)

        train.model.hideFrom(player)
        train.model.showTo(player)
        assertEquals(setOf(CHILD, ROOT), client(player).entities)
        assertEquals(listOf(CHILD), client(player).passengers[ROOT])
    }

    @Test
    fun `late viewer of a moving train receives child spawn at the live root anchor`() {
        val train = train()
        val observer = player()
        train.model.showTo(observer)
        // Exercise the real mounted DisplayModel update. Rotation is unchanged; the root's
        // cached position is advanced as EntityLibDisplayVisual.teleport does before sending.
        train.childModel.updateState(CompactTransform(), CompactTransform(Vector3d(256.0, 64.0, 0.0)), Vector3d(), false)
        train.root.setLocation(PacketLocation(256.0, 64.0, 0.0, 0f, 0f))
        assertEquals(256.0, train.childModel.state.position.x())

        val approaching = player()
        train.model.showTo(approaching)
        val spawnById = spawns(approaching).associateBy { it.entityId }

        assertEquals(256.0, spawnById.getValue(ROOT).position.x)
        assertEquals(256.0, spawnById.getValue(CHILD).position.x, "Mounted child must spawn beside its moving root")
        assertEquals(listOf(CHILD), client(approaching).passengers[ROOT])
        // This simplified receiver validates lifecycle order, not Minecraft rendering.
        assertEquals(setOf(CHILD, ROOT), client(approaching).entities)
    }

    @Test
    fun `moving train waits for client chunk then appears without player leaving range`() {
        val train = train()
        val player = player()
        repeat(20) { tick ->
            val x = tick * 16.0
            train.root.setLocation(PacketLocation(x, 64.0, 0.0, 0f, 0f))
            unsentChunks.getOrPut(player.uniqueId, ::mutableSetOf).add(Chunk.getChunkKey(tick, 0))
            train.model.showTo(player)
            train.model.updatePlayerVisibility(player)
            assertTrue(client(player).entities.isEmpty())
        }
        assertTrue(spawns(player).isEmpty(), "Do not declare a viewer spawned before its client chunk is sent")

        unsentChunks.getValue(player.uniqueId).remove(Chunk.getChunkKey(19, 0))
        chunks.onChunkLoad(PlayerChunkLoadEvent(chunk(19, 0), player))
        train.model.showTo(player)

        assertEquals(setOf(CHILD, ROOT), client(player).entities)
        assertEquals(listOf(CHILD), client(player).passengers[ROOT])
        assertTrue(spawns(player).all { it.position.x == 304.0 })
    }

    @Test
    fun `chunk resend repairs missing moving train even when no refresh saw the unload`() {
        val train = train()
        val player = player()
        train.model.showTo(player)
        val observer = player()
        train.model.showTo(observer)
        client(player).entities.clear()
        client(player).passengers.clear()
        chunks.onChunkUnload(PlayerChunkUnloadEvent(chunk(0, 0), player))
        chunks.onChunkLoad(PlayerChunkLoadEvent(chunk(0, 0), player))
        // The train keeps moving inside the same chunk throughout the client reload.
        repeat(20) { tick ->
            train.root.setLocation(PacketLocation(tick * 0.5, 64.0, 0.0, 0f, 0f))
            train.model.showTo(player)
            train.model.showTo(observer)
        }

        assertEquals(4, spawns(player).size, "Only one complete respawn after the chunk resend")
        assertEquals(2, spawns(observer).size, "Another viewer must not respawn")
        assertEquals(setOf(CHILD, ROOT), client(player).entities)
        assertEquals(listOf(CHILD), client(player).passengers[ROOT])
        assertEquals(listOf(CHILD, ROOT), spawns(player).takeLast(2).map { it.entityId })
    }

    @Test
    fun `chunk resend repairs a train that crossed a boundary before the next refresh`() {
        val train = train()
        val player = player()
        train.model.showTo(player)
        client(player).entities.clear()
        client(player).passengers.clear()
        chunks.onChunkUnload(PlayerChunkUnloadEvent(chunk(0, 0), player))
        chunks.onChunkLoad(PlayerChunkLoadEvent(chunk(0, 0), player))
        train.root.setLocation(PacketLocation(16.5, 64.0, 0.0, 0f, 0f))
        train.model.showTo(player)

        assertEquals(4, spawns(player).size)
        assertTrue(spawns(player).takeLast(2).all { it.position.x == 16.5 })
        assertEquals(listOf(CHILD), client(player).passengers[ROOT])
    }

    @Test
    fun `movement into sent chunks does not respawn the train every chunk boundary`() {
        val train = train()
        val player = player()
        chunks.onChunkLoad(PlayerChunkLoadEvent(chunk(0, 0), player))
        train.model.showTo(player)
        repeat(20) { tick ->
            val nextChunk = tick + 1
            chunks.onChunkLoad(PlayerChunkLoadEvent(chunk(nextChunk, 0), player))
            train.root.setLocation(PacketLocation(nextChunk * 16.0, 64.0, 0.0, 0f, 0f))
            train.model.showTo(player)
        }
        assertEquals(2, spawns(player).size)
    }

    @Test
    fun `unloaded client chunk hides the train and reload restores it`() {
        val train = train()
        val player = player()
        train.model.showTo(player)
        unsentChunks.getOrPut(player.uniqueId, ::mutableSetOf).add(Chunk.getChunkKey(0, 0))
        chunks.onChunkUnload(PlayerChunkUnloadEvent(chunk(0, 0), player))
        train.model.showTo(player)
        assertTrue(client(player).entities.isEmpty())
        assertFalse(train.root.hasViewer(player.uniqueId))
        assertFalse(train.child.hasViewer(player.uniqueId))

        unsentChunks.getValue(player.uniqueId).clear()
        chunks.onChunkLoad(PlayerChunkLoadEvent(chunk(0, 0), player))
        train.model.showTo(player)
        assertEquals(setOf(CHILD, ROOT), client(player).entities)
        assertEquals(listOf(CHILD), client(player).passengers[ROOT])
    }

    private fun train(): TrainFixture {
        val root = entity(ROOT)
        val child = entity(CHILD)
        val world = UnusedWorld()
        val childModel = DisplayModel(world, ModelState(CompactTransform()), emptyList(), emptyMap(), visual(child), EmptyItem())
        val rootVisual = visual(root)
        val model = CompositeModel(world, listOf(childModel), rootVisual)
        return TrainFixture(model, childModel, root, child, rootVisual)
    }

    private fun entity(id: Int, type: EntityType = EntityTypes.ITEM_DISPLAY): WrapperEntity = PassengerManagedDisplayEntity(id, UUID.randomUUID(), type).also {
        entities[id] = it
        assertTrue(it.spawn(PacketLocation(0.0, 64.0, 0.0, 0f, 0f), container))
    }

    private fun visual(entity: WrapperEntity): DisplayVisual = EntityLibDisplayVisual(entity, chunks)

    private fun chunk(x: Int, z: Int): Chunk = proxy { name, _ ->
        when (name) {
            "getWorld" -> bukkitWorld
            "getX" -> x
            "getZ" -> z
            else -> error("Unexpected chunk call: $name")
        }
    }

    private fun player(): Player {
        val id = UUID.randomUUID()
        return proxy { name, args ->
            when (name) {
                "getUniqueId" -> id
                "getWorld" -> bukkitWorld
                "getLocation" -> Location(bukkitWorld, 0.0, 64.0, 0.0)
                "isChunkSent" -> args[0] !in unsentChunks[id].orEmpty()
                else -> error("Unexpected player call: $name")
            }
        }
    }

    private fun client(player: Player) = clients.getOrPut(player.uniqueId, ::ClientEntityTable)
    private fun spawns(player: Player) = packets[player.uniqueId].orEmpty().filterIsInstance<WrapperPlayServerSpawnEntity>()
    private fun mounts(player: Player) = packets[player.uniqueId].orEmpty().filterIsInstance<WrapperPlayServerSetPassengers>()
    private fun deliver(viewer: UUID, packet: PacketWrapper<*>) {
        packets.getOrPut(viewer, ::mutableListOf).add(packet)
        clients.getOrPut(viewer, ::ClientEntityTable).receive(packet)
    }
    private fun entityLibField(name: String) = EntityLib::class.java.getDeclaredField(name).also { it.isAccessible = true }

    private data class TrainFixture(val model: CompositeModel, val childModel: DisplayModel, val root: WrapperEntity, val child: WrapperEntity, val rootVisual: DisplayVisual)

    // Item content is irrelevant to audience/position tests; no Bukkit item factory is needed.
    private class EmptyItem : ItemStack() {
        public override fun clone(): ItemStack = EmptyItem()
    }

    @OptIn(ExtensionData::class)
    private class UnusedWorld : World {
        override val executionMode: work.art1st.javarailways.core.api.physics.WorldExecutionMode get() = error("Not used")
        override val maximumPhysicsDeltaSeconds: Double get() = error("Not used")
        override val topologyState: work.art1st.javarailways.core.api.physics.rail.topology.TopologyState get() = error("Not used")
        override val tickManager: TickManager get() = error("Not used")
        override val allTrains: Collection<Train> get() = error("Not used")
        override fun getTrain(name: String): Train? = error("Not used")
        override fun getRail(id: UUID): Rail? = error("Not used")
        override fun removeRail(rail: Rail) = error("Not used")
        override fun <T : Any> getExtensionDataOrNull(plugin: Plugin): T? = error("Not used")
        override fun <T : Any> requireExtensionData(plugin: Plugin): T = error("Not used")
        override fun <T : Any> registerExtensionData(plugin: Plugin, data: T) = error("Not used")
        override fun forEachExtensionData(action: (Any) -> Unit) = error("Not used")
    }

    private class ClientEntityTable {
        val entities = mutableSetOf<Int>()
        val passengers = mutableMapOf<Int, List<Int>>()
        var dropSpawns = false
        var unknownVehicles = 0
        var unknownPassengers = 0

        fun receive(packet: PacketWrapper<*>) {
            when (packet) {
                is WrapperPlayServerSpawnEntity -> if (!dropSpawns) entities.add(packet.entityId)
                is WrapperPlayServerDestroyEntities -> packet.entityIds.forEach {
                    entities.remove(it)
                    passengers.remove(it)
                }
                is WrapperPlayServerSetPassengers -> if (packet.entityId in entities) {
                    unknownPassengers += packet.passengers.count { it !in entities }
                    passengers[packet.entityId] = packet.passengers.filter { it in entities }
                } else unknownVehicles++
            }
        }
    }

    private class TestPacketApi(private val protocol: ProtocolManager) : PacketEventsAPI<Any>() {
        override fun load() = Unit
        override fun isLoaded() = true
        override fun init() = Unit
        override fun isInitialized() = true
        override fun terminate() = Unit
        override fun isTerminated() = false
        override fun getPlugin(): Any = this
        override fun getServerManager() = ServerManager { ServerVersion.V_1_21_11 }
        override fun getProtocolManager() = protocol
        override fun getPlayerManager(): PlayerManager = error("Not used")
        override fun getNettyManager(): NettyManager = error("Not used")
        override fun getInjector(): ChannelInjector = error("Not used")
    }

    companion object {
        private const val ROOT = 100
        private const val CHILD = 101

        private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { instance, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === args?.firstOrNull()
                    "toString" -> "Test${T::class.java.simpleName}"
                    else -> call(method.name, args ?: emptyArray())
                }
            } as T
    }
}
