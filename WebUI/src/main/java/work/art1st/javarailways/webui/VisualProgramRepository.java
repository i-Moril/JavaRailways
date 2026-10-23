package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Authoring-only, world-scoped program library. Runtime executes embedded snapshots. */
public final class VisualProgramRepository {
    private final DraftRepository storage;
    private final ObjectMapper mapper;
    private final Consumer<JsonNode> validateDefinition;

    public VisualProgramRepository(Path directory, ObjectMapper mapper, Consumer<JsonNode> validateDefinition) {
        this.storage = new DraftRepository(directory, mapper);
        this.mapper = mapper;
        this.validateDefinition = java.util.Objects.requireNonNull(validateDefinition);
    }

    public synchronized List<JsonNode> list(String world) throws IOException {
        List<JsonNode> result = new ArrayList<>();
        for (ObjectNode item : storage.list(world)) {
            result.add(find(world, item.path("id").asText()).orElseThrow());
        }
        return result;
    }

    public synchronized Optional<JsonNode> find(String world, String id) throws IOException {
        Optional<JsonNode> record = storage.find(world, id);
        record.ifPresent(value -> validateRecord(value, id));
        return record;
    }

    public synchronized JsonNode create(String world, String id, String name, JsonNode definition) throws IOException {
        validateInput(name, definition);
        if (find(world, id).isPresent()) throw new Conflict("program ID already exists");
        ObjectNode record = mapper.createObjectNode();
        record.put("formatVersion", 1).put("id", id).put("name", name).put("revision", 1).put("archived", false);
        record.set("definition", definition.deepCopy());
        record.put("contentIdentity", ProgramContentIdentity.of(definition));
        storage.save(world, id, record);
        return record.deepCopy();
    }

    public synchronized Optional<JsonNode> update(String world, String id, long expectedRevision, String name, JsonNode definition) throws IOException {
        validateInput(name, definition);
        Optional<JsonNode> previous = find(world, id);
        if (previous.isEmpty()) return Optional.empty();
        checkRevision(previous.get(), expectedRevision);
        if (previous.get().path("archived").asBoolean()) throw new Conflict("archived programs cannot be edited");
        ObjectNode record = previous.get().deepCopy();
        record.put("name", name).put("revision", nextRevision(expectedRevision));
        record.set("definition", definition.deepCopy());
        record.put("contentIdentity", ProgramContentIdentity.of(definition));
        storage.save(world, id, record);
        return Optional.of(record.deepCopy());
    }

    public synchronized Optional<JsonNode> archive(String world, String id, long expectedRevision) throws IOException {
        Optional<JsonNode> previous = find(world, id);
        if (previous.isEmpty()) return Optional.empty();
        checkRevision(previous.get(), expectedRevision);
        if (previous.get().path("archived").asBoolean()) return previous;
        ObjectNode record = previous.get().deepCopy();
        record.put("archived", true).put("revision", nextRevision(expectedRevision));
        storage.save(world, id, record);
        return Optional.of(record.deepCopy());
    }

    private void validateRecord(JsonNode record, String id) {
        StationProgramValidator.fields(record, java.util.Set.of("formatVersion", "id", "name", "revision", "archived", "definition", "contentIdentity"), "program library record");
        if (!record.isObject() || !record.path("formatVersion").isIntegralNumber() || record.path("formatVersion").asLong() != 1
            || !record.path("id").isTextual() || !id.equals(record.path("id").asText())
            || !record.path("revision").isIntegralNumber() || !record.path("revision").canConvertToLong() || record.path("revision").asLong() < 1 || record.path("revision").asLong() > 9_007_199_254_740_991L
            || !record.path("archived").isBoolean()) throw new IllegalArgumentException("invalid program library record");
        validateInput(record.path("name").isTextual() ? record.path("name").asText() : null, record.path("definition"));
        if (!ProgramContentIdentity.of(record.path("definition")).equals(record.path("contentIdentity").asText())) {
            throw new IllegalArgumentException("program content identity mismatch");
        }
    }

    private void validateInput(String name, JsonNode definition) {
        if (name == null || name.isBlank() || name.length() > 256) throw new IllegalArgumentException("program name must contain 1–256 characters");
        validateDefinition.accept(definition);
    }

    private static void checkRevision(JsonNode record, long revision) {
        if (revision < 1 || record.path("revision").asLong() != revision) throw new Conflict("program revision conflict; reload the library entry");
    }

    private static long nextRevision(long revision) {
        if (revision >= 9_007_199_254_740_991L) throw new Conflict("program revision exhausted");
        return revision + 1;
    }

    public static final class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }
}
