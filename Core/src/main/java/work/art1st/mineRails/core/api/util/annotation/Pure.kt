package work.art1st.mineRails.core.api.util.annotation

/** Indicates that a function is intended to be free of externally visible side effects. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Pure()
