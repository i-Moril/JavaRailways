package work.art1st.javarailways.simplesignals.presentation

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.StringReader

/** Only ordinary YAML values reach the package parser. No Bukkit configuration or object loader. */
internal object ModelPackageYaml {
    fun read(text: String, source: ModelYamlSource): ModelYamlValue {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            // Composition creates nodes only. Our tag allowlist rejects at the exact field path
            // before SafeConstructor can construct any value from the same checked text.
            tagInspector = org.yaml.snakeyaml.inspector.TagInspector { true }
        }
        val yaml = Yaml(SafeConstructor(options))
        try {
            // Compose resolves aliases to shared nodes, so reject them before composing the tree.
            for (event in yaml.parse(StringReader(text))) {
                if (event is AliasEvent) source.fail("$", "YAML aliases are not allowed (line ${event.startMark.line + 1})")
            }
            val root = yaml.compose(StringReader(text)) ?: source.fail("$", "empty YAML document")
            validate(root, source, "$")
            // SafeConstructor now sees only checked standard tags, string keys and no aliases/merges.
            return ModelYamlValue(yaml.load<Any>(text), source, "$")
        } catch (error: ModelPackageException) {
            throw error
        } catch (error: RuntimeException) {
            source.fail("$", "invalid YAML: ${error.message}", error)
        }
    }

    private fun validate(node: Node, source: ModelYamlSource, path: String) {
        when (node) {
            is MappingNode -> {
                if (node.tag != Tag.MAP) source.fail(path, "YAML object tags are not allowed: ${node.tag}")
                val keys = HashSet<String>()
                for (entry in node.value) {
                    val key = entry.keyNode as? ScalarNode ?: source.fail(path, "mapping keys must be strings")
                    if (key.tag != Tag.STR || key.value == "<<") source.fail(path, "mapping keys must be strings; merge keys are not allowed")
                    val childPath = if (path == "$") key.value else "$path.${key.value}"
                    if (!keys.add(key.value)) source.fail(childPath, "duplicate YAML key '${key.value}'")
                    validate(entry.valueNode, source, childPath)
                }
            }
            is SequenceNode -> {
                if (node.tag != Tag.SEQ) source.fail(path, "YAML object tags are not allowed: ${node.tag}")
                node.value.forEachIndexed { index, child -> validate(child, source, "$path[$index]") }
            }
            is ScalarNode -> {
                if (node.tag == Tag.NULL) source.fail(path, "explicit null is not allowed; omit optional fields")
                if (node.tag !in setOf(Tag.STR, Tag.INT, Tag.FLOAT, Tag.BOOL)) {
                    source.fail(path, "YAML object/value tag is not allowed: ${node.tag}")
                }
            }
            else -> source.fail(path, "unsupported YAML value")
        }
    }
}

internal class ModelPackageException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

internal data class ModelYamlSource(val packageName: String, val file: String) {
    fun fail(field: String, reason: String, cause: Throwable? = null): Nothing =
        throw ModelPackageException("$packageName/$file: $field — $reason", cause)
}

/** Per-field location follows values through reference expansion and public-value validation. */
internal class ModelYamlValue(private val value: Any, val source: ModelYamlSource, val path: String) {
    fun fail(reason: String): Nothing = source.fail(path, reason)

    fun mapping(allowed: Set<String>? = null): Map<String, ModelYamlValue> {
        val map = value as? Map<*, *> ?: fail("expected a mapping")
        return map.entries.associate { (key, child) ->
            val name = key as? String ?: fail("mapping keys must be strings")
            val childPath = if (path == "$") name else "$path.$name"
            if (allowed != null && name !in allowed) source.fail(childPath, "unknown field '$name'")
            name to ModelYamlValue(child ?: source.fail(childPath, "explicit null is not allowed"), source, childPath)
        }
    }

    fun list(): List<ModelYamlValue> {
        val list = value as? List<*> ?: fail("expected a list")
        return list.mapIndexed { index, child ->
            ModelYamlValue(child ?: source.fail("$path[$index]", "explicit null is not allowed"), source, "$path[$index]")
        }
    }

    fun string(): String = (value as? String ?: fail("expected a string")).also {
        if (it.isBlank()) fail("name/value must not be blank")
    }

    fun boolean(): Boolean = value as? Boolean ?: fail("expected a boolean")

    fun number(): Double {
        val number = value as? Number ?: fail("expected a number")
        return number.toDouble().also { if (!it.isFinite()) fail("number must be finite") }
    }

    fun integer(): Int {
        if (value !is Byte && value !is Short && value !is Int && value !is Long && value !is java.math.BigInteger) {
            fail("expected an integer")
        }
        return value.toString().toIntOrNull() ?: fail("integer is outside the 32-bit range")
    }

    fun required(map: Map<String, ModelYamlValue>, key: String): ModelYamlValue = map[key]
        ?: source.fail(if (path == "$") key else "$path.$key", "missing required field")

    fun <T> publicValue(create: () -> T): T = try {
        create()
    } catch (error: ModelPackageException) {
        throw error
    } catch (error: IllegalArgumentException) {
        source.fail(path, error.message ?: "invalid public model value", error)
    }
}
