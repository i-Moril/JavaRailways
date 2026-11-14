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
import work.art1st.javarailways.bukkit.render.model.AbstractModel
import work.art1st.javarailways.bukkit.render.model.CompositeModel
import work.art1st.javarailways.bukkit.render.model.ModelState
import work.art1st.javarailways.bukkit.render.model.PlatformModel
import work.art1st.javarailways.bukkit.config.model.PartPlatformConfig
import work.art1st.javarailways.bukkit.render.backend.DisplayVisual
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

import work.art1st.javarailways.bukkit.extension.train.CarFramePublicationGate
import work.art1st.javarailways.bukkit.logic.dispatchTrainPresentation
import work.art1st.javarailways.bukkit.logic.dispatchTrainFrameChunks
import work.art1st.javarailways.bukkit.logic.PendingTrainFrame
import work.art1st.javarailways.bukkit.logic.CarRenderFrame
import work.art1st.javarailways.bukkit.util.LatestAffinityTaskDispatcher
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.physics.train.setControllerForTest
import java.util.concurrent.CompletableFuture

class PassengerMovementTest {
    @Test
    fun `moving entity ticks accumulate local movement without packets while render waits`() = fixture { h ->
        h.moving()
        val rider = h.mount(0.0)
        val start = rider.location.clone()
        val renders = h.access.renders
        val updates = h.modelUpdates
        rider.forward = true
        repeat(4) { h.dispatcher.tick() }
        val captured = h.manager.capture(h.bukkitWorld).values.single().single()
        assertEquals(0.86, captured.state.local.z, 1e-8)
        assertEquals(renders, h.access.renders)
        assertEquals(start.x, rider.location.x, 1e-8)
        assertEquals(start.z, rider.location.z, 1e-8)
        h.present(h.scene.frame)
        val target = h.scene.surfaces.first().pose.toWorld(PassengerPoint(0.0, 0.0, 0.86))
        assertEquals(target.x, rider.location.x, 1e-8)
        assertEquals(target.z, rider.location.z, 1e-8)
        assertEquals(updates, h.modelUpdates, "Equal revision walking must bypass model traversal")
    }

    @Test
    fun `idle rider and carriage share the accepted bundle even when physics is newer`() = fixture { h ->
        h.moving()
        val rider = h.mount(0.0)
        val before = rider.location.clone()
        val old = h.scene.frame
        h.moveTrain(3.0)
        val accepted = h.train.publishedFrame
        h.moveTrain(4.0)
        h.dispatcher.tick()
        assertEquals(before.x, rider.location.x, 1e-8)
        h.sent.clear()
        h.present(accepted)
        val target = h.scene.surfaces.first().pose.toWorld(PassengerPoint(0.0, 0.0, 0.0))
        assertEquals(target.x, rider.location.x, 1e-8)
        assertEquals(target.z, rider.location.z, 1e-8)
        assertEquals(listOf("car:${h.train.cars.first().id}:${accepted.revision}",
            "carrier:${rider.player.uniqueId}:${target.x},${target.z}"), h.sent.single())
        val renders = h.access.renders
        h.present(old)
        assertSame(accepted, h.scene.frame)
        assertEquals(renders, h.access.renders, "Rejected old geometry cannot output a rider")
    }

    @Test
    fun `stopped player movement updates only that carrier without train offers or model work`() = fixture { h ->
        val first = h.mount(0.0)
        val second = h.mount(0.0)
        val secondStart = second.location.clone()
        val offers = h.renderDispatcher.metrics().scheduledTasks
        val updates = h.modelUpdates
        val renders = h.access.renders
        first.forward = true
        repeat(3) { h.dispatcher.tick() }
        val target = h.scene.surfaces.first().pose.toWorld(PassengerPoint(0.0, 0.0, 0.645))
        assertEquals(target.x, first.location.x, 1e-8); assertEquals(target.z, first.location.z, 1e-8)
        assertEquals(secondStart, second.location)
        assertEquals(renders + 3, h.access.renders)
        assertEquals(offers, h.renderDispatcher.metrics().scheduledTasks)
        assertEquals(updates, h.modelUpdates)
        assertTrue(h.sent.flatten().all { it.startsWith("carrier:${first.player.uniqueId}:") })
        first.forward = false
        h.dispatcher.tick()
        assertEquals(renders + 3, h.access.renders, "Idle stopped players need no carrier packet")
    }

