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

    /** Publish only accepted carriage geometry; walking remains owned by the player clock. */
    fun present(registration: PassengerCarPlatforms, frame: TrainFrameSnapshot) {
        if (!closed) platforms.present(registration, frame)
    }

    /** Capture each membership and local point together, before splitting a train into regions. */
    fun capture(world: org.bukkit.World): Map<UUID, List<Presentation>> = sessions.values.mapNotNull { session ->
        val state = session.state
        if (closed || !session.ready || session.ending.get() || state.registration.world !== world ||
            !state.registration.isCurrent()) null else Presentation(session, state)
    }.groupBy { it.state.registration.train.publicationStreamId }

    /** Called inside the carriage's bundle, using the exact geometry accepted by that carriage. */
    fun render(presentation: Presentation, frame: TrainFrameSnapshot, flushed: CompletionStage<Void>) {
        val session = presentation.session
        val state = presentation.state
        val scene = platforms.capture(state.registration)?.takeIf { it.frame === frame } ?: return
        val surface = scene.surfaces.firstOrNull { it.id === state.surface } ?: return
        val carrier = session.carrier ?: return
        val revision = synchronized(lifecycle) {
            if (closed || session.ending.get() || !state.registration.isCurrent() || state.surface.retired) return
            session.flushed = flushed
            session.movementRevision.incrementAndGet()
        }
        val target = carrierLocation(state.registration, surface.pose.toWorld(state.exit ?: state.local), state.yaw)
        try { carriers.render(carrier, target, audience(session, state.surface)) }
        catch (failure: Throwable) { end(session); throw failure }
        // Native sync, dismount and audience reconciliation can re-enter model code. Run them
        // after this actual bundle has been submitted, without holding any session/model lock.
        flushed.whenComplete { _, failure ->
            if (failure != null) { end(session); return@whenComplete }
            val accepted = dispatcher.runForEntity(carrier, action = native@{
                if (closed || session.ending.get() || revision != session.movementRevision.get() ||
                    !state.registration.isCurrent() || !carrier.isValid) return@native
                val player = session.watch.player
                if (state.exit != null && session.state === state && carriers.canDismount(player)) {
                    // Fence reentrant dismount events while preserving an actual event veto.
                    session.detaching = true
                    try {
                        if (carriers.dismountAt(carrier, player, target)) end(session, alreadyDetached = true)
                        else session.state = state.copy(exit = null)
                    } finally { session.detaching = false }
                } else carriers.syncAuthority(carrier, target)
            }, retired = { end(session) })
            if (!accepted) end(session)
        }
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
            sessions.values.filter { it.state.registration === registration || it.state.surface in removed }
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
                mount(watch, registration, surface, contact)
                return
            }
        }
    }

    private fun mount(watch: Watch, registration: PassengerCarPlatforms, surface: PassengerSurface, local: PassengerPoint) {
        val player = watch.player
        val target = carrierLocation(registration, surface.pose.toWorld(local), player.location.yaw)
        if (!dispatcher.owns(target)) return
        val session = Session(watch, WalkingState(registration, surface.id, local, player.location.yaw))
        synchronized(lifecycle) {
            if (closed || !registration.isCurrent() || surface.id.retired || watches[player.uniqueId] !== watch) return
            sessions[player.uniqueId] = session
        }
        try {
            val carrier = carriers.spawn(target)
            session.carrier = carrier
            if (!closed && !session.ending.get() && registration.isCurrent() && !surface.id.retired &&
                eligible(player) && player.vehicle == null) {
                carriers.register(carrier, target, audience(session, surface.id))
                session.ready = carrier.addPassenger(player)
            }
        } catch (failure: Throwable) {
            end(session)
            throw failure
        } finally {
            session.allocated.complete(null)
            if (!session.ready || closed || session.ending.get()) end(session)
        }
    }

    /** Player ownership integrates walking; a stopped carriage needs only this rider updated. */
    private fun tickSession(session: Session) {
        if (!session.ready) return
        val player = session.watch.player
        val carrier = session.carrier ?: return
        val state = session.state
        if (!eligible(player) || player.world !== state.registration.world || !carrier.isValid ||
            player.vehicle !== carrier || !dispatcher.owns(carrier) || !state.registration.isCurrent() || state.surface.retired) {
            end(session)
            return
        }
        val scene = platforms.capture(state.registration) ?: return
        val current = scene.surfaces.firstOrNull { it.id === state.surface } ?: run { end(session); return }
        if (state.exit != null) {
            if (scene.frame.speedMetersPerSecond == 0.0 && carriers.canDismount(player))
                renderStationary(session, state, scene)
            return
        }
        val input = player.currentInput
        val yaw = player.location.yaw
        val direction = PassengerWalking.viewDirection(yaw,
            (if (input.isForward) 1 else 0) - (if (input.isBackward) 1 else 0),
            (if (input.isRight) 1 else 0) - (if (input.isLeft) 1 else 0))
        if (direction == PassengerPoint(0.0, 0.0, 0.0)) {
            if (state.yaw != yaw) session.state = state.copy(yaw = yaw)
            return
        }
        val walk = PassengerWalking.advance(scene.surfaces, current, state.local, direction)
        val next = if (walk.surface == null) state.copy(yaw = yaw, exit = current.pose.toLocal(walk.worldFeet))
        else {
            val nextOwner = platforms.owner(walk.surface.id) ?: run { end(session); return }
            WalkingState(nextOwner, walk.surface.id, walk.local, yaw)
        }
        if (!session.ending.get() && !closed) {
            session.state = next
            val accepted = if (next.registration === state.registration) scene else platforms.capture(next.registration)
            if (accepted?.frame?.speedMetersPerSecond == 0.0) renderStationary(session, next, accepted)
        }
    }

    /** No train submission or model update: use the already accepted platform and common carrier path. */
    private fun renderStationary(session: Session, state: WalkingState, scene: PassengerScene) {
        val flushed = CompletableFuture<Void>()
        try {
            render(Presentation(session, state), scene.frame, flushed)
            flushed.complete(null)
        } catch (failure: Throwable) {
            flushed.completeExceptionally(failure)
            throw failure
        }
    }

    private fun audience(session: Session, surface: PassengerSurfaceSource): Collection<Player> =
        LinkedHashMap<UUID, Player>().also { audience ->
            viewers[surface]?.let { audience.putAll(it) }
            audience[session.watch.player.uniqueId] = session.watch.player
        }.values

    /** Fence immediately; asynchronously await allocation and any bundle already admitted. */
    private fun end(
        session: Session,
        alreadyDetached: Boolean = false,
        dismountInProgress: Boolean = false
    ): CompletionStage<Void> {
        val flushed = synchronized(lifecycle) {
            if (!session.ending.compareAndSet(false, true)) return session.completion
            session.movementRevision.incrementAndGet()
            removals.add(session.completion)
            session.flushed
        }
        fun cleanup(): CompletionStage<Void> {
            val carrier = session.carrier ?: return CompletableFuture.completedFuture(null)
            val detach = if (alreadyDetached) CompletableFuture.completedFuture(null)
                else dispatcher.runForEntityCompletion(carrier) { carrier.eject() }
            return detach.handle { _, failure -> failure }.thenCompose { detachFailure ->
                val steps = DestructionSteps()
                if (detachFailure != null) steps.await { CompletableFuture.failedFuture(detachFailure) }
                steps.action { carriers.release(carrier) }
                steps.await { dispatcher.removeEntityWithPassengers(carrier) }
                steps.finish()
            }
        }
        val result = session.allocated.thenCompose {
            flushed.handle { _, failure -> failure }.thenCompose { flushFailure ->
                val removal = if (dismountInProgress) {
                    // Let vanilla's accepted dismount event return before eject/remove.
                    val deferred = CompletableFuture<Void>()
                    val carrier = checkNotNull(session.carrier)
                    val task = dispatcher.runForEntityLater(carrier, 1L, action = {
                        try { cleanup().whenComplete { _, failure ->
                            if (failure == null) deferred.complete(null) else deferred.completeExceptionally(failure)
                        } } catch (failure: Throwable) { deferred.completeExceptionally(failure) }
                    }, retired = {
                        carriers.release(carrier)
                        deferred.completeExceptionally(IllegalStateException("Passenger carrier retired before dismount cleanup"))
                    })
                    if (task == null) deferred.completeExceptionally(IllegalStateException("Passenger dismount cleanup was rejected"))
                    deferred
                } else cleanup()
                removal.handle { _, removalFailure ->
                    if (flushFailure != null) {
                        if (removalFailure != null) flushFailure.addSuppressed(removalFailure)
                        throw java.util.concurrent.CompletionException(flushFailure)
                    }
                    if (removalFailure != null) throw java.util.concurrent.CompletionException(removalFailure)
                    null
                }
            }
        }
        result.whenComplete { _, failure ->
            sessions.remove(session.watch.player.uniqueId, session)
            stopIfUnused(session.watch)
            if (failure == null) { session.completion.complete(null); removals.remove(session.completion) }
            else {
                session.completion.completeExceptionally(failure)
                plugin.logger.log(Level.SEVERE, "Passenger carrier cleanup failed for ${session.watch.player.uniqueId}", failure)
            }
        }
        return session.completion
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
        if (event.dismounted === session.carrier && !session.ending.get() && !session.detaching && event.isCancellable && player.currentInput.isSneak)
            event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDismount(event: EntityDismountEvent) {
        val session = sessions[event.entity.uniqueId] ?: return
        if (event.dismounted === session.carrier && !session.ending.get() && !session.detaching) end(session, dismountInProgress = true)
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

    internal class Watch(val player: Player) {
        val candidates = ConcurrentHashMap.newKeySet<PassengerSurfaceSource>()
        @Volatile var task: ScheduledTask? = null
    }
    internal data class WalkingState(
        val registration: PassengerCarPlatforms,
        val surface: PassengerSurfaceSource,
        val local: PassengerPoint,
        val yaw: Float,
        val exit: PassengerPoint? = null
    )
    internal class Presentation(val session: Session, val state: WalkingState)
    internal class Session(val watch: Watch, initial: WalkingState) {
        @Volatile var state = initial
        @Volatile var carrier: ArmorStand? = null
        @Volatile var ready = false
        var detaching = false // Only the rider/carrier owner handles native dismount events.
        val ending = AtomicBoolean()
        val movementRevision = AtomicLong()
        val allocated = CompletableFuture<Void>()
        var flushed: CompletionStage<Void> = CompletableFuture.completedFuture(null) // lifecycle admission lock
        val completion = CompletableFuture<Void>()
    }
}

private fun Location.point() = PassengerPoint(x, y, z)
