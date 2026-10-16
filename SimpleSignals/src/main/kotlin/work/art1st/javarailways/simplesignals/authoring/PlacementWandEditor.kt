package work.art1st.javarailways.simplesignals.authoring

import org.joml.Quaterniond
import org.joml.Vector3d
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import java.util.Locale

/** Text fields avoid slider rounding; null tilt means ground placement, not a rail default. */
internal data class PlacementWandInput(val fields: Map<String, String>, val followRailTilt: Boolean?)

/** One callback-local placement draft. The handler owns threading and continuation validity. */
internal class PlacementWandEditor(
    private val backend: SignalWandBackend,
    private val capture: SignalWandCapture,
    private val page: (String, List<Pair<String, () -> Unit>>) -> Unit,
    private val input: (String, PlacementWandInput, (PlacementWandInput?) -> Unit) -> Unit,
    private val reply: (String) -> Unit,
    private val active: () -> Boolean,
    private val locale: Locale = Locale.US
) {
    private fun text(key: String, vararg arguments: Any?): String = WandI18n.format(locale, key, *arguments)

    fun selectDevice() {
        val devices = backend.placedDevices(capture)
        require(devices.isNotEmpty()) { text("editor.placement.empty") }
        page(text("editor.placement.select"), devices.mapIndexed { index, device ->
            text("editor.placement.choice", authoringName(device.id, "name.signal"), index + 1,
                text("editor.kind.${device.kind.name.lowercase(Locale.ROOT)}")) to {
            require(backend.current(capture)) { text("editor.selection.expired") }
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
            input(text(if (placement is ModelPlacement.RailAttached) "editor.placement.rail"
                else "editor.placement.ground"), initial) { submitted ->
                if (submitted == null) reply(text("editor.placement.cancelled"))
                else confirm(device, pose, initial, submitted)
            }
        } } + (text("editor.cancel") to { reply(text("editor.placement.cancelled")) }))
    }

    private fun confirm(device: PlacedSignalChoice, oldPose: ModelPose,
        initial: PlacementWandInput, submitted: PlacementWandInput) {
        require(backend.current(capture)) { text("editor.selection.expired") }
        require((initial.followRailTilt == null) == (submitted.followRailTilt == null)) {
            text("editor.placement.tilt.rail_only")
        }
        val values = initial.fields.keys.associateWith { key ->
            requireNotNull(submitted.fields[key]?.toDoubleOrNull()?.takeIf { it.isFinite() }) {
                text("editor.number.finite", key)
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
        val tilt = text(when (submitted.followRailTilt) {
            true -> "editor.placement.tilt.enabled"
            false -> "editor.placement.tilt.disabled"
            null -> "editor.placement.tilt.not_applicable"
        })
        page(text("editor.placement.confirm", authoringName(device.id, "name.signal"), submitted.fields, tilt), listOf(
            text("editor.placement.save") to {
                reply(backend.savePlacement(capture, device, replacement, active).text.render(locale))
            }, text("editor.cancel") to { reply(text("editor.placement.cancelled")) }))
    }
}