    @Test
    fun `cross-car membership is captured once before region work and does not invalidate native target`() =
        fixture(listOf(2.0, 2.0)) { h ->
            h.moving()
            val rider = h.mount(-0.9)
            val carrier = rider.vehicle
            h.deferFrames = true
            val renders = h.access.renders
            val moves = h.access.moves
            h.present(h.scene.frame) // Capture A before the player crosses to B.
            rider.backward = true
            h.dispatcher.tick()
            val current = h.manager.capture(h.bukkitWorld).values.single().single()
            assertSame(h.otherRegistration, current.state.registration)
            h.flushFrames()
            assertEquals(renders + 1, h.access.renders, "A's captured membership is rendered once")
            assertEquals(moves + 1, h.access.moves, "New WalkingState does not invalidate the displayed native target")
            h.present(h.scene.frame); h.flushFrames()
            assertEquals(renders + 2, h.access.renders, "B is emitted only by the next captured batch")
            assertSame(carrier, rider.vehicle)
            assertEquals(1, h.access.registered)
        }

    @Test
    fun `boarding uses contacted carriage presentation rather than another car historical pose`() =
        fixture(listOf(2.0, 2.0)) { h ->
            h.moveTrain(3.0)
            h.manager.present(h.otherRegistration!!, h.train.publishedFrame)
            val rider = h.rider(0.9, h.second)
            h.manager.watch(rider.player, h.first)
            h.dispatcher.tick()
            assertNull(rider.vehicle)
            h.manager.watch(rider.player, h.second)
            h.dispatcher.tick()
            assertNotNull(rider.vehicle)
        }

    @Test
    fun `boarding pending follows presented carriage until vanilla confirmation allows outside release`() = fixture { h ->
        h.access.pendingBoarding = true
        val rider = h.mount(2.9)
        val carrier = rider.vehicle
        rider.forward = true
        h.dispatcher.tick()
        assertSame(carrier, rider.vehicle)
        assertEquals(0, h.access.exits)
        h.moveTrain(2.0); h.present()
        val outside = h.scene.surfaces[1].pose.toWorld(PassengerPoint(0.0, 0.0, 1.115))
        h.access.pendingBoarding = false
        h.dispatcher.tick()
        assertNull(rider.vehicle)
        assertEquals(outside.x, rider.location.x, 1e-8); assertEquals(outside.z, rider.location.z, 1e-8)
        assertEquals(1, h.access.exits)
        assertEquals(0, rider.teleports)
    }

    @Test
    fun `native veto retains same carrier and player owner retries from retained local point`() = fixture { h ->
        val rider = h.mount(2.9)
        val carrier = rider.vehicle
        h.access.vetoExit = true
        rider.forward = true
        h.dispatcher.tick()
        assertSame(carrier, rider.vehicle)
        assertEquals(0, h.access.releases)
        assertNull(h.manager.capture(h.bukkitWorld).values.single().single().state.exit)
        rider.sneak = true
        val shift = EntityDismountEvent(rider.player, carrier!!)
        h.manager.onManualDismount(shift)
        assertTrue(shift.isCancelled)
        h.access.vetoExit = false
        h.dispatcher.tick()
        assertNull(rider.vehicle)
        assertEquals(1, h.access.releases)
        assertEquals(1, h.access.created.size)
    }

    @Test
    fun `stale native work is skipped after a newer rendered target and after retirement`() = fixture { h ->
        val rider = h.mount(0.0)
        val moves = h.access.moves
        h.dispatcher.deferOwnerTasks = true
        rider.forward = true
        repeat(2) { h.dispatcher.tick() }
        h.dispatcher.drainOwnerTasks()
        assertEquals(moves + 1, h.access.moves)
        h.dispatcher.tick()
        val ended = h.manager.retire(h.registration).toCompletableFuture()
        assertFalse(ended.isDone)
        h.dispatcher.drainOwnerTasks()
        ended.join()
        assertEquals(moves + 1, h.access.moves)
        assertNull(rider.vehicle)
        assertTrue(h.access.created.single().removed)
    }

