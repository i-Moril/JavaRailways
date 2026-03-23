package work.art1st.mineRails.core.api.physics.curve

import work.art1st.mineRails.core.api.util.persistence.Loads
import work.art1st.mineRails.core.api.util.persistence.PersistentBy

interface PrimeCurve : Curve, PersistentBy<PrimeCurve.SavedPrimeCurve> {
    override val reversed: PrimeCurve

    interface SavedPrimeCurve : Loads<PrimeCurve, Unit>
}