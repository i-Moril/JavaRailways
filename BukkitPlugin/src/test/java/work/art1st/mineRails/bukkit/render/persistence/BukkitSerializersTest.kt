package work.art1st.mineRails.bukkit.render.persistence

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BukkitSerializersTest {
    @Test
    fun `unknown persisted material is rejected instead of becoming stone`() {
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(BukkitItemStackSerializer, """{"material":"NOT_A_MATERIAL"}""")
        }
    }

    @Test
    fun `missing persisted material is rejected instead of becoming stone`() {
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(BukkitItemStackSerializer, "{}")
        }
    }
}
