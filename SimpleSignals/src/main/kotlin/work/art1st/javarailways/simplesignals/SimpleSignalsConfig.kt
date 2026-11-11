package work.art1st.javarailways.simplesignals

/** Startup-only authoring radius, measured along track from the newly placed protection cut. */
internal class SimpleSignalsConfig private constructor(val protectionRegionMaxDistanceMeters: Double) {
    companion object {
        fun from(protectionRegionMaxDistanceMeters: Any?): SimpleSignalsConfig {
            val value = protectionRegionMaxDistanceMeters ?: 1000.0
            require(value is Number) { "config.yml protection-region-max-distance-meters must be a number" }
            val meters = value.toDouble()
            require(meters.isFinite() && meters > 0.0) { "config.yml protection-region-max-distance-meters must be finite and positive" }
            return SimpleSignalsConfig(meters)
        }
    }
}
