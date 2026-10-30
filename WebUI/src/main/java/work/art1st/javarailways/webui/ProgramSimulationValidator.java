package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Transient one-call simulation request. World references are rechecked by the bridge owner. */
final class ProgramSimulationValidator {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private ProgramSimulationValidator() {}

    static void validate(JsonNode request) {
        fields(request, Set.of("programId", "definition", "arguments", "startTick", "periodDays", "profile", "initialPosition", "plannedContext", "maximumDurationTicks"), "program simulation request");
        String id = text(request.path("programId"), "programId");
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) fail("invalid programId");
        StationProgramValidator.validateArguments(request.path("definition"), request.path("arguments"));
        long days = integer(request.path("periodDays"), "periodDays", 1, MAX_SAFE_INTEGER / 24_000);
        long period = days * 24_000;
        long start = integer(request.path("startTick"), "startTick", 0, period - 1);
        integer(request.path("maximumDurationTicks"), "maximumDurationTicks", 1, period - start);
        JsonNode profile = request.path("profile");
        fields(profile, Set.of("type", "id"), "profile");
        if (!Set.of("WORLD_TRAIN", "TRAIN_TEMPLATE").contains(text(profile.path("type"), "profile.type"))) fail("invalid profile.type");
        text(profile.path("id"), "profile.id");
        JsonNode position = request.path("initialPosition");
        fields(position, Set.of("wayPointId", "reverseTrain"), "initialPosition");
        text(position.path("wayPointId"), "initialPosition.wayPointId");
        if (!position.path("reverseTrain").isBoolean()) fail("initialPosition.reverseTrain must be an explicit boolean");
        if (request.has("plannedContext")) {
            JsonNode context = request.path("plannedContext");
            fields(context, Set.of("runType", "lineDirection", "stationId", "runId", "lineId", "callStatus", "dutyId"), "plannedContext");
            context.fields().forEachRemaining(entry -> text(entry.getValue(), "plannedContext." + entry.getKey()));
        }
        rejectUnresolvedEvents(request.path("definition").path("steps"));
    }

    private static void rejectUnresolvedEvents(JsonNode steps) {
        for (JsonNode step : steps) {
            if (step.path("type").asText().equals("WAIT_EVENT")) fail("Resolve WAIT_EVENT against the caller's document before simulation");
            if (step.path("type").asText().equals("BRANCH")) {
                rejectUnresolvedEvents(step.path("thenSteps")); rejectUnresolvedEvents(step.path("elseSteps"));
            }
        }
    }

    private static void fields(JsonNode value, Set<String> allowed, String label) {
        if (!value.isObject()) fail(label + " must be an object");
        value.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) fail(label + " contains unknown field: " + key); });
    }
    private static String text(JsonNode value, String label) {
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 128) fail(label + " must be a nonblank string of at most128 characters");
        return value.asText();
    }
    private static long integer(JsonNode value, String label, long minimum, long maximum) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < minimum || value.asLong() > maximum) fail(label + " must be an integer between " + minimum + " and " + maximum);
        return value.asLong();
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
