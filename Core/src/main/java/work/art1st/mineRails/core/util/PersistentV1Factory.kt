package work.art1st.mineRails.core.util

import kotlinx.serialization.json.JsonElement

interface PersistentV1Factory<T> {
    fun saveV1(obj: T): JsonElement
    fun loadV1(obj: JsonElement): T
}
