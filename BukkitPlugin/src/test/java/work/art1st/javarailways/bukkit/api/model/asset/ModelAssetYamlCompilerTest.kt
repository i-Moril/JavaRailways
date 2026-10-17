package work.art1st.javarailways.bukkit.api.model.asset

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.ModelPart

class ModelAssetYamlCompilerTest {
    @Test
    fun `shared-part mode compiles the legacy ordinary model language into immutable public values`() {
        val compiled = ModelAssetYamlCompiler.compile(
            ModelAssetYamlCompilation(
                "demo",
                mapOf("home" to source("models/home.yml", model("head"))),
                mapOf("head" to source("parts/head.yml", HEAD)),
                ModelAssetImportMode.SHARED_PARTS
            )
        ).getValue("home")

        val item = compiled.definition().parts().single() as ModelPart.Item
        assertEquals(Material.PAPER, item.appearance().material())
        assertEquals(mapOf("lamp" to "dark"), compiled.defaultScenes())
        assertEquals(mapOf("lamp" to "red"), compiled.variants().getValue("stop"))
        assertTrue(compiled.placement().followReferenceTilt())
        assertEquals(2.0, compiled.placement().presets().getValue("left").localPose().z())
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (compiled.variants() as MutableMap<String, Map<String, String>>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (compiled.definition().parts() as MutableList<ModelPart>).clear()
        }
    }

    @Test
    fun `model mode imports only the target parts tree and reuses the immutable subtree`() {
        val base = """
            parts:
              - type: dummy
                parts:
                  - type: item_display
                    item: {type: PAPER}
                    animations:
                      - name: lamp
                        scenes:
                          dark: {}
                          red: {item: {type: STICK}}
            default: {lamp: dark}
            variants:
              stop: {lamp: red}
            rail-placement:
              follow-rail-tilt: true
              presets:
                left: {offset: {z: 2}}
            selection-boxes:
              - min: [-1, 0, -0.5]
                max: [1, 2, 0.5]
        """.trimIndent()
        val home = """
            parts:
              - import: base
              - import: base
            default: {lamp: dark}
            variants:
              home-stop: {lamp: red}
            rail-placement:
              presets:
                home: {offset: {z: 4}}
        """.trimIndent()
        val compiled = compileModels(mapOf("base" to base, "home" to home))
        val materializedBase = compiled.getValue("base")
        val materializedHome = compiled.getValue("home")

        val first = materializedHome.definition().parts()[0] as ModelPart.Group
        val second = materializedHome.definition().parts()[1] as ModelPart.Group
        assertSame(first, second)
        assertSame(materializedBase.definition().parts().single(), first.children().single())
        assertEquals(setOf("home-stop"), materializedHome.variants().keys)
        assertEquals(setOf("home"), materializedHome.placement().presets().keys)
        assertEquals(setOf("stop"), materializedBase.variants().keys)
        assertEquals(setOf("left"), materializedBase.placement().presets().keys)
        assertEquals(-1.0, materializedBase.definition().selectionBoxes().single().minX())
        assertTrue(materializedHome.definition().selectionBoxes().isEmpty(), "imports only inherit parts, not root selection volumes")
    }

    @Test
    fun `selection boxes require explicit finite three-coordinate ordered bounds`() {
        for (box in listOf(
            "{min: [0, 0], max: [1, 1, 1]}",
            "{min: [0, 0, 0], max: [0, 1, 1]}",
            "{min: [0, 0, .inf], max: [1, 1, 1]}",
            "{min: [0, 0, 0], max: [1, 1, 1], guess: true}"
        )) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                compileModels(mapOf("bad" to "$INLINE_MODEL\nselection-boxes: [$box]"))
            }
            assertTrue(failure.message.orEmpty().contains("selection-boxes"), failure.message)
        }
    }

    @Test
    fun `model mode validates all models and rejects cycles and invalid selections`() {
        val cycle = assertThrows(IllegalArgumentException::class.java) {
            compileModels(
                mapOf(
                    "a" to model("b", emptySelection = true),
                    "b" to model("a", emptySelection = true)
                )
            )
        }
        assertTrue(cycle.message.orEmpty().contains("cyclic part reference: a -> b -> a"), cycle.message)

        val invalid = assertThrows(IllegalArgumentException::class.java) {
            compileModels(mapOf("broken" to model("missing")))
        }
        assertTrue(invalid.message.orEmpty().contains("missing model 'missing'"), invalid.message)

        val badVariant = assertThrows(IllegalArgumentException::class.java) {
            compileModels(mapOf("broken" to INLINE_MODEL.replace("lamp: red", "lamp: absent")))
        }
        assertTrue(badVariant.message.orEmpty().contains("unknown scene 'absent'"), badVariant.message)
    }

    private fun compileModels(models: Map<String, String>): Map<String, CompiledModelAsset> =
        ModelAssetYamlCompiler.compile(
            ModelAssetYamlCompilation(
                "demo",
                models.mapValues { (id, yaml) -> source("model/$id.yml", yaml) },
                emptyMap(),
                ModelAssetImportMode.MODELS
            )
        )

    private fun source(name: String, yaml: String) = ModelAssetYamlSource(name, yaml)

    private fun model(import: String, emptySelection: Boolean = false): String = """
        parts:
          - import: $import
        default: ${if (emptySelection) "{}" else "{lamp: dark}"}
        variants:
          stop: ${if (emptySelection) "{}" else "{lamp: red}"}
        rail-placement:
          follow-rail-tilt: true
          presets:
            left: {offset: {z: 2}}
    """.trimIndent()

    private companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapPaperItems() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        val HEAD = """
            type: item_display
            item: {type: PAPER}
            animations:
              - name: lamp
                scenes:
                  dark: {}
                  red: {item: {type: STICK}}
        """.trimIndent()

        val INLINE_MODEL = """
            parts:
              - type: item_display
                item: {type: PAPER}
                animations:
                  - name: lamp
                    scenes:
                      dark: {}
                      red: {item: {type: STICK}}
            default: {lamp: dark}
            variants:
              stop: {lamp: red}
        """.trimIndent()
    }
}
