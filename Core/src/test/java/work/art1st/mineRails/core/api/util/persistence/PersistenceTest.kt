package work.art1st.mineRails.core.api.util.persistence

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PersistenceTest {
    data class MockEntity(
        val value: String
    ) : PersistentBy<MockSurrogate> {
        override fun save(): MockSurrogate = MockSurrogate(value)
    }

    @Serializable
    data class MockSurrogate(
        val value: String
    ) : Loads<MockEntity, String> {
        override fun load(dependency: String): MockEntity = MockEntity("$dependency:$value")
    }

    @Serializable
    data class Wrapper(
        @Contextual val entity: MockEntity
    )

    @Test
    fun `delegate serializer serializes by save`() {
        val serializer = DelegateSerializer(MockSurrogate.serializer(), dependency = "ignored")
        val json = Json

        val encoded = json.encodeToString(serializer, MockEntity("payload"))

        assertEquals("""{"value":"payload"}""", encoded)
    }

    @Test
    fun `delegate serializer deserializes by load with dependency`() {
        val serializer = DelegateSerializer(MockSurrogate.serializer(), dependency = "dep")
        val json = Json

        val decoded = json.decodeFromString(serializer, """{"value":"payload"}""")

        assertEquals(MockEntity("dep:payload"), decoded)
    }

    @Test
    fun `register dependency entity provides contextual serializer`() {
        val module = SerializersModule {
            registerDependencyEntity(MockEntity::class, MockSurrogate.serializer(), "ctx")
        }
        val json = Json { serializersModule = module }

        val encoded = json.encodeToString(Wrapper.serializer(), Wrapper(MockEntity("payload")))
        val decoded = json.decodeFromString(Wrapper.serializer(), encoded)

        assertEquals("""{"entity":{"value":"payload"}}""", encoded)
        assertEquals(Wrapper(MockEntity("ctx:payload")), decoded)
    }
}
