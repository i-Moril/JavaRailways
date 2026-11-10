package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;

/** Wire-side mirror of the SimpleATO JSON metadata budget; no railway domain dependency. */
final class JsonMetadataValidator {
    private JsonMetadataValidator() {}

    static void validate(JsonNode value) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException("metadata must be a JSON object");
        visit(value, 0, new int[]{0});
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > 65_536) {
            throw new IllegalArgumentException("metadata exceeds 65536 bytes");
        }
    }

    private static void visit(JsonNode value, int depth, int[] nodes) {
        if (depth > 16 || ++nodes[0] > 4096) throw new IllegalArgumentException("metadata exceeds JSON depth or value budget");
        if (value.isNumber() && !Double.isFinite(value.doubleValue())) throw new IllegalArgumentException("metadata numbers must be finite");
        if (value.isContainerNode()) value.forEach(child -> visit(child, depth + 1, nodes));
    }
}
