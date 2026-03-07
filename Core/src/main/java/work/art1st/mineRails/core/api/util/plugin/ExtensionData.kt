package work.art1st.mineRails.core.api.util.plugin

@RequiresOptIn("Use a wrapper to wrap access to extension data for type safety.", level = RequiresOptIn.Level.ERROR)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ExtensionData