    @Test
    fun `stationary transfer keeps one carrier and view relative speed ignores jump sprint`() = fixture { h ->
        val rider = h.mount(0.9)
        val carrier = rider.vehicle
        val revision = h.train.publishedFrame.revision
        val updates = h.modelUpdates
        rider.forward = true
        h.dispatcher.tick()
        val expected = h.scene.surfaces[1].pose.toWorld(PassengerPoint(0.0, 0.0, -0.885))
        assertEquals(expected.x, rider.location.x, 1e-8); assertEquals(expected.z, rider.location.z, 1e-8)
        assertSame(carrier, rider.vehicle)
        assertEquals(updates, h.modelUpdates)
        rider.forward = false; rider.jump = true; rider.sprint = true
        val before = rider.location.clone()
        h.dispatcher.tick()
        assertEquals(before, rider.location)
        assertEquals(revision, h.train.publishedFrame.revision)
        rider.location.yaw += 90f
        rider.forward = true
        h.dispatcher.tick()
        val yaw = Math.toRadians(before.yaw.toDouble() + 90.0)
        assertEquals(-kotlin.math.sin(yaw) * 0.215, rider.location.x - before.x, 1e-8)
        assertEquals(kotlin.math.cos(yaw) * 0.215, rider.location.z - before.z, 1e-8)
        assertEquals(1, h.access.created.size)
    }

    @Test
    fun `Shift prevents manual exit until walking outside restores movement without teleport`() = fixture { h ->
        val rider = h.mount(2.9)
        rider.sneak = true
        val event = EntityDismountEvent(rider.player, rider.vehicle!!)
        h.manager.onManualDismount(event)
        assertTrue(event.isCancelled)
        rider.forward = true
        h.dispatcher.tick()
        assertNull(rider.vehicle)
        assertEquals(1, h.access.releases)
        assertTrue(h.access.created.single().removed)
        val after = EntityDismountEvent(rider.player, event.dismounted)
        h.manager.onManualDismount(after)
        assertFalse(after.isCancelled)
        assertEquals(0, rider.teleports)
    }

