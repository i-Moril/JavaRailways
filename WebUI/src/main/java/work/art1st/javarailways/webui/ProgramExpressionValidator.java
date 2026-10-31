package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, pure compile-time expressions. A missing result means an unbound parameter. */
final class ProgramExpressionValidator {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Set<String> NUMERIC = Set.of("NUMBER", "TICK", "DURATION");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private record Result(String type, JsonNode value) {}
    private ProgramExpressionValidator() {}

    static JsonNode validate(JsonNode expression, String expected, Map<String, String> parameters, JsonNode arguments) {
        return walk(expression, expected, parameters, arguments, 0, new int[]{0}).value();
    }

    private static Result walk(JsonNode expression, String expected, Map<String, String> parameters,
                               JsonNode arguments, int depth, int[] nodes) {
        if (depth > 16 || ++nodes[0] > 4096) fail("expression exceeds depth16 or4096 values");
        if (!expression.isObject() || expression.size() != 1) fail("expression requires exactly one value, parameterId, operation or object");
        Result result;
        if (expression.has("parameterId")) {
            String id = StationProgramValidator.text(expression.path("parameterId"), "parameter reference");
            String type = parameters.get(id);
            if (type == null) throw new IllegalArgumentException("unknown parameter: " + id);
            result = new Result(type, arguments == null ? null : arguments.path(id));
        } else if (expression.has("value")) {
            JsonNode value = expression.path("value");
            ProgramJsonValidator.value(value);
            String type = value.isNumber() ? "NUMBER" : value.isTextual() ? "STRING" : value.isObject() ? "JSON_OBJECT" : value.isArray() ? ("ID_LIST".equals(expected) ? expected : "ARRAY") : value.isBoolean() ? "BOOLEAN" : "NULL";
            result = new Result(type, value);
        } else if (expression.has("object")) {
            JsonNode object = expression.path("object");
            if (!object.isObject() || object.size() > 128) fail("object expression requires at most128 entries");
            ObjectNode value = JSON.objectNode(); boolean bound = true;
            var entries = object.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                if (entry.getKey().isBlank() || entry.getKey().length() > 256) fail("object key must be nonblank and at most256 characters");
                Result child = walk(entry.getValue(), null, parameters, arguments, depth + 1, nodes);
                if (child.value() == null) bound = false; else value.set(entry.getKey(), child.value());
            }
            result = new Result("JSON_OBJECT", bound ? value : null);
        } else if (expression.has("operation")) {
            JsonNode operation = expression.path("operation");
            StationProgramValidator.fields(operation, Set.of("type", "operands"), "operation");
            String type = StationProgramValidator.text(operation.path("type"), "operation type");
            List<String> inputs = switch (type) {
                case "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "MODULO", "MIN", "MAX" -> List.of("NUMBER", "NUMBER");
                case "ROUND", "FLOOR", "CEIL", "TO_STRING" -> List.of("NUMBER");
                case "CONCAT" -> List.of("STRING", "STRING");
                case "SUBSTRING" -> List.of("STRING", "NUMBER", "NUMBER");
                case "REPLACE" -> List.of("STRING", "STRING", "STRING");
                case "TEXT_LENGTH", "TO_NUMBER" -> List.of("STRING");
                default -> throw new IllegalArgumentException("unknown operation: " + type);
            };
            JsonNode operands = operation.path("operands");
            if (!operands.isArray() || operands.size() != inputs.size()) fail("invalid operand count for " + type);
            List<JsonNode> values = new ArrayList<>(); boolean bound = true;
            for (int i = 0; i < inputs.size(); i++) {
                JsonNode value = walk(operands.get(i), inputs.get(i), parameters, arguments, depth + 1, nodes).value();
                values.add(value); if (value == null) bound = false;
            }
            String output = Set.of("CONCAT", "SUBSTRING", "REPLACE", "TO_STRING").contains(type) ? "STRING" : "NUMBER";
            result = new Result(output, bound ? calculate(type, values) : null);
        } else throw new IllegalArgumentException("unknown expression");
        if (expected != null && !compatible(result.type(), expected)) fail("incompatible expression: expected " + expected);
        if (result.value() != null) {
            if (result.type() != null && NUMERIC.contains(result.type())) number(result.value().doubleValue());
            if (result.value().isTextual() && result.value().textValue().length() > 8192) fail("string exceeds8192 characters");
        }
        return result;
    }

    private static boolean compatible(String actual, String expected) {
        return expected.equals(actual) || NUMERIC.contains(expected) && actual != null && NUMERIC.contains(actual);
    }

    private static JsonNode calculate(String type, List<JsonNode> values) {
        double a = values.get(0).asDouble(), b = values.size() > 1 ? values.get(1).asDouble() : 0;
        String text = values.get(0).asText();
        switch (type) {
            case "CONCAT": return string(text + values.get(1).asText());
            case "SUBSTRING": {
                double end = values.get(2).doubleValue();
                if (b != Math.rint(b) || end != Math.rint(end) || b < 0 || end < b || end > text.length()) fail("invalid substring bounds");
                return string(text.substring((int) b, (int) end));
            }
            case "REPLACE": {
                String search = values.get(1).asText(), replacement = values.get(2).asText();
                if (search.isEmpty()) fail("replacement search cannot be empty");
                int count = 0, position = 0, next;
                while ((next = text.indexOf(search, position)) >= 0) { count++; position = next + search.length(); }
                long length = text.length() + (long) count * (replacement.length() - search.length());
                if (length > 8192) fail("string exceeds8192 characters");
                return string(text.replace(search, replacement));
            }
            case "TO_STRING": return string(plainNumber(a));
            case "TO_NUMBER": {
                String decimal = trimDecimal(text);
                if (!DECIMAL.matcher(decimal).matches()) fail("text must be a decimal number");
                return number(Double.parseDouble(decimal));
            }
            case "TEXT_LENGTH": return number(text.length());
            default: {
                if ((type.equals("DIVIDE") || type.equals("MODULO")) && b == 0) fail("division by zero");
                return number(switch (type) {
                    case "ADD" -> a + b; case "SUBTRACT" -> a - b; case "MULTIPLY" -> a * b;
                    case "DIVIDE" -> a / b; case "MODULO" -> a % b;
                    case "MIN" -> Math.min(a, b); case "MAX" -> Math.max(a, b);
                    case "ROUND" -> { double floor = Math.floor(a); yield a - floor >= 0.5 ? floor + 1 : floor; }
                    case "FLOOR" -> Math.floor(a); case "CEIL" -> Math.ceil(a);
                    default -> throw new IllegalArgumentException("unknown operation: " + type);
                });
            }
        }
    }

    /** Shortest round-tripping decimal, nearest to the exact binary value; ties choose even. */
    private static String plainNumber(double number) {
        if (number == 0) return "0";
        BigDecimal exact = new BigDecimal(number);
        Comparator<BigDecimal> nearest = Comparator.comparing((BigDecimal candidate) -> candidate.subtract(exact).abs())
            .thenComparing(candidate -> candidate.unscaledValue().abs().testBit(0));
        for (int precision = 1; precision <= 17; precision++) {
            BigDecimal lower = exact.round(new MathContext(precision, RoundingMode.FLOOR)).stripTrailingZeros();
            BigDecimal upper = exact.round(new MathContext(precision, RoundingMode.CEILING)).stripTrailingZeros();
            BigDecimal best = null;
            if (lower.doubleValue() == number) best = lower;
            if (upper.doubleValue() == number && (best == null || nearest.compare(upper, best) < 0)) best = upper;
            if (best != null) return best.toPlainString();
        }
        throw new IllegalArgumentException("finite double has no round-tripping decimal");
    }

    private static String trimDecimal(String text) {
        return text.replaceAll("^[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+|[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+$", "");
    }

    private static JsonNode string(String value) {
        if (value.length() > 8192) fail("string exceeds8192 characters");
        return JSON.textNode(value);
    }
    private static JsonNode number(double value) {
        if (!Double.isFinite(value) || Math.rint(value) == value && Math.abs(value) > 9_007_199_254_740_991d) fail("number must be finite and integral values must be safe integers");
        return Math.rint(value) == value ? JSON.numberNode((long) value) : JSON.numberNode(value);
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
