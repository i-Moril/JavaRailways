package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DraftRepositoryTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void savesAndReadsJsonDraft() throws Exception {
        DraftRepository repository = new DraftRepository(directory, mapper);
        repository.save("default", "weekday_1", mapper.readTree("{\"name\":\"Weekday\"}"));
        assertEquals("Weekday", repository.find("default", "weekday_1").orElseThrow().path("name").asText());
    }
    @Test void listsCreatesAndDeletesWorldScopedDrafts() throws Exception {
        DraftRepository repository = new DraftRepository(directory, mapper);
        String id = repository.create("world", mapper.readTree("{\"id\":\"weekday\"}"));
        assertEquals("weekday", id);
        assertEquals("weekday", repository.list("world").get(0).path("id").asText());
        assertTrue(repository.delete("world", id));
        assertTrue(repository.list("world").isEmpty());
    }

    @Test void rejectsTraversalAndNonObjectDrafts() throws Exception {
        DraftRepository repository = new DraftRepository(directory, mapper);
        assertThrows(IllegalArgumentException.class, () -> repository.save("default", "../outside", mapper.createObjectNode()));
        assertThrows(IllegalArgumentException.class, () -> repository.save("default", "valid", mapper.readTree("[]")));
    }
}
