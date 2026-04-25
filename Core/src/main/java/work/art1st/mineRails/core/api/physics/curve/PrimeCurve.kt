package work.art1st.mineRails.core.api.physics.curve

import work.art1st.configLib.persistence.Loads
import work.art1st.configLib.persistence.PersistentBy
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveImpl

interface PrimeCurve : Curve, PersistentBy<PrimeCurve.SavedPrimeCurve> {
    override val reversed: PrimeCurve

    interface SavedPrimeCurve : Loads<PrimeCurve, Unit>

    companion object {
        fun fromPoints(points: List<Curve.RawPoint>) : PrimeCurve = PrimeCurveImpl(points)
    }
}