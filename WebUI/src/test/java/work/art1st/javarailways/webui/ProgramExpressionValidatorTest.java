package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProgramExpressionValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode literal(Object value) { return mapper.createObjectNode().set("value", mapper.valueToTree(value)); }
    private JsonNode operation(String type, JsonNode... operands) {
        ObjectNode op = mapper.createObjectNode().put("type", type);
        var array = op.putArray("operands"); for (JsonNode operand : operands) array.add(operand);
        return mapper.createObjectNode().set("operation", op);
    }
    private JsonNode evaluate(JsonNode expression, String type) {
        return ProgramExpressionValidator.validate(expression, type, Map.of(), null);
    }
    private ObjectNode definition(JsonNode duration) {
        var definition = mapper.createObjectNode(); definition.putArray("parameters");
        definition.putArray("steps").addObject().put("id", "wait").put("type", "WAIT_DURATION").set("durationTicks", duration);
        return definition;
    }

    @Test void arithmeticAndTextSemanticsArePureAndComposable() {
        assertEquals(60, evaluate(operation("MULTIPLY", operation("ADD", literal(1), literal(2)), literal(20)), "NUMBER").asInt());
        assertEquals(-1, evaluate(operation("ROUND", literal(-1.5)), "NUMBER").asInt());
        assertEquals(2, evaluate(operation("TEXT_LENGTH", literal("😀")), "NUMBER").asInt());
        assertEquals("😀", evaluate(operation("SUBSTRING", literal("a😀z"), literal(1), literal(3)), "STRING").asText());
        assertEquals("x$x$", evaluate(operation("REPLACE", literal("abab"), literal("ab"), literal("x$")), "STRING").asText());
        assertEquals("0", evaluate(operation("TO_STRING", literal(-0.0)), "STRING").asText());
        assertEquals("0.0000001", evaluate(operation("TO_STRING", literal(1e-7)), "STRING").asText());
        assertEquals(-0.25, evaluate(operation("TO_NUMBER", literal(" \u00a0-.25e0\ufeff")), "NUMBER").asDouble());
        assertEquals("next: B", evaluate(operation("CONCAT", literal("next: "), literal("B")), "STRING").asText());
        assertDoesNotThrow(() -> StationProgramValidator.validate(definition(operation("MULTIPLY", literal(2), literal(20)))));
    }

    @Test void plainNumberFormattingMatchesJavaScriptForSubnormalsAndBinaryFractions() {
        assertEquals("0." + "0".repeat(323) + "5", evaluate(operation("TO_STRING", literal(Double.MIN_VALUE)), "STRING").asText());
        assertEquals("0." + "0".repeat(322) + "1", evaluate(operation("TO_STRING", literal(1e-323)), "STRING").asText());
        assertEquals("0.1", evaluate(operation("TO_STRING", literal(0.1)), "STRING").asText());
        assertEquals("0.30000000000000004", evaluate(operation("TO_STRING", literal(0.30000000000000004)), "STRING").asText());
    }

    @Test void malformedExpressionsAndInvalidResultsAreRejected() {
        for (JsonNode expression : new JsonNode[]{
            operation("DIVIDE", literal(1), literal(0)), operation("MODULO", literal(1), literal(0)),
            operation("ADD", literal(1)), operation("ADD", literal(true), literal(2)),
            operation("UNKNOWN", literal(1)), operation("TO_NUMBER", literal("0x10")),
            operation("TO_NUMBER", literal("")), operation("TO_NUMBER", literal("9007199254740992")),
            operation("SUBSTRING", literal("abc"), literal(2), literal(1)),
            operation("SUBSTRING", literal("abc"), literal(0), literal(4)),
            operation("REPLACE", literal("abc"), literal(""), literal("x")),
            operation("CONCAT", literal("a".repeat(8192)), literal("b"))
        }) assertThrows(IllegalArgumentException.class, () -> evaluate(expression, null), expression.toString());
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validate(definition(operation("DIVIDE", literal(1), literal(2)))));
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validate(definition(operation("SUBTRACT", literal(1), literal(1)))));
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validate(definition(operation("SUBTRACT", literal(1), literal(2)))));
        JsonNode expression = literal(1);
        for (int i = 0; i < 17; i++) expression = operation("FLOOR", expression);
        JsonNode excessive = expression;
        assertThrows(IllegalArgumentException.class, () -> evaluate(excessive, "NUMBER"));
        JsonNode wide = literal(0);
        for (int i = 0; i < 12; i++) wide = operation("ADD", wide, wide);
        JsonNode excessiveNodes = wide;
        assertThrows(IllegalArgumentException.class, () -> evaluate(excessiveNodes, "NUMBER"));
        var unknownField = (ObjectNode) operation("FLOOR", literal(1));
        ((ObjectNode) unknownField.path("operation")).put("extra", true);
        assertThrows(IllegalArgumentException.class, () -> evaluate(unknownField, "NUMBER"));
    }

    @Test void boundParametersAreEvaluatedAtEverySink() {
        JsonNode parameter = mapper.createObjectNode().put("parameterId", "seconds");
        var definition = definition(operation("MULTIPLY", parameter, literal(20)));
        definition.withArray("parameters").addObject().put("id", "seconds").put("type", "NUMBER");
        assertDoesNotThrow(() -> StationProgramValidator.validate(definition));
        assertDoesNotThrow(() -> StationProgramValidator.validateArguments(definition, mapper.createObjectNode().put("seconds", 1.5)));
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateArguments(definition, mapper.createObjectNode().put("seconds", 0.01)));
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateArguments(definition, mapper.createObjectNode().put("seconds", -1)));
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateArguments(definition, mapper.createObjectNode().put("seconds", "2")));
    }

    @Test void numericParametersUseIntegralValuesAndDurationPositivityAtTheirSink() {
        JsonNode parameter = mapper.createObjectNode().put("parameterId", "d");
        var definition = definition(parameter);
        definition.withArray("parameters").addObject().put("id", "d").put("type", "NUMBER");
        assertDoesNotThrow(() -> StationProgramValidator.validateArguments(definition, mapper.createObjectNode().put("d", 20.0)));
        ((ObjectNode) definition.path("steps").get(0)).put("type", "WAIT_UNTIL").remove("durationTicks");
        ((ObjectNode) definition.path("steps").get(0)).set("tick", parameter);
        assertDoesNotThrow(() -> StationProgramValidator.validateArguments(definition, mapper.createObjectNode().put("d", 20.0)));
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateArguments(definition, mapper.createObjectNode().put("d", 20.5)));

        var durationDefinition = definition(operation("ADD", parameter, literal(1)));
        durationDefinition.withArray("parameters").addObject().put("id", "d").put("type", "DURATION");
        JsonNode zero = mapper.createObjectNode().put("d", 0.0);
        assertDoesNotThrow(() -> StationProgramValidator.validateArguments(durationDefinition, zero));
        ((ObjectNode) durationDefinition.path("steps").get(0)).set("durationTicks", parameter);
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateArguments(durationDefinition, zero));

        var branch = mapper.createObjectNode().put("id", "branch").put("type", "BRANCH")
            .put("typeId", "javarailways:plan_run_id").put("version", 1);
        branch.putObject("parameters").putObject("value").putArray("values").add("never");
        branch.putArray("thenSteps").add(durationDefinition.path("steps").get(0));
        branch.putArray("elseSteps");
        durationDefinition.withArray("steps").removeAll().add(branch);
        assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateArguments(durationDefinition, zero));
    }

    @Test void libraryRoundTripKeepsExpressionTreesAndIdentity(@TempDir Path directory) throws Exception {
        JsonNode definition = definition(operation("MULTIPLY", literal(2), literal(20)));
        var repository = new VisualProgramRepository(directory, mapper, StationProgramValidator::validate);
        JsonNode created = repository.create("world", "1", "1", definition, null);
        var reopened = new VisualProgramRepository(directory, mapper, StationProgramValidator::validate);
        JsonNode loaded = reopened.find("world", "1").orElseThrow();
        assertEquals(created, loaded);
        assertEquals(definition, loaded.path("definition"));
        assertEquals(ProgramContentIdentity.of(definition), loaded.path("contentIdentity").asText());
    }

    @Test void oneStringParameterFeedsIdentifiersAndTextAndPersists(@TempDir Path directory) throws Exception {
        JsonNode definition = mapper.readTree("""
            {"parameters":[{"id":"place","type":"STRING"}],"steps":[
              {"id":"move","type":"MOVE","destinationId":{"parameterId":"place"},"viaIds":{"value":[]}},
              {"id":"display","type":"SET_DISPLAY","values":{"object":{
                "title":{"operation":{"type":"CONCAT","operands":[{"value":"Next: "},{"parameterId":"place"}]}},
                "id":{"parameterId":"place"}}}},
              {"id":"store","type":"STORE_AT_DEPOT","depotId":{"parameterId":"place"},"stablingPointIds":{"value":[]}}]}
            """);
        assertDoesNotThrow(() -> StationProgramValidator.validateArguments(definition, mapper.createObjectNode().put("place", "central")));
        var repository = new VisualProgramRepository(directory, mapper, StationProgramValidator::validate);
        var created = repository.create("world", "2", "2", definition, null);
        var loaded = new VisualProgramRepository(directory, mapper, StationProgramValidator::validate).find("world", "2").orElseThrow();
        assertEquals(created, loaded);
        assertEquals("STRING", loaded.path("definition").path("parameters").get(0).path("type").asText());
        StationProgramValidator.validateArguments(loaded.path("definition"), mapper.createObjectNode().put("place", "central"));
    }

    @Test void genericStringsRemainEmptyOrLongUntilUsedAsIdentifiers() throws Exception {
        JsonNode definition = mapper.readTree("""
            {"parameters":[{"id":"text","type":"STRING"}],"steps":[
              {"id":"display","type":"SET_DISPLAY","values":{"object":{"title":{"parameterId":"text"}}}}]}
            """);
        for (String text : new String[]{"", "   ", "x".repeat(8192)}) {
            assertEquals(text, evaluate(literal(text), "STRING").asText());
            assertDoesNotThrow(() -> StationProgramValidator.validateArguments(definition, mapper.createObjectNode().put("text", text)));
        }
        assertThrows(IllegalArgumentException.class, () -> evaluate(literal("x".repeat(8193)), "STRING"));
        for (String action : new String[]{"MOVE", "STORE_AT_DEPOT"}) {
            String field = action.equals("MOVE") ? "destinationId" : "depotId";
            String list = action.equals("MOVE") ? "viaIds" : "stablingPointIds";
            ObjectNode step = mapper.createObjectNode().put("id", "action").put("type", action);
            step.putObject(field).put("parameterId", "text");
            step.putObject(list).putArray("value").add("berth");
            ObjectNode bound = definition.deepCopy(); bound.withArray("steps").removeAll().add(step);
            for (String invalid : new String[]{"", "   ", "x".repeat(257)}) {
                assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validateArguments(bound, mapper.createObjectNode().put("text", invalid)));
                ObjectNode literalDefinition = bound.deepCopy();
                ((ObjectNode) literalDefinition.path("steps").get(0)).set(field, operation("CONCAT", literal(invalid), literal("")));
                assertThrows(IllegalArgumentException.class, () -> StationProgramValidator.validate(literalDefinition));
            }
            assertDoesNotThrow(() -> StationProgramValidator.validateArguments(bound, mapper.createObjectNode().put("text", "x".repeat(256))));
        }
    }

    @Test void objectExpressionsPreserveDataAndRejectNestedRuntimeBindings() throws Exception {
        var object = mapper.createObjectNode();
        object.set("title", operation("CONCAT", literal("platform "), operation("TO_STRING", literal(3))));
        object.set("empty", literal(""));
        var expression = mapper.createObjectNode().set("object", object);
        assertEquals("platform 3", evaluate(expression, "JSON_OBJECT").path("title").asText());
        assertEquals(expression, mapper.readTree(mapper.writeValueAsBytes(expression)));
        object.set("dynamic", mapper.createObjectNode().set("trainInfo", mapper.createObjectNode().put("cars", "CAR_COUNT")));
        assertThrows(IllegalArgumentException.class, () -> evaluate(expression, "JSON_OBJECT"));
    }
}
