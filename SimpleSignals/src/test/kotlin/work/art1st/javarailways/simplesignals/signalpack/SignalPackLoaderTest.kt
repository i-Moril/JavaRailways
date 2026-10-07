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
    fun `discovers first-class files and resolves cross-package references independently of directory order`() {
        pack(
            "z-directory",
            manifest("alpha", "Alpha package"),
            models = mapOf("heads/mast.yml" to "model"),
            logics = mapOf("local" to Logic(250.5)),
            signals = mapOf("aspects/cross.yml" to signal("Alpha Cross", "alpha:heads/mast", "beta:clear"))
        )
        pack(
            "a-directory",
            manifest("beta"),
            models = mapOf("head.yml" to "model"),
            logics = mapOf("clear" to Logic(800.0)),
            signals = mapOf("mutual.yml" to signal("Beta Mutual", "alpha:heads/mast", "beta:clear"))
        )

        val catalog = SignalPackLoader.load(temporary)

        assertEquals(listOf("beta", "alpha"), catalog.packages.keys.toList())
        assertEquals("Alpha package", catalog.packages.getValue("alpha").displayName)
        assertEquals("beta:clear", catalog.signals.getValue("alpha:aspects/cross").logicId)
        assertEquals("alpha:heads/mast", catalog.signals.getValue("beta:mutual").modelId)
        assertEquals(250.5, catalog.logics.getValue("alpha:local").scanDistanceMetres)
    }

    @Test
    fun `only signal files are player-visible while model and logic files remain addressable`() {
        pack(
            "library",
            manifest("library"),
            models = mapOf("unused.yml" to "model"),
            logics = mapOf("nested/unused" to Logic(100.0)),
            signals = emptyMap()
        )

        val catalog = SignalPackLoader.load(temporary)

        assertTrue(catalog.signals.isEmpty())
        assertTrue("library:unused" in catalog.models)
        assertTrue("library:nested/unused" in catalog.logics)
    }

    @Test
    fun `pack manifest contains metadata only`() {
        pack("unknown", manifest("unknown") + "\nmodels: {}")
        assertFails("unknown field 'models'")
        reset()

        pack("missing", "config-version: 1")
        assertFails("missing required field")
        reset()

        pack("duplicate-key", "config-version: 1\nname: duplicate\nname: repeated")
        assertFails("duplicate YAML key")
        reset()

        pack("one", manifest("same"))
        pack("two", manifest("same"))
        assertFails("duplicate package name 'same'")
    }

    @Test
    fun `rejects unknown and wrong-kind signal references`() {
        pack("unknown", manifest("unknown"), signals = mapOf(
            "standard.yml" to signal("Standard", "unknown:mast", "absent:logic")
        ))
        assertFails("unknown logic ID 'absent:logic'")
        reset()

        pack("wrong", manifest("wrong"), signals = mapOf(
            "standard.yml" to signal("Standard", "wrong:logic", "wrong:logic")
        ))
        assertFails("reference 'wrong:logic' is not a model ID")
        reset()

        pack("wrong", manifest("wrong"), signals = mapOf(
            "standard.yml" to signal("Standard", "wrong:mast", "wrong:mast")
        ))
        assertFails("reference 'wrong:mast' is not a logic ID")
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
    fun `requires one same-stem metadata file for each logic source`() {
        pack("zero", manifest("zero"), logics = mapOf("logic" to Logic(0.0)))
        assertFails("must be finite and positive")
        reset()

        pack("negative", manifest("negative"), logics = mapOf("logic" to Logic(-1.0)))
        assertFails("must be finite and positive")
        reset()

        val missingSource = pack("missing-source", manifest("missing-source"))
        Files.delete(missingSource.resolve("logic/logic.js"))
        assertFails("missing matching logic source 'logic.js'")
        reset()

        val missingMetadata = pack("missing-metadata", manifest("missing-metadata"))
        Files.delete(missingMetadata.resolve("logic/logic.yml"))
        assertFails("missing matching logic metadata 'logic.yml'")
    }

    @Test
    fun `requires the four package paths`() {
        val root = pack("missing-signal-directory", manifest("missing-signal-directory"))
        Files.walk(root.resolve("signal")).sorted(Comparator.reverseOrder()).forEach(Files::delete)

        assertFails("signal path must be a directory")
    }

    @Test
    fun `rejects unsafe derived IDs and symlink escape`() {
        pack("unsafe", manifest("unsafe"), models = mapOf("UPPER.yml" to "model"))
        assertFails("invalid local ID 'UPPER'")
        reset()

        pack("dot-segment", manifest("dot-segment"), models = mapOf("...yml" to "model"))
        assertFails("invalid local ID '..'")
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
    fun `rejects a directory symlink that escapes discovery root`() {
        val outside = Files.createTempDirectory("signal-pack-outside-directory")
        Files.writeString(outside.resolve("hidden.yml"), "model")
        val packageRoot = pack("linked-directory", manifest("linked-directory"))
        val link = packageRoot.resolve("model/linked")
        val symlinkCreated = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        assumeTrue(symlinkCreated)

        assertFails("path escapes the package directory")
    }

    @Test
    fun `rejects unknown fields in logic metadata and signal definitions`() {
        val logicRoot = pack("logic-field", manifest("logic-field"))
        Files.writeString(logicRoot.resolve("logic/logic.yml"), "scan-distance-metres: 100\nextra: true")
        assertFails("unknown field 'extra'")
        reset()

        pack(
            "signal-field",
            manifest("signal-field"),
            signals = mapOf(
                "standard.yml" to signal("Standard", "signal-field:mast", "signal-field:logic") + "\nextra: true"
            )
        )
        assertFails("unknown field 'extra'")
    }

    @Test
    fun `catalog maps cannot be mutated`() {
        pack("stable", manifest("stable"))
        val catalog = SignalPackLoader.load(temporary)

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (catalog.signals as MutableMap<String, SignalPackSignal>).clear()
        }
        assertFalse(catalog.signals.isEmpty())
    }

    @Test
    fun `catalog captures logic source and records both source paths`() {
        val root = pack("stable", manifest("stable"))
        val catalog = SignalPackLoader.load(temporary)
        val logic = catalog.logics.getValue("stable:logic")

        Files.writeString(root.resolve("logic/logic.js"), "changed")

        assertEquals(VALID_LOGIC, logic.source)
        assertEquals(root.resolve("logic/logic.yml").toRealPath(), logic.metadataFile)
        assertEquals(root.resolve("logic/logic.js").toRealPath(), logic.sourceFile)
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
        logics: Map<String, Logic> = mapOf("logic" to Logic(100.0)),
        signals: Map<String, String> = mapOf("standard.yml" to signal("Standard", "${packageName(yaml)}:mast", "${packageName(yaml)}:logic"))
    ): Path {
        val root = Files.createDirectories(temporary.resolve(directory))
        Files.createDirectories(root.resolve("model"))
        Files.createDirectories(root.resolve("logic"))
        Files.createDirectories(root.resolve("signal"))
        Files.writeString(root.resolve("pack.yml"), yaml.trimIndent())
        models.forEach { (file, value) -> write(root.resolve("model/$file"), value) }
        logics.forEach { (localId, logic) ->
            write(root.resolve("logic/$localId.yml"), "scan-distance-metres: ${logic.distance}")
            write(root.resolve("logic/$localId.js"), logic.source)
        }
        signals.forEach { (file, value) -> write(root.resolve("signal/$file"), value) }
        return root
    }

    private fun write(path: Path, value: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, value.trimIndent())
    }

    private fun manifest(name: String, displayName: String? = null): String = buildString {
        appendLine("config-version: 1")
        appendLine("name: $name")
        if (displayName != null) appendLine("display-name: $displayName")
    }.trim()

    private fun signal(displayName: String, modelId: String, logicId: String): String = """
        display-name: $displayName
        model: $modelId
        logic: $logicId
    """.trimIndent()

    private fun packageName(yaml: String): String = Regex("(?m)^name: ([a-z0-9._-]+)$")
        .find(yaml.trimIndent())?.groupValues?.get(1) ?: "missing"

    private data class Logic(val distance: Double, val source: String = VALID_LOGIC)

    private companion object {
        const val VALID_LOGIC = "function evaluate(input, output) { output.setSpeedLimit(0); output.setModelVariant('stop'); }"
    }
}
