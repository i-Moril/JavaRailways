package work.art1st.javarailways.core.api.util.annotation

/**
 * Coupling and splitting are supported capabilities, but their plugin contracts are unstable.
 * Signatures and semantics may change without source or binary compatibility guarantees.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Train coupling/splitting APIs are unstable and may change in future development."
)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class TrainStructureApi
