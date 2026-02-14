package work.art1st.mineRails.core.api.util.persistence

interface IndependentPersistentMapper<Logical, Persistent> : PersistentMapper<Logical, Persistent, Unit> {
    override fun load(persistent: Persistent, dependency: Unit) : Logical {
        return load(persistent)
    }
    fun load(persistent: Persistent) : Logical
}