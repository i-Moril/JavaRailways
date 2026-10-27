package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimedOperationValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode document() throws Exception {
        var document = (ObjectNode) mapper.readTree("""
            {"formatVersion":1,"id":"weekday","periodDays":1,"runs":[],"diagramLines":[],"arrivalOverrides":[],
             "events":[{"id":"marker","tick":20},{"id":"operation","stationId":"central","tick":200,"label":"turnback",
               "duration":{"type":"MANUAL","ticks":10},"program":{"programId":"1","arguments":{"delay":5}}}],
             "duties":[{"id":"d","selector":{"type":"TRAIN_NAME","name":"train"},"elements":[
               {"id":"local","type":"PROGRAM","tick":100,"program":{"programId":"1","arguments":{"delay":2}}},
               {"id":"reference","type":"EVENT","eventId":"operation"}]}]}
            """);
        var definition = mapper.readTree("""
            {"parameters":[{"id":"delay","type":"DURATION"}],
             "steps":[{"id":"wait","type":"WAIT_DURATION","durationTicks":{"parameterId":"delay"}}]}
            """);
        var snapshot = mapper.createObjectNode().put("programId", "1").put("revision", 1)
            .put("contentIdentity", ProgramContentIdentity.of(definition));
        snapshot.set("definition", definition);
        document.putArray("boundPrograms").add(snapshot);
        return document;
    }

    @Test void bothOwnersAndPureMarkersRoundTripWithOptionalStationAndDuration() throws Exception {
        var document = document();
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document));
        var restored = mapper.readTree(mapper.writeValueAsBytes(document));
        assertEquals(document, restored);
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(restored));
        var operation = (ObjectNode) document.path("events").get(1);
        operation.putObject("duration").put("type", "SIMULATED");
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document));
    }

    @Test void bothOwnersSharePeriodArgumentsAndDurationValidation() throws Exception {
        for (boolean event : new boolean[]{false, true}) {
            for (String fault : new String[]{"tick", "overflow", "duration", "arguments", "program", "unknown"}) {
                var document = document();
                var operation = (ObjectNode) (event ? document.path("events").get(1)
                    : document.path("duties").get(0).path("elements").get(0));
                switch (fault) {
                    case "tick" -> operation.put("tick", 24_000);
                    case "overflow" -> operation.put("tick", new java.math.BigInteger("184467440737095516160"));
                    case "duration" -> operation.putObject("duration").put("type", "MANUAL").put("ticks", -1);
                    case "arguments" -> ((ObjectNode) operation.path("program")).putObject("arguments").put("delay", "bad");
                    case "program" -> ((ObjectNode) operation.path("program")).put("programId", "missing");
                    case "unknown" -> operation.put("typo", true);
                }
                assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(document), event + " " + fault);
            }
        }
    }

    @Test void danglingAndMarkerReferencesRemainSavableForAuthoringDiagnostics() throws Exception {
        var document = document();
        var reference = (ObjectNode) document.path("duties").get(0).path("elements").get(1);
        reference.put("eventId", "missing");
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document));
        reference.put("eventId", "marker");
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document));
        reference.put("tick", 300);
        assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(document));
    }

    @Test void currentProgramRequiresInvocationAndUniqueElementIdentity() throws Exception {
        var document = document();
        var element = (ObjectNode) document.path("duties").get(0).path("elements").get(0);
        element.remove("program");
        assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(document));
        var duplicate = document();
        ((ObjectNode) duplicate.path("duties").get(0).path("elements").get(1)).put("id", "local");
        assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(duplicate));
    }
}
