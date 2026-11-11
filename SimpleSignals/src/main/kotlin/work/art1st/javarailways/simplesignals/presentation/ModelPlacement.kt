package work.art1st.javarailways.simplesignals.presentation

import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot

/** Plugin-local placement values. The owning binding supplies the world. */
internal sealed interface ModelPlacement {
    /**
     * [position] always measures arc length from A, including when facing BA.
     * [adjustment] stores metre offsets along directed X lateral (up × forward)/Y up/Z forward and a unit local rotation.
     * Its rotation turns the model in place, without rotating those offsets.
     */
    data class RailAttached(
        val position: RailPositionSnapshot,
        val adjustment: ModelPose = ModelPose.IDENTITY,
        val followRailTilt: Boolean = false
    ) : ModelPlacement {
        /**
         * Copy the selected values once, preserving the rail, arc coordinate and facing.
         * A later preset edit/removal cannot affect this instance. Reapplication resolves tilt afresh:
         * player choice, then the selected model/preset default, then upright.
         */
        fun applyPreset(preset: RailPlacementPreset, playerFollowRailTilt: Boolean? = null): RailAttached =
            copy(
                adjustment = preset.adjustment,
                followRailTilt = playerFollowRailTilt ?: preset.followRailTilt ?: false
            )
    }

    /** Absolute world pose; rail offsets and tilt choices have no meaning for ground placement. */
    data class Ground(val pose: ModelPose) : ModelPlacement
}

/** The selected preset's values, with the optional default provided by its model/preset. */
internal data class RailPlacementPreset(
    val adjustment: ModelPose,
    val followRailTilt: Boolean? = null
)
