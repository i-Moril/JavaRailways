package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit offline migration helper; never loaded by the server. */
public final class ProgramStringTypesMigrationBridge {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode input = mapper.readTree(System.in);
        var output = mapper.createObjectNode();
        switch (args[0]) {
            case "rawIdentity" -> output.put("identity", ProgramContentIdentity.of(input));
            case "identity" -> {
                StationProgramValidator.validate(input);
                output.put("identity", ProgramContentIdentity.of(input));
            }
            case "validate-document" -> {
                EditorDocumentValidator.validate(input);
                output.put("valid", true);
            }
            case "validate-record" -> {
                // Load through the real repository codec, including optional metadata.
                Path temporary = Files.createTempDirectory("jr-program-string-codec-");
                try {
                    String id = StationProgramValidator.text(input.path("id"), "record id");
                    if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) throw new IllegalArgumentException("invalid record id");
                    Path world = Files.createDirectory(temporary.resolve("world"));
                    mapper.writeValue(world.resolve(id + ".json").toFile(), input);
                    new VisualProgramRepository(temporary, mapper, StationProgramValidator::validate).find("world", id).orElseThrow();
                    output.put("valid", true);
                } finally {
                    try (var paths = Files.walk(temporary)) {
                        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                    }
                }
            }
            default -> throw new IllegalArgumentException("unknown offline operation");
        }
        System.out.write(mapper.writeValueAsBytes(output));
    }
}
