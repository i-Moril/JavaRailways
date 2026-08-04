package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.stream.StreamSupport;

/** Structural boundary validation for persisted V1 editor documents. Semantic validation stays in the authoring compiler. */
final class EditorDocumentValidator {
    private EditorDocumentValidator() {}

    static void validate(JsonNode document) {
        if (document == null || !document.isObject()) fail("editor document must be an object");
        if (document.path("formatVersion").asInt(-1) != 1) fail("unsupported editor document version");
        if (document.path("id").asText("").isBlank()) fail("editor document id is required");
        if (!document.path("periodDays").isIntegralNumber() || document.path("periodDays").asLong() < 1L) {
            fail("editor document periodDays must be a positive integer");
        }
        requireObjectArray(document, "runs");
        requireObjectArray(document, "duties");
        requireObjectArray(document, "diagramLines");
        requireObjectArray(document, "arrivalOverrides");
        document.path("runs").forEach(EditorDocumentValidator::validateRun);
        document.path("duties").forEach(EditorDocumentValidator::validateDuty);
        document.path("diagramLines").forEach(EditorDocumentValidator::validateDiagramLine);
    }

    private static void validateRun(JsonNode run) {
        requireText(run, "id", "run");
        requireText(run, "type", "run");
        requireText(run, "lineDirection", "run");
        requireObjectArray(run, "calls");
        run.path("calls").forEach(call -> {
            requireText(call, "id", "run call");
            requireText(call, "stationId", "run call");
            requireText(call, "status", "run call");
            requireText(call, "targetId", "run call");
        });
    }

    private static void validateDuty(JsonNode duty) {
        requireText(duty, "id", "duty");
        JsonNode selector = duty.path("selector");
        if (!selector.isObject()) fail("duty selector must be an object");
        validateSelector(selector);
        requireObjectArray(duty, "elements");
        duty.path("elements").forEach(EditorDocumentValidator::validateElement);
    }

    private static void validateSelector(JsonNode selector) {
        requireText(selector, "type", "duty selector");
        switch (selector.path("type").asText()) {
            case "TRAIN_NAME" -> requireText(selector, "name", "named train selector");
            case "TAKE_FROM_DEPOT" -> {
                requireText(selector, "depotId", "depot selector");
                requireIntegral(selector, "tick", "depot selector");
                requireStringArray(selector, "tags", "depot selector");
            }
            case "TAKE_FROM_WAYPOINT" -> {
                requireIntegral(selector, "tick", "waypoint selector");
                requireStringArray(selector, "wayPointIds", "waypoint selector");
            }
            default -> fail("unknown duty selector type");
        }
    }

    private static void validateElement(JsonNode element) {
        requireText(element, "id", "duty element");
        requireText(element, "type", "duty element");
        switch (element.path("type").asText()) {
            case "RUN" -> requireText(element, "runId", "run element");
            case "FUNCTION" -> {
                JsonNode call = element.path("call");
                if (!call.isObject()) fail("function element call must be an object");
                validateFunction(call);
            }
            default -> fail("unknown duty element type");
        }
    }

    private static void validateFunction(JsonNode call) {
        requireText(call, "id", "function call");
        requireText(call, "type", "function call");
        switch (call.path("type").asText()) {
            case "PLACE_INITIAL" -> {
                requireText(call, "trainName", "initial placement");
                requireText(call, "wayPointId", "initial placement");
            }
            case "REVERSE" -> requireIntegral(call, "tick", "reverse call");
            case "WAIT" -> {
                requireIntegral(call, "tick", "wait call");
                requireIntegral(call, "durationTicks", "wait call");
            }
            case "STORE_AT_DEPOT" -> {
                requireIntegral(call, "tick", "store call");
                requireText(call, "depotId", "store call");
                requireStringArray(call, "stablingPointIds", "store call");
            }
            default -> fail("unknown function call type");
        }
    }

    private static void validateDiagramLine(JsonNode line) {
        requireText(line, "id", "diagram line");
        requireStringArray(line, "upStationIds", "diagram line");
        requireStringArray(line, "downStationIds", "diagram line");
    }

    private static void requireObjectArray(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isArray() || !StreamSupport.stream(value.spliterator(), false).allMatch(JsonNode::isObject)) {
            fail("editor document " + field + " must be an array of objects");
        }
    }

    private static void requireText(JsonNode parent, String field, String subject) {
        if (!parent.path(field).isTextual()) {
            fail(subject + " " + field + " must be a string");
        }
    }

    private static void requireIntegral(JsonNode parent, String field, String subject) {
        if (!parent.path(field).isIntegralNumber()) fail(subject + " " + field + " must be an integer");
    }

    private static void requireStringArray(JsonNode parent, String field, String subject) {
        JsonNode value = parent.path(field);
        if (!value.isArray() || !StreamSupport.stream(value.spliterator(), false).allMatch(JsonNode::isTextual)) {
            fail(subject + " " + field + " must be a string array");
        }
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }
}
