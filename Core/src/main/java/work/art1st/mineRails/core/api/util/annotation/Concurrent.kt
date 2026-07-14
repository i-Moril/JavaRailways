package work.art1st.mineRails.core.api.util.annotation

/** Marks an API whose caller must account for concurrent execution and publication semantics. */
@RequiresOptIn()
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Concurrent()