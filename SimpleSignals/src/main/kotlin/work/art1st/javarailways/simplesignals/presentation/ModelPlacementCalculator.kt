package work.art1st.javarailways.simplesignals.presentation

import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection

/** Pure placement calculation: no lookup, retained curve, model tree or rendering side effect. */
internal object ModelPlacementCalculator {
    fun calculate(placement: ModelPlacement.Ground): ModelPose = placement.pose

    /**
     * [curve] must be the already obtained canonical AB curve for the placement's rail.
     * Invalid/missing geometry throws [IllegalArgumentException] without producing a pose.
     * Uses one curve sample (O(log N) for PrimeCurve), then constant-size vector operations.
     */
    fun calculate(placement: ModelPlacement.RailAttached, curve: Curve?): ModelPose {
        val anchor = placement.position
        val context = "Model placement on rail ${anchor.railId} at ${anchor.arcLengthInAbMeters} m"
        requireNotNull(curve) { "$context: curve is missing" }
        val length = curve.totalArcLength
        require(length.isFinite() && length >= 0.0) { "$context: curve length must be finite and non-negative" }
        require(anchor.arcLengthInAbMeters <= length) { "$context: arc length is outside [0, $length]" }

        val point = curve.getPointAt(anchor.arcLengthInAbMeters)
        require(point.position.isFinite) { "$context: sampled position must be finite" }
        val forward = unitVector(point.tangent, "$context: tangent")
        if (anchor.facing == ViewDirection.BA) forward.negate()
        if (!placement.followRailTilt) {
            forward.y = 0.0
            normalize(forward, "$context: horizontal tangent")
        }
        val upReference = if (placement.followRailTilt) {
            unitVector(point.normal, "$context: rail normal")
        } else {
            Vector3d(0.0, 1.0, 0.0)
        }
        val right = normalize(Vector3d(forward).cross(upReference), "$context: right axis")
        val up = Vector3d(right).cross(forward)
        val adjustment = placement.adjustment
        // Offset is in the rail frame, independently of the model's subsequent local rotation.
        val position = Vector3d(point.position)
            .fma(adjustment.x(), forward)
            .fma(adjustment.y(), up)
            .fma(adjustment.z(), right)
        require(position.isFinite) { "$context: offset produces a non-finite position" }

        val basis = Matrix3d().setColumn(0, forward).setColumn(1, up).setColumn(2, right)
        val rotation = Quaterniond().setFromNormalized(basis).normalize().mul(
            Quaterniond(adjustment.rotationX(), adjustment.rotationY(), adjustment.rotationZ(), adjustment.rotationW())
        ).normalize()
        // Local input was already validated by ModelPose. Normalize the computed product so
        // composition rounding cannot reject an input at the accepted unit-length tolerance.
        return ModelPose(position.x, position.y, position.z, rotation.x, rotation.y, rotation.z, rotation.w)
    }

    private fun unitVector(value: Vector3dc, label: String): Vector3d = normalize(Vector3d(value), label)

    private fun normalize(value: Vector3d, label: String): Vector3d {
        val lengthSquared = value.lengthSquared()
        require(value.isFinite && lengthSquared.isFinite() && lengthSquared > 1e-12) {
            "$label cannot form a usable direction"
        }
        return value.normalize()
    }
}
