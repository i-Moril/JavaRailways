package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OperationalTripAuthoringValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private String document(String trips) {
        return """
            {"formatVersion":1,"id":"t","periodDays":1,"diagramLines":[],"arrivalOverrides":[],"runs":[],
             "duties":[{"id":"d","selector":{"type":"TRAIN_NAME","name":"train"},"elements":[{"id":"e","type":"RUN","runId":"r"}]}],
             "operationalTrips":%s}
            """.formatted(trips);
    }
    @Test void acceptsPerUnitTripAndRejectsUnknownReferencesAndDuplicateAssignment() throws Exception {
        String trip = """
            {"id":"a","defaults":{"destination":"E"},"segments":[{"serviceId":"d","runId":"r","unitSlots":[1]}]}
            """;
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(mapper.readTree(document("[" + trip + "]"))));
        for (String invalid : new String[]{trip.replace("[1]", "[0]"), trip.replace("[1]", "[1,1]"), trip.replace("\"r\"", "\"missing\""), trip.replace("\"defaults\":", "\"unknown\":")}) {
            assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(mapper.readTree(document("[" + invalid + "]"))));
        }
        assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(mapper.readTree(document("[" + trip + "," + trip.replace("\"a\"", "\"b\"") + "]"))));
    }
    @Test void displayProgramSupportsTypedSlotsAndStrictNativeFields() throws Exception {
        String definition = """
            {"parameters":[{"id":"units","type":"UNIT_SLOTS"}],"steps":[
             {"id":"set","type":"SET_DISPLAY","unitSlots":{"parameterId":"units"},"values":{"value":{"destination":"E"}}},
             {"id":"reset","type":"RESET_DISPLAY","unitSlots":{"value":[2]}}]}
            """;
        assertDoesNotThrow(() -> StationProgramValidator.validateArguments(mapper.readTree(definition), mapper.readTree("{\"units\":[1,2]}")));
        for (String slots : new String[]{"[]", "[0]", "[1,1]", "[\"1\"]"}) {
            assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateArguments(mapper.readTree(definition), mapper.readTree("{\"units\":" + slots + "}")));
        }
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validate(mapper.readTree(definition.replace("\"values\":", "\"displayValues\":"))));
    }
}
