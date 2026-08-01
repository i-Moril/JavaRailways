package work.art1st.javarailways.basic.api.timetable

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal fun TimetableExtensionRegistry.registerJavaRailwaysConditions() {
    registerCondition(StringSetTrainCondition(
        typeId = "javarailways:train_model",
        title = "列车型号",
        parameterName = "models",
        value = { train -> train.modelPackageId?.let(::setOf).orEmpty() }
    ))
    registerCondition(StringSetTrainCondition(
        typeId = "javarailways:train_has_tags",
        title = "列车标签",
        parameterName = "tags",
        value = TimetableTrainContext::tags
    ))
    registerCondition(StringSetTrainCondition(
        typeId = "javarailways:train_has_capabilities",
        title = "列车能力",
        parameterName = "capabilities",
        value = TimetableTrainContext::capabilities
    ))
    registerCondition(object : TimetableConditionProvider {
        override val typeId = "javarailways:lateness_at_least"
        override val version = 1
        override val supportedScopes = setOf(TimetableConditionScope.RUNTIME_BRANCH)
        override val descriptor = TimetableExtensionDescriptor(
            title = "晚点至少",
            description = "当前晚点 Tick 大于或等于指定值",
            parameterSchema = JsonObject(mapOf(
                "ticks" to JsonObject(mapOf(
                    "type" to JsonPrimitive("integer"),
                    "minimum" to JsonPrimitive(0)
                ))
            ))
        )

        override fun validate(parameters: JsonObject): List<String> {
            val ticks = parameters["ticks"]?.jsonPrimitive?.longOrNull
            return if (ticks == null || ticks < 0L) listOf("ticks must be a non-negative integer")
            else emptyList()
        }

        override fun evaluate(
            parameters: JsonObject,
            context: TimetableRuntimeContext
        ): TimetableConditionResult {
            val errors = validate(parameters)
            if (errors.isNotEmpty()) return TimetableConditionResult.Error(errors.joinToString())
            val ticks = requireNotNull(parameters["ticks"]?.jsonPrimitive?.longOrNull)
            return if (context.latenessTicks >= ticks) TimetableConditionResult.True
            else TimetableConditionResult.False
        }
    })
}

private class StringSetTrainCondition(
    override val typeId: String,
    title: String,
    private val parameterName: String,
    private val value: (TimetableTrainContext) -> Set<String>
) : TimetableConditionProvider {
    override val version = 1
    override val supportedScopes = setOf(TimetableConditionScope.RUNTIME_BRANCH)
    override val descriptor = TimetableExtensionDescriptor(
        title = title,
        description = "列车必须匹配全部指定值",
        parameterSchema = JsonObject(mapOf(
            parameterName to JsonObject(mapOf(
                "type" to JsonPrimitive("array"),
                "items" to JsonObject(mapOf("type" to JsonPrimitive("string")))
            ))
        ))
    )

    override fun validate(parameters: JsonObject): List<String> = when {
        parameters.keys != setOf(parameterName) -> listOf("expected only parameter: $parameterName")
        readValues(parameters) == null -> listOf("$parameterName must be a non-empty string array")
        else -> emptyList()
    }

    override fun evaluate(
        parameters: JsonObject,
        context: TimetableRuntimeContext
    ): TimetableConditionResult {
        val expected = readValues(parameters)
            ?: return TimetableConditionResult.Error("$parameterName must be a non-empty string array")
        val train = context.train
            ?: return TimetableConditionResult.Unavailable("condition $typeId requires an assigned train")
        return if (value(train).containsAll(expected)) TimetableConditionResult.True
        else TimetableConditionResult.False
    }

    private fun readValues(parameters: JsonObject): Set<String>? {
        val array = parameters[parameterName] as? JsonArray ?: return null
        if (array.isEmpty()) return null
        val values = array.map { element ->
            val primitive = element as? JsonPrimitive ?: return null
            if (!primitive.isString || primitive.content.isBlank()) return null
            primitive.content
        }
        return values.toSet()
    }
}
