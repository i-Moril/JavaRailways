package work.art1st.minerails.webui;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** World-scoped atomic JSON draft storage. */
public final class DraftRepository {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private final Path directory;
    private final ObjectMapper mapper;

    public DraftRepository(Path directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }

    public Optional<JsonNode> find(String worldId, String id) throws IOException {
        Path file = file(worldId, id);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(mapper.readTree(Files.readAllBytes(file)));
    }

    /** Backward-compatible helper for local repository tests. */
    public Optional<JsonNode> find(String id) throws IOException {
        return find("default", id);
    }

    public List<ObjectNode> list(String worldId) throws IOException {
        Path worldDirectory = worldDirectory(worldId);
        if (!Files.isDirectory(worldDirectory)) return List.of();
        List<ObjectNode> result = new ArrayList<>();
        try (var files = Files.list(worldDirectory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .forEach(path -> {
                    try {
                        String fileName = path.getFileName().toString();
                        ObjectNode item = mapper.createObjectNode();
                        item.put("id", fileName.substring(0, fileName.length() - 5));
                        item.put("updatedAt", Files.getLastModifiedTime(path).toInstant().toString());
                        result.add(item);
                    } catch (IOException exception) {
                        throw new DraftReadException(exception);
                    }
                });
        } catch (DraftReadException exception) {
            throw exception.ioException;
        }
        return result;
    }

    public String create(String worldId, JsonNode draft) throws IOException {
        String preferred = draft.path("id").asText("draft");
        String base = sanitize(preferred);
        String candidate = base;
        int suffix = 2;
        while (Files.exists(file(worldId, candidate))) candidate = base + "-" + suffix++;
        save(worldId, candidate, draft);
        return candidate;
    }

    public void save(String worldId, String id, JsonNode draft) throws IOException {
        if (draft == null || !draft.isObject()) {
            throw new IllegalArgumentException("draft must be a JSON object");
        }
        Path target = file(worldId, id);
        Files.createDirectories(target.getParent());
        Path temporary = target.getParent().resolve("." + id + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, mapper.writeValueAsBytes(draft));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Backward-compatible helper for local repository tests. */
    public void save(String id, JsonNode draft) throws IOException {
        save("default", id, draft);
    }

    public boolean delete(String worldId, String id) throws IOException {
        return Files.deleteIfExists(file(worldId, id));
    }

    private Path file(String worldId, String id) {
        requireId(id, "draft id");
        return worldDirectory(worldId).resolve(id + ".json");
    }

    private Path worldDirectory(String worldId) {
        requireId(worldId, "world id");
        return directory.resolve(worldId);
    }

    private static void requireId(String value, String label) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }

    private static String sanitize(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9_-]", "-");
        if (sanitized.isBlank() || !Character.isLetterOrDigit(sanitized.charAt(0))) sanitized = "draft";
        return sanitized.substring(0, Math.min(100, sanitized.length()));
    }

    private static final class DraftReadException extends RuntimeException {
        private final IOException ioException;
        private DraftReadException(IOException ioException) {
            this.ioException = ioException;
        }
    }
}
