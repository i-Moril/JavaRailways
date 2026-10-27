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
        if (document.has("formalScriptName") &&
            (!document.path("formalScriptName").isTextual() ||
                !document.path("formalScriptName").asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,123}\\.js"))) {
            fail("editor document formalScriptName must be a valid .js file name");
        }
        if (!document.path("periodDays").isIntegralNumber() || document.path("periodDays").asLong() < 1L) {
            fail("editor document periodDays must be a positive integer");
        }
        requireObjectArray(document, "runs");
        requireObjectArray(document, "duties");
        requireObjectArray(document, "diagramLines");
        requireObjectArray(document, "arrivalOverrides");
        if (document.has("stationPrograms")) {
            requireObjectArray(document, "stationPrograms");
            java.util.Set<String> stations = new java.util.HashSet<>();
            for (JsonNode record : document.path("stationPrograms")) {
                StationProgramValidator.fields(record, java.util.Set.of("stationId", "arrival", "departurePreparation", "preparationLeadTicks"), "shared station program");
                String station = StationProgramValidator.text(record.path("stationId"), "station id");
                if (!stations.add(station)) fail("duplicate shared station program");
                StationProgramValidator.validateStation(record.path("arrival"));
                StationProgramValidator.validateStation(record.path("departurePreparation"));
                if (!record.path("preparationLeadTicks").isIntegralNumber() || !record.path("preparationLeadTicks").canConvertToLong() || record.path("preparationLeadTicks").asLong() < 0 || record.path("preparationLeadTicks").asLong() > 9_007_199_254_740_991L) fail("invalid preparation lead ticks");
            }
        }
        if (document.has("events")) {
            requireObjectArray(document, "events");
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (JsonNode event : document.path("events")) {
                java.util.Set<String> fields = new java.util.HashSet<>(java.util.Set.of("id", "stationId", "tick"));
                if (event.has("label")) fields.add("label");
                StationProgramValidator.fields(event, fields, "time event");
                if (!ids.add(StationProgramValidator.text(event.path("id"), "time event id"))) fail("duplicate time event id");
                StationProgramValidator.text(event.path("stationId"), "time event station");
                requireIntegral(event, "tick", "time event");
                if (event.path("tick").asLong() < 0 || event.path("tick").asLong() >= Math.multiplyExact(document.path("periodDays").asLong(), 24_000L)) fail("time event tick outside timetable period");
                if (event.has("label") && !event.path("label").isTextual()) fail("time event label must be text");
            }
        }
        java.util.Map<String, JsonNode> programs = new java.util.HashMap<>();
        if (document.has("boundPrograms")) {
            requireObjectArray(document, "boundPrograms");
            for (JsonNode snapshot : document.path("boundPrograms")) {
                StationProgramValidator.fields(snapshot, java.util.Set.of("programId", "revision", "contentIdentity", "definition"), "bound program");
                String id = StationProgramValidator.text(snapshot.path("programId"), "bound program id");
                if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) fail("invalid bound program id");
                if (programs.put(id, snapshot) != null) fail("duplicate bound program");
                StationProgramValidator.revision(snapshot.path("revision"));
                StationProgramValidator.validate(snapshot.path("definition"));
                if (!ProgramContentIdentity.of(snapshot.path("definition")).equals(snapshot.path("contentIdentity").asText())) fail("bound program content identity mismatch");
            }
        }
        for (JsonNode duty : document.path("duties")) {
            java.util.Set<String> callIds = new java.util.HashSet<>();
            for (JsonNode element : duty.path("elements")) if (element.path("type").asText().equals("PROGRAM")) {
                JsonNode call = element.path("call");
                StationProgramValidator.fields(call, java.util.Set.of("id", "programId", "tick", "arguments"), "program call");
                String callId = StationProgramValidator.text(call.path("id"), "program call id");
                if (!callId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}") || !callIds.add(callId)) fail("invalid or duplicate program call id");
                JsonNode snapshot = programs.get(call.path("programId").asText());
                if (snapshot == null) fail("missing bound program");
                if (!call.path("tick").isIntegralNumber() || !call.path("tick").canConvertToLong() || call.path("tick").asLong() < 0 || call.path("tick").asLong() > 9_007_199_254_740_991L) fail("invalid program call tick");
                StationProgramValidator.validateArguments(snapshot.path("definition"), call.path("arguments"));
            }
        }
        if (document.has("formationEvents")) {
            requireObjectArray(document, "formationEvents");
            document.path("formationEvents").forEach(FormationAuthoringValidator::validate);
        }
        if (document.has("operationalTrips")) validateOperationalTrips(document);
        document.path("runs").forEach(EditorDocumentValidator::validateRun);
        document.path("duties").forEach(EditorDocumentValidator::validateDuty);
        document.path("diagramLines").forEach(EditorDocumentValidator::validateDiagramLine);
    }

    private static void validateOperationalTrips(JsonNode document) {
        requireObjectArray(document, "operationalTrips");
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Set<String> assignments = new java.util.HashSet<>();
        java.util.Map<String, java.util.Set<String>> dutyRuns = new java.util.HashMap<>();
        for (JsonNode duty : document.path("duties")) {
            java.util.Set<String> runs = new java.util.HashSet<>();
            for (JsonNode element : duty.path("elements")) if (element.path("type").asText().equals("RUN")) runs.add(element.path("runId").asText());
            dutyRuns.put(duty.path("id").asText(), runs);
        }
        for (JsonNode trip : document.path("operationalTrips")) {
            StationProgramValidator.fields(trip, java.util.Set.of("id", "defaults", "segments"), "operational trip");
            String id = StationProgramValidator.text(trip.path("id"), "operational trip id");
            if (!ids.add(id)) fail("duplicate operational trip id");
            StationProgramValidator.displayValues(trip.path("defaults"));
            requireObjectArray(trip, "segments");
            if (trip.path("segments").isEmpty()) fail("operational trip segments must not be empty");
            for (JsonNode segment : trip.path("segments")) {
                StationProgramValidator.fields(segment, java.util.Set.of("serviceId", "runId", "unitSlots"), "operational trip segment");
                String serviceId = StationProgramValidator.text(segment.path("serviceId"), "serviceId");
                String runId = StationProgramValidator.text(segment.path("runId"), "runId");
                if (!dutyRuns.getOrDefault(serviceId, java.util.Set.of()).contains(runId)) fail("operational trip references missing duty/run");
                StationProgramValidator.unitSlots(segment.path("unitSlots"));
                for (JsonNode slot : segment.path("unitSlots")) if (!assignments.add(serviceId + "\u0000" + runId + "\u0000" + slot.asInt())) fail("duplicate operational trip unit assignment");
            }
        }
    }

    private static void validateRun(JsonNode run) {
        requireText(run, "id", "run");
        requireText(run, "type", "run");
        requireText(run, "lineDirection", "run");
        requireObjectArray(run, "calls");
        run.path("calls").forEach(call -> {
            java.util.Set<String> fields = new java.util.HashSet<>(java.util.Set.of("id", "stationId", "status", "targetId"));
            for (String optional : java.util.Set.of("berthId", "arrivalTick", "departureTick", "minimumDwellTicks", "stoppingOffsetMetersBefore", "doorSide", "viaIds")) {
                if (call.has(optional)) fields.add(optional);
            }
            StationProgramValidator.fields(call, fields, "run call");
            requireText(call, "id", "run call");
            requireText(call, "stationId", "run call");
            requireText(call, "status", "run call");
            requireText(call, "targetId", "run call");
            if (call.has("viaIds")) requireStringArray(call, "viaIds", "run call");
        });
    }

    private static void validateDuty(JsonNode duty) {
        requireText(duty, "id", "duty");
        JsonNode selector = duty.path("selector");
        if (!selector.isObject()) fail("duty selector must be an object");
        validateSelector(selector);
        if (duty.has("referenceVehicle")) {
            JsonNode profile = duty.path("referenceVehicle");
            StationProgramValidator.fields(profile, java.util.Set.of("type", "id"), "reference vehicle");
            if (!java.util.Set.of("WORLD_TRAIN", "TRAIN_TEMPLATE").contains(profile.path("type").asText())) fail("invalid reference vehicle type");
            StationProgramValidator.text(profile.path("id"), "reference vehicle id");
        }
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
            case "TAKE_FROM_FORMATION_OUTPUT" -> {
                requireIntegral(selector, "tick", "formation output selector");
                requireText(selector, "eventId", "formation output selector");
                requireText(selector, "outputId", "formation output selector");
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
            case "INLINE" -> {
                java.util.Set<String> fields = new java.util.HashSet<>(java.util.Set.of("id", "type", "definition"));
                if (element.has("tick")) fields.add("tick");
                StationProgramValidator.fields(element, fields, "inline duty program");
                if (element.has("tick")) {
                    requireIntegral(element, "tick", "inline duty program");
                    if (element.path("tick").asLong() < 0 || element.path("tick").asLong() > 9_007_199_254_740_991L) fail("invalid inline duty program tick");
                }
                StationProgramValidator.validate(element.path("definition"));
                if (!element.path("definition").path("parameters").isEmpty()) fail("inline duty program cannot declare parameters");
            }
            case "PROGRAM" -> { if (!element.path("call").isObject()) fail("program call must be an object"); }
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
