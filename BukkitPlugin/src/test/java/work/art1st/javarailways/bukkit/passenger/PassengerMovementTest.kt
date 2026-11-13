package work.art1st.javarailways.bukkit.passenger

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.GameMode
import org.bukkit.Input
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.damage.DamageSource
import net.kyori.adventure.text.Component
import work.art1st.javarailways.bukkit.extension.train.modelTransform
import org.joml.Quaterniond
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.joml.Vector3d
import work.art1st.javarailways.bukkit.render.model.CompactTransform
import work.art1st.javarailways.bukkit.util.PlatformDispatchMetrics
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.logging.Logger

class PassengerMovementTest {
    @Test
    fun `stationary train walks across platforms using one stable carrier and ignores jump sprint`() = fixture { h ->
        val rider = h.rider(0.9)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        assertEquals(1, h.access.created.size)
        val carrier = rider.vehicle
        val revision = h.train.publishedFrame.revision
        rider.forward = true
        h.dispatcher.tick()
        assertSame(carrier, rider.vehicle)
        assertEquals(1, h.access.registered)
        val expected = h.scene.surfaces[1].pose.toWorld(PassengerPoint(0.0, 0.0, -0.885))
        assertEquals(expected.x, rider.location.x, 1e-8)
        assertEquals(expected.z, rider.location.z, 1e-8)
        rider.forward = false; rider.jump = true; rider.sprint = true
        val before = rider.location.clone()
        h.dispatcher.tick()
        assertEquals(before.x, rider.location.x, 1e-8)
        assertEquals(before.y, rider.location.y, 1e-8)
        assertEquals(before.z, rider.location.z, 1e-8)
        assertEquals(revision, h.train.publishedFrame.revision, "Walking does not ask Core to republish a stationary train")
        rider.backward = true
        h.dispatcher.tick()
        assertSame(carrier, rider.vehicle)
        assertEquals(1, h.access.created.size)
    }

    @Test
    fun `two riders remain independent and turning the view changes walking direction`() = fixture { h ->
        val one = h.rider(0.0)
        val two = h.rider(0.0)
        h.manager.watch(one.player, h.first); h.manager.watch(two.player, h.first)
        h.dispatcher.tick()
        assertNotSame(one.vehicle, two.vehicle)
        one.forward = true
        val secondStart = two.location.clone()
        h.dispatcher.tick()
        assertEquals(secondStart.x, two.location.x, 1e-8)
        assertEquals(secondStart.z, two.location.z, 1e-8)
        val previous = one.location.clone()
        one.location.yaw += 90f
        h.dispatcher.tick()
        val yaw = Math.toRadians(one.location.yaw.toDouble())
        assertEquals(-kotlin.math.sin(yaw) * 0.215, one.location.x - previous.x, 1e-8)
        assertEquals(kotlin.math.cos(yaw) * 0.215, one.location.z - previous.z, 1e-8)
    }

    @Test
    fun `Shift is cancelled only while controlled and boundary exit restores ordinary movement without teleport`() = fixture { h ->
        val rider = h.rider(0.9)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        val carrier = rider.vehicle!!
        rider.sneak = true
        val shift = EntityDismountEvent(rider.player, carrier)
        h.manager.onManualDismount(shift)
        assertTrue(shift.isCancelled)
        rider.forward = true
        repeat(11) { h.dispatcher.tick() }
        assertNull(rider.vehicle)
        assertEquals(1, h.access.exits)
        assertTrue(h.access.created.single().removed)
        assertEquals(0, rider.teleports)
        val outside = EntityDismountEvent(rider.player, carrier)
        h.manager.onManualDismount(outside)
        assertFalse(outside.isCancelled)
    }

