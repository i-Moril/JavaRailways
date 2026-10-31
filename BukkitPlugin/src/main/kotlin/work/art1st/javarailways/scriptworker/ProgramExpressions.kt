package work.art1st.javarailways.scriptworker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.*

@Serializable internal data class StationProgramOperation(val type: String, val operands: List<StationProgramValue>)

/** Pure, bounded authoring expressions. Values are resolved before the dispatcher runs. */
internal object ProgramExpressions {
    private val numericTypes = setOf("NUMBER", "TICK", "DURATION")
    private val stringTypes = setOf("STRING", "DESTINATION", "STATION", "DEPOT")
    private val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val inputs = mapOf(
        "ADD" to listOf("NUMBER", "NUMBER"), "SUBTRACT" to listOf("NUMBER", "NUMBER"),
        "MULTIPLY" to listOf("NUMBER", "NUMBER"), "DIVIDE" to listOf("NUMBER", "NUMBER"),
        "MODULO" to listOf("NUMBER", "NUMBER"), "MIN" to listOf("NUMBER", "NUMBER"), "MAX" to listOf("NUMBER", "NUMBER"),
        "ROUND" to listOf("NUMBER"), "FLOOR" to listOf("NUMBER"), "CEIL" to listOf("NUMBER"),
        "CONCAT" to listOf("STRING", "STRING"), "SUBSTRING" to listOf("STRING", "NUMBER", "NUMBER"),
        "REPLACE" to listOf("STRING", "STRING", "STRING"), "TEXT_LENGTH" to listOf("STRING"),
        "TO_STRING" to listOf("NUMBER"), "TO_NUMBER" to listOf("STRING"))
    private val textResults = setOf("CONCAT", "SUBSTRING", "REPLACE", "TO_STRING")

    /** The shortest round-tripping decimal, nearest to the exact binary value (ties to even). */
    private fun plainNumber(number: Double): String {
        if (number == 0.0) return "0"
        val exact = BigDecimal(number)
        for (precision in 1..17) {
            val candidates = listOf(RoundingMode.FLOOR, RoundingMode.CEILING)
                .map { exact.round(MathContext(precision, it)).stripTrailingZeros() }
                .distinct().filter { it.toDouble() == number }
            val best = candidates.minWithOrNull(compareBy<BigDecimal> { it.subtract(exact).abs() }
                .thenBy { it.unscaledValue().abs().testBit(0) })
            if (best != null) return best.toPlainString()
        }
        error("finite double has no round-tripping decimal")
    }

    fun validateShape(raw: JsonElement, allowTrainInfo: Boolean = false) {
        var nodes = 0
        fun walk(raw: JsonElement, depth: Int) {
            require(depth <= 16 && ++nodes <= 4096) { "expression exceeds depth16 or4096 nodes" }
            val fields = raw as? JsonObject ?: error("program expression must be an object")
            when (fields.keys) {
                setOf("value") -> Unit
                setOf("parameterId") -> require(fields.getValue("parameterId") is JsonPrimitive && fields.getValue("parameterId").jsonPrimitive.isString) { "parameterId must be a string" }
                setOf("trainInfo") -> { require(depth == 0 && allowTrainInfo) { "trainInfo is only allowed as a complete display or extension binding" }; parseTrainInfo(fields.getValue("trainInfo")) }
                setOf("operation") -> {
                    val operation = fields.getValue("operation") as? JsonObject ?: error("operation must be an object")
                    require(operation.keys == setOf("type", "operands")) { "operation requires type and operands" }
                    val type = operation.getValue("type").jsonPrimitive.also { require(it.isString) }.content
                    val expected = inputs[type] ?: error("unknown expression operation: $type")
                    val operands = operation.getValue("operands") as? JsonArray ?: error("operation operands must be an array")
                    require(operands.size == expected.size) { "$type requires${expected.size} operands" }
                    operands.forEach { walk(it, depth + 1) }
                }
                setOf("object") -> {
                    val entries = fields.getValue("object") as? JsonObject ?: error("object expression must contain an object")
                    require(entries.size <= 128 && entries.keys.all { it.isNotBlank() && it.length <= 256 }) { "object expression requires at most128 nonblank keys of at most256 characters" }
                    entries.values.forEach { walk(it, depth + 1) }
                }
                else -> error("program expression requires exactly one of value, parameterId, operation, object or trainInfo")
            }
        }
        walk(raw, 0)
    }

