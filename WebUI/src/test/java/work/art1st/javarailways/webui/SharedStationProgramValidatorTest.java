package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SharedStationProgramValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void currentDocumentPersistsSharedStationsEventsAndReferenceVehicle() throws Exception {
        var document = mapper.readTree("""
            {"formatVersion":1,"id":"weekday","periodDays":1,"runs":[],"diagramLines":[],"arrivalOverrides":[],
             "events":[{"id":"turn","stationId":"central","tick":200,"label":"turnback"}],
             "stationPrograms":[{"stationId":"central","preparationLeadTicks":10,
               "arrival":{"parameters":[],"steps":[{"id":"open","type":"OPEN_DOORS"},{"id":"end","type":"END"}]},
               "departurePreparation":{"parameters":[],"steps":[{"id":"reverse","type":"REVERSE"},{"id":"close","type":"CLOSE_DOORS"},{"id":"end","type":"END"}]}}],
             "duties":[{"id":"d","selector":{"type":"TRAIN_NAME","name":"train"},"elements":[],
               "referenceVehicle":{"type":"TRAIN_TEMPLATE","id":"emu"}}]}
            """);
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document));
        assertEquals(document, mapper.readTree(mapper.writeValueAsString(document)));
    }

    @Test void stationaryActionsAndTaskTreesValidateButMovementAndDynamicConditionsFail() throws Exception {
        assertDoesNotThrow(() -> StationProgramValidator.validateStation(mapper.readTree("""
            {"parameters":[],"steps":[{"id":"launch","type":"ASYNC","taskId":"announcement","steps":[
              {"id":"wait","type":"WAIT_EVENT","eventId":"turn","offsetTicks":-10}]},
              {"id":"join","type":"AWAIT","taskId":"announcement"},{"id":"end","type":"END"}]}
            """)));
        for (String step : new String[]{
            "{\"id\":\"move\",\"type\":\"MOVE\",\"destinationId\":{\"value\":\"B\"},\"viaIds\":{\"value\":[]}}",
            "{\"id\":\"dynamic\",\"type\":\"BRANCH\",\"typeId\":\"javarailways:train_model\",\"version\":1,\"parameters\":{\"value\":{}},\"thenSteps\":[],\"elseSteps\":[]}"
        }) assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateStation(mapper.readTree("{\"parameters\":[],\"steps\":[" + step + "]}")));
    }

    @Test void programDurationAndInitialPositionUseCurrentModel() throws Exception {
        var definition = mapper.readTree("{\"parameters\":[],\"steps\":[]}");
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("""
            {"formatVersion":1,"id":"weekday","periodDays":1,"runs":[],"diagramLines":[],"arrivalOverrides":[],
             "duties":[{"id":"d","selector":{"type":"TRAIN_NAME","name":"train"},"initialPosition":"A","elements":[
               {"id":"1","type":"PROGRAM","call":{"id":"1","programId":"1","tick":100,"arguments":{}},"duration":{"type":"MANUAL","ticks":20}}]}]}
            """);
        var snapshot = mapper.createObjectNode().put("programId", "1").put("revision", 1).put("contentIdentity", ProgramContentIdentity.of(definition));
        snapshot.set("definition", definition);
        document.putArray("boundPrograms").add(snapshot);
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document));
        var duration = (com.fasterxml.jackson.databind.node.ObjectNode) document.path("duties").get(0).path("elements").get(0).path("duration");
        duration.put("ticks", -1);
        assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(document));
        duration.remove("ticks");duration.put("type", "SIMULATED");
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document));
        duration.put("ticks", 20);
        assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(document));
    }

    @Test void asynchronousEndCannotReleaseStationScope() {
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateStation(mapper.readTree("""
            {"parameters":[],"steps":[{"id":"launch","type":"ASYNC","taskId":"t","steps":[{"id":"bad","type":"END"}]},{"id":"end","type":"END"}]}
            """)));
    }
}
