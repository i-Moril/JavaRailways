package work.art1st.javarailways.simplesignals.authoring

import org.joml.Quaterniond
import org.joml.Vector3d
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement

/** Text fields avoid slider rounding; null tilt means ground placement, not a rail default. */
internal data class PlacementWandInput(val fields: Map<String, String>, val followRailTilt: Boolean?)

/** One callback-local placement draft. The handler owns threading and continuation validity. */
internal class PlacementWandEditor(
    private val backend: SignalWandBackend,
    private val capture: SignalWandCapture,
    private val page: (String, List<Pair<String, () -> Unit>>) -> Unit,
    private val input: (String, PlacementWandInput, (PlacementWandInput?) -> Unit) -> Unit,
    private val reply: (String) -> Unit,
    private val active: () -> Boolean
) {
    fun selectDevice() {
        val devices = backend.placedDevices(capture)
        require(devices.isNotEmpty()) { "This world has no placed signal device to edit" }
        page("Select placed signal device", devices.mapIndexed { index, device -> "${sectionAuthoringName(device.id, "Signal ${index + 1}")} | ${device.kind}" to {
            require(backend.current(capture)) { "Signal selection expired; select again" }
            val placement = device.placement
            val pose = when (placement) {
                is ModelPlacement.RailAttached -> placement.adjustment
                is ModelPlacement.Ground -> placement.pose
            }
            val angles = Quaterniond(pose.rotationX(), pose.rotationY(), pose.rotationZ(), pose.rotationW())
                .getEulerAnglesXYZ(Vector3d())
            val initial = PlacementWandInput(linkedMapOf(
                "x" to pose.x().toString(), "y" to pose.y().toString(), "z" to pose.z().toString(),
                "rx" to Math.toDegrees(angles.x).toString(), "ry" to Math.toDegrees(angles.y).toString(),
                "rz" to Math.toDegrees(angles.z).toString()),
                (placement as? ModelPlacement.RailAttached)?.followRailTilt)
            input(if (placement is ModelPlacement.RailAttached) "Rail offsets: X forward / Y up / Z right (m)"
                else "Ground position: absolute world XYZ (m)", initial) { submitted ->
                if (submitted == null) reply("Placement editing cancelled")
                else confirm(device, pose, initial, submitted)
            }
        } } + ("Cancel" to { reply("Placement editing cancelled") }))
    }

    private fun confirm(device: PlacedSignalChoice, oldPose: ModelPose,
        initial: PlacementWandInput, submitted: PlacementWandInput) {
        require(backend.current(capture)) { "Signal selection expired; select again" }
        require((initial.followRailTilt == null) == (submitted.followRailTilt == null)) {
            "Only rail placement accepts a tilt setting"
        }
        val values = initial.fields.keys.associateWith { key ->
            requireNotNull(submitted.fields[key]?.toDoubleOrNull()?.takeIf { it.isFinite() }) {
                "$key must be a finite number"
            }
        }
        // Unchanged angles preserve the exact original quaternion, including at Euler singularities.
        val rotation = if (listOf("rx", "ry", "rz").all { values.getValue(it) == initial.fields.getValue(it).toDouble() })
            Quaterniond(oldPose.rotationX(), oldPose.rotationY(), oldPose.rotationZ(), oldPose.rotationW())
        else Quaterniond().rotateXYZ(Math.toRadians(values.getValue("rx")),
            Math.toRadians(values.getValue("ry")), Math.toRadians(values.getValue("rz")))
        val pose = ModelPose(values.getValue("x"), values.getValue("y"), values.getValue("z"),
            rotation.x, rotation.y, rotation.z, rotation.w)
        val replacement = when (val old = device.placement) {
            is ModelPlacement.RailAttached -> old.copy(adjustment = pose, followRailTilt = checkNotNull(submitted.followRailTilt))
            is ModelPlacement.Ground -> old.copy(pose = pose)
        }
        page("Confirm ${sectionAuthoringName(device.id, "signal")} | ${submitted.fields} | tilt: ${submitted.followRailTilt ?: "not applicable"}", listOf(
            "Save placement (keep model / binding)" to {
                reply(backend.savePlacement(capture, device, replacement, active).message)
            }, "Cancel" to { reply("Placement editing cancelled") }))
    }
}