    fun evaluate(expression: StationProgramValue, type: String, parameters: Map<String, String> = emptyMap(), arguments: JsonObject = JsonObject(emptyMap())): JsonElement {
        validateShape(Json.encodeToJsonElement(expression))
        fun compatible(actual: String, expected: String): Boolean = actual == expected ||
            (actual in numericTypes && expected in numericTypes) || (actual in stringTypes && expected in stringTypes)
        fun resolve(expression: StationProgramValue, expected: String): JsonElement {
            val result = when {
                expression.value != null -> expression.value
                expression.parameterId != null -> {
                    require(parameters[expression.parameterId]?.let { compatible(it, expected) } == true) { "unknown or incompatible parameter: ${expression.parameterId}" }
                    arguments.getValue(expression.parameterId)
                }
                expression.`object` != null -> {
                    require(expected == "JSON_OBJECT") { "object expression requires JSON_OBJECT" }
                    JsonObject(expression.`object`.mapValues { (_, child) ->
                        // Object properties may hold any pure expression, including structured literals.
                        fun infer(value: StationProgramValue): String = when {
                            value.operation != null -> if (value.operation.type in textResults) "STRING" else "NUMBER"
                            value.`object` != null -> "JSON_OBJECT"
                            value.parameterId != null -> parameters[value.parameterId] ?: error("unknown parameter: ${value.parameterId}")
                            value.value is JsonPrimitive -> if (value.value.isString) "STRING" else "JSON_VALUE"
                            else -> "JSON_VALUE"
                        }
                        resolve(child, infer(child))
                    })
                }
                expression.operation != null -> {
                    val op = expression.operation
                    val resultType = if (op.type in textResults) "STRING" else "NUMBER"
                    require(compatible(resultType, expected)) { "${op.type} produces $resultType, not $expected" }
                    val args = op.operands.zip(inputs.getValue(op.type)).map { (child, input) -> resolve(child, input) }
                    fun n(i: Int) = args[i].jsonPrimitive.double
                    fun s(i: Int) = args[i].jsonPrimitive.content
                    when (op.type) {
                        "CONCAT" -> JsonPrimitive(s(0) + s(1))
                        "SUBSTRING" -> {
                            val start = n(1); val end = n(2)
                            require(start == floor(start) && end == floor(end) && start >= 0 && start <= end && end <= s(0).length) { "SUBSTRING requires integral0 <= start <= end <= text length" }
                            JsonPrimitive(s(0).substring(start.toInt(), end.toInt()))
                        }
                        "REPLACE" -> {
                            require(s(1).isNotEmpty()) { "REPLACE search must not be empty" }
                            var matches = 0; var index = 0
                            while (true) { val found = s(0).indexOf(s(1), index); if (found < 0) break; matches++; index = found + s(1).length }
                            require(s(0).length.toLong() + matches.toLong() * (s(2).length - s(1).length) <= 8192) { "STRING must contain at most8192 characters" }
                            JsonPrimitive(s(0).replace(s(1), s(2)))
                        }
                        "TO_STRING" -> JsonPrimitive(plainNumber(n(0)))
                        else -> {
                            val number = when (op.type) {
                                "ADD" -> n(0) + n(1); "SUBTRACT" -> n(0) - n(1); "MULTIPLY" -> n(0) * n(1)
                                "DIVIDE" -> { require(n(1) != 0.0) { "division by zero" }; n(0) / n(1) }
                                "MODULO" -> { require(n(1) != 0.0) { "modulo by zero" }; n(0) % n(1) }
                                "MIN" -> min(n(0), n(1)); "MAX" -> max(n(0), n(1))
                                "ROUND" -> { val x = n(0); val lower = floor(x); if (x - lower < 0.5) lower else lower + 1.0 }
                                "FLOOR" -> floor(n(0)); "CEIL" -> ceil(n(0)); "TEXT_LENGTH" -> s(0).length.toDouble()
                                "TO_NUMBER" -> { val text = s(0).trim { it in '\u0009'..'\u000D' || it == '\u0020' || it == '\u00A0' || it == '\u1680' || it in '\u2000'..'\u200A' || it == '\u2028' || it == '\u2029' || it == '\u202F' || it == '\u205F' || it == '\u3000' || it == '\uFEFF' }; require(decimal.matches(text)) { "TO_NUMBER requires decimal text" }; text.toDouble() }
                                else -> error("unknown operation: ${op.type}")
                            }
                            validateProgramLiteral(JsonPrimitive(number), "NUMBER")
                            if (number == floor(number)) JsonPrimitive(number.toLong()) else JsonPrimitive(number)
                        }
                    }
                }
                else -> error("expression is missing a value")
            }
            if (expected == "JSON_VALUE") validateProgramJsonObject(buildJsonObject { put("value", result) })
            else validateProgramLiteral(result, expected)
            return if (expected == "TICK" || expected == "DURATION") JsonPrimitive(result.jsonPrimitive.double.toLong()) else result
        }
        return resolve(expression, type)
    }
}
