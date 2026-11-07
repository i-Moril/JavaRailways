package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FormationAuthoringValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String split = """
        {"id":"split","tick":100,"kind":"SPLIT","coordinatorDutyId":"source",
         "partition":{"from":"HEAD","firstUnitCount":1},"outputs":[
          {"id":"first","group":"FIRST","disposition":{"type":"DUTY","dutyId":"source"}},
          {"id":"rest","group":"REST","disposition":{"type":"HOLD"}}]}
        """;
    @Test void retainsWholeUnitPartitionAndHeldOutput() throws Exception {
        assertDoesNotThrow(() -> FormationAuthoringValidator.validate(mapper.readTree(split)));
        assertThrows(IllegalArgumentException.class, () -> FormationAuthoringValidator.validate(mapper.readTree(split.replace("\"HEAD\"", "\"TAIL\""))));
        assertThrows(IllegalArgumentException.class, () -> FormationAuthoringValidator.validate(mapper.readTree(split.replace("\"firstUnitCount\":1", "\"firstUnitCount\":0"))));
        assertThrows(IllegalArgumentException.class, () -> FormationAuthoringValidator.validate(mapper.readTree(split.replace("\"REST\"", "\"FIRST\""))));
    }
    @Test void validatesAuthoredTimingAndDuration() throws Exception {
        for (String tick : java.util.List.of("-1", "1.5", "9007199254740992", "null")) {
            assertThrows(IllegalArgumentException.class, () -> FormationAuthoringValidator.validate(
                mapper.readTree(split.replace("\"tick\":100", "\"tick\":" + tick))));
        }
        assertThrows(IllegalArgumentException.class, () -> FormationAuthoringValidator.validate(
            mapper.readTree(split.replace("\"tick\":100,", ""))));
        var event = mapper.readTree(split);
        ((com.fasterxml.jackson.databind.node.ObjectNode) event).putObject("duration").put("type", "SIMULATED");
        assertDoesNotThrow(() -> FormationAuthoringValidator.validate(event));
        ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("duration")).put("ticks", 20);
        assertThrows(IllegalArgumentException.class, () -> FormationAuthoringValidator.validate(event));
    }
    @Test void editorAllowsUnresolvedTaskReferencesButChecksFormationPeriodAndSelectorShape() throws Exception {
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("""
            {"formatVersion":1,"id":"weekday","periodDays":1,"runs":[],"diagramLines":[],"initialPositions":[],"arrivalOverrides":[],
             "duties":[{"id":"2","selector":{"type":"TAKE_FROM_FORMATION_OUTPUT","eventId":"split","outputId":"rest"},
                "elements":[{"id":"ref","type":"FORMATION","eventId":"missing"}]}]}
            """);
        document.putArray("formationEvents").add(mapper.readTree(split));
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document));
        var selector = (com.fasterxml.jackson.databind.node.ObjectNode) document.path("duties").get(0).path("selector");
        selector.put("extra", true);
        assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(document));
        selector.remove("extra");
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.path("formationEvents").get(0)).put("tick", 24_000);
        assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(document));
    }
    @Test void rejectsHiddenHeldAssignmentAndUnknownFields() {
        assertThrows(IllegalArgumentException.class, () -> FormationAuthoringValidator.validate(mapper.readTree(split.replace("\"type\":\"HOLD\"", "\"type\":\"HOLD\",\"dutyId\":\"source\""))));
        assertThrows(IllegalArgumentException.class, () -> FormationAuthoringValidator.validate(mapper.readTree(split.replace("\"partition\":", "\"runtimeCondition\":true,\"partition\":"))));
    }
    @Test void validatesDistinctCouplingParticipantsAndFinitePositiveSpeed() throws Exception {
        String couple = """
            {"id":"join","tick":200,"duration":{"type":"MANUAL","ticks":40},"stationId":"central","kind":"COUPLE","coordinatorDutyId":"a","approachingDutyId":"a","stationaryDutyId":"b","stationaryEnd":"TAIL",
             "rendezvous":{"approachDestinationId":"prepare","stationaryDestinationId":"park"},"maximumApproachSpeedMetersPerSecond":0.5,
             "output":{"id":"combined","disposition":{"type":"DUTY","dutyId":"a"}}}
            """;
        assertDoesNotThrow(() -> FormationAuthoringValidator.validate(mapper.readTree(couple)));
        assertThrows(IllegalArgumentException.class, () -> FormationAuthoringValidator.validate(mapper.readTree(couple.replace("\"stationaryDutyId\":\"b\"", "\"stationaryDutyId\":\"a\""))));
        assertThrows(IllegalArgumentException.class, () -> FormationAuthoringValidator.validate(mapper.readTree(couple.replace(":0.5", ":0"))));
    }
}
