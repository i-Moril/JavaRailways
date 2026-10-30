package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Offline helper: use exactly the candidate JAR's validators and content identity. */
public final class DutyTimelineMigrationBridge {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode input = mapper.readTree(System.in);
        ObjectNode output = mapper.createObjectNode();
        if (args[0].equals("identity")) {
            StationProgramValidator.validate(input);
            output.put("identity", ProgramContentIdentity.of(input));
        } else {
            for (JsonNode document : input.path("documents")) EditorDocumentValidator.validate(document);
            for (JsonNode record : input.path("library")) {
                StationProgramValidator.fields(record, java.util.Set.of("formatVersion", "id", "name", "revision", "definition", "contentIdentity"), "program library record");
                if (!record.path("formatVersion").isIntegralNumber() || record.path("formatVersion").asLong() != 1
                    || !record.path("id").isTextual() || !record.path("id").asText().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                    || !record.path("name").isTextual() || record.path("name").asText().isBlank()
                    || record.path("name").asText().length() > 256) throw new IllegalArgumentException("invalid record");
                StationProgramValidator.revision(record.path("revision"));
                StationProgramValidator.validate(record.path("definition"));
                if (!ProgramContentIdentity.of(record.path("definition")).equals(record.path("contentIdentity").asText())) throw new IllegalArgumentException("content identity mismatch");
            }
            output.put("documents", input.path("documents").size());
            output.put("library", input.path("library").size());
        }
        System.out.write(mapper.writeValueAsBytes(output));
    }
}