    @Test
    fun `accepted teleport fences old ticks but cancelled teleport preserves the session`() = fixture { h ->
        val rider = h.rider(0.0)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        val carrier = rider.vehicle
        val listener = registered(h.manager, "onTeleport", PlayerTeleportEvent::class.java, h.plugin)
        val event = PlayerTeleportEvent(rider.player, rider.location.clone(), rider.location.clone().add(100.0, 0.0, 0.0))
        event.isCancelled = true
        listener.callEvent(event)
        assertSame(carrier, rider.vehicle)
        rider.forward = true
        h.dispatcher.tick()
        event.isCancelled = false
        listener.callEvent(event)
        assertNull(rider.vehicle)
        rider.location = event.to.clone()
        val after = rider.location.clone()
        h.dispatcher.tick(includeCancelled = true)
        assertEquals(after.x, rider.location.x, 1e-8)
        assertEquals(after.z, rider.location.z, 1e-8)
        assertEquals(0, rider.teleports)
    }

    @Test
    fun `accepted dismount cleanup waits for vanilla event to return and never reenters eject`() = fixture { h ->
        val rider = h.rider(0.0)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        val native = h.access.created.single()
        h.manager.onDismount(EntityDismountEvent(rider.player, native.entity))
        assertEquals(0, native.ejections)
        assertFalse(native.removed)
        native.passengers.clear(); rider.vehicle = null // Vanilla applies the accepted event after listeners return.
        h.dispatcher.runDelayed()
        assertTrue(native.removed)
        assertEquals(1, h.access.releases)
    }

    @Test
    fun `model retirement fences queued ticks and awaits detach before packet release and removal`() = fixture { h ->
        val rider = h.rider(0.0)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        val native = h.access.created.single()
        h.dispatcher.deferOwnerTasks = true
        val completion = h.manager.retire(h.registration).toCompletableFuture()
        assertFalse(completion.isDone)
        assertEquals(0, h.access.releases)
        assertFalse(native.removed)
        val moves = h.access.moves
        rider.forward = true
        h.dispatcher.tick(includeCancelled = true)
        assertEquals(moves, h.access.moves)
        h.dispatcher.drainOwnerTasks()
        completion.join()
        assertNull(rider.vehicle)
        assertEquals(listOf("detach", "release", "detach", "remove"), h.access.cleanupEvents)
        assertTrue(native.removed)
    }

    @Test
    fun `existing seat is never stolen and preactivation margins do not force a mount`() = fixture { h ->
        val seated = h.rider(0.0)
        seated.vehicle = proxy<Entity> { _, _ -> null }
        val outside = h.rider(0.0)
        outside.location.y += 1.0
        h.manager.watch(seated.player, h.first); h.manager.watch(outside.player, h.first)
        h.dispatcher.tick()
        assertTrue(h.access.created.isEmpty())
        outside.location.y -= 1.0
        h.dispatcher.tick()
        assertEquals(1, h.access.created.size)
    }

    @Test
    fun `close rejects late watch register callbacks and releases every mounted carrier`() = fixture { h ->
        val rider = h.rider(0.0)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        h.manager.close().toCompletableFuture().join()
        h.manager.register(h.registration)
        h.manager.watch(rider.player, h.first)
        rider.forward = true
        h.dispatcher.tick(includeCancelled = true)
        assertNull(rider.vehicle)
        assertEquals(1, h.access.created.size)
        assertEquals(1, h.access.releases)
        assertNull(h.manager.platforms.capture(h.registration))
    }

    @Test
    fun `two real car registrations share a frame cross the car seam and follow a later published pose`() =
        fixture(listOf(2.0, 2.0)) { h ->
            val scene = h.scene
            assertSame(h.train.publishedFrame, scene.frame)
            assertEquals(2, scene.surfaces.size)
            val rider = h.rider(-0.9)
            h.manager.watch(rider.player, h.first)
            h.dispatcher.tick()
            val carrier = rider.vehicle
            rider.backward = true
            h.dispatcher.tick()
            val expected = scene.surfaces[1].pose.toWorld(PassengerPoint(0.0, 0.0, 0.885))
            assertSame(carrier, rider.vehicle)
            assertEquals(expected.x, rider.location.x, 1e-8)
            assertEquals(expected.z, rider.location.z, 1e-8)
            rider.backward = false
            h.train.world.onOwnerForTest {
                val location = h.train.headLocation as RailLocationWithFacing
                h.train.setLocation(location.forward(3.0)!!)
                h.train.commit()
            }
            val later = h.scene
            assertNotSame(scene.frame, later.frame)
            assertSame(h.train.publishedFrame, later.frame)
            h.dispatcher.tick()
            val moved = later.surfaces[1].pose.toWorld(PassengerPoint(0.0, 0.0, 0.885))
            assertEquals(moved.x, rider.location.x, 1e-8)
            assertEquals(moved.z, rider.location.z, 1e-8)
            assertSame(carrier, rider.vehicle)
            assertEquals(1, h.access.registered)
        }

