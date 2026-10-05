package work.art1st.javarailways.simplesignals.authoring

import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.entity.Player
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import java.util.UUID
import kotlin.math.ceil

internal enum class PreviewParticleKind { PATH, DIRECTION, ENTRY, REQUESTED_EXIT, ACTUAL_END, WARNING }
internal data class PreviewParticle(val x: Double, val y: Double, val z: Double, val kind: PreviewParticleKind)

/** All geometry is prepared on the worker. The owner sends bounded rotating batches of these values. */
internal class RoutePreviewDrawing(points: Collection<PreviewParticle>, val connected: Boolean,
    val unavailableGeometry: Set<UUID> = emptySet()) {
    val points: List<PreviewParticle> = java.util.List.copyOf(points)
    private val markers = this.points.filter { it.kind in setOf(PreviewParticleKind.ENTRY, PreviewParticleKind.REQUESTED_EXIT, PreviewParticleKind.ACTUAL_END) }
    private val path = this.points.filter { it.kind !in setOf(PreviewParticleKind.ENTRY, PreviewParticleKind.REQUESTED_EXIT, PreviewParticleKind.ACTUAL_END) }
    val batched: Boolean get() = points.size > BUDGET

    fun batch(offset: Int): List<PreviewParticle> {
        if (points.isEmpty()) return emptyList()
        // Keep endpoints visible in every batch, including the actual end of a failed prefix.
        val count = minOf(BUDGET - markers.size, path.size)
        return markers + (0 until count).map { path[(offset + it) % path.size] }
    }

    fun nextOffset(offset: Int): Int = if (path.isEmpty()) 0 else (offset + minOf(BUDGET - markers.size, path.size)) % path.size

    fun draw(player: Player, offset: Int) {
        batch(offset).forEach { point ->
            val color = when (point.kind) {
                PreviewParticleKind.PATH -> if (connected) Color.LIME else Color.ORANGE
                PreviewParticleKind.DIRECTION -> Color.WHITE
                PreviewParticleKind.ENTRY -> Color.AQUA
                PreviewParticleKind.REQUESTED_EXIT -> Color.YELLOW
                PreviewParticleKind.ACTUAL_END -> if (connected) Color.LIME else Color.RED
                PreviewParticleKind.WARNING -> Color.ORANGE
            }
            player.spawnParticle(Particle.DUST, point.x, point.y, point.z, 1,
                0.0, 0.0, 0.0, 0.0, Particle.DustOptions(color, 1.0f))
        }
    }

    companion object {
        const val BUDGET = 512
        const val REFRESH_TICKS = 20L
        fun warnings(boundaries: Collection<ProtectionBoundaryDefinition>, findCurve: (UUID) -> Curve?): RoutePreviewDrawing {
            val points = arrayListOf<PreviewParticle>()
            val missing = linkedSetOf<UUID>()
            val curves = hashMapOf<UUID, Curve?>()
            for (boundary in boundaries) {
                val id = boundary.position.railId
                val curve = curves.getOrPut(id) { findCurve(id) }
                if (curve == null || boundary.position.arcLengthInAbMeters > curve.totalArcLength) { missing += id; continue }
                val position = curve.getPointAt(boundary.position.arcLengthInAbMeters).position
                // Upright exclamation mark: separated dot + vertical stroke. Not a direction arrow.
                points += PreviewParticle(position.x(), position.y() + 0.8, position.z(), PreviewParticleKind.WARNING)
                repeat(6) { points += PreviewParticle(position.x(), position.y() + 1.15 + it * 0.15, position.z(), PreviewParticleKind.WARNING) }
            }
            return RoutePreviewDrawing(points, true, java.util.Set.copyOf(missing))
        }

        fun prepare(path: CurrentRoutePath, findCurve: (UUID) -> Curve?): RoutePreviewDrawing {
            val points = arrayListOf<PreviewParticle>()
            val curves = hashMapOf<UUID, Curve?>()
            val missing = linkedSetOf<UUID>()
            fun curve(id: UUID): Curve? = if (curves.containsKey(id)) curves[id] else findCurve(id).also {
                curves[id] = it
                if (it == null) missing += id
            }
            path.traversals.forEach { span ->
                val geometry = curve(span.railId) ?: return@forEach
                val distance = span.upperAbMeters - span.lowerAbMeters
                val steps = maxOf(1, ceil(distance / 2.0).toInt())
                for (step in 0..steps) {
                    val ab = span.travelStartAbMeters + (span.travelEndAbMeters - span.travelStartAbMeters) * step / steps
                    val p = geometry.getPointAt(ab).position
                    points += PreviewParticle(p.x(), p.y() + 0.15, p.z(), PreviewParticleKind.PATH)
                }
                val arrows = maxOf(1, ceil(distance / 8.0).toInt())
                for (index in 0 until arrows) {
                    val ab = span.travelStartAbMeters + (span.travelEndAbMeters - span.travelStartAbMeters) * (index + 0.5) / arrows
                    val p = geometry.getPointAt(ab)
                    val sign = if (span.facing == ViewDirection.AB) 1.0 else -1.0
                    val tangent = p.tangent
                    val normal = p.normal
                    val side = org.joml.Vector3d(tangent).cross(normal).normalize()
                    for (arm in listOf(-1.0, 1.0)) for (step in 0..3) {
                        val back = step * 0.18
                        points += PreviewParticle(p.position.x() - sign * tangent.x() * back + side.x() * back * arm,
                            p.position.y() + 0.25 - sign * tangent.y() * back + side.y() * back * arm,
                            p.position.z() - sign * tangent.z() * back + side.z() * back * arm, PreviewParticleKind.DIRECTION)
                    }
                }
            }
            fun mark(position: RailPositionSnapshot, kind: PreviewParticleKind, height: Double) {
                val geometry = curve(position.railId) ?: return
                // A missing/deleted or shortened rail cannot have a meaningful old attachment point.
                if (position.arcLengthInAbMeters > geometry.totalArcLength) { missing += position.railId; return }
                val p = geometry.getPointAt(position.arcLengthInAbMeters).position
                repeat(5) { points += PreviewParticle(p.x(), p.y() + height + it * 0.15, p.z(), kind) }
            }
            mark(path.entry.position, PreviewParticleKind.ENTRY, 0.3)
            mark(path.requestedExit.position, PreviewParticleKind.REQUESTED_EXIT, 0.3)
            mark(path.reached, PreviewParticleKind.ACTUAL_END, 1.2)
            return RoutePreviewDrawing(points, path.end == CurrentRouteEnd.REACHED_EXIT, java.util.Set.copyOf(missing))
        }
    }
}
