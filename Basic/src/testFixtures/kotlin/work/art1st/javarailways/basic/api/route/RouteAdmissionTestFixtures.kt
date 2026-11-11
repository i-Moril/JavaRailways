package work.art1st.javarailways.basic.api.route

/** Test-only construction helpers for Basic-owned opaque process-local identities. */
object RouteAdmissionTestFixtures {
    @JvmStatic
    fun handle(): RouteAdmissionHandle = RouteAdmissionHandle()

    @JvmStatic
    fun grantId(): RouteGrantId = RouteGrantId()
}
