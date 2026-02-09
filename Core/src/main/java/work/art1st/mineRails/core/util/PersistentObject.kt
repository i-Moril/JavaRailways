package work.art1st.mineRails.core.util

import kotlin.reflect.KClass

interface PersistentObject<PersistentDataType> {
    fun save() : PersistentDataType
}

interface PersistentFactoryWithDependency<Type, PersistentDataType, DependencyProvider> {
    fun load(saved: PersistentDataType, dependencyProvider: DependencyProvider) : Type
}

interface PersistentFactory<Type, PersistentDataType> {
    fun load(saved: PersistentDataType) : Type
}

class DeserializationMissingDependencyException(type: Class<Any>, identifier: Any, provider: Any) : RuntimeException(
    "Missing dependency for type $type with identifier $identifier in $provider."
)