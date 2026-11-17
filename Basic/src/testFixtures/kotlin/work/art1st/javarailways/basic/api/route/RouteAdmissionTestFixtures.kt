package work.art1st.javarailways.basic.api.route

/** Test-only construction helpers for Basic-owned opaque process-local identities. */
object RouteAdmissionTestFixtures {
    @JvmStatic
    fun handle(): RouteAdmissionHandle = RouteAdmissionHandle()

    @JvmStatic
    fun grantId(): RouteGrantId = RouteGrantId()

    @JvmStatic
    fun couplingContactAuthority(
        eventId: String,
        proof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof,
        contactBoundary: RouteBoundary,
        maximumApproachSpeedMetersPerSecond: Double
    ): CouplingContactAuthority = CouplingContactAuthority(eventId, proof, contactBoundary,
        maximumApproachSpeedMetersPerSecond)
}
