package work.art1st.javarailways.webui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunColorValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode document() throws Exception {
        return (ObjectNode) mapper.readTree("""
            {"formatVersion":1,"id":"weekday","periodDays":1,
             "runs":[{"id":"run","type":"PASSENGER","lineDirection":"UP","calls":[]}],
             "duties":[],"diagramLines":[],"initialPositions":[],"arrivalOverrides":[]}
            """);
    }

    @Test void acceptsAutomaticColorAndFullHexColors() throws Exception {
        var document = document();
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document));
        var run = (ObjectNode) document.path("runs").get(0);
        for (String color : new String[]{"#12aBcD", "#000000", "#FFFFFF"}) {
            run.put("color", color);
            assertDoesNotThrow(() -> EditorDocumentValidator.validate(document), color);
        }
        run.remove("color");
        assertDoesNotThrow(() -> EditorDocumentValidator.validate(document));
    }

    @Test void rejectsNullNonStringsAndNonHexCss() throws Exception {
        for (String value : new String[]{"null", "123", "true", "{}", "[]", "\"red\"",
            "\"#abc\"", "\"#11223344\"", "\"112233\"", "\"#gg0000\"", "\" #112233\"",
            "\"rgb(1, 2, 3)\"", "\"url(https://example.com)\""}) {
            var document = document();
            ((ObjectNode) document.path("runs").get(0)).set("color", mapper.readTree(value));
            assertThrows(IllegalArgumentException.class, () -> EditorDocumentValidator.validate(document), value);
        }
    }
}
