package work.art1st.javarailways.core.api.physics.exception

import work.art1st.javarailways.core.api.physics.train.Bogie

/** Captures the bogie's published position as diagnostic text without retaining the live bogie. */
class BogieException(bogie: Bogie) : PhysicsException() {
    override val message: String = bogie.location.let { location ->
        "Bogie operation failed: published rail=${location.rail.id}, facing=${location.facing}, " +
            "arcLengthMeters=${location.arcLength}"
    }
}
