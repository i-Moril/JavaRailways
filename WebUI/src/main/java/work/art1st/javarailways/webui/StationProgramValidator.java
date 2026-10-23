package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Strict authoring structure. World references and executable continuity remain runtime validation. */
final class StationProgramValidator {
    private static final Set<String> PARAMETER_TYPES = Set.of("TICK", "DURATION", "DESTINATION", "STATION", "DEPOT", "FORMATION_EVENT", "STRING", "ID_LIST", "JSON_OBJECT", "UNIT_SLOTS");
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private StationProgramValidator() {}

    static void validate(JsonNode definition) {
        fields(definition, Set.of("parameters", "steps"), "program definition");
        array(definition.path("parameters"), 64, "parameters");
        array(definition.path("steps"), 256, "steps");
        Map<String, String> parameters = new HashMap<>();
        for (JsonNode parameter : definition.path("parameters")) {
            fields(parameter, Set.of("id", "type"), "parameter");
            String id = id(parameter.path("id"), "parameter id");
            String type = text(parameter.path("type"), "parameter type");
            if (!PARAMETER_TYPES.contains(type)) fail("unknown parameter type: " + type);
            if (parameters.put(id, type) != null) fail("duplicate parameter id: " + id);
        }
        validateSteps(definition.path("steps"), parameters, new HashSet<>(), 0);
    }

    private static void validateSteps(JsonNode values, Map<String, String> parameters, Set<String> steps, int depth) {
        if (depth > 16) fail("program exceeds branch depth16");
        array(values, 256, "steps");
        for (JsonNode step : values) {
            String id = id(step.path("id"), "step id");
            if (!steps.add(id)) fail("duplicate step id: " + id);
            if (steps.size() > 256) fail("program exceeds256 total steps");
            String type = text(step.path("type"), "step type");
            if (depth > 0 && Set.of("COUPLE", "SPLIT", "STORE_AT_DEPOT").contains(type)) fail("fixed operations cannot be conditional: " + id);
            switch (type) {
                case "SET_DISPLAY" -> {
                    fields(step, step.has("removeKeys") ? Set.of("id", "type", "unitSlots", "values", "removeKeys") : Set.of("id", "type", "unitSlots", "values"), "SET_DISPLAY step");
                    value(step.path("unitSlots"), "UNIT_SLOTS", parameters, false);
                    value(step.path("values"), "JSON_OBJECT", parameters, false);
                    if (step.path("values").has("value")) displayValues(step.path("values").path("value"));
                    if (step.has("removeKeys")) {
                        value(step.path("removeKeys"), "ID_LIST", parameters, false);
                        if (step.path("removeKeys").has("value")) displayRemovalKeys(step.path("removeKeys").path("value"));
                    }
                }
                case "RESET_DISPLAY" -> {
                    fields(step, Set.of("id", "type", "unitSlots"), "RESET_DISPLAY step");
                    value(step.path("unitSlots"), "UNIT_SLOTS", parameters, false);
                }
                case "WAIT_UNTIL" -> {
                    fields(step, Set.of("id", "type", "tick"), "WAIT_UNTIL step");
                    value(step.path("tick"), "TICK", parameters, false);
                }
                case "WAIT_DURATION" -> {
                    fields(step, Set.of("id", "type", "durationTicks"), "WAIT_DURATION step");
                    value(step.path("durationTicks"), "DURATION", parameters, true);
                }
                case "BRANCH" -> {
                    fields(step, Set.of("id", "type", "typeId", "version", "parameters", "thenSteps", "elseSteps"), "BRANCH step");
                    String typeId = text(step.path("typeId"), "condition typeId");
                    if (!typeId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) fail("condition typeId must be namespaced");
                    if (!step.path("version").isIntegralNumber() || !step.path("version").canConvertToInt() || step.path("version").asInt() < 1) fail("condition version must be a positive integer");
                    value(step.path("parameters"), "JSON_OBJECT", parameters, false);
                    validateSteps(step.path("thenSteps"), parameters, steps, depth + 1);
                    validateSteps(step.path("elseSteps"), parameters, steps, depth + 1);
                }
                case "EXTENSION" -> {
                    fields(step, Set.of("id", "type", "typeId", "version", "parameters"), "EXTENSION step");
                    String typeId = text(step.path("typeId"), "extension typeId");
                    if (!typeId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) fail("extension typeId must be namespaced");
                    if (!step.path("version").isIntegralNumber() || !step.path("version").canConvertToInt() || step.path("version").asInt() < 1) fail("extension version must be a positive integer");
                    value(step.path("parameters"), "JSON_OBJECT", parameters, false);
                }
                case "COUPLE", "SPLIT" -> {
                    fields(step, Set.of("id", "type", "eventId"), type + " step");
                    value(step.path("eventId"), "FORMATION_EVENT", parameters, false);
                }
                case "REVERSE" -> fields(step, Set.of("id", "type"), "REVERSE step");
                case "MOVE" -> {
                    fields(step, Set.of("id", "type", "destinationId", "viaIds"), "MOVE step");
                    value(step.path("destinationId"), "DESTINATION", parameters, false);
                    value(step.path("viaIds"), "ID_LIST", parameters, false);
                }
                case "SELECT_STOP" -> {
                    fields(step, Set.of("id", "type", "stationId", "berthIds", "selectionPolicy"), "SELECT_STOP step");
                    value(step.path("stationId"), "STATION", parameters, false);
                    value(step.path("berthIds"), "ID_LIST", parameters, false);
                    if (!Set.of("FIXED_PREFERENCE", "AVAILABLE_PRIORITY").contains(text(step.path("selectionPolicy"), "selection policy"))) fail("invalid station selection policy");
                    if (step.path("berthIds").has("value")) berthIds(step.path("berthIds").path("value"));
                }
                case "STORE_AT_DEPOT" -> {
                    fields(step, Set.of("id", "type", "depotId", "stablingPointIds"), "STORE_AT_DEPOT step");
                    value(step.path("depotId"), "DEPOT", parameters, false);
                    value(step.path("stablingPointIds"), "ID_LIST", parameters, false);
                }
                default -> fail("unsupported sequential program step: " + type);
            }
        }
    }

