package work.art1st.javarailways.basic.api.timetable

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TimetableExtensionRegistryTest {
    private val descriptor = TimetableExtensionDescriptor("Test", "Test provider")
    private val context = TimetableRuntimeContext(
        timetableId = "tt",
        serviceId = "service",
        cycleIndex = 2,
        timetableElapsedTicks = 120,
        scheduledTick = 100,
        latenessTicks = 20,
        currentWayPointId = "central",
        train = TimetableTrainContext("train-1", modelPackageId = "metro")
    )

    @Test
    fun `built-in train model condition uses assigned train context`() {
        val registry = TimetableExtensionRegistry()
        val condition = TimetableCondition.Predicate(TimetableExtensionCall(
            "javarailways:train_model",
            parameters = JsonObject(mapOf(
                "models" to JsonArray(listOf(JsonPrimitive("metro")))
            ))
        ))

        assertEquals(TimetableConditionResult.True, registry.evaluate(condition, context))
        assertEquals(emptyList<String>(), registry.validateCondition(condition))
    }

    @Test
    fun `registers versions independently and rejects duplicate providers`() {
        val registry = TimetableExtensionRegistry()
        val v1 = provider("example:enabled", 1, TimetableConditionResult.True)
        val v2 = provider("example:enabled", 2, TimetableConditionResult.False)

        registry.registerCondition(v1)
        registry.registerCondition(v2)

        assertEquals(v1, registry.condition("example:enabled", 1))
        assertEquals(v2, registry.condition("example:enabled", 2))
        assertThrows(IllegalStateException::class.java) { registry.registerCondition(v1) }
    }

    @Test
    fun `evaluates composable conditions with short circuit behavior`() {
        val registry = TimetableExtensionRegistry()
        registry.registerCondition(provider("example:true", result = TimetableConditionResult.True))
        registry.registerCondition(provider("example:false", result = TimetableConditionResult.False))

        val condition = TimetableCondition.All(listOf(
            predicate("example:true"),
            TimetableCondition.Not(predicate("example:false"))
        ))

        assertEquals(TimetableConditionResult.True, registry.evaluate(condition, context))
        assertEquals(
            TimetableConditionResult.False,
            registry.evaluate(TimetableCondition.Any(listOf(predicate("example:false"))), context)
        )
    }

    @Test
    fun `reports missing and failing providers without throwing into dispatcher`() {
        val registry = TimetableExtensionRegistry()
        registry.registerCondition(object : TimetableConditionProvider {
            override val typeId = "example:broken"
            override val version = 1
            override val descriptor = this@TimetableExtensionRegistryTest.descriptor
            override fun evaluate(parameters: JsonObject, context: TimetableRuntimeContext): TimetableConditionResult {
                error("boom")
            }
        })

        assertEquals(
            listOf("condition provider is not registered: missing:condition@1"),
            registry.validateCondition(predicate("missing:condition"))
        )
        assertEquals(
            TimetableConditionResult.Unavailable(
                "condition provider is not registered: missing:condition@1"
            ),
            registry.evaluate(predicate("missing:condition"), context)
        )
        assertEquals(
            TimetableConditionResult.Error("condition provider example:broken@1 failed: boom"),
            registry.evaluate(predicate("example:broken"), context)
        )
    }

    @Test
    fun `passes parameters and immutable runtime context to provider`() {
        val registry = TimetableExtensionRegistry()
        registry.registerCondition(object : TimetableConditionProvider {
            override val typeId = "example:model"
            override val version = 1
            override val descriptor = this@TimetableExtensionRegistryTest.descriptor
            override fun evaluate(parameters: JsonObject, context: TimetableRuntimeContext) =
                if (parameters["model"] == JsonPrimitive(context.train?.modelPackageId)) {
                    TimetableConditionResult.True
                } else TimetableConditionResult.False
        })

        val condition = TimetableCondition.Predicate(TimetableExtensionCall(
            "example:model",
            parameters = JsonObject(mapOf("model" to JsonPrimitive("metro")))
        ))

        assertEquals(TimetableConditionResult.True, registry.evaluate(condition, context))
    }

    private fun predicate(typeId: String) =
        TimetableCondition.Predicate(TimetableExtensionCall(typeId))

    private fun provider(
        typeId: String,
        version: Int = 1,
        result: TimetableConditionResult
    ) = object : TimetableConditionProvider {
        override val typeId = typeId
        override val version = version
        override val descriptor = this@TimetableExtensionRegistryTest.descriptor
        override fun evaluate(parameters: JsonObject, context: TimetableRuntimeContext) = result
    }
}
