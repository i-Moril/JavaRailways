package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;

/** JSON data only. This is not a JavaScript or JSON-Schema expression interpreter. */
final class ProgramJsonValidator {
    private ProgramJsonValidator() {}
    static void object(JsonNode value) {
        if (!value.isObject()) throw new IllegalArgumentException("JSON_OBJECT must be a JSON object");
        walk(value, 0, new int[]{0});
    }
    private static void walk(JsonNode value, int depth, int[] nodes) {
        if (depth > 16 || ++nodes[0] > 4096) fail("JSON_OBJECT exceeds depth16 or4096 values");
        if (value.isObject()) {
            value.fieldNames().forEachRemaining(name -> { if (name.length() > 8192) fail("JSON object key exceeds8192 characters"); });
            value.forEach(child -> walk(child, depth + 1, nodes));
        } else if (value.isArray()) value.forEach(child -> walk(child, depth + 1, nodes));
        else if (value.isTextual()) { if (value.textValue().length() > 8192) fail("JSON string exceeds8192 characters"); }
        else if (value.isNumber()) {
            double number = value.doubleValue();
            if (!Double.isFinite(number) || Math.rint(number) == number && Math.abs(number) > 9_007_199_254_740_991d) fail("JSON number must be finite and integral values must be safe integers");
        } else if (!value.isBoolean() && !value.isNull()) fail("unsupported JSON value");
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