    static void validateArguments(JsonNode definition, JsonNode arguments) {
        validate(definition);
        if (!arguments.isObject()) fail("program arguments must be an object");
        Set<String> names = new HashSet<>();
        for (JsonNode parameter : definition.path("parameters")) {
            String id = parameter.path("id").asText();
            names.add(id);
            literal(arguments.path(id), parameter.path("type").asText(), false);
        }
        arguments.fieldNames().forEachRemaining(name -> { if (!names.contains(name)) fail("unknown program argument: " + name); });
        validateStepArguments(definition.path("steps"), arguments);
    }

    private static void validateStepArguments(JsonNode steps, JsonNode arguments) {
        for (JsonNode step : steps) {
            if (step.path("type").asText().equals("BRANCH")) {
                validateStepArguments(step.path("thenSteps"), arguments);
                validateStepArguments(step.path("elseSteps"), arguments);
            }
            if (step.path("type").asText().equals("SET_DISPLAY") && step.path("removeKeys").has("parameterId")) {
                displayRemovalKeys(arguments.path(step.path("removeKeys").path("parameterId").asText()));
            }
            if (step.path("type").asText().equals("SET_DISPLAY") && step.path("values").has("parameterId")) {
                displayValues(arguments.path(step.path("values").path("parameterId").asText()));
            }
            if (step.path("type").asText().equals("SELECT_STOP") && step.path("berthIds").has("parameterId")) {
                berthIds(arguments.path(step.path("berthIds").path("parameterId").asText()));
            }
            if (step.path("type").asText().equals("WAIT_DURATION") && step.path("durationTicks").has("parameterId")) {
                literal(arguments.path(step.path("durationTicks").path("parameterId").asText()), "DURATION", true);
            }
        }
    }

    private static void berthIds(JsonNode value) {
        array(value, 64, "berth IDs");
        Set<String> ids = new HashSet<>();
        if (value.isEmpty()) fail("station selection requires distinct nonempty berth IDs");
        for (JsonNode item : value) if (!ids.add(text(item, "berth ID"))) fail("station selection requires distinct nonempty berth IDs");
    }

    private static void value(JsonNode node, String type, Map<String, String> parameters, boolean positive) {
        if (!node.isObject() || node.size() != 1) fail("program value must contain exactly value or parameterId");
        if (node.has("parameterId")) {
            String id = text(node.path("parameterId"), "parameter reference");
            if (!type.equals(parameters.get(id))) fail("unknown or incompatible parameter: " + id);
        } else if (node.has("value")) literal(node.path("value"), type, positive);
        else fail("program value must contain value or parameterId");
    }

    private static void literal(JsonNode value, String type, boolean positive) {
        switch (type) {
            case "TICK", "DURATION" -> {
                if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < (positive ? 1 : 0) || value.asLong() > MAX_SAFE_INTEGER) fail(type + " must be a " + (positive ? "positive" : "nonnegative") + " safe integer");
            }
            case "UNIT_SLOTS" -> unitSlots(value);
            case "JSON_OBJECT" -> ProgramJsonValidator.object(value);
            case "ID_LIST" -> {
                array(value, 256, "ID_LIST");
                for (JsonNode item : value) text(item, "ID_LIST item");
            }
            default -> text(value, type);
        }
    }

    private static void displayRemovalKeys(JsonNode values) {
        array(values, 256, "display removal keys");
        Set<String> keys = new HashSet<>();
        for (JsonNode value : values) if (!keys.add(text(value, "display removal key"))) fail("duplicate display removal key");
    }

    static void displayValues(JsonNode values) {
        ProgramJsonValidator.object(values);
        if (values.size() > 128 || values.toString().length() > 65536) fail("display values exceed128 keys or64 KiB");
        values.fieldNames().forEachRemaining(key -> { if (key.isBlank()) fail("display keys must not be blank"); });
    }

    static void unitSlots(JsonNode values) {
        array(values, 128, "unitSlots");
        if (values.isEmpty()) fail("unitSlots must not be empty");
        Set<Integer> slots = new HashSet<>();
        for (JsonNode slot : values) {
            if (!slot.isIntegralNumber() || !slot.canConvertToInt() || slot.asInt() <= 0 || !slots.add(slot.asInt())) {
                fail("unitSlots must contain distinct positive integers");
            }
        }
    }

    static void fields(JsonNode node, Set<String> fields, String label) {
        if (node == null || !node.isObject()) fail(label + " must be an object");
        node.fieldNames().forEachRemaining(name -> { if (!fields.contains(name)) fail("unknown " + label + " field: " + name); });
        for (String field : fields) if (!node.has(field)) fail(label + " requires " + field);
    }

    static long revision(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 1 || node.asLong() > MAX_SAFE_INTEGER) fail("expectedRevision must be a positive safe integer");
        return node.asLong();
    }

    static String text(JsonNode node, String label) {
        if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > 256) fail(label + " must be a nonblank string of at most256 characters");
        return node.asText();
    }

    private static String id(JsonNode node, String label) {
        String id = text(node, label);
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) fail("invalid " + label);
        return id;
    }

    private static void array(JsonNode node, int maximum, String label) {
        if (!node.isArray() || node.size() > maximum) fail(label + " must be an array with at most" + maximum + " entries");
    }

    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
