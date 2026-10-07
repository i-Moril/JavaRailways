package work.art1st.javarailways.simplesignals.signalpack

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class SignalLogicRuntimeTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `evaluates immutable JSON input and returns both setters atomically`() {
        val runtime = runtime("""
            function evaluate(input, output) {
              if (!Object.isFrozen(input) || !Object.isFrozen(input.nested) || !Object.isFrozen(input.items)) {
                throw new Error('input is not deeply frozen');
              }
              try { input.speed = 99; input.nested.value = 99; input.items.push(99); } catch (error) {}
              if (input.speed !== 12.5 || input.nested.value !== 7 || input.items.length !== 2) {
                throw new Error('input mutation escaped');
              }
              output.setSpeedLimit(input.speed);
              output.setModelVariant(input.variant);
            }
        """)

        runtime.use {
            val result = it.evaluate(
                "test:logic",
                kotlinx.serialization.json.buildJsonObject {
                    put("speed", 12.5)
                    put("variant", "YG")
                    put("nested", kotlinx.serialization.json.buildJsonObject { put("value", 7) })
                    put("items", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(2)) })
                }
            )
            assertEquals(SignalLogicOutput(12.5, "YG"), result)
        }
    }

    @Test
    fun `does not leak closure or global state across evaluations`() {
        runtime("""
            let closureCount = 0;
            function evaluate(input, output) {
              closureCount += 1;
              globalThis.globalCount = (globalThis.globalCount || 0) + 1;
              output.setSpeedLimit(closureCount);
              output.setModelVariant(String(globalThis.globalCount));
            }
        """).use { runtime ->
            assertEquals(SignalLogicOutput(1.0, "1"), runtime.evaluate("test:logic", input(0.0)))
            assertEquals(SignalLogicOutput(1.0, "1"), runtime.evaluate("test:logic", input(0.0)))
        }
    }

    @Test
    fun `uses the logic source captured by catalog preparation`() {
        val catalog = catalog(validBody())
        Files.writeString(
            temporary.resolve("pack/logic/logic.js"),
            "function evaluate(input, output) { output.setSpeedLimit(99); output.setModelVariant('changed'); }"
        )

        SignalLogicRuntime(catalog).use { runtime ->
            assertEquals(SignalLogicOutput(0.0, "R"), runtime.evaluate("test:logic", input(0.0)))
        }
    }

    @Test
    fun `discards guest console output and exposes no host bridge`() {
        val originalOut = System.out
        val originalErr = System.err
        val capturedOut = ByteArrayOutputStream()
        val capturedErr = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(capturedOut, true, Charsets.UTF_8))
            System.setErr(PrintStream(capturedErr, true, Charsets.UTF_8))
            runtime("""
                function evaluate(input, output) {
                  console.log('guest stdout');
                  console.error('guest stderr');
                  if (typeof Java !== 'undefined' || typeof Polyglot !== 'undefined' ||
                      output.getClass !== undefined || output.class !== undefined) {
                    throw new Error('host bridge is visible');
                  }
                  output.setSpeedLimit(0);
                  output.setModelVariant('R');
                }
            """).use { runtime -> runtime.evaluate("test:logic", input(0.0)) }
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        assertEquals("", capturedOut.toString(Charsets.UTF_8))
        assertEquals("", capturedErr.toString(Charsets.UTF_8))
    }

    @Test
    fun `rejects syntax errors and missing evaluate during preparation`() {
        assertRuntimeFails("function evaluate(", "cannot prepare signal logic runtime")
        assertRuntimeFails("function another() {}", "logic must define function evaluate")
        assertRuntimeFails("import value from './other.js'; function evaluate() {}", "cannot prepare signal logic runtime")
    }

    @Test
    fun `rejects missing duplicate and invalid setters`() {
        assertEvaluationFails("output.setModelVariant('R');", "did not call setSpeedLimit")
        assertEvaluationFails("output.setSpeedLimit(0);", "did not call setModelVariant")
        assertEvaluationFails(
            "output.setSpeedLimit(1); output.setSpeedLimit(2); output.setModelVariant('G');",
            "setSpeedLimit more than once"
        )
        assertEvaluationFails(
            "output.setSpeedLimit(1); output.setModelVariant('G'); output.setModelVariant('Y');",
            "setModelVariant more than once"
        )
        assertEvaluationFails("output.setSpeedLimit(-1); output.setModelVariant('R');", "invalid speed limit")
        assertEvaluationFails("output.setSpeedLimit(NaN); output.setModelVariant('R');", "invalid speed limit")
        assertEvaluationFails("output.setSpeedLimit(Infinity); output.setModelVariant('R');", "invalid speed limit")
        assertEvaluationFails("output.setSpeedLimit(0); output.setModelVariant('   ');", "blank model variant")
        assertEvaluationRejected("output.setSpeedLimit('fast'); output.setModelVariant('R');")
        assertEvaluationRejected("output.setSpeedLimit(0); output.setModelVariant({});")
    }

    @Test
    fun `rejects thrown exceptions and Promise results`() {
        assertEvaluationFails("throw new Error('broken rule');", "broken rule")
        assertEvaluationRejected("return Promise.resolve('later');")
        assertEvaluationFails(
            "async function nested() {} return nested();",
            "Promise/async signal rule results are not allowed"
        )
        assertEvaluationFails(
            "async function late() { await 0; output.setSpeedLimit(1); } return late();",
            "Promise/async signal rule results are not allowed"
        )
        assertEvaluationRejected(
            "(async function late() { await 0; output.setSpeedLimit(2); })(); " +
                "output.setSpeedLimit(1); output.setModelVariant('G');"
        )
    }

    @Test
    fun `denies host classes clock random and dynamic imports`() {
        assertEvaluationRejected("Java.type('java.lang.System');")
        assertEvaluationRejected("new Date();")
        assertEvaluationRejected("Math.random();")
        assertEvaluationRejected("Promise.resolve();")
        assertEvaluationRejected("return import('./other.js');")
    }

    @Test
    fun `runtime is serially reusable and rejects calls after close`() {
        val runtime = runtime("""
            function evaluate(input, output) {
              output.setSpeedLimit(input.speed);
              output.setModelVariant('G');
            }
        """)
        assertEquals(1.0, runtime.evaluate("test:logic", input(1.0)).speedLimitMetresPerSecond)
        assertEquals(2.0, runtime.evaluate("test:logic", input(2.0)).speedLimitMetresPerSecond)
        runtime.close()
        runtime.close()

        val error = assertThrows(IllegalStateException::class.java) { runtime.evaluate("test:logic", input(3.0)) }
        assertEquals("signal logic runtime is closed", error.message)
    }

    @Test
    fun `rejects unknown logic identity`() {
        runtime(validBody()).use { runtime ->
            val error = assertThrows(SignalLogicException::class.java) { runtime.evaluate("other:logic", input(0.0)) }
            assertTrue(error.message.orEmpty().contains("unknown signal logic"))
        }
    }

    private fun assertRuntimeFails(script: String, message: String) {
        val error = assertThrows(SignalLogicException::class.java) { runtime(script) }
        assertTrue(error.message.orEmpty().contains(message), error.message)
    }

    private fun assertEvaluationFails(body: String, message: String) {
        runtime("function evaluate(input, output) { $body }").use { runtime ->
            val error = assertThrows(SignalLogicException::class.java) { runtime.evaluate("test:logic", input(0.0)) }
            assertTrue(error.message.orEmpty().contains(message), error.message)
        }
    }

    private fun assertEvaluationRejected(body: String) {
        runtime("function evaluate(input, output) { $body }").use { runtime ->
            assertThrows(SignalLogicException::class.java) { runtime.evaluate("test:logic", input(0.0)) }
        }
    }

    private fun runtime(script: String): SignalLogicRuntime {
        return SignalLogicRuntime(catalog(script))
    }

    private fun catalog(script: String): SignalPackCatalog {
        Files.walk(temporary).sorted(Comparator.reverseOrder()).filter { it != temporary }.forEach(Files::delete)
        val pack = Files.createDirectories(temporary.resolve("pack"))
        Files.createDirectories(pack.resolve("model"))
        Files.createDirectories(pack.resolve("logic"))
        Files.createDirectories(pack.resolve("signal"))
        Files.writeString(pack.resolve("model/model.yml"), "model")
        Files.writeString(pack.resolve("logic/logic.js"), script.trimIndent())
        Files.writeString(pack.resolve("logic/logic.yml"), "scan-distance-metres: 100")
        Files.writeString(
            pack.resolve("signal/standard.yml"),
            """
                display-name: Standard
                model: test:model
                logic: test:logic
            """.trimIndent()
        )
        Files.writeString(
            pack.resolve("pack.yml"),
            """
                config-version: 1
                name: test
            """.trimIndent()
        )
        return SignalPackLoader.load(temporary)
    }

    private fun input(speed: Double) = buildJsonObject { put("speed", speed) }

    private fun validBody() = """
        function evaluate(input, output) {
          output.setSpeedLimit(0);
          output.setModelVariant('R');
        }
    """
}
