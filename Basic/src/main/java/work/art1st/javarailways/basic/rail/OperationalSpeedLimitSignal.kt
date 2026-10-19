package work.art1st.javarailways.basic.rail

import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture
import work.art1st.javarailways.core.api.util.Destruction
import org.joml.Vector3d
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.control.signal.Signal
import work.art1st.javarailways.core.physics.rail.RailLocationWithoutFacing

/** Default infrastructure transition; deliberately returns the same speed for every train. */
class OperationalSpeedLimitSignal(
    ref: RailObjectRef,
    location: RailLocationWithoutFacing,
    val appliesTo: ViewDirection,
    val resultingLimits: SpeedLimitMetadata?
) : Signal(ref, location) {
    @Volatile
    private var state = RailObjectLifecycleState.ACTIVE

    override val lifecycleState: RailObjectLifecycleState
        get() = state

    override val velocity = Vector3d()

    override fun appliesTo(direction: ViewDirection): Boolean = direction == appliesTo

    /** Same value for every train; this signal carries no per-train policy. */
    val speedLimitMetersPerSecond: Double?
        get() = resultingLimits?.effectiveLimitMetersPerSecond()

    private val destruction = Destruction()

    override fun destroy(): CompletionStage<Void> = destruction.run {
        state = RailObjectLifecycleState.REMOVED
        CompletableFuture.completedFuture(null)
    }
}
