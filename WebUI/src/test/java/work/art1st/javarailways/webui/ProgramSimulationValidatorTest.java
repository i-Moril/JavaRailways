package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProgramSimulationValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode request() throws Exception {
        return (ObjectNode) mapper.readTree("""
            {"programId":"p","definition":{"parameters":[],"steps":[]},"arguments":{},
             "startTick":100,"executionTimeoutMillis":60000,"maximumDurationTicks":2000,
             "profile":{"type":"TRAIN_TEMPLATE","id":"train"},
             "initialPosition":{"wayPointId":"point","reverseTrain":true}}
            """);
    }
    @Test void acceptsExplicitReverseAndBothReferenceBlueprintTypes() throws Exception {
        ObjectNode request = request();
        ProgramSimulationValidator.validate(request);
        ((ObjectNode) request.path("profile")).put("type", "WORLD_TRAIN");
        ((ObjectNode) request.path("initialPosition")).put("reverseTrain", false);
        request.putObject("plannedContext").put("dutyId", "duty");
        ProgramSimulationValidator.validate(request);
    }
    @Test void rejectsImplicitReverseWrongTypesAndUnsafeLimits() throws Exception {
        for (String field : new String[]{"programId", "definition", "arguments", "startTick", "maximumDurationTicks", "executionTimeoutMillis", "profile", "initialPosition"}) {
            ObjectNode request = request(); request.remove(field);
            assertThrows(IllegalArgumentException.class, () -> ProgramSimulationValidator.validate(request), field);
        }
        ObjectNode noReverse = request(); ((ObjectNode) noReverse.path("initialPosition")).remove("reverseTrain");
        assertThrows(IllegalArgumentException.class, () -> ProgramSimulationValidator.validate(noReverse));
        ObjectNode request = request(); request.put("maximumDurationTicks", Long.MAX_VALUE);
        ObjectNode horizon = request;
        assertThrows(IllegalArgumentException.class, () -> ProgramSimulationValidator.validate(horizon));
        request = request(); request.put("startTick", "100");
        ObjectNode numericString = request;
        assertThrows(IllegalArgumentException.class, () -> ProgramSimulationValidator.validate(numericString));
        request = request(); request.put("extra", true);
        ObjectNode unknown = request;
        assertThrows(IllegalArgumentException.class, () -> ProgramSimulationValidator.validate(unknown));
    }
    @Test void acceptsMultiDayAdvanceWithoutTimetablePeriod() throws Exception {
        ObjectNode request = request(); request.put("startTick", 23900); request.put("maximumDurationTicks", 48000);
        ProgramSimulationValidator.validate(request);
        long edge = (9_007_199_254_740_991L / 24_000L) * 24_000L - 1L;
        request.put("startTick", edge - 1); request.put("maximumDurationTicks", 1);
        ProgramSimulationValidator.validate(request);
        request.put("maximumDurationTicks", 2);
        assertThrows(IllegalArgumentException.class, () -> ProgramSimulationValidator.validate(request));
    }

    @Test void validatesExecutionBudgetIndependentlyOfSimulatedTicks() throws Exception {
        for (long value : new long[]{1, 1001, 3600000}) {
            ObjectNode request = request(); request.put("executionTimeoutMillis", value);
            ProgramSimulationValidator.validate(request);
        }
        for (long value : new long[]{0, 3600001}) {
            ObjectNode request = request(); request.put("executionTimeoutMillis", value);
            assertThrows(IllegalArgumentException.class, () -> ProgramSimulationValidator.validate(request));
        }
        ObjectNode request = request(); request.put("executionTimeoutMillis", "1000");
        assertThrows(IllegalArgumentException.class, () -> ProgramSimulationValidator.validate(request));
    }

    @Test void doesNotTreatOldFacingOrBooleanStringsAsReverseChoices() throws Exception {
        for (String position : new String[]{
            "{\"wayPointId\":\"point\",\"facing\":\"ALONG\"}",
            "{\"wayPointId\":\"point\",\"reverseTrain\":\"true\"}",
            "{\"wayPointId\":\"point\",\"reverseTrain\":null}",
            "{\"wayPointId\":\"point\",\"reverseTrain\":false,\"facing\":\"OPPOSITE\"}"
        }) {
            ObjectNode request = request(); request.set("initialPosition", mapper.readTree(position));
            assertThrows(IllegalArgumentException.class, () -> ProgramSimulationValidator.validate(request));
        }
    }

    @Test void validatesActualArgumentsAndRejectsUnresolvedDocumentEvents() throws Exception {
        ObjectNode request = request();
        request.set("definition", mapper.readTree("""
            {"parameters":[{"id":"time","type":"DURATION"}],"steps":[{"id":"wait","type":"WAIT_DURATION","durationTicks":{"parameterId":"time"}}]}
            """));
        assertThrows(IllegalArgumentException.class, () -> ProgramSimulationValidator.validate(request));
        request.putObject("arguments").put("time", 1300);
        ProgramSimulationValidator.validate(request);
        request.set("definition", mapper.readTree("""
            {"parameters":[],"steps":[{"id":"wait","type":"WAIT_EVENT","eventId":"event","offsetTicks":0}]}
            """));
        request.putObject("arguments");
        assertThrows(IllegalArgumentException.class, () -> ProgramSimulationValidator.validate(request));
    }
}
