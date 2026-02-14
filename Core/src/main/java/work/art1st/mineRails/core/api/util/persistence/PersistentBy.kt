package work.art1st.mineRails.core.api.util.persistence

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class PersistentBy(vararg val cls: KClass<out PersistentMapper<*, *, *>>)