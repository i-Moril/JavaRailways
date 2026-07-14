package work.art1st.mineRails.core.api.util.annotation

/** Marks an operation too expensive for a per-tick hot path without explicit review. */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This method has high performance cost. Do not use this in functions called by tick()."
)
@Retention(AnnotationRetention.BINARY)
annotation class Heavy
