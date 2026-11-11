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
import com.github.retrooper.packetevents.protocol.world.Location as PacketLocation
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import me.tofaa.entitylib.APIConfig
import me.tofaa.entitylib.EntityLib
import me.tofaa.entitylib.EntityLibAPI
import me.tofaa.entitylib.Platform
import me.tofaa.entitylib.UserLocaleProvider
import me.tofaa.entitylib.container.EntityContainer
import me.tofaa.entitylib.event.EventHandler
import me.tofaa.entitylib.wrapper.WrapperEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.joml.Vector3d
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
import work.art1st.javarailways.bukkit.render.backend.EntityLibDisplayVisual
import work.art1st.javarailways.bukkit.render.backend.PacketDisplayChunks
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

    @BeforeEach
    fun setUp() {
        previousPacketApi = PacketEvents.getAPI()
        previousEntityApi = entityLibField("api").get(null)
        previousPlatform = entityLibField("platform").get(null)
        val protocol = proxy<ProtocolManager> { name, args ->
            when (name) {
                "getChannel" -> args[0]
                "sendPacket" -> {
                    val viewer = args[0] as UUID
                    val packet = args[1] as PacketWrapper<*>
                    packets.getOrPut(viewer, ::mutableListOf).add(packet)
                    clients.getOrPut(viewer, ::ClientEntityTable).receive(packet)
                    null
                }
                else -> error("Unexpected protocol call: $name")
            }
        }
        val packetApi = TestPacketApi(protocol)
        PacketEvents.setAPI(packetApi)
        val settings = APIConfig(packetApi).disableBStats()
        val api = proxy<EntityLibAPI<Any>> { name, args ->
            when (name) {
                "getSettings" -> settings
                "getPacketEvents" -> packetApi
                "getEntity" -> entities[args[0]]
                "getDefaultContainer" -> container
                "getUserLocaleProvider" -> UserLocaleProvider { Locale.US }
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
        val model = CompositeModel(world, listOf(childModel), visual(root))
        return TrainFixture(model, childModel, root, child)
    }

    private fun entity(id: Int): WrapperEntity = WrapperEntity(id, UUID.randomUUID(), EntityTypes.ITEM_DISPLAY).also {
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
    private fun entityLibField(name: String) = EntityLib::class.java.getDeclaredField(name).also { it.isAccessible = true }

    private data class TrainFixture(val model: CompositeModel, val childModel: DisplayModel, val root: WrapperEntity, val child: WrapperEntity)

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

        fun receive(packet: PacketWrapper<*>) {
            when (packet) {
                is WrapperPlayServerSpawnEntity -> if (!dropSpawns) entities.add(packet.entityId)
                is WrapperPlayServerDestroyEntities -> packet.entityIds.forEach {
                    entities.remove(it)
                    passengers.remove(it)
                }
                is WrapperPlayServerSetPassengers -> if (packet.entityId in entities) {
                    passengers[packet.entityId] = packet.passengers.filter { it in entities }
                }
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
