package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class VisualProgramRepositoryTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private VisualProgramRepository repository() {
        return new VisualProgramRepository(directory, mapper, definition -> {
            if (!definition.isObject()) throw new IllegalArgumentException("definition must be an object");
        });
    }
    private JsonNode definition() throws Exception { return mapper.readTree("{\"parameters\":[],\"steps\":[]}"); }

    @Test void dynamicStopIsNotAnAuthoredTimetableProgramAction() throws Exception {
        JsonNode selection = mapper.readTree("""
            {"parameters":[{"id":"station","type":"STATION"},{"id":"berths","type":"ID_LIST"}],"steps":[
              {"id":"choose","type":"SELECT_STOP","stationId":{"parameterId":"station"},"berthIds":{"parameterId":"berths"},"selectionPolicy":"AVAILABLE_PRIORITY"}]}
            """);
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateArguments(selection, mapper.readTree("{\"station\":\"central\",\"berths\":[\"long\",\"short\"]}")));
        for (String ids : java.util.List.of("[]", "[\"same\",\"same\"]")) {
            assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateArguments(selection,
                mapper.readTree("{\"station\":\"central\",\"berths\":" + ids + "}")));
        }
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validate(mapper.readTree(selection.toString().replace("STATION", "DESTINATION"))));
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validate(mapper.readTree(selection.toString().replace("AVAILABLE_PRIORITY", "RANDOM"))));
    }

    @Test void nestedBranchesValidateParametersAndRejectFixedOperations() throws Exception {
        JsonNode branch = mapper.readTree("""
            {"parameters":[{"id":"duration","type":"DURATION"}],"steps":[
              {"id":"choose","type":"BRANCH","typeId":"javarailways:plan_duty_id","version":1,"parameters":{"value":{"values":["d"]}},
                "thenSteps":[{"id":"wait","type":"WAIT_DURATION","durationTicks":{"parameterId":"duration"}}],"elseSteps":[]}]}
            """);
        assertDoesNotThrow(() -> StationProgramValidator.validateArguments(branch, mapper.readTree("{\"duration\":1}")));
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateArguments(branch, mapper.readTree("{\"duration\":0}")));
        var forbidden = branch.deepCopy();
        var children = (com.fasterxml.jackson.databind.node.ArrayNode) forbidden.path("steps").get(0).path("elseSteps");
        var store = children.addObject().put("id", "fixed").put("type", "STORE_AT_DEPOT");
        store.putObject("depotId").put("value", "depot");
        store.putObject("stablingPointIds").putArray("value");
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validate(forbidden));
    }

    @Test void revisionCheckedDeletionSurvivesReloadWithoutMutatingBoundValues() throws Exception {
        VisualProgramRepository repository = repository();
        JsonNode bound = repository.create("world", "turnback", "Turnback", definition(), null);
        JsonNode updated = repository.update("world", "turnback", 1, "New name", definition(), null).orElseThrow();
        assertEquals(2, updated.path("revision").asInt());
        assertEquals("Turnback", bound.path("name").asText());
        assertEquals(1, bound.path("revision").asInt());
        assertTrue(repository.list("other").isEmpty());
        assertThrows(VisualProgramRepository.Conflict.class, () -> repository.update("world", "turnback", 1, "Stale", definition(), null));
        assertThrows(VisualProgramRepository.Conflict.class, () -> repository.create("world", "turnback", "Duplicate", definition(), null));
        byte[] beforeStaleDelete = Files.readAllBytes(directory.resolve("world/turnback.json"));
        assertThrows(VisualProgramRepository.Conflict.class, () -> repository.delete("world", "turnback", 1));
        assertArrayEquals(beforeStaleDelete, Files.readAllBytes(directory.resolve("world/turnback.json")));
        assertEquals(2, repository().find("world", "turnback").orElseThrow().path("revision").asInt());
        assertFalse(repository.delete("other", "turnback", 2));
        assertTrue(repository.delete("world", "turnback", 2));
        assertTrue(repository().find("world", "turnback").isEmpty());
        assertTrue(repository().list("world").isEmpty());
        assertFalse(Files.exists(directory.resolve("world/turnback.json")));
        assertFalse(repository.delete("world", "turnback", 2));
        assertEquals(1, bound.path("revision").asInt());
    }

    @Test void fixedRecordWithoutOptionalEstimateLoadsAndCanBeUpdatedAndCleared() throws Exception {
        Files.createDirectories(directory.resolve("world"));
        Files.writeString(directory.resolve("world/plain.json"), """
            {"formatVersion":1,"id":"plain","name":"Plain","revision":1,
             "contentIdentity":"sha256:358c449f2f98a213bf02d367456a91eebb7611aeadf618c32a1e75e6e1e131c5",
             "definition":{"parameters":[],"steps":[]}}
            """);
        VisualProgramRepository repository = repository();
        assertFalse(repository.find("world", "plain").orElseThrow().has("estimatedDurationTicks"));
        assertEquals(10, repository.update("world", "plain", 1, "Plain", definition(), mapper.readTree("10")).orElseThrow().path("estimatedDurationTicks").asInt());
        repository.update("world", "plain", 2, "Plain", definition(), null).orElseThrow();
        assertFalse(repository().find("world", "plain").orElseThrow().has("estimatedDurationTicks"));
    }

    @Test void estimateMetadataPersistsWithoutChangingExecutableIdentityAndCanBeCleared() throws Exception {
        VisualProgramRepository repository = repository();
        JsonNode baseline = repository.create("world", "estimate", "Estimate", definition(), null);
        assertFalse(baseline.has("estimatedDurationTicks"));
        JsonNode estimated = repository.update("world", "estimate", 1, "Estimate", definition(), mapper.readTree("120")).orElseThrow();
        assertEquals(120, repository().find("world", "estimate").orElseThrow().path("estimatedDurationTicks").asInt());
        assertEquals(baseline.path("contentIdentity"), estimated.path("contentIdentity"));
        assertEquals(2, estimated.path("revision").asInt());
        JsonNode zero = repository.update("world", "estimate", 2, "Estimate", definition(), mapper.readTree("0")).orElseThrow();
        assertEquals(0, zero.path("estimatedDurationTicks").asInt());
        assertFalse(repository.update("world", "estimate", 3, "Estimate", definition(), null).orElseThrow().has("estimatedDurationTicks"));
        for (String invalid : java.util.List.of("-1", "1.5", "9007199254740992", "null", "\"20\"")) {
            assertThrows(IllegalArgumentException.class, () -> repository.update("world", "estimate", 4, "Estimate", definition(), mapper.readTree(invalid)));
        }
        assertEquals(4, repository.find("world", "estimate").orElseThrow().path("revision").asInt());
    }

    @Test void contentIdentityIgnoresObjectOrderButPreservesStepOrderAndValues() throws Exception {
        assertEquals(ProgramContentIdentity.of(mapper.readTree("{\"a\":1,\"b\":[{\"x\":2,\"y\":3}]}")),
            ProgramContentIdentity.of(mapper.readTree("{\"b\":[{\"y\":3,\"x\":2}],\"a\":1}")));
        assertNotEquals(ProgramContentIdentity.of(mapper.readTree("[1,2]")), ProgramContentIdentity.of(mapper.readTree("[2,1]")));
        assertNotEquals(ProgramContentIdentity.of(mapper.readTree("{\"tick\":1}")), ProgramContentIdentity.of(mapper.readTree("{\"tick\":2}")));
    }

    @Test void concurrentExpectedRevisionAllowsExactlyOneWinner() throws Exception {
        VisualProgramRepository repository = repository();
        repository.create("world", "turnback", "Initial", definition(), null);
        Callable<Boolean> write = () -> {
            try { repository.update("world", "turnback", 1, "Winner", definition(), null); return true; }
            catch (VisualProgramRepository.Conflict conflict) { return false; }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var results = pool.invokeAll(java.util.List.of(write, write));
            assertNotEquals(results.get(0).get(), results.get(1).get());
        } finally { pool.shutdownNow(); }
        assertEquals(2, repository.find("world", "turnback").orElseThrow().path("revision").asInt());
    }

    @Test void rejectedWritesLeavePreviousFileAndNoTemporaryFiles() throws Exception {
        VisualProgramRepository repository = repository();
        repository.create("world", "turnback", "Initial", definition(), null);
        assertThrows(IllegalArgumentException.class, () -> repository.create("../outside", "x", "Name", definition(), null));
        assertThrows(IllegalArgumentException.class, () -> repository.create("world", "../outside", "Name", definition(), null));
        assertThrows(IllegalArgumentException.class, () -> repository.delete("../outside", "turnback", 1));
        assertThrows(IllegalArgumentException.class, () -> repository.delete("world", "../outside", 1));
        assertThrows(IllegalArgumentException.class, () -> repository.update("world", "turnback", 1, "", definition(), null));
        assertThrows(IllegalArgumentException.class, () -> repository.update("world", "turnback", 1, "Invalid", mapper.createArrayNode(), null));
        assertTrue(repository.update("world", "missing", 1, "Missing", definition(), null).isEmpty());
        assertEquals("Initial", repository.find("world", "turnback").orElseThrow().path("name").asText());
        try (var paths = Files.walk(directory)) { assertFalse(paths.anyMatch(path -> path.toString().endsWith(".tmp"))); }
    }
}