    @Test
    fun `close waits for an accepted dismount already queued on the entity clock`() = fixture { h ->
        val rider = h.rider(0.0)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        val native = h.access.created.single()
        h.manager.onDismount(EntityDismountEvent(rider.player, native.entity))
        native.passengers.clear(); rider.vehicle = null
        val closed = h.manager.close().toCompletableFuture()
        assertFalse(closed.isDone)
        h.dispatcher.runDelayed()
        closed.join()
        assertTrue(native.removed)
        assertEquals(1, h.access.releases)
    }

    @Test
    fun `cancelled death keeps riding while accepted death and quit remove restrictions and carriers`() = fixture { h ->
        val dying = h.rider(0.0)
        val quitting = h.rider(0.0)
        h.manager.watch(dying.player, h.first); h.manager.watch(quitting.player, h.first)
        h.dispatcher.tick()
        val death = PlayerDeathEvent(dying.player, proxy<DamageSource> { _, _ -> null }, mutableListOf(), 0, Component.empty(), true)
        val listener = registered(h.manager, "onDeath", PlayerDeathEvent::class.java, h.plugin)
        death.isCancelled = true
        listener.callEvent(death)
        assertNotNull(dying.vehicle)
        death.isCancelled = false
        listener.callEvent(death)
        h.manager.onQuit(PlayerQuitEvent(quitting.player, Component.empty()))
        assertNull(dying.vehicle); assertNull(quitting.vehicle)
        assertEquals(2, h.access.releases)
        h.dispatcher.tick(includeCancelled = true)
        assertEquals(2, h.access.created.size)
    }

    @Test
    fun `bogie platform uses the same floor offset rotation and local pose composition as rendering`() = fixture { h ->
        val source = PassengerSurfaceSource(1.0, 1.0)
        val local = CompactTransform(Vector3d(0.3, -1.0, 0.7), Quaterniond().rotateY(0.2))
        source.publish(local)
        val registration = PassengerCarPlatforms(h.train.cars.single(), h.train, h.bukkitWorld, 1.25,
            listOf(PassengerPlatformPart(source, 0)))
        val registry = PassengerPlatformRegistry().also { it.register(registration) }
        val scene = registry.capture(registration)!!
        assertSame(h.train.publishedFrame, scene.frame)
        val expected = local.applyTo(scene.frame.cars.single().bogies[0].modelTransform(1.25))
        val forward = expected.rotation.transform(Vector3d(0.0, 0.0, 1.0))
        val yaw = kotlin.math.atan2(-forward.x, forward.z)
        val top = Vector3d(expected.position).add(kotlin.math.sin(yaw) * 0.1, COLLISION_TOP_HEIGHT, -kotlin.math.cos(yaw) * 0.1)
        val actual = scene.surfaces.single().pose
        assertEquals(top.x, actual.position.x, 1e-9)
        assertEquals(top.y, actual.position.y, 1e-9)
        assertEquals(top.z, actual.position.z, 1e-9)
        val expectedDirection = expected.rotation.transform(Vector3d(0.0, 0.0, 1.0))
        val actualDirection = actual.toWorld(PassengerPoint(0.0, 0.0, 1.0)).vector().sub(actual.position.vector())
        assertEquals(expectedDirection.x, actualDirection.x, 1e-9)
        assertEquals(expectedDirection.y, actualDirection.y, 1e-9)
        assertEquals(expectedDirection.z, actualDirection.z, 1e-9)
    }

