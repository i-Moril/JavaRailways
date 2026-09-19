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
    private val latestByWorld = linkedMapOf<UUID, RailwayObservation>()
    private val registrations = linkedMapOf<Long, Registration>()
    private var closed = false

    override fun latest(worldId: UUID): Optional<RailwayObservation> = synchronized(lock) {
        Optional.ofNullable(latestByWorld[worldId])
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

    /** Publishes at most once for a completed world epoch and invokes listeners without a queue. */
    fun publish(worldId: UUID, frame: RailwayWorldFrameSnapshot, completedPhysicsEpoch: Long): Boolean {
        require(frame.physicsEpoch == completedPhysicsEpoch) {
            "world frame epoch ${frame.physicsEpoch} does not match completed ticket $completedPhysicsEpoch"
        }
        val observation = RailwayObservation.fromCore(worldId.toString(), frame)
        val listeners = synchronized(lock) {
            if (closed) return false
            val previous = latestByWorld[worldId]
            require(previous == null || previous.stamp.physicsEpoch <= observation.stamp.physicsEpoch) {
                "observation epoch moved backwards for world $worldId"
            }
            if (previous?.stamp?.physicsEpoch == observation.stamp.physicsEpoch) {
                require(previous.stamp == observation.stamp) {
                    "one physics epoch cannot be published with multiple topology revisions"
                }
                return false
            }
            latestByWorld[worldId] = observation
            registrations.values.filter { it.worldId == worldId }.sortedBy(Registration::id)
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
        return true
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
