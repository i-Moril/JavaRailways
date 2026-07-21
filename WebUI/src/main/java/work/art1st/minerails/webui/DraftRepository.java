package work.art1st.minerails.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stores each draft as a single JSON file. A rename makes readers see either revision, never a partial write. */
public final class DraftRepository {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    private final Path directory;
    private final ObjectMapper mapper;

    public DraftRepository(Path directory, ObjectMapper mapper) { this.directory = directory; this.mapper = mapper; }

    public Optional<JsonNode> find(String id) throws IOException {
        Path file = file(id);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(mapper.readTree(Files.readAllBytes(file)));
    }

    public void save(String id, JsonNode draft) throws IOException {
        if (draft == null || !draft.isObject()) throw new IllegalArgumentException("draft must be a JSON object");
        Path target = file(id);
        Files.createDirectories(directory);
        Path temporary = directory.resolve("." + id + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, mapper.writeValueAsBytes(draft));
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private Path file(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("invalid draft id");
        return directory.resolve(id + ".json");
    }
}
