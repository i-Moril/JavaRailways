package work.art1st.configLib

interface PersistentBy<Persistent> {
    fun save() : Persistent
}

interface Loads<Data> {
    fun load() : Data
}