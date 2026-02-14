package work.art1st.mineRails.core.util

interface SerializedObject<Persistent> {
    fun load() : Persistent
}

interface PersistentObject<Serialized : SerializedObject<Any>> {
    fun save() : Serialized
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