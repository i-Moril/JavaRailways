package work.art1st.javarailways.core.api.util.annotation

/**
 * Declares that a platform main thread must own the annotated operation.
 *
 * This annotation is documentation only; callers must arrange scheduling themselves. Selected
 * implementations additionally enforce the contract with ThreadGuard.
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.FIELD,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class MainThread()