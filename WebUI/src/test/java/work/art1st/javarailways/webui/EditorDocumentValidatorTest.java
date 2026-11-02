package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EditorDocumentValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void acceptsMissingZeroAndExplicitGlobalMinimumWithoutChangingVersion() throws Exception {
        ObjectNode document = document();
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document));
        for (long value : new long[] {0L, 80L, 9_007_199_254_740_991L}) {
            document.put("minimumDwellTicks", value);
            assertDoesNotThrow(() -> EditorDocumentValidator.validate(document));
            assertEquals(value, document.path("minimumDwellTicks").asLong());
            assertEquals(1, document.path("formatVersion").asInt());
        }
    }

    @Test void rejectsNonIntegralNegativeUnsafeAndNullGlobalMinimum() throws Exception {
        for (String value : new String[] {"-1", "0.5", "9007199254740992", "null", "\"20\"", "true"}) {
            ObjectNode document = document();
            document.set("minimumDwellTicks", mapper.readTree(value));
            assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(document), value);
        }
    }

    @Test void rejectsRemovedPerCallMinimumInsteadOfReadingItAsAnOverride() throws Exception {
        ObjectNode document = document();
        document.put("minimumDwellTicks", 40);
        document.withArray("runs").add(mapper.readTree("""
            {"id":"run","type":"PASSENGER","lineDirection":"UP","calls":[
              {"id":"call","stationId":"station","status":"TERMINAL","targetId":"station@one","minimumDwellTicks":10}
            ]}
            """));
        assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(document));
    }

    private ObjectNode document() throws Exception {
        return (ObjectNode) mapper.readTree("""
            {"formatVersion":1,"id":"timetable","periodDays":1,"runs":[],"duties":[],"diagramLines":[],"arrivalOverrides":[]}
            """);
    }
}
