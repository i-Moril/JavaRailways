package work.art1st.configLib.annotation

import kotlin.reflect.KClass

annotation class PersistentBy(val config: KClass<out Config>)
