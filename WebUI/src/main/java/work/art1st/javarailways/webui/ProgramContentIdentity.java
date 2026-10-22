package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/** Hashes definition content only; JSON object order and library metadata are not identity. */
final class ProgramContentIdentity {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ProgramContentIdentity() {}

    static String of(JsonNode definition) {
        try {
            byte[] canonical = MAPPER.writeValueAsBytes(sorted(definition));
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("cannot compute program content identity", exception);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            node.fieldNames().forEachRemaining(names::add);
            for (String name : names) result.set(name, sorted(node.get(name)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            node.forEach(item -> result.add(sorted(item)));
            return result;
        }
        if (node.isNumber()) {
            double value = node.doubleValue();
            if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite program number");
            return com.fasterxml.jackson.databind.node.DoubleNode.valueOf(value == 0d ? 0d : value);
        }
        return node.deepCopy();
    }
}
