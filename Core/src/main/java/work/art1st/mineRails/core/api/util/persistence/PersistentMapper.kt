package work.art1st.mineRails.core.api.util.persistence

interface PersistentMapper<Logical, Persistent, Dependency> {
    fun save(obj: Logical) : Persistent
    fun load(persistent: Persistent, dependency: Dependency) : Logical
}