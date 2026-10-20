package work.art1st.javarailways.basic.curve

import java.util.Collections

/** Stable engineering inputs for track geometry generation; numerical solver tolerances are intentionally excluded. */
class RailGeometryParameters(
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
    autoRadiusFallbackRadiiMeters: List<Double> = listOf(40.0, 30.0, 20.0, 10.0)
) {
    val autoRadiusFallbackRadiiMeters: List<Double> =
        Collections.unmodifiableList(ArrayList(autoRadiusFallbackRadiiMeters))

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
        require(this.autoRadiusFallbackRadiiMeters.isNotEmpty())
        require(this.autoRadiusFallbackRadiiMeters.all { it.isFinite() && it > 0.0 })
        require(this.autoRadiusFallbackRadiiMeters.zipWithNext().all { (first, second) -> first > second })
    }

    fun adaptiveStep(radiusMeters: Double): Double = when {
        radiusMeters < tightRadiusUpperBoundMeters -> tightStepMeters
        radiusMeters < mediumRadiusUpperBoundMeters -> mediumStepMeters
        radiusMeters < wideRadiusUpperBoundMeters -> wideStepMeters
        else -> veryWideStepMeters
    }

    fun copy(
        gaugeMeters: Double = this.gaugeMeters,
        gravityMetersPerSecondSquared: Double = this.gravityMetersPerSecondSquared,
        defaultStepMeters: Double = this.defaultStepMeters,
        tightRadiusUpperBoundMeters: Double = this.tightRadiusUpperBoundMeters,
        tightStepMeters: Double = this.tightStepMeters,
        mediumRadiusUpperBoundMeters: Double = this.mediumRadiusUpperBoundMeters,
        mediumStepMeters: Double = this.mediumStepMeters,
        wideRadiusUpperBoundMeters: Double = this.wideRadiusUpperBoundMeters,
        wideStepMeters: Double = this.wideStepMeters,
        veryWideStepMeters: Double = this.veryWideStepMeters,
        sCurveTargetStepMeters: Double = this.sCurveTargetStepMeters,
        sCurveMinimumSamples: Int = this.sCurveMinimumSamples,
        sCurveMinimumHandleFactor: Double = this.sCurveMinimumHandleFactor,
        sCurveMaximumHandleFactor: Double = this.sCurveMaximumHandleFactor,
        sCurveCurvatureProbeCount: Int = this.sCurveCurvatureProbeCount,
        turnoutStepMeters: Double = this.turnoutStepMeters,
        autoRadiusDistanceFactor: Double = this.autoRadiusDistanceFactor,
        autoRadiusIncrementMeters: Double = this.autoRadiusIncrementMeters,
        autoRadiusFallbackRadiiMeters: List<Double> = this.autoRadiusFallbackRadiiMeters
    ): RailGeometryParameters = RailGeometryParameters(
        gaugeMeters = gaugeMeters,
        gravityMetersPerSecondSquared = gravityMetersPerSecondSquared,
        defaultStepMeters = defaultStepMeters,
        tightRadiusUpperBoundMeters = tightRadiusUpperBoundMeters,
        tightStepMeters = tightStepMeters,
        mediumRadiusUpperBoundMeters = mediumRadiusUpperBoundMeters,
        mediumStepMeters = mediumStepMeters,
        wideRadiusUpperBoundMeters = wideRadiusUpperBoundMeters,
        wideStepMeters = wideStepMeters,
        veryWideStepMeters = veryWideStepMeters,
        sCurveTargetStepMeters = sCurveTargetStepMeters,
        sCurveMinimumSamples = sCurveMinimumSamples,
        sCurveMinimumHandleFactor = sCurveMinimumHandleFactor,
        sCurveMaximumHandleFactor = sCurveMaximumHandleFactor,
        sCurveCurvatureProbeCount = sCurveCurvatureProbeCount,
        turnoutStepMeters = turnoutStepMeters,
        autoRadiusDistanceFactor = autoRadiusDistanceFactor,
        autoRadiusIncrementMeters = autoRadiusIncrementMeters,
        autoRadiusFallbackRadiiMeters = autoRadiusFallbackRadiiMeters
    )

    override fun equals(other: Any?): Boolean = other is RailGeometryParameters &&
        gaugeMeters.compareTo(other.gaugeMeters) == 0 &&
        gravityMetersPerSecondSquared.compareTo(other.gravityMetersPerSecondSquared) == 0 &&
        defaultStepMeters.compareTo(other.defaultStepMeters) == 0 &&
        tightRadiusUpperBoundMeters.compareTo(other.tightRadiusUpperBoundMeters) == 0 &&
        tightStepMeters.compareTo(other.tightStepMeters) == 0 &&
        mediumRadiusUpperBoundMeters.compareTo(other.mediumRadiusUpperBoundMeters) == 0 &&
        mediumStepMeters.compareTo(other.mediumStepMeters) == 0 &&
        wideRadiusUpperBoundMeters.compareTo(other.wideRadiusUpperBoundMeters) == 0 &&
        wideStepMeters.compareTo(other.wideStepMeters) == 0 &&
        veryWideStepMeters.compareTo(other.veryWideStepMeters) == 0 &&
        sCurveTargetStepMeters.compareTo(other.sCurveTargetStepMeters) == 0 &&
        sCurveMinimumSamples == other.sCurveMinimumSamples &&
        sCurveMinimumHandleFactor.compareTo(other.sCurveMinimumHandleFactor) == 0 &&
        sCurveMaximumHandleFactor.compareTo(other.sCurveMaximumHandleFactor) == 0 &&
        sCurveCurvatureProbeCount == other.sCurveCurvatureProbeCount &&
        turnoutStepMeters.compareTo(other.turnoutStepMeters) == 0 &&
        autoRadiusDistanceFactor.compareTo(other.autoRadiusDistanceFactor) == 0 &&
        autoRadiusIncrementMeters.compareTo(other.autoRadiusIncrementMeters) == 0 &&
        autoRadiusFallbackRadiiMeters == other.autoRadiusFallbackRadiiMeters

    override fun hashCode(): Int {
        var result = gaugeMeters.hashCode()
        result = 31 * result + gravityMetersPerSecondSquared.hashCode()
        result = 31 * result + defaultStepMeters.hashCode()
        result = 31 * result + tightRadiusUpperBoundMeters.hashCode()
        result = 31 * result + tightStepMeters.hashCode()
        result = 31 * result + mediumRadiusUpperBoundMeters.hashCode()
        result = 31 * result + mediumStepMeters.hashCode()
        result = 31 * result + wideRadiusUpperBoundMeters.hashCode()
        result = 31 * result + wideStepMeters.hashCode()
        result = 31 * result + veryWideStepMeters.hashCode()
        result = 31 * result + sCurveTargetStepMeters.hashCode()
        result = 31 * result + sCurveMinimumSamples.hashCode()
        result = 31 * result + sCurveMinimumHandleFactor.hashCode()
        result = 31 * result + sCurveMaximumHandleFactor.hashCode()
        result = 31 * result + sCurveCurvatureProbeCount.hashCode()
        result = 31 * result + turnoutStepMeters.hashCode()
        result = 31 * result + autoRadiusDistanceFactor.hashCode()
        result = 31 * result + autoRadiusIncrementMeters.hashCode()
        result = 31 * result + autoRadiusFallbackRadiiMeters.hashCode()
        return result
    }

    override fun toString(): String =
        "RailGeometryParameters(gaugeMeters=$gaugeMeters, " +
        "gravityMetersPerSecondSquared=$gravityMetersPerSecondSquared, defaultStepMeters=$defaultStepMeters, " +
        "tightRadiusUpperBoundMeters=$tightRadiusUpperBoundMeters, tightStepMeters=$tightStepMeters, " +
        "mediumRadiusUpperBoundMeters=$mediumRadiusUpperBoundMeters, mediumStepMeters=$mediumStepMeters, " +
        "wideRadiusUpperBoundMeters=$wideRadiusUpperBoundMeters, wideStepMeters=$wideStepMeters, " +
        "veryWideStepMeters=$veryWideStepMeters, sCurveTargetStepMeters=$sCurveTargetStepMeters, " +
        "sCurveMinimumSamples=$sCurveMinimumSamples, sCurveMinimumHandleFactor=$sCurveMinimumHandleFactor, " +
        "sCurveMaximumHandleFactor=$sCurveMaximumHandleFactor, " +
        "sCurveCurvatureProbeCount=$sCurveCurvatureProbeCount, turnoutStepMeters=$turnoutStepMeters, " +
        "autoRadiusDistanceFactor=$autoRadiusDistanceFactor, " +
        "autoRadiusIncrementMeters=$autoRadiusIncrementMeters, " +
        "autoRadiusFallbackRadiiMeters=$autoRadiusFallbackRadiiMeters)"
}
