package work.art1st.mineRails.core.api.physics.curve

interface PrimeCurve : Curve {
    override val reversed: PrimeCurve
}