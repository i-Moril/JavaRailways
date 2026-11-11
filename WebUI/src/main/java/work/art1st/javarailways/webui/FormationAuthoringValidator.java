package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** Editor shape only. The compiled runtime catalog owns reciprocal duties and physical feasibility. */
final class FormationAuthoringValidator {
    private FormationAuthoringValidator() {}
    static void validate(JsonNode event) {
        id(event.path("id"));
        String coordinator = id(event.path("coordinatorDutyId"));
        EditorDocumentValidator.requireSafeNonnegative(event.path("tick"), "formation event tick");
        EditorDocumentValidator.validateDuration(event);
        if (event.has("stationId")) id(event.path("stationId"));
        switch (event.path("kind").asText()) {
            case "SPLIT" -> {
                fields(event, Set.of("id", "kind", "coordinatorDutyId", "partition", "outputs"), "split event");
                JsonNode partition = event.path("partition");
                StationProgramValidator.fields(partition, Set.of("from", "firstUnitCount"), "split partition");
                if (!partition.path("from").asText().equals("HEAD") || !partition.path("firstUnitCount").isIntegralNumber() ||
                    !partition.path("firstUnitCount").canConvertToInt() || partition.path("firstUnitCount").asInt() < 1) fail("invalid complete-unit HEAD partition");
                JsonNode outputs = event.path("outputs");
                if (!outputs.isArray() || outputs.size() != 2) fail("split requires two outputs");
                Set<String> groups = new HashSet<>(), ids = new HashSet<>(), duties = new HashSet<>();
                for (JsonNode output : outputs) {
                    output(output, true);
                    if (!ids.add(output.path("id").asText())) fail("duplicate output id");
                    groups.add(output.path("group").asText());
                    if (output.path("disposition").has("dutyId") && !duties.add(output.path("disposition").path("dutyId").asText())) fail("outputs cannot share a duty");
                }
                if (!groups.equals(Set.of("FIRST", "REST"))) fail("split requires FIRST and REST outputs");
            }
            case "COUPLE" -> {
                fields(event, Set.of("id", "kind", "coordinatorDutyId", "approachingDutyId", "stationaryDutyId", "stationaryEnd", "rendezvous", "maximumApproachSpeedMetersPerSecond", "output"), "coupling event");
                String approaching = id(event.path("approachingDutyId")), stationary = id(event.path("stationaryDutyId"));
                if (approaching.equals(stationary) || !Set.of(approaching, stationary).contains(coordinator)) fail("invalid coupling participants");
                if (!Set.of("HEAD", "TAIL").contains(event.path("stationaryEnd").asText())) fail("invalid stationary end");
                JsonNode rendezvous = event.path("rendezvous");
                StationProgramValidator.fields(rendezvous, Set.of("approachDestinationId", "stationaryDestinationId"), "rendezvous");
                id(rendezvous.path("approachDestinationId")); id(rendezvous.path("stationaryDestinationId"));
                JsonNode speed = event.path("maximumApproachSpeedMetersPerSecond");
                if (!speed.isNumber() || !Double.isFinite(speed.asDouble()) || speed.asDouble() <= 0) fail("approach speed must be finite and positive");
                output(event.path("output"), false);
            }
            default -> fail("unknown formation event kind");
        }
    }
    private static void fields(JsonNode event, Set<String> required, String label) {
        Set<String> fields = new HashSet<>(required);
        fields.add("tick");
        for (String optional : Set.of("duration", "stationId")) if (event.has(optional)) fields.add(optional);
        StationProgramValidator.fields(event, fields, label);
    }
    private static void output(JsonNode output, boolean split) {
        StationProgramValidator.fields(output, split ? Set.of("id", "group", "disposition") : Set.of("id", "disposition"), "formation output");
        id(output.path("id"));
        JsonNode disposition = output.path("disposition");
        switch (disposition.path("type").asText()) {
            case "HOLD" -> StationProgramValidator.fields(disposition, Set.of("type"), "held output");
            case "DUTY" -> { StationProgramValidator.fields(disposition, Set.of("type", "dutyId"), "duty output"); id(disposition.path("dutyId")); }
            default -> fail("unknown output disposition");
        }
    }
    private static String id(JsonNode value) {
        String id = StationProgramValidator.text(value, "formation id");
        if (!id.equals(id.trim())) fail("formation id must be trimmed");
        return id;
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
