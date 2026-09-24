package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl

/** Core-internal exact piecewise-linear geometry used only while updating structural indexes. */
@OptIn(Heavy::class)
internal fun CoreRail.geometryBreakpoints(): List<Curve.Point> =
    curve.geometryBreakpoints()

/** Value-only variant used by asynchronous rail-creation preparation before a CoreRail exists. */
@OptIn(Heavy::class)
internal fun PrimeCurve.geometryBreakpoints(): List<Curve.Point> =
    (this as? PrimeCurveImpl)?.geometryBreakpoints()
        ?: stream().use { stream -> stream.toList() }
