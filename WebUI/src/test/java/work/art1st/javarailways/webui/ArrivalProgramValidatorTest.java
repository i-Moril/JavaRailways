package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArrivalProgramValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void stationaryArrivalActionsValidateButMotionAndParametersFail() throws Exception {
        assertDoesNotThrow(() -> StationProgramValidator.validateArrival(mapper.readTree("""
          {"parameters":[],"steps":[{"id":"wait","type":"WAIT_DURATION","durationTicks":{"value":10}},
           {"id":"branch","type":"BRANCH","condition":{"operation":{"type":"EQUALS","operands":[{"operation":{"type":"PLAN_DUTY_ID","operands":[]}},{"value":"d"}]}},
            "thenSteps":[{"id":"until","type":"WAIT_UNTIL","tick":{"value":500}}],"elseSteps":[]}]}
          """)));
        for(String definition : new String[]{
            "{\"parameters\":[{\"id\":\"param\",\"type\":\"STRING\"}],\"steps\":[]}",
            "{\"parameters\":[],\"steps\":[{\"id\":\"reverse\",\"type\":\"REVERSE\"}]}"
        }) assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateArrival(mapper.readTree(definition)));
    }
    @Test void persistedRunCallsUseCurrentFieldSet() throws Exception {
        String document = """
          {"formatVersion":1,"id":"doc","periodDays":1,"minimumDwellTicks":20,"duties":[],"diagramLines":[],"arrivalOverrides":[],
           "runs":[{"id":"run","type":"PASSENGER","lineDirection":"UP","calls":[
             {"id":"call","stationId":"station","berthId":"platform","status":"TERMINAL","targetId":"destination",
              "arrivalTick":100,"departureTick":120,"stoppingOffsetMetersBefore":2,
              "doorSide":"LEFT","viaIds":["junction"]}]}]}
          """;
        var current = mapper.readTree(document);
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(current));
        assertEquals(20, current.path("minimumDwellTicks").asInt());
        var call = (com.fasterxml.jackson.databind.node.ObjectNode) current.path("runs").get(0).path("calls").get(0);
        call.remove(java.util.List.of("berthId", "arrivalTick", "departureTick", "stoppingOffsetMetersBefore", "doorSide", "viaIds"));
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(current));
        call.put("unexpectedField", true);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(current));
        assertTrue(failure.getMessage().contains("unexpectedField"));
    }
}
