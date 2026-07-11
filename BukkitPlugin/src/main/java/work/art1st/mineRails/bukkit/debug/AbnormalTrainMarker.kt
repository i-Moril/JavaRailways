package work.art1st.mineRails.bukkit.debug

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World as BukkitWorld
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.bukkit.extension.bukkitWorldDataOrNull
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.util.ThreadGuard
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.physics.train.SavedTrainV1
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Visual marker for trains that failed restore/update or are derailed.
 *
 * Rendering can be unavailable for a broken train because car transforms may fail before the
 * Bukkit model tree is built. This marker uses saved/live bogie and train-end positions instead,
 * so operators can still find the problematic consist in-world.
 */
object AbnormalTrainMarker {
    private const val SPAWN_INTERVAL_MS = 750L
    private const val MAX_MARKER_POINTS = 24

    private val barrierData by lazy { Material.BARRIER.createBlockData() }
    private val markers = ConcurrentHashMap<UUID, MutableMap<String, Marker>>()

    private data class Marker(
        val trainId: String,
        var reason: String,
        var staticPositions: List<Vector3d>? = null,
        var lastSpawnMs: Long = 0L
    )

    fun markTrain(train: CoreTrain, reason: String) {
        val bukkitWorld = train.world.bukkitWorldDataOrNull?.bukkitWorld ?: return
        val marker = markersFor(bukkitWorld).getOrPut(train.id) { Marker(train.id, reason) }
        marker.reason = reason
        marker.staticPositions = null
    }

    fun markSavedTrain(coreWorld: CoreWorld, bukkitWorld: BukkitWorld, savedTrain: SavedTrainV1, reason: String) {
        val positions = savedTrain.cars.flatMap { savedCar ->
            listOf(savedCar.bogieA.location, savedCar.bogieB.location).mapNotNull { savedLocation ->
                runCatching {
                    val location = savedLocation.load(coreWorld)
                    Vector3d(location.position)
                }.getOrNull()
            }
        }
        val marker = markersFor(bukkitWorld).getOrPut(savedTrain.id) { Marker(savedTrain.id, reason) }
        marker.reason = reason
        marker.staticPositions = distinctPositions(positions)
    }

    fun clearTrain(bukkitWorld: BukkitWorld, trainId: String) {
        markers[bukkitWorld.uid]?.remove(trainId)
    }

    fun clearWorld(bukkitWorld: BukkitWorld) {
        markers.remove(bukkitWorld.uid)
    }

    fun tick(coreWorld: CoreWorld, bukkitWorld: BukkitWorld, nowMs: Long = System.currentTimeMillis()) {
        ThreadGuard.ensureMainThread("AbnormalTrainMarker.tick")
        coreWorld.allTrains.forEach { train ->
            if (train.isDerailed) {
                val marker = markersFor(bukkitWorld).getOrPut(train.id) { Marker(train.id, "derailed") }
                if (marker.reason != "derailed") marker.reason = "derailed"
                marker.staticPositions = null
            }
        }

        val worldMarkers = markers[bukkitWorld.uid] ?: return
        if (worldMarkers.isEmpty()) return
        val liveById = coreWorld.allTrains.associateBy { it.id }
        val iterator = worldMarkers.iterator()
        while (iterator.hasNext()) {
            val (_, marker) = iterator.next()
            val liveTrain = liveById[marker.trainId]
            if (liveTrain == null && marker.staticPositions == null) {
                iterator.remove()
                continue
            }
            if (nowMs - marker.lastSpawnMs < SPAWN_INTERVAL_MS) continue
            marker.lastSpawnMs = nowMs
            val positions = liveTrain?.let(::trainMarkerPositions) ?: marker.staticPositions.orEmpty()
            spawnMarkers(bukkitWorld, positions)
        }
    }

    private fun trainMarkerPositions(train: CoreTrain): List<Vector3d> {
        val positions = mutableListOf<Vector3d>()
        runCatching { positions.add(Vector3d(train.head.location.position)) }
        runCatching { positions.add(Vector3d(train.tail.location.position)) }
        train.cars.forEach { car ->
            car.bogies.forEach { bogie -> positions.add(Vector3d(bogie.position)) }
            val carPosition = car.position
            if (carPosition.lengthSquared() > 1e-9) positions.add(Vector3d(carPosition))
        }
        return distinctPositions(positions)
    }

    private fun distinctPositions(positions: List<Vector3d>): List<Vector3d> {
        val seen = HashSet<String>()
        return positions.filter { position ->
            val key = listOf(position.x(), position.y(), position.z())
                .joinToString(":") { value -> "%.1f".format(java.util.Locale.US, value) }
            seen.add(key)
        }.take(MAX_MARKER_POINTS)
    }

    private fun spawnMarkers(bukkitWorld: BukkitWorld, positions: List<Vector3d>) {
        positions.forEach { position ->
            for (offset in MARKER_OFFSETS) {
                val location = Location(
                    bukkitWorld,
                    position.x() + offset.x(),
                    position.y() + 1.0 + offset.y(),
                    position.z() + offset.z()
                )
                bukkitWorld.spawnParticle(
                    Particle.BLOCK_MARKER,
                    location,
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    barrierData,
                    true
                )
            }
        }
    }

    private fun markersFor(bukkitWorld: BukkitWorld): MutableMap<String, Marker> =
        markers.computeIfAbsent(bukkitWorld.uid) { mutableMapOf() }

    private val MARKER_OFFSETS = listOf<Vector3dc>(
        Vector3d(0.0, 0.0, 0.0),
        Vector3d(1.0, 0.0, 0.0),
        Vector3d(-1.0, 0.0, 0.0),
        Vector3d(0.0, 0.0, 1.0),
        Vector3d(0.0, 0.0, -1.0),
        Vector3d(0.0, 1.0, 0.0)
    )
}
