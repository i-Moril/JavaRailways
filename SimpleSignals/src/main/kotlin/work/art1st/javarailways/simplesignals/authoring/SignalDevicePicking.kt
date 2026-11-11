package work.art1st.javarailways.simplesignals.authoring

import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.model.ModelPresentationService
import work.art1st.javarailways.bukkit.api.model.ModelRay
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.math.floor
import kotlin.math.min

/** Capture Bukkit sight and solid obstruction on the player owner; query prepared bounds on a worker. */
internal fun pickSignalDevice(plugin: Plugin, player: Player, worker: (() -> Unit) -> Unit): CompletionStage<Optional<String>> {
    val eye = player.eyeLocation
    val direction = eye.direction.normalize()
    val world = player.world
    val worldId = world.uid
    // Collision shapes can read neighboring blocks. Require the neighboring chunks too, rather
    // than treating a loaded chunk as permission to read another Folia region.
    val ownedLimit = ownedSignalRayDistance(eye.x, eye.z, direction.x, direction.z, 64.0) { x, z ->
        Bukkit.isOwnedByCurrentRegion(world, x, z, 1)
    }
    if (ownedLimit <= 0.0) return CompletableFuture.completedFuture(Optional.empty())
    val limit = world.rayTraceBlocks(eye, direction, ownedLimit, FluidCollisionMode.NEVER, true)
        ?.hitPosition?.distance(eye.toVector()) ?: ownedLimit
    if (limit <= 0.0) return CompletableFuture.completedFuture(Optional.empty())
    val ray = ModelRay(eye.x, eye.y, eye.z, direction.x, direction.y, direction.z, limit)
    val service = requireNotNull(plugin.server.servicesManager.load(ModelPresentationService::class.java))
    val result = CompletableFuture<Optional<String>>()
    try { worker {
        try { result.complete(service.pick(plugin, worldId, ray).map { it.objectId() }) }
        catch (failure: Exception) { result.completeExceptionally(failure) }
    } } catch (failure: Exception) { result.completeExceptionally(failure) }
    return result
}

/** Walk chunk boundaries without reading world data; stop before the first unowned chunk. */
internal fun ownedSignalRayDistance(x: Double, z: Double, dx: Double, dz: Double,
    maximum: Double, owns: (Int, Int) -> Boolean): Double {
    var chunkX = floor(x / 16.0).toInt()
    var chunkZ = floor(z / 16.0).toInt()
    if (!owns(chunkX, chunkZ)) return 0.0
    val stepX = if (dx > 0.0) 1 else if (dx < 0.0) -1 else 0
    val stepZ = if (dz > 0.0) 1 else if (dz < 0.0) -1 else 0
    val deltaX = if (stepX == 0) Double.POSITIVE_INFINITY else 16.0 / kotlin.math.abs(dx)
    val deltaZ = if (stepZ == 0) Double.POSITIVE_INFINITY else 16.0 / kotlin.math.abs(dz)
    var nextX = if (stepX == 0) Double.POSITIVE_INFINITY
        else ((if (stepX > 0) chunkX + 1 else chunkX) * 16.0 - x) / dx
    var nextZ = if (stepZ == 0) Double.POSITIVE_INFINITY
        else ((if (stepZ > 0) chunkZ + 1 else chunkZ) * 16.0 - z) / dz
    while (min(nextX, nextZ) <= maximum) {
        val distance = min(nextX, nextZ)
        val crossesX = nextX == distance
        val crossesZ = nextZ == distance
        // At a corner the block ray may touch either side before entering the diagonal chunk.
        if ((crossesX && !owns(chunkX + stepX, chunkZ)) ||
            (crossesZ && !owns(chunkX, chunkZ + stepZ)) ||
            (crossesX && crossesZ && !owns(chunkX + stepX, chunkZ + stepZ)))
            return (distance - 1e-5).coerceAtLeast(0.0)
        if (crossesX) { chunkX += stepX; nextX += deltaX }
        if (crossesZ) { chunkZ += stepZ; nextZ += deltaZ }
    }
    return maximum
}

internal fun PlacedSignalChoice.renderObjectId(): String = when (kind) {
    PlacedSignalKind.FIXED_BLOCK -> "fixed-block-signal/$id"
    PlacedSignalKind.PROTECTION -> "protection-signal/$id"
    PlacedSignalKind.ATTACHED -> "attached-signal/$id"
    PlacedSignalKind.INDICATOR -> "route-indicator/$id"
}
