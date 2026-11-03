package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Programs owned by one timetable document; no shared library or program revisions. */
final class TimetableProgramRepository {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private final Path directory;
    private final ObjectMapper mapper;

    TimetableProgramRepository(Path directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }

    synchronized List<JsonNode> list(String world, String document) throws IOException {
        Path folder = folder(world, document);
        if (!Files.isDirectory(folder)) return List.of();
        List<JsonNode> result = new ArrayList<>();
        try (var files = Files.list(folder)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(path -> {
                    try {
                        result.add(read(path));
                    } catch (IOException exception) {
                        throw new ProgramReadException(exception);
                    }
                });
        } catch (ProgramReadException exception) {
            throw exception.ioException;
        }
        result.sort(Comparator.comparing((JsonNode record) -> record.path("name").asText())
            .thenComparing(record -> record.path("id").asText()));
        return result;
    }

    synchronized Optional<JsonNode> find(String world, String document, String program) throws IOException {
        Path path = file(world, document, program);
        return Files.isRegularFile(path) ? Optional.of(read(path)) : Optional.empty();
    }

    synchronized JsonNode create(String world, String document, String id, String name, JsonNode definition, JsonNode estimatedDurationTicks) throws IOException {
        requireId(id, "program id");
        validateInput(name, definition);
        validateEstimatedDuration(estimatedDurationTicks);
        Path path = file(world, document, id);
        if (Files.exists(path)) throw new IllegalArgumentException("program ID already exists");
        ObjectNode record = record(id, name, definition, estimatedDurationTicks);
        write(path, record);
        return record.deepCopy();
    }

    synchronized Optional<JsonNode> save(String world, String document, String id, String name, JsonNode definition, JsonNode estimatedDurationTicks) throws IOException {
        validateInput(name, definition);
        validateEstimatedDuration(estimatedDurationTicks);
        Path path = file(world, document, id);
        if (!Files.isRegularFile(path)) return Optional.empty();
        ObjectNode record = record(id, name, definition, estimatedDurationTicks);
        write(path, record);
        return Optional.of(record.deepCopy());
    }

    synchronized boolean delete(String world, String document, String id) throws IOException {
        return Files.deleteIfExists(file(world, document, id));
    }

    synchronized void deleteAll(String world, String document) throws IOException {
        Path folder = folder(world, document);
        if (!Files.isDirectory(folder)) return;
        try (var paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException exception) { throw new ProgramReadException(exception); }
            });
        } catch (ProgramReadException exception) {
            throw exception.ioException;
        }
    }

    /** Copy selected source programs into the target timetable, renaming only local ID collisions. */
    synchronized List<JsonNode> importPrograms(String world, String targetDocument, String sourceDocument, List<String> programIds) throws IOException {
        requireId(targetDocument, "target timetable id");
        requireId(sourceDocument, "source timetable id");
        if (targetDocument.equals(sourceDocument)) throw new IllegalArgumentException("source and target timetable must differ");
        List<JsonNode> source = list(world, sourceDocument);
        if (source.isEmpty()) throw new IllegalArgumentException("source timetable has no programs");
        Set<String> requested = programIds == null ? null : new HashSet<>(programIds);
        Set<String> existing = new HashSet<>();
        for (JsonNode item : list(world, targetDocument)) existing.add(item.path("id").asText());
        List<JsonNode> imported = new ArrayList<>();
        for (JsonNode item : source) {
            String sourceId = item.path("id").asText();
            if (requested != null && !requested.contains(sourceId)) continue;
            String targetId = existing.contains(sourceId) ? copyId(sourceId, existing) : sourceId;
            existing.add(targetId);
            JsonNode record = record(targetId, item.path("name").asText(), item.path("definition"), item.get("estimatedDurationTicks"));
            write(file(world, targetDocument, targetId), record);
            imported.add(record.deepCopy());
        }
        if (requested != null && imported.size() != requested.size()) throw new IllegalArgumentException("source program not found");
        return imported;
    }

    private static String copyId(String source, Set<String> used) {
        for (int suffix = 2; ; suffix++) {
            String candidate = source + "-copy-" + suffix;
            if (!used.contains(candidate)) return candidate;
        }
    }

    private ObjectNode record(String id, String name, JsonNode definition, JsonNode estimatedDurationTicks) {
        ObjectNode record = mapper.createObjectNode();
        record.put("formatVersion", 1).put("id", id).put("name", name);
        record.set("definition", definition.deepCopy());
        if (estimatedDurationTicks != null) record.set("estimatedDurationTicks", estimatedDurationTicks.deepCopy());
        return record;
    }

    private JsonNode read(Path path) throws IOException {
        JsonNode record = mapper.readTree(Files.readAllBytes(path));
        validateRecord(record, path.getFileName().toString().replaceFirst("\\.json$", ""));
        return record;
    }

    static void validateRecord(JsonNode record, String expectedId) {
        StationProgramValidator.fields(record, Set.of("formatVersion", "id", "name", "definition"),
            Set.of("estimatedDurationTicks"), "timetable program");
        if (record.path("formatVersion").asLong(-1) != 1) throw new IllegalArgumentException("unsupported timetable program version");
        String id = StationProgramValidator.text(record.path("id"), "program id");
        if (!id.equals(expectedId)) throw new IllegalArgumentException("program id does not match its file");
        validateInput(record.path("name").isTextual() ? record.path("name").asText() : null, record.path("definition"));
        validateEstimatedDuration(record.get("estimatedDurationTicks"));
    }

    static void validateEstimatedDuration(JsonNode value) {
        if (value == null) return;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0
            || value.asLong() > MAX_SAFE_INTEGER) throw new IllegalArgumentException("invalid program estimated duration");
    }

    private static void validateInput(String name, JsonNode definition) {
        if (name == null || name.isBlank() || name.length() > 256) throw new IllegalArgumentException("program name must contain 1–256 characters");
        StationProgramValidator.validate(definition);
    }

    private void write(Path path, JsonNode record) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.getParent().resolve("." + path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, mapper.writeValueAsBytes(record));
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path file(String world, String document, String program) {
        requireId(world, "world id");
        requireId(document, "timetable id");
        requireId(program, "program id");
        return folder(world, document).resolve(program + ".json");
    }

    private Path folder(String world, String document) {
        requireId(world, "world id");
        requireId(document, "timetable id");
        return directory.resolve(world).resolve(document);
    }

    private static void requireId(String value, String label) {
        if (value == null || !ID.matcher(value).matches()) throw new IllegalArgumentException("invalid " + label);
    }

    private static final class ProgramReadException extends RuntimeException {
        private final IOException ioException;
        private ProgramReadException(IOException ioException) { this.ioException = ioException; }
    }
}
