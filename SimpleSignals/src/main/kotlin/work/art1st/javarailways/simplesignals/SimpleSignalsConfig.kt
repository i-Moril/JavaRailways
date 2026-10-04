package work.art1st.javarailways.simplesignals

/** Startup-only policy. Unit conversion happens once before runtimes are constructed. */
internal class SimpleSignalsConfig private constructor(val disabledBlockSpeedMetersPerSecond: Double) {
    companion object {
        fun from(disabledBlockSpeedKmh: Any?): SimpleSignalsConfig {
            val value = disabledBlockSpeedKmh ?: 20.0
            require(value is Number) { "config.yml disabled-block-speed-kmh must be a number" }
            val kmh = value.toDouble()
            require(kmh.isFinite() && kmh > 0.0) { "config.yml disabled-block-speed-kmh must be finite and positive" }
            val speed = kmh / 3.6
            require(speed.isFinite() && speed > 0.0) { "config.yml disabled-block-speed-kmh cannot be represented in m/s" }
            return SimpleSignalsConfig(speed)
        }
    }
}
