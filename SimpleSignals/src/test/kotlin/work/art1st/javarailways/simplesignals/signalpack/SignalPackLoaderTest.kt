package work.art1st.javarailways.simplesignals.signalpack

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SignalPackLoaderTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `resolves mutual cross-package references independently of directory order`() {
        pack(
            "z-directory",
            """
                config-version: 1
                name: alpha
                models:
                  mast: mast.yml
                logics:
                  local:
                    file: local.js
                    scan-distance-metres: 250.5
                combinations:
                  cross:
                    display-name: Alpha Cross
                    model: alpha:mast
                    logic: beta:clear
            """,
            models = mapOf("mast.yml" to "model"),
            logics = mapOf("local.js" to VALID_LOGIC)
        )
        pack(
            "a-directory",
            """
                config-version: 1
                name: beta
                models:
                  head: head.yml
                logics:
                  clear:
                    file: clear.js
                    scan-distance-metres: 800
                combinations:
                  mutual:
                    display-name: Beta Mutual
                    model: alpha:mast
                    logic: beta:clear
            """,
            models = mapOf("head.yml" to "model"),
            logics = mapOf("clear.js" to VALID_LOGIC)
        )

        val catalog = SignalPackLoader.load(temporary)

        assertEquals(listOf("beta", "alpha"), catalog.packages.keys.toList())
        assertEquals("beta:clear", catalog.combinations.getValue("alpha:cross").logic.id)
        assertEquals("alpha:mast", catalog.combinations.getValue("beta:mutual").model.id)
        assertEquals(250.5, catalog.logics.getValue("alpha:local").scanDistanceMetres)
    }

    @Test
    fun `only declared combinations are player-visible catalog entries`() {
        pack(
            "library",
            manifest("library", combination = false)
                .replace("mast: mast.yml", "unused: unused.yml")
                .replace("logic:\n    file: logic.js", "unused:\n    file: unused.js"),
            models = mapOf("unused.yml" to "model"),
            logics = mapOf("unused.js" to VALID_LOGIC)
        )

        val catalog = SignalPackLoader.load(temporary)

        assertTrue(catalog.combinations.isEmpty())
        assertTrue("library:unused" in catalog.models)
        assertTrue("library:unused" in catalog.logics)
    }

    @Test
    fun `rejects unknown fields missing fields duplicate keys and package names`() {
        pack("unknown", manifest("unknown").replace("name: unknown", "name: unknown\nextra: false"))
        assertFails("unknown field 'extra'")
        reset()

        pack("missing", manifest("missing").replace("models:\n  mast: mast.yml\n", ""))
        assertFails("missing required field")
        reset()

        pack("duplicate-key", manifest("duplicate").replace("name: duplicate", "name: duplicate\nname: repeated"))
        assertFails("duplicate YAML key")
        reset()

        pack("one", manifest("same"))
        pack("two", manifest("same"))
        assertFails("duplicate package name 'same'")
    }

    @Test
    fun `rejects unknown and wrong-kind references`() {
        pack("unknown", manifest("unknown").replace("logic: unknown:logic", "logic: absent:logic"))
        assertFails("unknown logic export 'absent:logic'")
        reset()

        pack("wrong", manifest("wrong").replace("model: wrong:mast", "model: wrong:logic"))
        assertFails("reference 'wrong:logic' is not a model export")
        reset()

        pack("wrong", manifest("wrong").replace("logic: wrong:logic", "logic: wrong:mast"))
        assertFails("reference 'wrong:mast' is not a logic export")
    }

    @Test
    fun `accepts only current config version one`() {
        pack("old", manifest("old").replace("config-version: 1", "config-version: 0"))
        assertFails("only config-version 1 is supported")
        reset()

        pack("future", manifest("future").replace("config-version: 1", "config-version: 2"))
        assertFails("only config-version 1 is supported")
    }

    @Test
    fun `rejects invalid scan distances and non-js logic files`() {
        pack("zero", manifest("zero").replace("scan-distance-metres: 100", "scan-distance-metres: 0"))
        assertFails("must be finite and positive")
        reset()

        pack("negative", manifest("negative").replace("scan-distance-metres: 100", "scan-distance-metres: -1"))
        assertFails("must be finite and positive")
        reset()

        pack("nan", manifest("nan").replace("scan-distance-metres: 100", "scan-distance-metres: .nan"))
        assertFails("number must be finite")
        reset()

        pack("wrong-extension", manifest("wrong-extension").replace("logic.js", "logic.txt"), logics = mapOf("logic.txt" to VALID_LOGIC))
        assertFails("logic file must end with '.js'")
    }

    @Test
    fun `requires the declared package directory shape`() {
        val root = pack("missing-model-directory", manifest("missing-model-directory"))
        Files.walk(root.resolve("model")).sorted(Comparator.reverseOrder()).forEach(Files::delete)

        assertFails("model path must be a directory")
    }

    @Test
    fun `rejects lexical path escape and symlink escape`() {
        pack("lexical", manifest("lexical").replace("mast.yml", "../outside.yml"))
        Files.writeString(temporary.resolve("lexical/outside.yml"), "model")
        assertFails("invalid relative file")
        reset()

        val outside = Files.createTempFile("signal-pack-outside", ".js")
        val packageRoot = pack("linked", manifest("linked"))
        val link = packageRoot.resolve("logic/logic.js")
        Files.delete(link)
        val symlinkCreated = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        assumeTrue(symlinkCreated)
        assertFails("path escapes the package directory")
    }

    @Test
    fun `catalog maps cannot be mutated`() {
        pack("stable", manifest("stable"))
        val catalog = SignalPackLoader.load(temporary)

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (catalog.combinations as MutableMap<String, SignalPackCombination>).clear()
        }
        assertFalse(catalog.combinations.isEmpty())
    }

    @Test
    fun `catalog captures logic source and does not retain a deferred file read`() {
        val root = pack("stable", manifest("stable"))
        val catalog = SignalPackLoader.load(temporary)
        val captured = catalog.logics.getValue("stable:logic").source

        Files.writeString(root.resolve("logic/logic.js"), "changed")

        assertEquals(VALID_LOGIC, captured)
        assertEquals(VALID_LOGIC, catalog.logics.getValue("stable:logic").source)
    }

    private fun assertFails(message: String) {
        val error = assertThrows(IllegalArgumentException::class.java) { SignalPackLoader.load(temporary) }
        assertTrue(error.message.orEmpty().contains(message), error.message)
    }

    private fun reset() {
        Files.walk(temporary).sorted(Comparator.reverseOrder()).filter { it != temporary }.forEach(Files::delete)
    }

    private fun pack(
        directory: String,
        yaml: String,
        models: Map<String, String> = mapOf("mast.yml" to "model"),
        logics: Map<String, String> = mapOf("logic.js" to VALID_LOGIC)
    ): Path {
        val root = Files.createDirectories(temporary.resolve(directory))
        Files.createDirectories(root.resolve("model"))
        Files.createDirectories(root.resolve("logic"))
        Files.writeString(root.resolve("pack.yml"), yaml.trimIndent())
        models.forEach { (file, value) ->
            val path = root.resolve("model/$file")
            Files.createDirectories(path.parent)
            Files.writeString(path, value)
        }
        logics.forEach { (file, value) ->
            val path = root.resolve("logic/$file")
            Files.createDirectories(path.parent)
            Files.writeString(path, value)
        }
        return root
    }

    private fun manifest(name: String, combination: Boolean = true): String = """
        config-version: 1
        name: $name
        models:
          mast: mast.yml
        logics:
          logic:
            file: logic.js
            scan-distance-metres: 100
        combinations:${if (combination) """

          standard:
            display-name: Standard
            model: $name:mast
            logic: $name:logic
        """ else " {}"}
    """.trimIndent()

    private companion object {
        const val VALID_LOGIC = "function evaluate(input, output) { output.setSpeedLimit(0); output.setModelVariant('stop'); }"
    }
}
