package work.art1st.mineRails.core.api.util.annotation

@RequiresOptIn()
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Concurrent()