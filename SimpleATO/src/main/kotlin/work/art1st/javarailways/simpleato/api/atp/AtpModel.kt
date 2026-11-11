package work.art1st.javarailways.simpleato.api.atp

/** Hard protection boundary; distance is measured from the current physical head. */
data class AtpTarget(val id: String, val distanceMeters: Double, val targetSpeedMetersPerSecond: Double)
enum class AtpStatus { SAFE, INTERVENTION, UNAVAILABLE }
enum class AtpReason { CLEAR, INSUFFICIENT_DISTANCE, CURRENT_OVERSPEED, INVALID_MODEL, INVALID_STATE,
    OUTSIDE_MODEL_DOMAIN, INPUT_UNAVAILABLE, NO_GUARANTEED_BRAKING, NO_ORDINARY_RECOVERY }
data class AtpWitness(val target: AtpTarget, val requiredDistanceMeters: Double,
    val availableDistanceMeters: Double, val responsePeakSpeedMetersPerSecond: Double)
/** Device preparation is analytic; the grade table covers only fully established constant braking. */
data class AtpResponse(val distanceMeters: Double, val endSpeedMetersPerSecond: Double,
    val peakSpeedMetersPerSecond: Double, val preparationSeconds: Double,
    val flatGuaranteedDecelerationMetersPerSecondSquared: Double)
data class AtpJudgement(val status: AtpStatus, val reason: AtpReason,
    val detail: String = "", val witness: AtpWitness? = null, val response: AtpResponse? = null)