    private fun fixture(carLengths: List<Double> = listOf(10.0), action: (Harness) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            world.physicsCoordinator.use {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(300.0))
                val train = createTrainWithConfig("passenger", rail, world, carLengths)
                val h = Harness(train)
                try { action(h) } finally { h.dispatcher.deferOwnerTasks = false; h.manager.close().toCompletableFuture().join() }
            }
        }
    }

    private class Harness(val train: work.art1st.javarailways.core.physics.train.CoreTrain) {
        val bukkitWorld = proxy<World> { _, _ -> null }
        val dispatcher = TestDispatcher()
        val plugin = proxy<Plugin> { method, _ -> if (method.name == "getLogger") Logger.getAnonymousLogger() else null }
        val first = PassengerSurfaceSource(2.0, 1.0).also { it.publish(CompactTransform()) }
        val second = PassengerSurfaceSource(2.0, 1.0).also {
            it.publish(CompactTransform(if (train.cars.size == 1) Vector3d(0.0, 0.0, 2.0) else Vector3d()))
        }
        val registration = PassengerCarPlatforms(train.cars.first(), train, bukkitWorld, 0.0,
            if (train.cars.size == 1) listOf(PassengerPlatformPart(first, null), PassengerPlatformPart(second, null))
            else listOf(PassengerPlatformPart(first, null)))
        val otherRegistration = if (train.cars.size == 1) null else PassengerCarPlatforms(train.cars.last(), train, bukkitWorld, 0.0,
            listOf(PassengerPlatformPart(second, null)))
        val access = TestCarrierAccess()
        val manager = PassengerMovementManager(plugin, dispatcher, access).also {
            it.register(registration); otherRegistration?.let(it::register)
        }
        val scene get() = manager.platforms.capture(registration)!!
        fun rider(z: Double): TestRider {
            val surface = scene.surfaces.first()
            val p = surface.pose.toWorld(PassengerPoint(0.0, 0.0, z))
            val ahead = surface.pose.toWorld(PassengerPoint(0.0, 0.0, z + 1.0))
            val yaw = Math.toDegrees(kotlin.math.atan2(-(ahead.x - p.x), ahead.z - p.z)).toFloat()
            return TestRider(Location(bukkitWorld, p.x, p.y, p.z, yaw, 0f)).also { access.riders[it.player.uniqueId] = it }
        }
    }

    private class TestRider(var location: Location) {
        var vehicle: Entity? = null
        var forward = false; var backward = false; var sneak = false; var jump = false; var sprint = false
        var teleports = 0
        private val id = UUID.randomUUID()
        private val input = proxy<Input> { method, _ -> when (method.name) {
            "isForward" -> forward; "isBackward" -> backward; "isSneak" -> sneak; "isJump" -> jump; "isSprint" -> sprint
            else -> false
        } }
        val player = proxy<Player> { method, _ -> when (method.name) {
            "getUniqueId" -> id; "getEntityId" -> id.hashCode(); "getWorld" -> location.world; "getLocation" -> location.clone()
            "getVehicle" -> vehicle; "getCurrentInput" -> input; "getGameMode" -> GameMode.SURVIVAL
            "isOnline", "isValid" -> true
            "teleport", "teleportAsync" -> { teleports++; error("Passenger release must not schedule a player teleport") }
            else -> null
        } }
    }

    private class TestCarrierAccess : PassengerCarrierAccess {
        val riders = mutableMapOf<UUID, TestRider>()
        val created = mutableListOf<TestNative>()
        val cleanupEvents = mutableListOf<String>()
        var registered = 0; var moves = 0; var exits = 0; var releases = 0
        override fun spawn(location: Location): ArmorStand = TestNative(location, riders, cleanupEvents).also { created += it }.entity
        override fun register(carrier: ArmorStand, location: Location, viewers: Collection<Player>) { registered++ }
        override fun move(carrier: ArmorStand, location: Location, viewers: Collection<Player>) {
            moves++
            val native = created.single { it.entity === carrier }
            native.location = location.clone()
            native.passengers.forEach { player -> riders[player.uniqueId]!!.location = location.clone().subtract(0.0, PASSENGER_CARRIER_LIFT, 0.0) }
        }
        override fun dismountAt(carrier: ArmorStand, player: Player, feet: Location) {
            exits++
            carrier.removePassenger(player)
            riders[player.uniqueId]!!.location = feet.clone()
        }
        override fun release(carrier: ArmorStand) { releases++; cleanupEvents += "release" }
    }

    private class TestNative(var location: Location, private val riders: Map<UUID, TestRider>, val events: MutableList<String>) {
        val passengers = mutableListOf<Player>()
        var removed = false; var ejections = 0
        val entity: ArmorStand = proxy<ArmorStand> { method, args -> when (method.name) {
            "isValid" -> !removed; "getWorld" -> location.world; "getLocation" -> location.clone(); "getPassengers" -> passengers.toList()
            "addPassenger" -> { val player = args!![0] as Player; passengers += player; riders[player.uniqueId]!!.vehicle = entity; true }
            "removePassenger" -> { val player = args!![0] as Player; passengers.remove(player); riders[player.uniqueId]!!.vehicle = null; true }
            "eject" -> { ejections++; events += "detach"; passengers.toList().forEach { riders[it.uniqueId]!!.vehicle = null }; passengers.clear(); true }
            "remove" -> { removed = true; events += "remove"; null }
            else -> null
        } }
    }

    private class TestDispatcher : PlatformDispatcher {
        private class Task(val repeat: Boolean, val action: (ScheduledTask) -> Unit) {
            var cancelled = false
            val handle = proxy<ScheduledTask> { method, _ -> if (method.name == "cancel") { cancelled = true; null } else null }
        }
        val queued = ArrayDeque<() -> Unit>()
        private val tasks = mutableListOf<Task>()
        var deferOwnerTasks = false
        fun tick(includeCancelled: Boolean = false) { tasks.filter { it.repeat }.toList().forEach { if (!it.cancelled || includeCancelled) it.action(it.handle) } }
        fun runDelayed() { tasks.filter { !it.repeat && !it.cancelled }.toList().forEach { it.cancelled = true; it.action(it.handle) } }
        fun drainOwnerTasks() { while (queued.isNotEmpty()) queued.removeFirst()() }
        override fun runForEntity(entity: Entity, action: () -> Unit, retired: () -> Unit): Boolean {
            if (deferOwnerTasks) queued.add(action) else action()
            return true
        }
        override fun runForEntityAtFixedRate(entity: Entity, initialDelayTicks: Long, periodTicks: Long, action: (ScheduledTask) -> Unit, retired: () -> Unit): ScheduledTask =
            Task(true, action).also { tasks += it }.handle
        override fun runForEntityLater(entity: Entity, delayTicks: Long, action: () -> Unit, retired: () -> Unit): ScheduledTask =
            Task(false) { action() }.also { tasks += it }.handle
        override fun runGlobal(action: () -> Unit) = action()
        override fun runGlobalAtFixedRate(initialDelayTicks: Long, periodTicks: Long, action: () -> Unit): ScheduledTask = error("not used")
        override fun runAsync(action: () -> Unit) = action()
        override fun runAt(location: Location, action: () -> Unit) = action()
        override fun owns(location: Location) = true
        override fun owns(entity: Entity) = true
        override fun metrics() = PlatformDispatchMetrics(0,0,0,0,0,0,0,0)
    }

    companion object {
        private inline fun <reified T> proxy(crossinline handler: (Method, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    "toString" -> T::class.java.simpleName
                    else -> handler(method, args) ?: when (method.returnType) {
                        java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L
                        java.lang.Float.TYPE -> 0f; java.lang.Double.TYPE -> 0.0
                        else -> null
                    }
                }
            } as T

        private fun registered(listener: Listener, name: String, type: Class<out Event>, plugin: Plugin): RegisteredListener {
            val method = listener.javaClass.getMethod(name, type)
            val annotation = method.getAnnotation(EventHandler::class.java)
            return RegisteredListener(listener, EventExecutor { _, event -> method.invoke(listener, event) }, annotation.priority, plugin, annotation.ignoreCancelled)
        }
    }
}
