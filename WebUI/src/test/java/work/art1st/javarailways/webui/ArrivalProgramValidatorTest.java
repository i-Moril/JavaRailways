package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArrivalProgramValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void stationaryArrivalActionsValidateButMotionAndParametersFail() throws Exception {
        assertDoesNotThrow(() -> StationProgramValidator.validateArrival(mapper.readTree("""
          {"parameters":[],"steps":[{"id":"wait","type":"WAIT_DURATION","durationTicks":{"value":10}},
           {"id":"branch","type":"BRANCH","typeId":"test:condition","version":1,"parameters":{"value":{}},
            "thenSteps":[{"id":"until","type":"WAIT_UNTIL","tick":{"value":500}}],"elseSteps":[]}]}
          """)));
        for(String definition : new String[]{
            "{\"parameters\":[{\"id\":\"param\",\"type\":\"STRING\"}],\"steps\":[]}",
            "{\"parameters\":[],\"steps\":[{\"id\":\"reverse\",\"type\":\"REVERSE\"}]}"
        }) assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateArrival(mapper.readTree(definition)));
    }
    @Test void persistedDocumentAcceptsStoppingArrivalButRejectsPassingCall() throws Exception {
        String document="""
          {"formatVersion":1,"id":"doc","periodDays":1,"duties":[],"diagramLines":[],"arrivalOverrides":[],
           "runs":[{"id":"run","type":"PASSENGER","lineDirection":"UP","calls":[
             {"id":"call","stationId":"station","status":"TERMINAL","targetId":"destination",
              "arrivalProgram":{"parameters":[],"steps":[]}}]}]}
          """;
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(mapper.readTree(document)));
        assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(mapper.readTree(document.replace("TERMINAL","PASS"))));
    }
}
