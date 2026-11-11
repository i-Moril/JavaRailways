package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Set;

/** Timing-only numeric expressions; action parameters and arbitrary runtime values cannot enter here. */
final class StationPreparationLeadValidator {
    private static final Set<String> OPERATIONS = Set.of("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "MODULO", "MIN", "MAX", "ROUND", "FLOOR", "CEIL");
    private StationPreparationLeadValidator() {}
    static void validate(JsonNode expression) {
        JsonNode numeric = numericExpression(expression, 0, new int[]{0});
        // Reuse the regular arithmetic semantics, with exactly one locally declared numeric source.
        JsonNode value = ProgramExpressionValidator.validate(numeric, "NUMBER", Map.of("@doorCloseTicks", "NUMBER"), null);
        if (value != null) {
            double number = value.doubleValue();
            if (number < 0 || number != Math.rint(number) || number > 9_007_199_254_740_991d)
                throw new IllegalArgumentException("preparation lead must be a nonnegative safe integer");
        }
    }
    private static JsonNode numericExpression(JsonNode expression, int depth, int[] nodes) {
        if (depth > 16 || ++nodes[0] > 4096) throw new IllegalArgumentException("preparation lead exceeds depth16 or4096 values");
        if (expression == null || !expression.isObject() || expression.size() != 1) throw new IllegalArgumentException("invalid preparation lead expression");
        if (expression.has("value")) {
            if (!expression.path("value").isNumber()) throw new IllegalArgumentException("lead value must be a number");
            return expression;
        }
        if (expression.has("doorCloseTicks")) {
            if (!expression.path("doorCloseTicks").isBoolean() || !expression.path("doorCloseTicks").booleanValue())
                throw new IllegalArgumentException("doorCloseTicks must be true");
            return JsonNodeFactory.instance.objectNode().put("parameterId", "@doorCloseTicks");
        }
        if (!expression.has("operation")) throw new IllegalArgumentException("invalid preparation lead expression");
        JsonNode operation = expression.path("operation");
        StationProgramValidator.fields(operation, Set.of("type", "operands"), "timing operation");
        if (!operation.path("type").isTextual() || !OPERATIONS.contains(operation.path("type").asText()) || !operation.path("operands").isArray())
            throw new IllegalArgumentException("invalid timing operation");
        ObjectNode transformed = JsonNodeFactory.instance.objectNode();
        transformed.put("type", operation.path("type").asText());
        var operands = transformed.putArray("operands");
        for (JsonNode operand : operation.path("operands")) operands.add(numericExpression(operand, depth + 1, nodes));
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("operation", transformed);
        return result;
    }
}
