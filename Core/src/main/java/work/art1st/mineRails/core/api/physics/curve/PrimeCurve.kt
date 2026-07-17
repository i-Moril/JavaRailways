package work.art1st.mineRails.core.api.physics.curve

import work.art1st.configLib.persistence.Loads
import work.art1st.configLib.persistence.PersistentBy
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveImpl

interface PrimeCurve : Curve, PersistentBy<PrimeCurve.SavedPrimeCurve> {
    override val reversed: PrimeCurve

    /** Split this sampled curve without running a geometry solver. */
    fun splitAt(arcLength: Double): Pair<PrimeCurve, PrimeCurve>

    /** Join two contiguous sampled curves without running a geometry solver. */
    fun concatenate(next: PrimeCurve): PrimeCurve

    interface SavedPrimeCurve : Loads<PrimeCurve, Unit>

    companion object {
        fun fromPoints(points: List<Curve.RawPoint>) : PrimeCurve = PrimeCurveImpl(points)
    }
}