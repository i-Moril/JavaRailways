package work.art1st.javarailways.bukkit.api.internal

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationListener
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationService
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationSubscription
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

internal class DefaultRailwayObservationService(
    private val logger: Logger
) : RailwayObservationService, AutoCloseable {
    private data class Registration(
        val id: Long,
        val owner: Plugin,
        val worldId: UUID,
        val listener: RailwayObservationListener
    )

    private val lock = Any()
    private val nextRegistrationId = AtomicLong()
    private data class CurrentObservation(
        val frame: RailwayWorldFrameSnapshot,
        val observation: RailwayObservation,
        val lastDeliveredEpoch: Long?
    )
    private val latestByWorld = linkedMapOf<UUID, CurrentObservation>()
    private val registrations = linkedMapOf<Long, Registration>()
    private var closed = false

    override fun latest(worldId: UUID): Optional<RailwayObservation> = synchronized(lock) {
        Optional.ofNullable(latestByWorld[worldId]?.observation)
    }

    override fun subscribe(
        owner: Plugin,
        worldId: UUID,
        listener: RailwayObservationListener
    ): RailwayObservationSubscription {
        check(owner.isEnabled) { "Cannot subscribe disabled plugin ${owner.name}" }
        val registration = synchronized(lock) {
            check(!closed) { "RailwayObservationService is closed" }
            Registration(nextRegistrationId.incrementAndGet(), owner, worldId, listener).also {
                registrations[it.id] = it
            }
        }
        val subscriptionClosed = AtomicBoolean()
        return RailwayObservationSubscription {
            if (subscriptionClosed.compareAndSet(false, true)) {
                synchronized(lock) { registrations.remove(registration.id) }
            }
        }
    }

    override fun unregisterOwner(owner: Plugin) {
        synchronized(lock) {
            registrations.values.removeIf { it.owner === owner }
        }
    }

    /** Updates the current owner view without sending another physical-epoch notification. */
    fun current(worldId: UUID, frame: RailwayWorldFrameSnapshot): RailwayObservation? = synchronized(lock) {
        if (closed) null else currentLocked(worldId, frame).observation
    }

    private fun currentLocked(worldId: UUID, frame: RailwayWorldFrameSnapshot): CurrentObservation {
        val previous = latestByWorld[worldId]
        if (previous?.frame === frame) return previous
        require(previous == null || previous.frame.physicsEpoch <= frame.physicsEpoch) {
            "observation epoch moved backwards for world $worldId"
        }
        return CurrentObservation(
            frame, RailwayObservation.fromCore(worldId.toString(), frame), previous?.lastDeliveredEpoch
        ).also { latestByWorld[worldId] = it }
    }

    /** Delivers at most once per physical epoch; same-epoch owner changes still update the view. */
    fun publish(
        worldId: UUID,
        frame: RailwayWorldFrameSnapshot,
        completedPhysicsEpoch: Long
    ): RailwayObservation? {
        require(frame.physicsEpoch == completedPhysicsEpoch) {
            "world frame epoch ${frame.physicsEpoch} does not match completed ticket $completedPhysicsEpoch"
        }
        val (observation, listeners) = synchronized(lock) {
            if (closed) return null
            val current = currentLocked(worldId, frame)
            if (current.lastDeliveredEpoch == frame.physicsEpoch) return null
            latestByWorld[worldId] = current.copy(lastDeliveredEpoch = frame.physicsEpoch)
            current.observation to registrations.values.filter { it.worldId == worldId }.sortedBy(Registration::id)
        }
        listeners.forEach { registration ->
            try {
                registration.listener.onObservation(worldId, observation)
            } catch (failure: Exception) {
                logger.log(
                    Level.SEVERE,
                    "Railway observation listener from plugin ${registration.owner.name} failed for world $worldId",
                    failure
                )
            }
        }
        return observation
    }

    fun removeWorld(worldId: UUID) {
        synchronized(lock) { latestByWorld.remove(worldId) }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            latestByWorld.clear()
            registrations.clear()
        }
    }
}
