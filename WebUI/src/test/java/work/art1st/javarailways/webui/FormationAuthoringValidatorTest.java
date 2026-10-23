package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FormationAuthoringValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String split = """
        {"id":"split","kind":"SPLIT","coordinatorDutyId":"source",
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
    @Test void rejectsHiddenHeldAssignmentAndUnknownFields() {
        assertThrows(IllegalArgumentException.class, () -> FormationAuthoringValidator.validate(mapper.readTree(split.replace("\"type\":\"HOLD\"", "\"type\":\"HOLD\",\"dutyId\":\"source\""))));
        assertThrows(IllegalArgumentException.class, () -> FormationAuthoringValidator.validate(mapper.readTree(split.replace("\"partition\":", "\"runtimeCondition\":true,\"partition\":"))));
    }
    @Test void validatesDistinctCouplingParticipantsAndFinitePositiveSpeed() throws Exception {
        String couple = """
            {"id":"join","kind":"COUPLE","coordinatorDutyId":"a","approachingDutyId":"a","stationaryDutyId":"b","stationaryEnd":"TAIL",
             "rendezvous":{"approachDestinationId":"prepare","stationaryDestinationId":"park"},"maximumApproachSpeedMetersPerSecond":0.5,
             "output":{"id":"combined","disposition":{"type":"DUTY","dutyId":"a"}}}
            """;
        assertDoesNotThrow(() -> FormationAuthoringValidator.validate(mapper.readTree(couple)));
        assertThrows(IllegalArgumentException.class, () -> FormationAuthoringValidator.validate(mapper.readTree(couple.replace("\"stationaryDutyId\":\"b\"", "\"stationaryDutyId\":\"a\""))));
        assertThrows(IllegalArgumentException.class, () -> FormationAuthoringValidator.validate(mapper.readTree(couple.replace(":0.5", ":0"))));
    }
}
