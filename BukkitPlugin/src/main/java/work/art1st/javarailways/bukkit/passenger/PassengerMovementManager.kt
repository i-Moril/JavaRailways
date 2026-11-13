package work.art1st.javarailways.bukkit.passenger

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import work.art1st.javarailways.core.api.util.DestructionSteps
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One entity-owner clock per nearby player; mounted riders and carriers share that owner. */
internal class PassengerMovementManager(
    private val plugin: Plugin,
    private val dispatcher: PlatformDispatcher,
    private val carriers: PassengerCarrierAccess,
    internal val platforms: PassengerPlatformRegistry = PassengerPlatformRegistry()
) : Listener {
    private val lifecycle = Any()
    private val watches = ConcurrentHashMap<UUID, Watch>()
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val viewers = ConcurrentHashMap<PassengerSurfaceSource, ConcurrentHashMap<UUID, Player>>()
    private val removals = ConcurrentHashMap.newKeySet<CompletableFuture<Void>>()
    @Volatile private var closed = false

    fun start() { plugin.server.pluginManager.registerEvents(this, plugin) }

    fun register(registration: PassengerCarPlatforms): Unit = synchronized(lifecycle) {
        if (!closed) platforms.register(registration)
    }

    /** The carriage invokes this in the SAME packet frame as its model and fixed seats. */
    fun present(registration: PassengerCarPlatforms, frame: TrainFrameSnapshot) {
        if (closed) return
        val scene = platforms.present(registration, frame) ?: return
        sessions.values.filter { it.registration === registration }.forEach { session -> session.lock.withLock {
            if (closed || session.ending.get() || session.registration !== registration ||
                scene.frame.revision < session.scene.frame.revision) return@withLock
            val surface = scene.surfaces.firstOrNull { it.id === session.surface } ?: return@withLock
            carriers.holdPresentation(session.lock)
            session.scene = scene
            move(session, surface.pose.toWorld(session.pendingExit ?: session.local))
        } }
    }

    /** Called through the existing platform pre-activation audience path, on the player owner. */
    fun watch(player: Player, source: PassengerSurfaceSource): Unit = synchronized(lifecycle) {
        if (closed || source.retired) return
        viewers.computeIfAbsent(source) { ConcurrentHashMap() }[player.uniqueId] = player
        val watch = watches.computeIfAbsent(player.uniqueId) { Watch(player) }
        watch.candidates.add(source)
        if (watch.task == null) {
            watch.task = dispatcher.runForEntityAtFixedRate(player, 1L, 1L,
                action = { task ->
                    if (closed || watches[player.uniqueId] !== watch) { task.cancel(); return@runForEntityAtFixedRate }
                    try { tick(watch) } catch (failure: Exception) {
                        sessions[player.uniqueId]?.let { end(it) }
                        plugin.logger.log(Level.SEVERE, "Passenger movement failed for ${player.uniqueId}", failure)
                    }
                }, retired = { forget(watch) })
        }
    }

    fun unwatch(player: Player, source: PassengerSurfaceSource): Unit = synchronized(lifecycle) {
        viewers[source]?.remove(player.uniqueId)
        val watch = watches[player.uniqueId] ?: return
        watch.candidates.remove(source)
        stopIfUnused(watch)
    }

    fun retire(registration: PassengerCarPlatforms): CompletionStage<Void> {
        val affected = synchronized(lifecycle) {
            platforms.remove(registration)
            val removed = registration.parts.map { it.source }.toSet()
            removed.forEach { viewers.remove(it) }
            watches.values.forEach { watch -> watch.candidates.removeAll(removed); stopIfUnused(watch) }
            sessions.values.filter { it.registration === registration || it.surface in removed }
        }
        val steps = DestructionSteps()
        affected.forEach { session -> steps.await { end(session) } }
        return steps.finish()
    }

    fun close(): CompletionStage<Void> {
        val (active, pending) = synchronized(lifecycle) {
            closed = true
            HandlerList.unregisterAll(this)
            watches.values.forEach { it.task?.cancel() }
            platforms.clear()
            viewers.clear()
            watches.clear()
            sessions.values.toList() to removals.toList()
        }
        val steps = DestructionSteps()
        active.forEach { session -> steps.await { end(session) } }
        pending.forEach { removal -> steps.await { removal } }
        return steps.finish()
    }

    private fun tick(watch: Watch) {
        val player = watch.player
        val session = sessions[player.uniqueId]
        if (session != null) {
            if (!session.ending.get()) tickSession(session)
            return
        }
        if (!eligible(player) || player.vehicle != null) return
        val feet = player.location.point()
        val tried = hashSetOf<PassengerCarPlatforms>()
        for (candidate in watch.candidates) {
            val registration = platforms.owner(candidate) ?: continue
            if (player.world !== registration.world || !tried.add(registration)) continue
            val scene = platforms.capture(registration) ?: continue
            for (surface in scene.surfaces) {
                if (platforms.owner(surface.id) !== registration) continue
                val contact = surface.contact(feet) ?: continue
                mount(watch, registration, scene, surface, contact)
                return
            }
        }
    }

    private fun mount(watch: Watch, registration: PassengerCarPlatforms, scene: PassengerScene, surface: PassengerSurface, local: PassengerPoint) {
        if (closed || !registration.isCurrent() || surface.id.retired) return
        val player = watch.player
        val feet = surface.pose.toWorld(local)
        val target = carrierLocation(registration, feet, player.location.yaw)
        // As with fixed seats, never mutate a carrier or mount across a Folia owner boundary.
        if (!dispatcher.owns(target)) return
        val allocation = CompletableFuture<Void>()
        synchronized(lifecycle) {
            if (closed || !registration.isCurrent() || surface.id.retired || watches[player.uniqueId] !== watch) return
            removals.add(allocation)
        }
        val carrier = try { carriers.spawn(target) } catch (failure: Throwable) {
            allocation.completeExceptionally(failure)
            throw failure
        }
        val session = Session(watch, registration, scene, surface.id, local, player.location.yaw, carrier)
        session.lock.withLock {
            val accepted = synchronized(lifecycle) {
                if (closed || !registration.isCurrent() || surface.id.retired || watches[player.uniqueId] !== watch) false
                else { sessions[player.uniqueId] = session; true }
            }
            if (!accepted) {
                end(session).whenComplete { _, failure ->
                    if (failure == null) { allocation.complete(null); removals.remove(allocation) }
                    else allocation.completeExceptionally(failure)
                }
                return
            }
            try {
                carriers.register(carrier, target, audience(session))
                if (closed || !registration.isCurrent() || surface.id.retired || !eligible(player) || player.vehicle != null ||
                    !carrier.addPassenger(player)) end(session)
                allocation.complete(null)
                removals.remove(allocation)
            } catch (failure: Throwable) {
                end(session)
                allocation.completeExceptionally(failure)
                throw failure
            }
        }
    }

    private fun tickSession(session: Session): Unit = session.lock.withLock {
        val player = session.watch.player
        val carrier = session.carrier
        if (!eligible(player) || player.world !== session.registration.world || !carrier.isValid || player.vehicle !== carrier ||
            !dispatcher.owns(carrier)) {
            end(session)
            return
        }
        if (!session.registration.isCurrent() || session.surface.retired) { end(session); return }
        val scene = session.scene
        val current = scene.surfaces.firstOrNull { it.id === session.surface }
        if (current == null) { end(session); return }
        session.pendingExit?.let { pending ->
            if (carriers.canDismount(player)) leave(session, current.pose.toWorld(pending))
            return
        }
        val input = player.currentInput
        val direction = PassengerWalking.viewDirection(player.location.yaw,
            (if (input.isForward) 1 else 0) - (if (input.isBackward) 1 else 0),
            (if (input.isRight) 1 else 0) - (if (input.isLeft) 1 else 0))
        session.yaw = player.location.yaw
        // Following a moving carriage belongs to its render callback. The independent clock
        // only integrates local walking, including when Core suppresses stationary frames.
        if (direction == PassengerPoint(0.0, 0.0, 0.0)) return
        val walk = PassengerWalking.advance(scene.surfaces, current, session.local, direction)
        if (session.ending.get() || closed) return
        if (walk.surface == null) {
            if (carriers.canDismount(player)) leave(session, walk.worldFeet)
            else {
                session.pendingExit = current.pose.toLocal(walk.worldFeet)
                move(session, walk.worldFeet)
            }
            return
        }
        val nextOwner = platforms.owner(walk.surface.id) ?: run { end(session); return }
        session.registration = nextOwner
        session.surface = walk.surface.id
        session.local = walk.local
        move(session, walk.worldFeet)
    }

    /** Like fixed seats: present immediately, then move native authority on its own owner. */
    private fun move(session: Session, feet: PassengerPoint) {
        val target = carrierLocation(session.registration, feet, session.yaw)
        val revision = session.movementRevision.incrementAndGet()
        carriers.render(session.carrier, target, audience(session))
        dispatcher.runForEntity(session.carrier, action = { session.lock.withLock {
            if (closed || session.ending.get() || revision != session.movementRevision.get() ||
                !session.registration.isCurrent() || !session.carrier.isValid) return@withLock
            carriers.syncAuthority(session.carrier, target)
        } }, retired = { end(session) })
    }

    private fun audience(session: Session): Collection<Player> = LinkedHashMap<UUID, Player>().also { audience ->
        viewers[session.surface]?.let { audience.putAll(it) }
        audience[session.watch.player.uniqueId] = session.watch.player
    }.values

    /** Runs on the shared rider/carrier owner. An event veto retains the existing session. */
    private fun leave(session: Session, outsideFeet: PassengerPoint) {
        if (!session.ending.compareAndSet(false, true)) return
        session.movementRevision.incrementAndGet()
        val player = session.watch.player
        val target = carrierLocation(session.registration, outsideFeet, player.location.yaw)
        try {
            carriers.render(session.carrier, target, audience(session))
            if (!carriers.dismountAt(session.carrier, player, target)) {
                session.ending.set(false)
                session.pendingExit = null
                val surface = session.scene.surfaces.first { it.id === session.surface }
                move(session, surface.pose.toWorld(session.local))
                return
            }
            finish(session, alreadyDetached = true)
        } catch (failure: Throwable) {
            if (session.ending.get()) finish(session) else end(session)
            throw failure
        }
    }

    /** Fence first. All later queued ticks see ending=true before any owner cleanup is submitted. */
    private fun end(
        session: Session,
        dismountInProgress: Boolean = false
    ): CompletionStage<Void> = session.lock.withLock {
        if (!session.ending.compareAndSet(false, true)) return session.completion
        session.movementRevision.incrementAndGet()
        return finish(session, dismountInProgress = dismountInProgress)
    }

    private fun finish(
        session: Session,
        alreadyDetached: Boolean = false,
        dismountInProgress: Boolean = false
    ): CompletionStage<Void> {
        val completion = session.completion
        removals.add(completion)
        fun cleanup(): CompletionStage<Void> {
            val detach = if (alreadyDetached) CompletableFuture.completedFuture(null)
                else dispatcher.runForEntityCompletion(session.carrier) { session.carrier.eject() }
            // Continue resource cleanup even when detaching failed, but do not destroy the virtual
            // mount or native carrier until the detach attempt has actually finished on its owner.
            return detach.handle { _, failure -> failure }.thenCompose { detachFailure ->
                val steps = DestructionSteps()
                if (detachFailure != null) steps.await { CompletableFuture.failedFuture(detachFailure) }
                steps.action { carriers.release(session.carrier) }
                steps.await { dispatcher.removeEntityWithPassengers(session.carrier) }
                steps.finish()
            }
        }
        val result = try {
            if (dismountInProgress) {
                // The accepted event is inside vanilla stopRiding. Let it return before eject/remove
                // to avoid reentering the same dismount; the session is already fenced above.
                val deferred = CompletableFuture<Void>()
                val task = dispatcher.runForEntityLater(session.carrier, 1L, action = {
                    try {
                        cleanup().whenComplete { _, failure ->
                            if (failure == null) deferred.complete(null) else deferred.completeExceptionally(failure)
                        }
                    } catch (failure: Throwable) { deferred.completeExceptionally(failure) }
                }, retired = {
                    carriers.release(session.carrier)
                    deferred.completeExceptionally(IllegalStateException("Passenger carrier retired before dismount cleanup"))
                })
                if (task == null) deferred.completeExceptionally(IllegalStateException("Passenger dismount cleanup was rejected"))
                deferred
            } else cleanup()
        } catch (failure: Throwable) { CompletableFuture.failedFuture<Void>(failure) }
        result.whenComplete { _, failure ->
            sessions.remove(session.watch.player.uniqueId, session)
            stopIfUnused(session.watch)
            if (failure == null) { completion.complete(null); removals.remove(completion) }
            else {
                completion.completeExceptionally(failure)
                plugin.logger.log(Level.SEVERE, "Passenger carrier cleanup failed for ${session.watch.player.uniqueId}", failure)
            }
        }
        return completion
    }

    private fun stopIfUnused(watch: Watch): Unit = synchronized(lifecycle) {
        if (watch.candidates.isNotEmpty() || sessions.containsKey(watch.player.uniqueId)) return
        if (watches.remove(watch.player.uniqueId, watch)) watch.task?.cancel()
    }

    private fun forget(watch: Watch) {
        val active = synchronized(lifecycle) {
            watch.candidates.clear()
            viewers.values.forEach { it.remove(watch.player.uniqueId) }
            watches.remove(watch.player.uniqueId, watch)
            watch.task?.cancel()
            sessions[watch.player.uniqueId]
        }
        active?.let { end(it) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onManualDismount(event: EntityDismountEvent) {
        val player = event.entity as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (event.dismounted === session.carrier && !session.ending.get() && event.isCancellable && player.currentInput.isSneak)
            event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDismount(event: EntityDismountEvent) {
        val session = sessions[event.entity.uniqueId] ?: return
        if (event.dismounted === session.carrier && !session.ending.get()) end(session, dismountInProgress = true)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) { watches[event.player.uniqueId]?.let(::forget) }
    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) { watches[event.player.uniqueId]?.let(::forget) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) { watches[event.entity.uniqueId]?.let(::forget) }
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { watches[event.player.uniqueId]?.let(::forget) }

    private fun eligible(player: Player): Boolean =
        player.isOnline && player.isValid && !player.isDead && player.gameMode != GameMode.SPECTATOR

    private fun carrierLocation(registration: PassengerCarPlatforms, feet: PassengerPoint, yaw: Float) =
        Location(registration.world, feet.x, feet.y + PASSENGER_CARRIER_LIFT, feet.z, yaw, 0.0f)

    private class Watch(val player: Player) {
        val candidates = ConcurrentHashMap.newKeySet<PassengerSurfaceSource>()
        @Volatile var task: ScheduledTask? = null
    }
    private class Session(
        val watch: Watch,
        @Volatile var registration: PassengerCarPlatforms,
        var scene: PassengerScene,
        @Volatile var surface: PassengerSurfaceSource,
        var local: PassengerPoint,
        var yaw: Float,
        val carrier: ArmorStand
    ) {
        val lock = ReentrantLock()
        val ending = AtomicBoolean()
        val movementRevision = AtomicLong()
        var pendingExit: PassengerPoint? = null
        val completion = CompletableFuture<Void>()
    }
}

private fun Location.point() = PassengerPoint(x, y, z)
