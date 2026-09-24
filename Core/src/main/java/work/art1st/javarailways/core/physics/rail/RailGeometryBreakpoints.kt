package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.util.annotation.Heavy
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl

/** Core-internal exact piecewise-linear geometry used only while updating structural indexes. */
@OptIn(Heavy::class)
internal fun CoreRail.geometryBreakpoints(): List<Curve.Point> =
    (curve as? PrimeCurveImpl)?.geometryBreakpoints()
        ?: curve.stream().use { stream -> stream.toList() }
