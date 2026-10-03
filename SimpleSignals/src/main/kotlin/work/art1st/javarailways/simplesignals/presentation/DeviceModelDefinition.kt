package work.art1st.javarailways.simplesignals.presentation

import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState

/** A stable package:model reference and the device's resolved placement values. */
internal data class DeviceModelDefinition(val modelId: String, val placement: ModelPlacement) {
    init {
        val parts = modelId.split(':')
        require(parts.size == 2 && NAME.matches(parts[0]) &&
            parts[1].split('/').all { NAME.matches(it) && it != "." && it != ".." }) {
            "modelId must be a complete package:model reference with a valid local model path: $modelId"
        }
    }

    /** Low-frequency candidate validation against the captured immutable topology, without sampling. */
    fun validatePlacement(topology: TopologyState, deviceId: String) {
        val attached = placement as? ModelPlacement.RailAttached ?: return
        val position = attached.position
        val spans = requireNotNull(topology.railProjection[position.railId]) {
            "device $deviceId model references missing rail ${position.railId}"
        }
        val length = spans.maxOf { maxOf(it.railStartAbMeters, it.railEndAbMeters) }
        require(position.arcLengthInAbMeters in 0.0..length) {
            "device $deviceId model placement ${position.arcLengthInAbMeters} is outside rail " +
                "${position.railId} (0..$length metres)"
        }
    }

    private companion object {
        val NAME = Regex("[a-z0-9._-]+")
    }
}
