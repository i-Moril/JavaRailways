package work.art1st.javarailways.bukkit.passenger

import org.bukkit.World
import org.joml.Vector3d
import work.art1st.javarailways.bukkit.extension.train.modelTransform
import work.art1st.javarailways.bukkit.render.model.CompactTransform
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.train.CoreCar
import work.art1st.javarailways.core.physics.train.CoreTrain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One platform model's lifetime and copied root-local pose. No model state is exposed. */
internal class PassengerSurfaceSource(val halfX: Double, val halfZ: Double) {
    @Volatile var retired = false
        private set
    private val pose = AtomicReference<PassengerPose?>()
    fun publish(transform: CompactTransform) {
        if (!retired) pose.set(PassengerPose.capture(transform))
    }
    fun current(): PassengerPose? = if (retired) null else pose.get()
    fun retire() { retired = true; pose.set(null) }
}

internal data class PassengerPlatformPart(val source: PassengerSurfaceSource, val bogieIndex: Int?)

internal class PassengerCarPlatforms(
    val car: CoreCar,
    val train: CoreTrain,
    val world: World,
    val floorHeight: Double,
    val parts: List<PassengerPlatformPart>
) {
    @Volatile var retired = false
        private set
    fun retire() { retired = true }
    fun isCurrent(): Boolean = !retired && car.isOwnedByPublicationStream(train.publicationStreamId)
}

internal data class PassengerScene(val frame: TrainFrameSnapshot, val surfaces: List<PassengerSurface>)

/** Holds complete scenes accepted by each carriage's presentation, never the latest physics pose. */
internal class PassengerPlatformRegistry {
    private val trains = ConcurrentHashMap<java.util.UUID, ConcurrentHashMap<String, PassengerCarPlatforms>>()
    private val sources = ConcurrentHashMap<PassengerSurfaceSource, PassengerCarPlatforms>()
    private val presented = ConcurrentHashMap<PassengerCarPlatforms, PassengerScene>()

    @Synchronized
    fun register(registration: PassengerCarPlatforms) {
        trains.computeIfAbsent(registration.train.publicationStreamId) { ConcurrentHashMap() }[registration.car.id] = registration
        registration.parts.forEach { sources[it.source] = registration }
    }

    fun owner(source: PassengerSurfaceSource): PassengerCarPlatforms? = sources[source]?.takeIf { it.isCurrent() }

    @Synchronized
    fun remove(registration: PassengerCarPlatforms) {
        registration.retire()
        val entries = trains[registration.train.publicationStreamId]
        entries?.remove(registration.car.id, registration)
        // Keep the empty stream map only until its exact final registration is removed.
        if (entries != null && entries.isEmpty()) trains.remove(registration.train.publicationStreamId, entries)
        registration.parts.forEach { sources.remove(it.source, registration) }
        presented.remove(registration)
    }

    fun capture(registration: PassengerCarPlatforms): PassengerScene? =
        presented[registration]?.takeIf { registration.isCurrent() }

    /** Called after this carriage has applied its model transforms, inside its packet bundle. */
    @Synchronized
    fun present(registration: PassengerCarPlatforms, frame: TrainFrameSnapshot): PassengerScene? {
        if (!registration.isCurrent()) return null
        val entries = trains[registration.train.publicationStreamId] ?: return null
        if (entries[registration.car.id] !== registration) return null
        if ((presented[registration]?.frame?.revision ?: -1L) > frame.revision) return null
        val registrations = entries.toMap()
        val surfaces = frame.cars.flatMap { carFrame ->
            val member = registrations[carFrame.carId]?.takeIf { it.isCurrent() } ?: return@flatMap emptyList()
            member.parts.mapNotNull { part ->
                val localPose = part.source.current() ?: return@mapNotNull null
                val root = if (part.bogieIndex == null) carFrame.state.modelTransform()
                    else carFrame.bogies[part.bogieIndex].modelTransform(member.floorHeight)
                val pose = localPose.transform().applyTo(root)
                // Current platform collision uses upright Shulkers riding Chickens. Their top has
                // a WORLD vertical lift and the Chicken's yaw-only rearward attachment offset.
                val forward = pose.rotation.transform(Vector3d(0.0, 0.0, 1.0))
                val yaw = atan2(-forward.x, forward.z)
                val top = Vector3d(pose.position).add(sin(yaw) * CHICKEN_ATTACHMENT_BACK,
                    COLLISION_TOP_HEIGHT, -cos(yaw) * CHICKEN_ATTACHMENT_BACK)
                PassengerSurface(part.source, PassengerPose.capture(CompactTransform(top, pose.rotation)),
                    part.source.halfX, part.source.halfZ)
            }
        }
        return if (registration.isCurrent()) PassengerScene(frame, surfaces).also { presented[registration] = it } else null
    }

    @Synchronized
    fun clear() {
        trains.values.forEach { entries -> entries.values.forEach { it.retire() } }
        sources.clear()
        trains.clear()
        presented.clear()
    }
}

// Vanilla Chicken attachment (0, 0.7, -0.1), closed Shulker height 1, current carrier compensation 0.6875.
internal const val COLLISION_TOP_HEIGHT = 0.7 + 1.0 - 0.6875
private const val CHICKEN_ATTACHMENT_BACK = 0.1
