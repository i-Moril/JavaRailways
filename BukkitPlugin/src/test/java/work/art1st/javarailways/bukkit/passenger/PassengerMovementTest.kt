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
import work.art1st.javarailways.bukkit.render.backend.ViewerPacketFrame
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
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class PassengerMovementTest {
    @Test
    fun `boarding uses the contacted carriage presentation instead of another car's older whole scene`() =
        fixture(listOf(2.0, 2.0)) { h ->
            h.train.world.onOwnerForTest {
                h.train.setLocation((h.train.headLocation as RailLocationWithFacing).forward(3.0)!!)
                h.train.commit()
            }
            val second = h.otherRegistration!!
            h.manager.present(second, h.train.publishedFrame)
            val rider = h.rider(0.9, h.second)
            val expected = rider.location.clone()
            h.manager.watch(rider.player, h.first)
            h.dispatcher.tick()
            assertNull(rider.vehicle, "An old scene from A cannot board a passenger through B's historical pose")
            h.manager.watch(rider.player, h.second)
            h.dispatcher.tick()
            assertNotNull(rider.vehicle)
            assertEquals(expected.x, rider.location.x, 1e-8)
            assertEquals(expected.z, rider.location.z, 1e-8)
            h.manager.present(h.registration, h.scene.frame)
            assertEquals(expected.x, rider.location.x, 1e-8)
        }

    @Test
    fun `walking packet cannot overtake an outer carriage frame that has not flushed yet`() = fixture { h ->
        val rider = h.rider(0.0)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        rider.forward = true
        val sent = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val frames = ViewerPacketFrame<String> { _, packets -> sent += packets }
        h.access.frames = frames
        h.access.onRender = { target -> frames.enqueue(listOf(rider.player.uniqueId)) { "carrier:${target.x},${target.z}" } }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            val done = CountDownLatch(1)
            var walking: java.util.concurrent.Future<*>? = null
            frames.frame {
                frames.enqueue(listOf(rider.player.uniqueId)) { "car" }
                h.manager.present(h.registration, h.train.publishedFrame)
                walking = executor.submit { entered.countDown(); h.dispatcher.tick(); done.countDown() }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertFalse(done.await(100, TimeUnit.MILLISECONDS), "The owner cannot send walking before the car packet frame")
                assertTrue(sent.isEmpty())
            }
            walking!!.get(2, TimeUnit.SECONDS)
            assertEquals(2, sent.size)
            assertEquals("car", sent[0][0])
            assertEquals(2, sent[0].size)
            assertEquals(1, sent[1].size)
            assertNotEquals(sent[0][1], sent[1][0])
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `close waits for a carrier allocation already in progress and rejects its late publication`() = fixture { h ->
        val rider = h.rider(0.0)
        h.manager.watch(rider.player, h.first)
        val spawned = CountDownLatch(1)
        val resume = CountDownLatch(1)
        h.access.onSpawn = { spawned.countDown(); assertTrue(resume.await(2, TimeUnit.SECONDS)) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val mount = executor.submit { h.dispatcher.tick() }
            assertTrue(spawned.await(2, TimeUnit.SECONDS))
            val completion = h.manager.close().toCompletableFuture()
            assertFalse(completion.isDone)
            resume.countDown()
            mount.get(2, TimeUnit.SECONDS)
            completion.get(2, TimeUnit.SECONDS)
            assertNull(rider.vehicle)
            assertEquals(0, h.access.registered)
            assertTrue(h.access.created.single().removed)
        } finally { resume.countDown(); executor.shutdownNow() }
    }

    @Test
    fun `early exit waits for processed boarding confirmation and still follows presented carriage`() = fixture { h ->
        h.access.pendingBoarding = true
        val rider = h.rider(2.9)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        val carrier = rider.vehicle
        rider.forward = true
        h.dispatcher.tick()
        assertSame(carrier, rider.vehicle)
        assertEquals(0, h.access.exits)
        val before = rider.location.clone()
        h.train.world.onOwnerForTest {
            h.train.setLocation((h.train.headLocation as RailLocationWithFacing).forward(2.0)!!)
            h.train.commit()
        }
        h.present()
        assertNotEquals(before.x, rider.location.x)
        val outside = h.scene.surfaces[1].pose.toWorld(PassengerPoint(0.0, 0.0, 1.115))
        h.access.pendingBoarding = false // Fixture boundary represents vanilla having cleared its native pending position.
        h.dispatcher.tick()
        assertNull(rider.vehicle)
        assertEquals(outside.x, rider.location.x, 1e-8)
        assertEquals(outside.z, rider.location.z, 1e-8)
        assertEquals(1, h.access.exits)
        assertEquals(0, rider.teleports)
    }

    @Test
    fun `retirement before boarding confirmation cannot revive a pending exit`() = fixture { h ->
        h.access.pendingBoarding = true
        val rider = h.rider(2.9)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        rider.forward = true
        h.dispatcher.tick()
        h.manager.retire(h.registration).toCompletableFuture().join()
        h.access.pendingBoarding = false
        h.dispatcher.tick(includeCancelled = true)
        assertEquals(0, h.access.exits)
        assertNull(rider.vehicle)
        assertEquals(1, h.access.releases)
    }

    @Test
    fun `failed native mount removes only its newly allocated carrier`() = fixture { h ->
        h.access.rejectMount = true
        val rider = h.rider(0.0)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        assertNull(rider.vehicle)
        assertEquals(1, h.access.releases)
        assertTrue(h.access.created.single().removed)
    }

    @Test
    fun `idle passenger follows presented frame in carriage bundle while newer physics waits`() = fixture { h ->
        val rider = h.rider(0.0)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        val original = h.scene
        val originalFeet = rider.location.clone()
        val rendered = mutableListOf<List<String>>()
        val packets = ViewerPacketFrame<String> { _, frame -> rendered += frame }
        h.access.onRender = { target -> packets.enqueue(listOf(rider.player.uniqueId)) { "carrier:${target.x},${target.z}" } }
        h.train.world.onOwnerForTest {
            val location = h.train.headLocation as RailLocationWithFacing
            h.train.setLocation(location.forward(3.0)!!)
            h.train.commit()
        }
        val accepted = h.train.publishedFrame
        h.train.world.onOwnerForTest {
            val location = h.train.headLocation as RailLocationWithFacing
            h.train.setLocation(location.forward(4.0)!!)
            h.train.commit()
        }
        assertTrue(h.train.publishedFrame.revision > accepted.revision)
        h.dispatcher.tick()
        assertTrue(rendered.isEmpty(), "Idle entity clock must not send a separate carrier packet")
        assertEquals(originalFeet.x, rider.location.x, 1e-8)
        assertEquals(originalFeet.z, rider.location.z, 1e-8)
        packets.frame {
            packets.enqueue(listOf(rider.player.uniqueId)) { "car:${accepted.revision}" }
            h.manager.present(h.registration, accepted)
        }
        assertSame(accepted, h.scene.frame)
        val target = h.scene.surfaces.first().pose.toWorld(PassengerPoint(0.0, 0.0, 0.0))
        assertEquals(target.x, rider.location.x, 1e-8)
        assertEquals(target.z, rider.location.z, 1e-8)
        assertEquals(listOf(listOf("car:${accepted.revision}", "carrier:${target.x},${target.z}")), rendered)
        h.manager.present(h.registration, original.frame)
        h.dispatcher.tick()
        assertSame(accepted, h.scene.frame, "Late old presentation cannot rewind the native or packet carrier")
        assertEquals(target.x, rider.location.x, 1e-8)
        assertEquals(target.z, rider.location.z, 1e-8)
        assertEquals(1, rendered.size)
    }

    @Test
    fun `walking uses copied presented geometry until another carriage presentation`() = fixture { h ->
        val rider = h.rider(0.0)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        val presented = h.scene
        h.first.publish(CompactTransform(Vector3d(40.0, 0.0, 0.0)))
        rider.forward = true
        h.dispatcher.tick()
        val expected = presented.surfaces.first().pose.toWorld(PassengerPoint(0.0, 0.0, 0.215))
        assertEquals(expected.x, rider.location.x, 1e-8)
        assertEquals(expected.z, rider.location.z, 1e-8)
        assertSame(presented, h.scene)
    }

    @Test
    fun `latest presented move owns authority and queued moves cannot run after boundary release`() = fixture { h ->
        val rider = h.rider(2.9)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        h.dispatcher.deferOwnerTasks = true
        h.present()
        h.present()
        assertEquals(0, h.access.moves)
        h.dispatcher.drainOwnerTasks()
        assertEquals(1, h.access.moves, "Only latest native target applies")
        h.present() // A native update is still queued when the owner decides to leave the platform.
        rider.forward = true
        val expected = h.scene.surfaces[1].pose.toWorld(PassengerPoint(0.0, 0.0, 1.115))
        h.dispatcher.tick()
        val renders = h.access.renders
        h.present() // A later carriage packet must also respect the release fence.
        h.dispatcher.drainOwnerTasks()
        assertEquals(2, h.access.moves, "Only the synchronous handoff moves the native carrier after its last accepted target")
        assertEquals(renders, h.access.renders)
        assertEquals(1, h.access.exits)
        assertEquals(expected.x, rider.location.x, 1e-8)
        assertEquals(expected.y, rider.location.y, 1e-8)
        assertEquals(expected.z, rider.location.z, 1e-8)
        assertNull(rider.vehicle)
        h.dispatcher.tick()
        assertEquals(1, h.access.created.size, "Static outside feet must not immediately mount again")
    }

    @Test
    fun `boundary dismount veto keeps the same carrier and session until an accepted exit`() = fixture { h ->
        val rider = h.rider(2.9)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        val carrier = rider.vehicle
        val inside = rider.location.clone()
        h.access.vetoExit = true
        rider.forward = true
        h.dispatcher.tick()
        assertSame(carrier, rider.vehicle)
        assertEquals(0, h.access.releases)
        assertFalse(h.access.created.single().removed)
        assertEquals(inside.z, rider.location.z, 1e-8)
        rider.sneak = true
        val shift = EntityDismountEvent(rider.player, carrier!!)
        h.manager.onManualDismount(shift)
        assertTrue(shift.isCancelled, "The veto did not discard input restrictions or the active session")
        h.access.vetoExit = false
        h.dispatcher.tick()
        assertNull(rider.vehicle)
        assertEquals(1, h.access.releases)
        assertEquals(1, h.access.created.size)
        assertEquals(0, rider.teleports)
    }

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
            assertSame(scene.frame, later.frame, "A physics publication alone is not a presented scene")
            h.dispatcher.tick()
            assertEquals(expected.x, rider.location.x, 1e-8)
            h.present()
            val presented = h.scene
            assertSame(h.train.publishedFrame, presented.frame)
            val moved = presented.surfaces[1].pose.toWorld(PassengerPoint(0.0, 0.0, 0.885))
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
        val registry = PassengerPlatformRegistry().also { it.register(registration); it.present(registration, h.train.publishedFrame) }
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
            it.present(registration, train.publishedFrame); otherRegistration?.let { other -> it.present(other, train.publishedFrame) }
        }
        val scene get() = manager.platforms.capture(registration)!!
        fun present() {
            val frame = train.publishedFrame
            manager.present(registration, frame)
            otherRegistration?.let { manager.present(it, frame) }
        }
        fun rider(z: Double, source: PassengerSurfaceSource = first): TestRider {
            val owner = manager.platforms.owner(source)!!
            val surface = manager.platforms.capture(owner)!!.surfaces.first { it.id === source }
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
        var registered = 0; var moves = 0; var renders = 0; var exits = 0; var releases = 0
        var onSpawn: (() -> Unit)? = null
        override fun spawn(location: Location): ArmorStand = TestNative(location, riders, cleanupEvents) { !rejectMount }.also { created += it; onSpawn?.invoke() }.entity
        override fun register(carrier: ArmorStand, location: Location, viewers: Collection<Player>) { registered++ }
        var onRender: ((Location) -> Unit)? = null
        var vetoExit = false
        var rejectMount = false
        var pendingBoarding = false
        override fun canDismount(player: Player) = !pendingBoarding
        var frames: ViewerPacketFrame<String>? = null
        override fun holdPresentation(lock: ReentrantLock) { frames?.holdUntilFrameSent(lock) }
        override fun render(carrier: ArmorStand, location: Location, viewers: Collection<Player>) {
            renders++
            onRender?.invoke(location)
        }
        override fun syncAuthority(carrier: ArmorStand, location: Location) {
            moves++
            val native = created.single { it.entity === carrier }
            native.location = location.clone()
            native.passengers.forEach { player -> riders[player.uniqueId]!!.location = location.clone().subtract(0.0, PASSENGER_CARRIER_LIFT, 0.0) }
        }
        override fun dismountAt(carrier: ArmorStand, player: Player, carrierTarget: Location): Boolean {
            exits++
            syncAuthority(carrier, carrierTarget)
            if (vetoExit) return false
            carrier.removePassenger(player)
            return true
        }
        override fun release(carrier: ArmorStand) {
            releases++; cleanupEvents += "release"
        }
    }

    private class TestNative(var location: Location, private val riders: Map<UUID, TestRider>, val events: MutableList<String>, val acceptMount: () -> Boolean) {
        val passengers = mutableListOf<Player>()
        var removed = false; var ejections = 0
        val entity: ArmorStand = proxy<ArmorStand> { method, args -> when (method.name) {
            "isValid" -> !removed; "getWorld" -> location.world; "getLocation" -> location.clone(); "getPassengers" -> passengers.toList()
            "addPassenger" -> { if (!acceptMount()) false else { val player = args!![0] as Player; passengers += player; riders[player.uniqueId]!!.vehicle = entity; true } }
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
