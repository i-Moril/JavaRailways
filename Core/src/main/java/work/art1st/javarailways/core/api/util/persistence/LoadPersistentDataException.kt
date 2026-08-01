package work.art1st.javarailways.core.api.util.persistence

import kotlin.reflect.KClass

class LoadPersistentDataException(val targetClass: KClass<out Any>, val persistentData: Any) : PersistenceException() {

}
