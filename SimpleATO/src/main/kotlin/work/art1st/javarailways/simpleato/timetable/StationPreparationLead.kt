package work.art1st.javarailways.simpleato.timetable

import kotlinx.serialization.json.*
import kotlin.math.*

/** Bounded numeric timing expression. The only runtime input is the actual train's close duration. */
class StationPreparationLead(expression: JsonElement) {
    private sealed interface Node {
        fun value(doorCloseTicks: Long): Double
        val dynamic: Boolean
        fun json(): JsonObject
    }
    private class Constant(val number: Double) : Node {
        override val dynamic = false
        override fun value(doorCloseTicks: Long) = number
        override fun json() = buildJsonObject { if (number == floor(number)) put("value", number.toLong()) else put("value", number) }
    }
    private data object CloseDuration : Node {
        override val dynamic = true
        override fun value(doorCloseTicks: Long) = doorCloseTicks.toDouble()
        override fun json() = buildJsonObject { put("doorCloseTicks", true) }
    }
    private class Operation(val type: String, val operands: List<Node>) : Node {
        override val dynamic = operands.any { it.dynamic }
        override fun value(doorCloseTicks: Long): Double {
            val a = operands[0].value(doorCloseTicks)
            val b = operands.getOrNull(1)?.value(doorCloseTicks) ?: 0.0
            require(type !in setOf("DIVIDE", "MODULO") || b != 0.0) { "division by zero" }
            return checkedNumber(when(type) {
                "ADD" -> a + b; "SUBTRACT" -> a - b; "MULTIPLY" -> a * b
                "DIVIDE" -> a / b; "MODULO" -> a % b; "MIN" -> min(a,b); "MAX" -> max(a,b)
                "ROUND" -> floor(a).let { if (a - it >= 0.5) it + 1 else it }
                "FLOOR" -> floor(a); "CEIL" -> ceil(a)
                else -> error("unknown timing operation: $type")
            })
        }
        override fun json() = buildJsonObject { put("operation", buildJsonObject {
            put("type", type); put("operands", JsonArray(operands.map { it.json() }))
        }) }
    }
    private val root: Node
    val expression: JsonObject
    val requiresDoorCloseDuration: Boolean get() = root.dynamic
    init {
        var count = 0
        fun parse(raw: JsonElement, depth: Int): Node {
            require(depth <= 16 && ++count <= 4096) { "preparation lead exceeds depth16 or4096 values" }
            val obj = raw as? JsonObject ?: error("preparation lead must be a numeric expression")
            val node = when (obj.keys) {
                setOf("value") -> {
                    val value = obj.getValue("value") as? JsonPrimitive ?: error("lead value must be a number")
                    require(!value.isString) { "lead value must be a number" }
                    Constant(checkedNumber(requireNotNull(value.doubleOrNull) { "lead value must be a number" }))
                }
                setOf("doorCloseTicks") -> {
                    require(obj.getValue("doorCloseTicks") == JsonPrimitive(true)) { "doorCloseTicks must be true" }
                    CloseDuration
                }
                setOf("operation") -> {
                    val operation = obj.getValue("operation") as? JsonObject ?: error("invalid timing operation")
                    require(operation.keys == setOf("type", "operands")) { "invalid timing operation fields" }
                    val typeNode = operation.getValue("type") as? JsonPrimitive ?: error("invalid timing operation type")
                    require(typeNode.isString) { "invalid timing operation type" }
                    val type = typeNode.content
                    val arity = when(type) {
                        "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "MODULO", "MIN", "MAX" -> 2
                        "ROUND", "FLOOR", "CEIL" -> 1
                        else -> error("unknown timing operation: $type")
                    }
                    val operands = operation.getValue("operands") as? JsonArray ?: error("timing operands must be an array")
                    require(operands.size == arity) { "invalid timing operand count" }
                    Operation(type, operands.map { parse(it, depth + 1) })
                }
                else -> error("invalid preparation lead expression")
            }
            // Fold only pure numeric subtrees; never execute a general program expression here.
            return if (node.dynamic) node else Constant(node.value(0))
        }
        root = parse(expression, 0)
        this.expression = root.json()
        if (!root.dynamic) evaluate()
    }
    fun evaluate(doorCloseTicks: Long = 0): Long {
        require(doorCloseTicks in 0..MAX_SAFE) { "invalid door close duration" }
        val result = root.value(doorCloseTicks)
        require(result.isFinite() && result >= 0 && result <= MAX_SAFE.toDouble() && result == floor(result)) {
            "preparation lead must be a nonnegative safe integer"
        }
        return result.toLong()
    }
    override fun equals(other: Any?) = other is StationPreparationLead && expression == other.expression
    override fun hashCode() = expression.hashCode()
    companion object {
        const val MAX_SAFE = 9_007_199_254_740_991L
        fun constant(ticks: Long) = StationPreparationLead(buildJsonObject { put("value", ticks) })
        private fun checkedNumber(value: Double): Double {
            require(value.isFinite() && (value != floor(value) || abs(value) <= MAX_SAFE.toDouble())) {
                "number must be finite and integral values must be safe integers"
            }
            return value
        }
    }
}
