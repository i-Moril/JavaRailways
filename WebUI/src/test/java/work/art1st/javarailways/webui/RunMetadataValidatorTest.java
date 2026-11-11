package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RunMetadataValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode document() throws Exception {
        return (ObjectNode) mapper.readTree("""
            {"formatVersion":1,"id":"weekday","periodDays":1,
             "runs":[{"id":"run","type":"PASSENGER","lineDirection":"UP","calls":[]}],
             "duties":[],"diagramLines":[],"initialPositions":[],"arrivalOverrides":[]}
            """);
    }
    @Test void missingDefaultsAndArbitraryNestedFieldsAreAcceptedAndPreserved() throws Exception {
        var document = document();
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document));
        var metadata = mapper.readTree("{\"label\":\"Express\",\"custom\":{\"array\":[true,null,1.25]},\"__proto__\":{\"a\":1}}");
        ((ObjectNode) document.path("runs").get(0)).set("metadata", metadata);
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document));
        assertEquals(metadata, mapper.readTree(mapper.writeValueAsString(document)).path("runs").get(0).path("metadata"));
    }
    @Test void nonObjectsAndBudgetsAreRejected() throws Exception {
        for (String input : new String[]{"null", "[]", "1", "true", "\"text\"", "{\"large\":\"" + "x".repeat(65_536) + "\"}", "{\"values\":[" + "0,".repeat(4095) + "0]}"}) {
            var document = document();
            ((ObjectNode) document.path("runs").get(0)).set("metadata", mapper.readTree(input));
            assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(document));
        }
        ObjectNode deep = mapper.createObjectNode(), cursor = deep;
        for (int i=0; i<17; i++) cursor = cursor.putObject("next");
        assertThrows(IllegalArgumentException.class, () -> JsonMetadataValidator.validate(deep));
        var nonfinite = mapper.createObjectNode().put("bad", Double.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, () -> JsonMetadataValidator.validate(nonfinite));
    }
}
