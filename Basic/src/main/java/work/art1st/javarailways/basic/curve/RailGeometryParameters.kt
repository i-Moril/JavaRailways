package work.art1st.javarailways.basic.curve

/**
 * Stable engineering inputs for track geometry generation; numerical solver tolerances are excluded.
 *
 * Publish this value and its fallback radii as read-only inputs. Callers must not mutate a supplied
 * list after construction; configuration updates create a replacement value instead.
 */
data class RailGeometryParameters(
    val gaugeMeters: Double = 1.435,
    val gravityMetersPerSecondSquared: Double = 9.80665,
    val defaultStepMeters: Double = 0.2,
    val tightRadiusUpperBoundMeters: Double = 30.0,
    val tightStepMeters: Double = 0.02,
    val mediumRadiusUpperBoundMeters: Double = 100.0,
    val mediumStepMeters: Double = 0.05,
    val wideRadiusUpperBoundMeters: Double = 300.0,
    val wideStepMeters: Double = 0.10,
    val veryWideStepMeters: Double = 0.20,
    val sCurveTargetStepMeters: Double = 0.40,
    val sCurveMinimumSamples: Int = 24,
    val sCurveMinimumHandleFactor: Double = 0.60,
    val sCurveMaximumHandleFactor: Double = 1.50,
    val sCurveCurvatureProbeCount: Int = 100,
    val turnoutStepMeters: Double = 0.20,
    val autoRadiusDistanceFactor: Double = 5.0,
    val autoRadiusIncrementMeters: Double = 50.0,
    val autoRadiusFallbackRadiiMeters: List<Double> = listOf(40.0, 30.0, 20.0, 10.0)
) {
    init {
        require(gaugeMeters.isFinite() && gaugeMeters > 0.0)
        require(gravityMetersPerSecondSquared.isFinite() && gravityMetersPerSecondSquared > 0.0)
        require(defaultStepMeters.isFinite() && defaultStepMeters > 0.0)
        require(tightRadiusUpperBoundMeters < mediumRadiusUpperBoundMeters)
        require(mediumRadiusUpperBoundMeters < wideRadiusUpperBoundMeters)
        require(listOf(tightStepMeters, mediumStepMeters, wideStepMeters, veryWideStepMeters,
            sCurveTargetStepMeters, turnoutStepMeters).all { it.isFinite() && it > 0.0 })
        require(sCurveMinimumSamples >= 2)
        require(sCurveMinimumHandleFactor.isFinite() && sCurveMinimumHandleFactor > 0.0)
        require(sCurveMaximumHandleFactor.isFinite() && sCurveMaximumHandleFactor >= sCurveMinimumHandleFactor)
        require(sCurveCurvatureProbeCount >= 2)
        require(autoRadiusDistanceFactor.isFinite() && autoRadiusDistanceFactor > 0.0)
        require(autoRadiusIncrementMeters.isFinite() && autoRadiusIncrementMeters > 0.0)
        require(autoRadiusFallbackRadiiMeters.isNotEmpty())
        require(autoRadiusFallbackRadiiMeters.all { it.isFinite() && it > 0.0 })
        require(autoRadiusFallbackRadiiMeters.zipWithNext().all { (first, second) -> first > second })
    }

    fun adaptiveStep(radiusMeters: Double): Double = when {
        radiusMeters < tightRadiusUpperBoundMeters -> tightStepMeters
        radiusMeters < mediumRadiusUpperBoundMeters -> mediumStepMeters
        radiusMeters < wideRadiusUpperBoundMeters -> wideStepMeters
        else -> veryWideStepMeters
    }
}
