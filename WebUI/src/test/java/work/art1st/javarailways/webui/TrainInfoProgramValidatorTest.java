package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrainInfoProgramValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String BINDINGS = "{\"name\":\"NAME\",\"cars\":\"CAR_COUNT\",\"units\":\"UNIT_COUNT\",\"direction\":\"DIRECTION\"}";
    private JsonNode definition(String steps) throws Exception {
        return mapper.readTree("{\"parameters\":[],\"steps\":[" + steps + "]}");
    }
    private String extension(String expression) {
        return "{\"id\":\"action\",\"type\":\"EXTENSION\",\"typeId\":\"demo:action\",\"version\":1,\"parameters\":" + expression + "}";
    }
    @Test void explicitBindingsAvailableInDataSlotsAcrossProgramContexts() throws Exception {
        String steps = "{\"id\":\"display\",\"type\":\"SET_DISPLAY\",\"unitSlots\":{\"value\":[1]},\"values\":{\"trainInfo\":"+BINDINGS+"}}," + extension("{\"trainInfo\":"+BINDINGS+"}");
        var definition = definition(steps);
        assertDoesNotThrow(() -> StationProgramValidator.validate(definition));
        assertDoesNotThrow(() -> StationProgramValidator.validateArrival(definition));
        assertDoesNotThrow(() -> StationProgramValidator.validateStation(definition));
        assertDoesNotThrow(() -> StationProgramValidator.validateArguments(definition, mapper.createObjectNode()));
        assertDoesNotThrow(() -> StationProgramValidator.validate(definition(extension("{\"value\":{\"trainInfo\":{\"x\":\"UNKNOWN\"}}}"))));
        var parameters = mapper.readTree("{\"parameters\":[{\"id\":\"p\",\"type\":\"JSON_OBJECT\"}],\"steps\":["+extension("{\"parameterId\":\"p\"}")+"]}");
        assertDoesNotThrow(() -> StationProgramValidator.validateArguments(parameters, mapper.readTree("{\"p\":{\"trainInfo\":{\"x\":\"UNKNOWN\"}}}")));
    }
    @Test void malformedAndUnselectedSourcesAreRejected() throws Exception {
        for (String bindings : new String[]{"{}", "[]", "null", "{\"x\":\"MODEL\"}", "{\"x\":false}", "{\" \":\"NAME\"}", "{\""+"x".repeat(257)+"\":\"NAME\"}"}) {
            String invalid = extension("{\"trainInfo\":"+bindings+"}");
            assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validate(definition(invalid)));
            assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateStation(definition(invalid)));
            String branch = "{\"id\":\"branch\",\"type\":\"BRANCH\",\"typeId\":\"javarailways:plan_duty_id\",\"version\":1,\"parameters\":{\"value\":{\"values\":[\"d\"]}},\"thenSteps\":[],\"elseSteps\":["+invalid+"]}";
            assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validate(definition(branch)));
        }
        var sixtyFive = mapper.createObjectNode();
        for (int i=0;i<65;i++) sixtyFive.put("k"+i,"NAME");
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validate(definition(extension("{\"trainInfo\":"+sixtyFive+"}"))));
    }
    @Test void sourcesCannotAffectConditionsMovementWaitsOrUnitSelection() {
        for (String step : new String[]{
            "{\"id\":\"wait\",\"type\":\"WAIT_DURATION\",\"durationTicks\":{\"trainInfo\":"+BINDINGS+"}}",
            "{\"id\":\"display\",\"type\":\"SET_DISPLAY\",\"unitSlots\":{\"trainInfo\":"+BINDINGS+"},\"values\":{\"value\":{}}}",
            "{\"id\":\"move\",\"type\":\"MOVE\",\"destinationId\":{\"trainInfo\":"+BINDINGS+"},\"viaIds\":{\"value\":[]}}",
            "{\"id\":\"branch\",\"type\":\"BRANCH\",\"typeId\":\"javarailways:plan_duty_id\",\"version\":1,\"parameters\":{\"trainInfo\":"+BINDINGS+"},\"thenSteps\":[],\"elseSteps\":[]}",
            extension("{\"trainInfo\":"+BINDINGS+",\"value\":{}}")
        }) assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validate(definition(step)));
    }
}
