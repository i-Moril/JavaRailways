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
             "stationPrograms":[{"stationId":"central","preparationLead":{"value":10},
               "arrival":{"parameters":[],"steps":[{"id":"open","type":"OPEN_DOORS"},{"id":"end","type":"END"}]},
               "departurePreparation":{"parameters":[],"steps":[{"id":"reverse","type":"REVERSE"},{"id":"close","type":"CLOSE_DOORS"},{"id":"end","type":"END"}]}}],
             "duties":[{"id":"d","selector":{"type":"TRAIN_NAME","name":"train"},"elements":[],
               "referenceVehicle":{"type":"TRAIN_TEMPLATE","id":"emu"}}]}
            """);
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document, java.util.Map.of()));
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
               {"id":"1","type":"PROGRAM","tick":100,"program":{"programId":"1","arguments":{}},"duration":{"type":"MANUAL","ticks":20}}]}]}
            """);
        var program = mapper.createObjectNode();program.set("definition",definition);
        var programs = java.util.Map.<String, com.fasterxml.jackson.databind.JsonNode>of("1", program);
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document, programs));
        var duration = (com.fasterxml.jackson.databind.node.ObjectNode) document.path("duties").get(0).path("elements").get(0).path("duration");
        duration.put("ticks", -1);
        assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(document, programs));
        duration.remove("ticks");duration.put("type", "SIMULATED");
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document, programs));
        duration.put("ticks", 20);
        assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(document, programs));
    }

    @Test void asynchronousEndCannotReleaseStationScope() {
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateStation(mapper.readTree("""
            {"parameters":[],"steps":[{"id":"launch","type":"ASYNC","taskId":"t","steps":[{"id":"bad","type":"END"}]},{"id":"end","type":"END"}]}
            """)));
    }
    @Test void preparationTimingAcceptsOnlyBoundedNumericExpressions() throws Exception {
        for (String valid : new String[]{"{\"value\":0}", "{\"doorCloseTicks\":true}",
            "{\"operation\":{\"type\":\"ADD\",\"operands\":[{\"doorCloseTicks\":true},{\"value\":10}]}}"}) {
            assertDoesNotThrow(() -> StationPreparationLeadValidator.validate(mapper.readTree(valid)));
        }
        for (String invalid : new String[]{"{}", "{\"value\":-1}", "{\"value\":0.5}", "{\"value\":\"20\"}",
            "{\"doorCloseTicks\":false}", "{\"parameterId\":\"x\"}", "{\"trainInfo\":{}}", "{\"value\":9007199254740992}",
            "{\"operation\":{\"type\":\"DIVIDE\",\"operands\":[{\"value\":1},{\"value\":0}]}}",
            "{\"operation\":{\"type\":\"TEXT_LENGTH\",\"operands\":[{\"value\":\"x\"}]}}"}) {
            assertThrows(IllegalArgumentException.class, () -> StationPreparationLeadValidator.validate(mapper.readTree(invalid)), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> ProgramExpressionValidator.validate(
            mapper.readTree("{\"doorCloseTicks\":true}"), "NUMBER", java.util.Map.of(), null));
    }

}
