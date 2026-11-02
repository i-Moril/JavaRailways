package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Strict authoring structure. World references and executable continuity remain runtime validation. */
final class StationProgramValidator {
    private static final Set<String> PARAMETER_TYPES = Set.of("NUMBER", "TICK", "DURATION", "STRING", "ID_LIST", "JSON_OBJECT");
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private StationProgramValidator() {}

    static void validate(JsonNode definition) {
        validateDefinition(definition);
        validateLibrarySteps(definition.path("steps"));
    }

    private static void validateLibrarySteps(JsonNode steps) {
        for (JsonNode step : steps) {
            String type = step.path("type").asText();
            if (Set.of("END", "ASYNC", "AWAIT", "SELECT_STOP").contains(type)) fail("action is unavailable in duty/library program: " + type);
            if (type.equals("BRANCH")) { validateLibrarySteps(step.path("thenSteps")); validateLibrarySteps(step.path("elseSteps")); }
        }
    }

    private static void validateDefinition(JsonNode definition) {
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

    static void validateArrival(JsonNode definition) {
        validate(definition);
        if (!definition.path("parameters").isEmpty()) fail("arrival program cannot declare parameters");
        validateArrivalSteps(definition.path("steps"));
    }

    static void validateStation(JsonNode definition) {
        validateDefinition(definition);
        if (!definition.path("parameters").isEmpty()) fail("shared station programs cannot declare parameters");
        validateStationSteps(definition.path("steps"), false);
    }

    private static void validateStationSteps(JsonNode steps, boolean asynchronous) {
        boolean ends = false;
        for (JsonNode step : steps) {
            String type = step.path("type").asText();
            if (ends) fail("unreachable station instruction after END");
            if (type.equals("OPEN_DOORS")) fields(step, Set.of("id", "type"), "station OPEN_DOORS step");
            if (!Set.of("SET_DISPLAY", "RESET_DISPLAY", "WAIT_UNTIL", "WAIT_EVENT", "WAIT_DURATION", "EXTENSION", "BRANCH", "REVERSE", "OPEN_DOORS", "CLOSE_DOORS", "END", "ASYNC", "AWAIT").contains(type)) fail("action is not allowed in station program: " + type);
            if (type.equals("END")) { if (asynchronous) fail("END cannot occur in ASYNC"); ends = true; }
            if (type.equals("ASYNC")) validateStationSteps(step.path("steps"), true);
            if (type.equals("BRANCH")) {
                String provider = step.path("typeId").asText();
                if (!Set.of("javarailways:plan_run_type", "javarailways:plan_line_direction", "javarailways:plan_station_id", "javarailways:plan_run_id", "javarailways:plan_line_id", "javarailways:plan_call_status", "javarailways:plan_duty_id").contains(provider)) fail("station condition must be editable-plan deterministic");
                // Branch END reachability is checked after planned branches are folded per occurrence.
                validateStationSteps(step.path("thenSteps"), asynchronous);
                validateStationSteps(step.path("elseSteps"), asynchronous);
            }
        }
    }

    private static void validateArrivalSteps(JsonNode steps) {
        for (JsonNode step : steps) {
            String type = step.path("type").asText();
            if (!Set.of("SET_DISPLAY", "RESET_DISPLAY", "WAIT_UNTIL", "WAIT_DURATION", "EXTENSION", "BRANCH").contains(type))
                fail("action is not allowed in arrival program: " + step.path("id").asText());
            if (type.equals("BRANCH")) {
                validateArrivalSteps(step.path("thenSteps"));
                validateArrivalSteps(step.path("elseSteps"));
            }
        }
    }

    private static void validateSteps(JsonNode values, Map<String, String> parameters, Set<String> steps, int depth) {
        if (depth > 16) fail("program exceeds branch depth16");
        array(values, 256, "steps");
        for (JsonNode step : values) {
            String id = id(step.path("id"), "step id");
            if (!steps.add(id)) fail("duplicate step id: " + id);
            if (steps.size() > 256) fail("program exceeds256 total steps");
            String type = text(step.path("type"), "step type");
            if (depth > 0 && type.equals("STORE_AT_DEPOT")) fail("fixed operations cannot be conditional: " + id);
            switch (type) {
                case "SET_DISPLAY" -> {
                    fields(step, step.has("removeKeys") ? Set.of("id", "type", "values", "removeKeys") : Set.of("id", "type", "values"), "SET_DISPLAY step");
                    dataValue(step.path("values"), parameters);
                    if (!step.path("values").has("trainInfo")) {
                        JsonNode display = ProgramExpressionValidator.validate(step.path("values"), "JSON_OBJECT", parameters, null);
                        if (display != null) displayValues(display);
                    }
                    if (step.has("removeKeys")) {
                        value(step.path("removeKeys"), "ID_LIST", parameters, false);
                        if (step.path("removeKeys").has("value")) displayRemovalKeys(step.path("removeKeys").path("value"));
                    }
                }
                case "RESET_DISPLAY" -> {
                    fields(step, Set.of("id", "type"), "RESET_DISPLAY step");
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
                    if (!Set.of("javarailways:plan_run_type", "javarailways:plan_line_direction", "javarailways:plan_station_id", "javarailways:plan_run_id", "javarailways:plan_line_id", "javarailways:plan_call_status", "javarailways:plan_duty_id").contains(typeId)) fail("condition must be editable-plan deterministic");
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
                    dataValue(step.path("parameters"), parameters);
                }
                case "REVERSE", "CLOSE_DOORS", "END" -> fields(step, Set.of("id", "type"), type + " step");
                case "OPEN_DOORS" -> {
                    fields(step, step.has("doorSideOverride") ? Set.of("id", "type", "doorSideOverride") : Set.of("id", "type"), "OPEN_DOORS step");
                    if (step.hasNonNull("doorSideOverride")) {
                        JsonNode side = step.path("doorSideOverride");
                        JsonNode evaluated = ProgramExpressionValidator.validate(side, "STRING", parameters, null);
                        if (evaluated != null && !Set.of("LEFT", "RIGHT", "BOTH").contains(text(evaluated, "doorSideOverride"))) fail("invalid doorSideOverride");
                    }
                }
                case "WAIT_EVENT" -> {
                    fields(step, Set.of("id", "type", "eventId", "offsetTicks"), "WAIT_EVENT step");
                    id(step.path("eventId"), "time event id");
                    JsonNode offset = step.path("offsetTicks");
                    if (!offset.isIntegralNumber() || !offset.canConvertToLong() || Math.abs((double) offset.asLong()) > MAX_SAFE_INTEGER) fail("event offset must be a signed safe integer");
                }
                case "ASYNC" -> {
                    fields(step, Set.of("id", "type", "taskId", "steps"), "ASYNC step");
                    id(step.path("taskId"), "task id");
                    validateSteps(step.path("steps"), parameters, steps, depth + 1);
                }
                case "AWAIT" -> {
                    fields(step, Set.of("id", "type", "taskId"), "AWAIT step");
                    id(step.path("taskId"), "task id");
                }
                case "MOVE" -> {
                    fields(step, Set.of("id", "type", "destinationId", "viaIds"), "MOVE step");
                    identifierValue(step.path("destinationId"), parameters, "destinationId");
                    value(step.path("viaIds"), "ID_LIST", parameters, false);
                }
                case "SELECT_STOP" -> {
                    fields(step, Set.of("id", "type", "stationId", "berthIds", "selectionPolicy"), "SELECT_STOP step");
                    identifierValue(step.path("stationId"), parameters, "stationId");
                    value(step.path("berthIds"), "ID_LIST", parameters, false);
                    if (!Set.of("FIXED_PREFERENCE", "AVAILABLE_PRIORITY").contains(text(step.path("selectionPolicy"), "selection policy"))) fail("invalid station selection policy");
                    if (step.path("berthIds").has("value")) berthIds(step.path("berthIds").path("value"));
                }
                case "STORE_AT_DEPOT" -> {
                    fields(step, Set.of("id", "type", "depotId", "stablingPointIds"), "STORE_AT_DEPOT step");
                    identifierValue(step.path("depotId"), parameters, "depotId");
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
        Map<String, String> parameters = new HashMap<>();
        definition.path("parameters").forEach(parameter -> parameters.put(parameter.path("id").asText(), parameter.path("type").asText()));
        validateStepArguments(definition.path("steps"), parameters, arguments);
    }

    private static void validateStepArguments(JsonNode steps, Map<String, String> parameters, JsonNode arguments) {
        for (JsonNode step : steps) {
            String type = step.path("type").asText();
            if (type.equals("BRANCH")) {
                validateStepArguments(step.path("thenSteps"), parameters, arguments);
                validateStepArguments(step.path("elseSteps"), parameters, arguments);
            }
            if (type.equals("ASYNC")) validateStepArguments(step.path("steps"), parameters, arguments);
            if (type.equals("OPEN_DOORS") && step.hasNonNull("doorSideOverride")) {
                JsonNode side = ProgramExpressionValidator.validate(step.path("doorSideOverride"), "STRING", parameters, arguments);
                if (!Set.of("LEFT", "RIGHT", "BOTH").contains(text(side, "doorSideOverride"))) fail("invalid doorSideOverride");
            }
            Map<String, String> sinks = switch (type) {
                case "SET_DISPLAY" -> Map.of("values", "JSON_OBJECT", "removeKeys", "ID_LIST");
                case "WAIT_UNTIL" -> Map.of("tick", "TICK");
                case "WAIT_DURATION" -> Map.of("durationTicks", "DURATION");
                case "BRANCH", "EXTENSION" -> Map.of("parameters", "JSON_OBJECT");
                case "MOVE" -> Map.of("destinationId", "STRING", "viaIds", "ID_LIST");
                case "SELECT_STOP" -> Map.of("stationId", "STRING", "berthIds", "ID_LIST");
                case "STORE_AT_DEPOT" -> Map.of("depotId", "STRING", "stablingPointIds", "ID_LIST");
                default -> Map.of();
            };
            sinks.forEach((field, expected) -> {
                if (!step.has(field) || step.path(field).has("trainInfo")) return;
                JsonNode evaluated = ProgramExpressionValidator.validate(step.path(field), expected, parameters, arguments);
                literal(evaluated, expected, type.equals("WAIT_DURATION"));
                if (Set.of("destinationId", "stationId", "depotId").contains(field)) text(evaluated, field);
                if (field.equals("values")) displayValues(evaluated);
                if (field.equals("removeKeys")) displayRemovalKeys(evaluated);
                if (field.equals("berthIds")) berthIds(evaluated);
            });
        }
    }

    private static void berthIds(JsonNode value) {
        array(value, 64, "berth IDs");
        Set<String> ids = new HashSet<>();
        if (value.isEmpty()) fail("station selection requires distinct nonempty berth IDs");
        for (JsonNode item : value) if (!ids.add(text(item, "berth ID"))) fail("station selection requires distinct nonempty berth IDs");
    }

    private static void dataValue(JsonNode node, Map<String, String> parameters) {
        if (node.isObject() && node.size() == 1 && node.has("trainInfo")) {
            JsonNode bindings = node.path("trainInfo");
            if (!bindings.isObject() || bindings.size() < 1 || bindings.size() > 64) fail("trainInfo requires1 to64 bindings");
            bindings.fields().forEachRemaining(entry -> {
                if (entry.getKey().isBlank() || entry.getKey().length() > 256) fail("trainInfo key must be nonblank and at most256 characters");
                if (!entry.getValue().isTextual() || !Set.of("NAME", "CAR_COUNT", "DIRECTION").contains(entry.getValue().asText())) fail("unknown trainInfo field");
            });
        } else value(node, "JSON_OBJECT", parameters, false);
    }

    private static void identifierValue(JsonNode node, Map<String, String> parameters, String label) {
        JsonNode evaluated = ProgramExpressionValidator.validate(node, "STRING", parameters, null);
        if (evaluated != null) text(evaluated, label);
    }

    private static void value(JsonNode node, String type, Map<String, String> parameters, boolean positive) {
        JsonNode evaluated = ProgramExpressionValidator.validate(node, type, parameters, null);
        if (evaluated != null) literal(evaluated, type, positive);
    }

    private static void literal(JsonNode value, String type, boolean positive) {
        switch (type) {
            case "TICK", "DURATION" -> {
                double number = value.doubleValue();
                if (!value.isNumber() || !Double.isFinite(number) || Math.rint(number) != number || number < (positive ? 1 : 0) || number > MAX_SAFE_INTEGER) fail(type + " must be a " + (positive ? "positive" : "nonnegative") + " safe integer");
            }
            case "JSON_OBJECT" -> ProgramJsonValidator.object(value);
            case "ID_LIST" -> {
                array(value, 256, "ID_LIST");
                for (JsonNode item : value) text(item, "ID_LIST item");
            }
            case "NUMBER" -> {
                if (!value.isNumber() || !Double.isFinite(value.doubleValue()) || Math.rint(value.doubleValue()) == value.doubleValue() && Math.abs(value.doubleValue()) > MAX_SAFE_INTEGER) fail("NUMBER must be finite and integral values must be safe integers");
            }
            case "STRING" -> {
                if (!value.isTextual() || value.textValue().length() > 8192) fail("STRING must be a string of at most8192 characters");
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
        fields(node, fields, Set.of(), label);
    }

    static void fields(JsonNode node, Set<String> required, Set<String> optional, String label) {
        if (node == null || !node.isObject()) fail(label + " must be an object");
        node.fieldNames().forEachRemaining(name -> { if (!required.contains(name) && !optional.contains(name)) fail("unknown " + label + " field: " + name); });
        for (String field : required) if (!node.has(field)) fail(label + " requires " + field);
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