    @Test
    fun `close waits for in-progress bundle enqueue without blocking player ticks`() = fixture { h ->
        h.moving()
        val rider = h.mount(0.0)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        h.onSend = { entered.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val render = executor.submit { h.present(h.scene.frame) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            rider.forward = true
            h.dispatcher.tick()
            val completion = h.manager.close().toCompletableFuture()
            assertFalse(completion.isDone)
            assertEquals(0, h.access.releases)
            resume.countDown()
            render.get(5, TimeUnit.SECONDS)
            completion.get(5, TimeUnit.SECONDS)
            assertNull(rider.vehicle)
            assertEquals(1, h.access.releases)
            assertTrue(h.access.created.single().removed)
        } finally { resume.countDown(); h.onSend = null; executor.shutdownNow() }
    }

    @Test
    fun `close waits for carrier allocation in progress and rejects late registration`() = fixture { h ->
        val rider = h.rider(0.0)
        h.manager.watch(rider.player, h.first)
        val spawned = CountDownLatch(1)
        val resume = CountDownLatch(1)
        h.access.onSpawn = { spawned.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val mount = executor.submit { h.dispatcher.tick() }
            assertTrue(spawned.await(5, TimeUnit.SECONDS))
            val completion = h.manager.close().toCompletableFuture()
            assertFalse(completion.isDone)
            resume.countDown()
            mount.get(5, TimeUnit.SECONDS)
            completion.get(5, TimeUnit.SECONDS)
            assertNull(rider.vehicle)
            assertEquals(0, h.access.registered)
            assertTrue(h.access.created.single().removed)
        } finally { resume.countDown(); executor.shutdownNow() }
    }

    @Test
    fun `failed native mount removes its carrier and does not claim an existing seat`() = fixture { h ->
        h.access.rejectMount = true
        val rider = h.rider(0.0)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick()
        assertNull(rider.vehicle)
        assertEquals(1, h.access.releases)
        assertTrue(h.access.created.single().removed)
        val seated = h.rider(0.0)
        seated.vehicle = proxy<Entity> { _, _ -> null }
        h.manager.watch(seated.player, h.first)
        h.manager.unwatch(rider.player, h.first)
        h.dispatcher.tick()
        assertEquals(1, h.access.created.size)
    }

    @Test
    fun `accepted teleport fences queued packets while cancelled teleport preserves control`() = fixture { h ->
        val rider = h.mount(0.0)
        val carrier = rider.vehicle
        val listener = registered(h.manager, "onTeleport", PlayerTeleportEvent::class.java, h.plugin)
        val event = PlayerTeleportEvent(rider.player, rider.location.clone(), rider.location.clone().add(100.0, 0.0, 0.0))
        event.isCancelled = true
        listener.callEvent(event)
        assertSame(carrier, rider.vehicle)
        h.deferFrames = true
        h.present(h.scene.frame)
        event.isCancelled = false
        listener.callEvent(event)
        rider.location = event.to.clone()
        val position = rider.location.clone()
        val renders = h.access.renders
        h.flushFrames()
        h.dispatcher.tick(includeCancelled = true)
        h.flushFrames()
        assertNull(rider.vehicle)
        assertEquals(position, rider.location)
        assertEquals(renders, h.access.renders)
        assertEquals(0, rider.teleports)
    }

    @Test
    fun `accepted dismount cleanup waits for vanilla event return and close awaits it`() = fixture { h ->
        val rider = h.mount(0.0)
        val native = h.access.created.single()
        h.manager.onDismount(EntityDismountEvent(rider.player, native.entity))
        assertEquals(0, native.ejections)
        native.passengers.clear(); rider.vehicle = null
        val closed = h.manager.close().toCompletableFuture()
        assertFalse(closed.isDone)
        h.dispatcher.runDelayed()
        closed.join()
        assertTrue(native.removed)
        assertEquals(1, h.access.releases)
    }

    @Test
    fun `close without another world tick fences late callbacks and future watches`() = fixture { h ->
        val rider = h.mount(0.0)
        h.manager.close().toCompletableFuture().join()
        h.manager.register(h.registration)
        h.manager.watch(rider.player, h.first)
        h.dispatcher.tick(includeCancelled = true)
        assertNull(rider.vehicle)
        assertEquals(1, h.access.created.size)
        assertEquals(1, h.access.releases)
        assertNull(h.manager.platforms.capture(h.registration))
    }

    @Test
    fun `cancelled death preserves control while accepted death and quit release carriers`() = fixture { h ->
        val dying = h.mount(0.0)
        val quitting = h.mount(0.0)
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
    }

    @Test
    fun `bogie platform retains render floor composition and upright collision offset`() = fixture { h ->
        val local = CompactTransform(Vector3d(0.3, -1.0, 0.7), Quaterniond().rotateY(0.2))
        val root = h.train.publishedFrame.cars.single().bogies[0].modelTransform(1.25)
        val (_, platform) = renderedPlatform(h, root,
            CompactTransform(Vector3d(0.3, 0.0, 0.7), Quaterniond().rotateY(0.2)))
        val source = platform.passengerSurface
        val registration = PassengerCarPlatforms(h.train.cars.single(), h.train, h.bukkitWorld, 1.25,
            listOf(PassengerPlatformPart(source, 0)))
        val registry = PassengerPlatformRegistry().also { it.register(registration); it.present(registration, h.train.publishedFrame) }
        val scene = registry.capture(registration)!!
        val expected = local.applyTo(scene.frame.cars.single().bogies[0].modelTransform(1.25))
        val forward = expected.rotation.transform(Vector3d(0.0, 0.0, 1.0))
        val yaw = kotlin.math.atan2(-forward.x, forward.z)
        val top = Vector3d(expected.position).add(kotlin.math.sin(yaw) * 0.1, COLLISION_TOP_HEIGHT, -kotlin.math.cos(yaw) * 0.1)
        val actual = scene.surfaces.single().pose
        assertEquals(top.x, actual.position.x, 1e-9); assertEquals(top.y, actual.position.y, 1e-9); assertEquals(top.z, actual.position.z, 1e-9)
    }

    @Test
    fun `rendered rotated platform does not mount a player standing beside the carriage`() = fixture { h ->
        val frame = h.train.publishedFrame
        val root = frame.cars.single().state.modelTransform()
        val (_, platform) = renderedPlatform(h, root)
        h.manager.retire(h.registration).toCompletableFuture().join()
        val registration = PassengerCarPlatforms(h.train.cars.single(), h.train, h.bukkitWorld, 0.0,
            listOf(PassengerPlatformPart(platform.passengerSurface, null)))
        h.manager.register(registration)
        h.manager.present(registration, frame)
        val actualPose = collisionTop(platform.state.worldTransform)
        val outside = actualPose.toWorld(PassengerPoint(4.0, 0.0, 0.0))
        val rider = TestRider(Location(h.bukkitWorld, outside.x, outside.y, outside.z))
        h.access.riders[rider.player.uniqueId] = rider
        h.manager.watch(rider.player, platform.passengerSurface)
        h.dispatcher.tick()
        assertNull(rider.vehicle, "A station player four meters across the real platform must not board")
        assertTrue(h.access.created.isEmpty())

        val inside = actualPose.toWorld(PassengerPoint(0.0, 0.0, 4.0))
        rider.location = Location(h.bukkitWorld, inside.x, inside.y, inside.z)
        h.dispatcher.tick()
        assertNotNull(rider.vehicle, "A player on the actual platform must still board")
    }

    @Test
    fun `rendered nested platform publishes root local pose across yaw slope and repeated updates`() = fixture { h ->
        val nested = CompactTransform(Vector3d(0.2, 0.1, 0.7), Quaterniond().rotateY(0.2))
        val (model, platform) = renderedPlatform(h, CompactTransform(), nested)
        val local = CompactTransform(Vector3d(0.0, -1.0, 0.0)).applyTo(nested)
        for (yaw in listOf(0.0, 0.7, Math.PI / 2, Math.PI)) {
            val root = CompactTransform(Vector3d(100.0, 64.0, 200.0),
                Quaterniond().rotateY(yaw).rotateX(0.15).rotateZ(-0.1))
            repeat(2) {
                model.updateState(root)
                // Animation callbacks re-enter the part without another CompositeModel update.
                platform.projectAnimationFrame(null, null, null, null)
                val published = platform.passengerSurface.current()!!
                for (point in listOf(PassengerPoint(0.0, 0.0, 0.0), PassengerPoint(0.3, 0.0, 2.0))) {
                    val expected = PassengerPose.capture(local).toWorld(point)
                    val actual = published.toWorld(point)
                    assertEquals(expected.x, actual.x, 1e-9)
                    assertEquals(expected.y, actual.y, 1e-9)
                    assertEquals(expected.z, actual.z, 1e-9)
                    val rendered = PassengerPose.capture(platform.state.worldTransform).toWorld(point)
                    val projected = PassengerPose.capture(published.transform().applyTo(root)).toWorld(point)
                    assertEquals(rendered.x, projected.x, 1e-9)
                    assertEquals(rendered.y, projected.y, 1e-9)
                    assertEquals(rendered.z, projected.z, 1e-9)
                }
                platform.projectAnimationFrame(null, null, CompactTransform(Vector3d(0.25, 0.0, 0.0)), null)
                val animatedLocal = CompactTransform(Vector3d(0.25, -1.0, 0.0)).applyTo(nested)
                val expected = PassengerPose.capture(animatedLocal).toWorld(PassengerPoint(0.3, 0.0, 2.0))
                val actual = platform.passengerSurface.current()!!.toWorld(PassengerPoint(0.3, 0.0, 2.0))
                assertEquals(expected.x, actual.x, 1e-9)
                assertEquals(expected.y, actual.y, 1e-9)
                assertEquals(expected.z, actual.z, 1e-9)
            }
        }
    }

    private fun renderedPlatform(
        h: Harness,
        root: CompactTransform,
        parent: CompactTransform = CompactTransform()
    ): Pair<CompositeModel, PlatformModel> {
        val platform = PlatformModel(h.train.world, ModelState(CompactTransform(Vector3d(0.0, -1.0, 0.0))),
            emptyList(), emptyMap(), Location(h.bukkitWorld, 0.0, 0.0, 0.0),
            PartPlatformConfig(rows = 3, columns = 18))
        val group = object : AbstractModel(h.train.world, ModelState(parent), listOf(platform), emptyMap()) {}
        val model = CompositeModel(h.train.world, listOf(group), proxy<DisplayVisual> { _, _ -> null }, h.bukkitWorld)
        model.updateState(root)
        return model to platform
    }

    private fun collisionTop(pose: CompactTransform): PassengerPose {
        val forward = pose.rotation.transform(Vector3d(0.0, 0.0, 1.0))
        val yaw = kotlin.math.atan2(-forward.x, forward.z)
        val top = Vector3d(pose.position).add(kotlin.math.sin(yaw) * 0.1,
            COLLISION_TOP_HEIGHT, -kotlin.math.cos(yaw) * 0.1)
        return PassengerPose.capture(CompactTransform(top, pose.rotation))
    }

    private fun fixture(carLengths: List<Double> = listOf(10.0), action: (Harness) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            world.physicsCoordinator.use {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(300.0))
                val train = createTrainWithConfig("passenger", rail, world, carLengths)
                val h = Harness(train)
                try { action(h) } finally { h.dispatcher.deferOwnerTasks = false; h.deferFrames = false; h.flushFrames(); h.manager.close().toCompletableFuture().join() }
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
        var deferFrames = false
        var modelUpdates = 0
        val sent = mutableListOf<List<String>>()
        var onSend: (() -> Unit)? = null
        private val packets = ViewerPacketFrame<String> { _, batch -> onSend?.invoke(); sent += batch }
        private val gates = train.cars.associateWith { CarFramePublicationGate().also { gate ->
            gate.accept(it, train.publicationStreamId, train.publishedFrame.revision)
        } }
        private val accepted = train.cars.associateWith { train.publishedFrame }.toMutableMap()
        private val queuedFrames = ArrayDeque<() -> Unit>()
        val renderDispatcher = LatestAffinityTaskDispatcher<UUID, PendingTrainFrame> { candidate, complete ->
            dispatchTrainFrameChunks(candidate.cars,
                locationOf = { Location(bukkitWorld, it.frame.state.positionX, it.frame.state.positionY, it.frame.state.positionZ) },
                schedule = { _, action -> if (deferFrames) queuedFrames.add(action) else action() }) { cars ->
                dispatchTrainPresentation(candidate, cars, { packets.frame(it) }, apply = { item ->
                    val gate = gates.getValue(item.car)
                    if (gate.accept(item.car, candidate.streamId, candidate.frame.revision)) {
                        modelUpdates++
                        accepted[item.car] = candidate.frame
                        val reg = if (item.car === registration.car) registration else otherRegistration!!
                        manager.present(reg, candidate.frame)
                        access.riders.keys.forEach { viewer -> packets.enqueue(listOf(viewer)) {
                            "car:${item.car.id}:${candidate.frame.revision}"
                        } }
                    }
                    accepted[item.car]?.takeIf { gate.isCurrent(item.car, candidate.streamId, candidate.frame.revision) }
                }, present = manager::render, isCurrent = { true }, failed = { _, failure -> throw failure })
            }.whenComplete { _, failure -> complete(); if (failure != null) throw java.util.concurrent.CompletionException(failure) }
        }
        init {
            train.world.drainPublishedTrainFrames()
            access.onRender = { carrier, target ->
                val rider = access.created.single { it.entity === carrier }.passengers.singleOrNull()
                if (rider != null) packets.enqueue(listOf(rider.uniqueId)) {
                    "carrier:${rider.uniqueId}:${target.x},${target.z}"
                }
            }
        }
        fun mount(z: Double): TestRider = rider(z).also { manager.watch(it.player, first); dispatcher.tick() }
        fun moveTrain(distance: Double) = train.world.onOwnerForTest {
            train.setLocation((train.headLocation as RailLocationWithFacing).forward(distance)!!)
            train.commit()
        }
        fun present(frame: TrainFrameSnapshot = train.publishedFrame) {
            val riders = manager.capture(bukkitWorld)[train.publicationStreamId].orEmpty()
            val cars = frame.cars.mapIndexed { index, car -> CarRenderFrame(train.cars[index], car, null) }
            renderDispatcher.offer(train.publicationStreamId,
                PendingTrainFrame(bukkitWorld, train.publicationStreamId, frame, cars, riders))
        }
        fun moving() {
            val controller = object : TrainController(train) {
                override fun tick(deltaT: Double) = Unit
                fun publishSpeed() { speed = 1.0; commit() }
            }
            train.setControllerForTest(controller)
            train.world.onOwnerForTest { controller.publishSpeed() }
            present()
            assertTrue(scene.frame.speedMetersPerSecond > 0.0)
        }
        fun flushFrames() { while (queuedFrames.isNotEmpty()) queuedFrames.removeFirst()() }
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
        var onRender: ((ArmorStand, Location) -> Unit)? = null
        var vetoExit = false
        var rejectMount = false
        var pendingBoarding = false
        override fun canDismount(player: Player) = !pendingBoarding
        override fun render(carrier: ArmorStand, location: Location, viewers: Collection<Player>) {
            renders++
            onRender?.invoke(carrier, location)
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
