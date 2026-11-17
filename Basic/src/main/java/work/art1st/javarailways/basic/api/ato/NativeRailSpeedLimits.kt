package work.art1st.javarailways.basic.api.ato

import work.art1st.javarailways.basic.rail.infrastructureMetadata
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing

/**
 * Native-limit context captured from a live train head, for first-party physical control. Its
 * rail and facing identify a controller's current-limit tracking context; the rail remains a live
 * Core API handle. This is not a published position/frame and does not capture metadata.
 *
 * @property rail original live head rail, whose UUID identifies the tracked native-limit rail.
 * @property facing physical travel direction at the time the head was read.
 */
data class NativeHeadSpeedLimitContext(val rail: Rail, val facing: ViewDirection)

/**
 * Captures the current live head's rail and facing without reading its speed metadata. Call
 * synchronously in the world's allowed simulation/physics context at the original head-read
 * stage. The caller may compare these values with its previous context before invoking
 * [nativeRailSpeedLimit], avoiding an extra metadata sample on an unchanged rail/direction.
 * Does not read a published frame, traverse track, register state, schedule work or persist data.
 * Work is O(1); existing live head getter failures propagate unchanged.
 *
 * @param train live train whose head supplies the tracking context.
 * @return captured rail handle and facing; neither freezes subsequent world changes.
 * @throws IllegalStateException if the head does not provide physical facing.
 */
fun nativeHeadSpeedLimitContext(train: Train): NativeHeadSpeedLimitContext {
    val head = train.headLocation as? RailLocationWithFacing
        ?: error("SimpleATO requires a facing-aware train head location")
    return NativeHeadSpeedLimitContext(head.rail, head.facing)
}

/**
 * Reads this rail's current effective native cap (minimum CURVE/SUBGRADE), in m/s; null means no
 * finite native limit. Reads the existing world-local metadata once, without geometric
 * recalculation, mutation or caching. The returned scalar is detached from later metadata changes.
 *
 * Call synchronously in the rail world's allowed simulation/physics context. The caller owns
 * rail/world lifecycle and sampling order; no frame/epoch currentness is inferred or checked.
 * Metadata lookup is O(1), plus the existing per-type minimum calculation; access failures propagate
 * unchanged. No object registration, resource grant or persistence change is involved.
 *
 * @param rail existing live Core API rail supplying its own world's metadata.
 * @return effective cap in m/s, or null when native limits are absent.
 */
fun nativeRailSpeedLimit(rail: Rail): Double? =
    rail.infrastructureMetadata.speedLimits.effectiveLimitMetersPerSecond()
