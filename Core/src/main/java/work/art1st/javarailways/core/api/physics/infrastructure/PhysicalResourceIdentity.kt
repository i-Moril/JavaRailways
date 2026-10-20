package work.art1st.javarailways.core.api.physics.infrastructure

import work.art1st.javarailways.core.api.physics.rail.topology.AtGradeRailCrossing

/** Stable physical conflict identities shared by editing and route resource ownership. */
object PhysicalResourceIdentity {
    const val DIAMOND_TYPE_ID = "javarailways:diamond"

    fun diamond(crossing: AtGradeRailCrossing): StableInfrastructureResource =
        StableInfrastructureResource(
            DIAMOND_TYPE_ID,
            buildString {
                append(crossing.first.railId)
                append('/')
                append(crossing.second.railId)
                append('/')
                append(unsignedBits(crossing.first.arcLengthAbMeters))
                append('-')
                append(unsignedBits(crossing.second.arcLengthAbMeters))
            }
        )

    private fun unsignedBits(value: Double): String =
        java.lang.Long.toUnsignedString(java.lang.Double.doubleToLongBits(value), 16)
}
