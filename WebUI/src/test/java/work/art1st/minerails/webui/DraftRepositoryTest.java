package work.art1st.minerails.webui;

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
        repository.save("weekday_1", mapper.readTree("{\"name\":\"Weekday\"}"));
        assertEquals("Weekday", repository.find("weekday_1").orElseThrow().path("name").asText());
    }
    @Test void rejectsTraversalAndNonObjectDrafts() throws Exception {
        DraftRepository repository = new DraftRepository(directory, mapper);
        assertThrows(IllegalArgumentException.class, () -> repository.save("../outside", mapper.createObjectNode()));
        assertThrows(IllegalArgumentException.class, () -> repository.save("valid", mapper.readTree("[]")));
    }
}
