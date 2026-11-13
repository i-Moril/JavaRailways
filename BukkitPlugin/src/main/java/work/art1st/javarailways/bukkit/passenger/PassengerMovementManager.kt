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
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

    fun retire(registration: PassengerCarPlatforms): CompletionStage<Void> = synchronized(lifecycle) {
        platforms.remove(registration)
        val removed = registration.parts.map { it.source }.toSet()
        removed.forEach { viewers.remove(it) }
        watches.values.forEach { watch -> watch.candidates.removeAll(removed); stopIfUnused(watch) }
        val steps = DestructionSteps()
        sessions.values.filter { it.registration === registration || it.surface in removed }.forEach { session ->
            steps.await { end(session) }
        }
        return steps.finish()
    }

    fun close(): CompletionStage<Void> = synchronized(lifecycle) {
        closed = true
        HandlerList.unregisterAll(this)
        watches.values.forEach { it.task?.cancel() }
        platforms.clear()
        viewers.clear()
        val steps = DestructionSteps()
        sessions.values.forEach { session -> steps.await { end(session) } }
        removals.toList().forEach { pending -> steps.await { pending } }
        watches.clear()
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
                val contact = surface.contact(feet) ?: continue
                val owner = platforms.owner(surface.id) ?: continue
                mount(watch, owner, surface, contact)
                return
            }
        }
    }

    private fun mount(watch: Watch, registration: PassengerCarPlatforms, surface: PassengerSurface, local: PassengerPoint): Unit = synchronized(lifecycle) {
        if (closed || !registration.isCurrent() || surface.id.retired) return
        val player = watch.player
        val feet = surface.pose.toWorld(local)
        val target = carrierLocation(registration, feet, player.location.yaw)
        // As with fixed seats, never mutate a carrier or mount across a Folia owner boundary.
        if (!dispatcher.owns(target)) return
        val carrier = carriers.spawn(target)
        val session = Session(watch, registration, surface.id, local, carrier)
        sessions[player.uniqueId] = session
        try {
            carriers.register(carrier, target, audience(session))
            if (closed || !registration.isCurrent() || surface.id.retired || !eligible(player) || player.vehicle != null ||
                !carrier.addPassenger(player)) {
                end(session)
            }
        } catch (failure: Throwable) {
            end(session)
            throw failure
        }
    }

    private fun tickSession(session: Session) {
        val player = session.watch.player
        val carrier = session.carrier
        if (!eligible(player) || player.world !== session.registration.world || !carrier.isValid || player.vehicle !== carrier ||
            !dispatcher.owns(carrier)) {
            end(session)
            return
        }
        val scene = platforms.capture(session.registration)
        val current = scene?.surfaces?.firstOrNull { it.id === session.surface }
        if (current == null) { end(session); return }
        val input = player.currentInput
        val direction = PassengerWalking.viewDirection(player.location.yaw,
            (if (input.isForward) 1 else 0) - (if (input.isBackward) 1 else 0),
            (if (input.isRight) 1 else 0) - (if (input.isLeft) 1 else 0))
        val walk = PassengerWalking.advance(scene.surfaces, current, session.local, direction)
        if (session.ending.get() || closed) return
        if (walk.surface == null) {
            // First move the passenger to the outside foot position while still mounted, then
            // dismount synchronously. No delayed teleport can undo a subsequent external teleport.
            carriers.move(carrier, carrierLocation(session.registration, walk.worldFeet, player.location.yaw), audience(session))
            end(session, walk.worldFeet)
            return
        }
        val nextOwner = platforms.owner(walk.surface.id) ?: run { end(session); return }
        session.registration = nextOwner
        session.surface = walk.surface.id
        session.local = walk.local
        carriers.move(carrier, carrierLocation(nextOwner, walk.worldFeet, player.location.yaw), audience(session))
    }

    private fun audience(session: Session): Collection<Player> = LinkedHashMap<UUID, Player>().also { audience ->
        viewers[session.surface]?.let { audience.putAll(it) }
        audience[session.watch.player.uniqueId] = session.watch.player
    }.values

    /** Fence first. All later queued ticks see ending=true before any owner cleanup is submitted. */
    private fun end(
        session: Session,
        outsideFeet: PassengerPoint? = null,
        dismountInProgress: Boolean = false
    ): CompletionStage<Void> {
        if (!session.ending.compareAndSet(false, true)) return session.completion
        val completion = session.completion
        removals.add(completion)
        fun cleanup(): CompletionStage<Void> {
            val detach = dispatcher.runForEntityCompletion(session.carrier) {
                val player = session.watch.player
                if (outsideFeet != null && dispatcher.owns(player) && player.vehicle === session.carrier) {
                    carriers.dismountAt(session.carrier, player, Location(session.registration.world,
                        outsideFeet.x, outsideFeet.y, outsideFeet.z, player.location.yaw, player.location.pitch))
                } else {
                    session.carrier.eject()
                }
            }
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

    private fun forget(watch: Watch): Unit = synchronized(lifecycle) {
        watch.candidates.clear()
        viewers.values.forEach { it.remove(watch.player.uniqueId) }
        sessions[watch.player.uniqueId]?.let { end(it) }
        watches.remove(watch.player.uniqueId, watch)
        watch.task?.cancel()
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
        @Volatile var surface: PassengerSurfaceSource,
        var local: PassengerPoint,
        val carrier: ArmorStand
    ) {
        val ending = AtomicBoolean()
        val completion = CompletableFuture<Void>()
    }
}

private fun Location.point() = PassengerPoint(x, y, z)
