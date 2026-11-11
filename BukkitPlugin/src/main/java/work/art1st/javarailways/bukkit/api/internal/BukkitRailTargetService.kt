package work.art1st.javarailways.bukkit.api.internal

import org.joml.Vector3d
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.api.rail.RailTarget
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.bukkit.logic.WorldManager
import work.art1st.javarailways.bukkit.util.HeavyRailQueries
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

internal class BukkitRailTargetService(
    private val worldManager: () -> WorldManager,
    private val platformDispatcher: () -> PlatformDispatcher,
    private val isRuntimeActive: () -> Boolean = { true }
) : RailTargetService {
    constructor(plugin: BukkitPlugin) : this(
        plugin.worldManager.let { captured -> { captured } },
        plugin.platformDispatcher.let { captured -> { captured } },
        { plugin.isRuntimeActive }
    )

    // Freeze this generation even when the plugin instance is enabled again later.
    private val worlds = worldManager()
    private val dispatcher by lazy(platformDispatcher)
    @Volatile private var closed = false
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<Optional<RailTarget>>>()

    fun close() {
        closed = true
        pending.forEach { it.completeExceptionally(IllegalStateException("JavaRailways rail target service is closed")) }
        pending.clear()
    }

    override fun findCurve(worldId: UUID, railId: UUID): Optional<PrimeCurve> {
        check(!closed && isRuntimeActive()) { "JavaRailways rail target service is not active" }
        val railWorld = worlds.loadedRailWorld(worldId)
            ?: throw IllegalStateException("JavaRailways world $worldId is not loaded")
        return Optional.ofNullable(railWorld.getRail(railId)?.curve)
    }

    override fun findLookedAtRail(
        player: org.bukkit.entity.Player,
        maxDistance: Double,
        hitRadius: Double
    ): CompletionStage<Optional<RailTarget>> {
        require(maxDistance.isFinite() && maxDistance >= 0.0) {
            "maxDistance must be finite and non-negative"
        }
        require(hitRadius.isFinite() && hitRadius >= 0.0) {
            "hitRadius must be finite and non-negative"
        }
        check(!closed && isRuntimeActive()) { "JavaRailways rail target service is not active" }
        check(dispatcher.owns(player)) {
            "findLookedAtRail must be called from the player's owning thread/region"
        }

        val railWorld = worlds.railWorlds[player.world] as? CoreWorld
            ?: return CompletableFuture.completedFuture(Optional.empty())
        val eye = player.eyeLocation
        val origin = Vector3d(eye.x, eye.y, eye.z)
        val direction = Vector3d(eye.direction.x, eye.direction.y, eye.direction.z)
        if (direction.lengthSquared() < 1.0e-12) {
            return CompletableFuture.completedFuture(Optional.empty())
        }
        direction.normalize()
        val candidates = railWorld.getRailsAlongRay(origin, direction, maxDistance, hitRadius).toList()
        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty())
        }
        val candidatesById = candidates.associateBy { it.id }
        val result = CompletableFuture<Optional<RailTarget>>()
        pending.add(result)
        result.whenComplete { _, _ -> pending.remove(result) }
        if (closed) {
            result.completeExceptionally(IllegalStateException("JavaRailways rail target service is closed"))
            return result
        }
        dispatcher.runAsync {
            try {
                check(!closed && isRuntimeActive()) { "JavaRailways rail target service is not active" }
                val match = HeavyRailQueries.findLookedAtRailPoint(
                    candidates,
                    origin,
                    direction,
                    maxDistance,
                    hitRadius
                )
                val target = match?.let { selected ->
                    candidatesById[selected.railId]?.let { rail ->
                        RailTarget(rail, selected.arcLength, selected.distanceToRay)
                    }
                }
                result.complete(Optional.ofNullable(target))
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        return result
    }
}
