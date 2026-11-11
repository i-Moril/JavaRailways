package work.art1st.javarailways.simplesignals.presentation

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.bukkit.Material
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.bukkit.api.model.ItemAppearance
import work.art1st.javarailways.bukkit.api.model.ModelPart
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.ModelScale
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.sqrt

class ModelPackageLoaderTest {
    @TempDir lateinit var temporary: Path
    private lateinit var directory: Path

    @BeforeEach
    fun fixture() {
        directory = temporary.resolve("package")
        val fixture = Path.of(requireNotNull(javaClass.getResource("/model-packages/demo_signals")).toURI())
        Files.walk(fixture).use { paths ->
            paths.forEach { source ->
                val destination = directory.resolve(fixture.relativize(source))
                if (Files.isDirectory(source)) Files.createDirectories(destination) else Files.copy(source, destination)
            }
        }
    }

    @Test
    fun `complete fixed five file package produces both real immutable host models`() {
        val loaded = load()
        assertEquals("demo_signals", loaded.name)
        assertEquals("示例信号模型", loaded.displayName)
        assertEquals(setOf("demo_signals:home", "demo_signals:wall"), loaded.models.keys)
        val home = loaded.models.getValue("demo_signals:home")
        val wall = loaded.models.getValue("demo_signals:wall")
        assertEquals(2, home.definition.parts().size)
        val mast = home.definition.parts()[0] as ModelPart.Item
        val head = home.definition.parts()[1] as ModelPart.Item
        assertEquals(Material.PAPER, mast.appearance().material())
        assertEquals("demo_signals:signal/mast", mast.appearance().itemModelKey())
        assertEquals(3.2, head.pose().y())
        assertEquals(ModelScale.UNIT, head.scale())
        assertEquals(mapOf("lamp" to "dark"), home.defaultScenes)
        assertEquals(mapOf("lamp" to "green"), home.variant("proceed"))
        assertEquals(home.variants, wall.variants)
        assertEquals(0.0, wall.definition.parts().single().pose().y())
        assertEquals(listOf(0.0, 1.0, 2.0), head.animations().single().frames().map { it.atSeconds() })
        val green = head.animations().single().frames().single { it.scene() == "green" }
        assertEquals("demo_signals:signal/head_green", green.appearance().itemModelKey())
        assertEquals(ItemAppearance.Brightness(15, 15), green.appearance().brightness())
        assertEquals(2.0, home.railPlacementPresets.getValue("left").adjustment.x())
        assertEquals(1.0, home.railPlacementPresets.getValue("left").adjustment.rotationY(), 1e-12)
        val catalog = ModelTypeCatalogSnapshot.from(loaded.models)
        val variants = checkNotNull(catalog.variantNames(home.id))
        assertEquals(listOf("proceed", "stop"), variants)
        assertThrows(UnsupportedOperationException::class.java) { (variants as MutableList<String>).clear() }
        assertNull(catalog.variantNames("demo_signals:missing"))
    }

    @Test
    fun `import adds author components before XYZ conversion and never adds a wrapper`() {
        write("parts/mast.yml", """
            type: item_display
            offset: {x: 1, y: 2, z: 3}
            rotation: {x: 30, y: 20, z: -40}
            item: {type: PAPER}
        """)
        write("models/home.yml", """
            parts:
              - import: mast
                offset: {x: 4, y: 5, z: 6}
                rotation: {x: 60, y: 70, z: 130}
              - import: mast
            default: {}
        """)
        val parts = home().definition.parts()
        val merged = parts[0] as ModelPart.Item
        assertEquals(listOf(5.0, 7.0, 9.0), listOf(merged.pose().x(), merged.pose().y(), merged.pose().z()))
        // Independent qX(90) * qY(90) * qZ(90), not multiplication of two author quaternions.
        assertQuaternion(merged.pose(), sqrt(0.5), 0.0, sqrt(0.5), 0.0)
        val original = parts[1] as ModelPart.Item
        assertEquals(listOf(1.0, 2.0, 3.0), listOf(original.pose().x(), original.pose().y(), original.pose().z()))
        assertNotEquals(merged.pose(), original.pose())
    }

    @Test
    fun `inline transforms apply once and explicit dummy is the real parent`() {
        write("models/home.yml", """
            parts:
              - type: dummy
                offset: {y: 4}
                rotation: {z: 90}
                parts:
                  - type: item_display
                    offset: {x: 2}
                    rotation: {x: 90}
                    item: {type: PAPER}
            default: {}
        """)
        val group = home().definition.parts().single() as ModelPart.Group
        val child = group.children().single() as ModelPart.Item
        assertEquals(4.0, group.pose().y())
        assertQuaternion(group.pose(), 0.0, 0.0, sqrt(0.5), sqrt(0.5))
        assertEquals(2.0, child.pose().x())
        assertQuaternion(child.pose(), sqrt(0.5), 0.0, 0.0, sqrt(0.5))
        val worldOffset = quaternion(group.pose()).transform(Vector3d(child.pose().x(), child.pose().y(), child.pose().z()))
            .add(group.pose().x(), group.pose().y(), group.pose().z())
        assertEquals(0.0, worldOffset.x, 1e-12)
        assertEquals(6.0, worldOffset.y, 1e-12)
        assertEquals(0.0, worldOffset.z, 1e-12)
        val rotation = quaternion(group.pose()).mul(quaternion(child.pose()))
        assertEquals(0.5, rotation.x, 1e-12)
        assertEquals(0.5, rotation.y, 1e-12)
        assertEquals(0.5, rotation.z, 1e-12)
        assertEquals(0.5, rotation.w, 1e-12)
    }

    @Test
    fun `nested shared references and repeated imports reuse safely at separate occurrences`() {
        write("parts/shared/bracket.yml", """
            type: dummy
            offset: {x: 1}
            rotation: {y: 90}
            parts:
              - import: head
              - import: head
                offset: {z: 1}
        """)
        write("models/home.yml", """
            parts:
              - import: shared/bracket
              - import: shared/bracket
              - import: shared/bracket
                offset: {z: 2}
                rotation: {y: 90}
              - type: item_display
                item: {type: PAPER}
            default: {lamp: dark}
            variants: {proceed: {lamp: green}}
        """)
        val home = home()
        assertSame(home.definition.parts()[0], home.definition.parts()[1])
        val group = home.definition.parts()[0] as ModelPart.Group
        assertEquals(2, group.children().size)
        assertEquals(1.0, group.children()[1].pose().z())
        val adjusted = home.definition.parts()[2] as ModelPart.Group
        assertEquals(group.children(), adjusted.children())
        assertEquals(1.0, adjusted.pose().x())
        assertEquals(2.0, adjusted.pose().z())
        assertQuaternion(adjusted.pose(), 0.0, 1.0, 0.0, 0.0)
        assertEquals(mapOf("lamp" to "green"), home.variant("proceed"))
    }

    @Test
    fun `scenes are named static targets and their order does not change appearance`() {
        val before = home().definition.parts()[1] as ModelPart.Item
        write("parts/head.yml", """
            type: item_display
            item: {type: PAPER, item-model: 'demo_signals:signal/head_dark'}
            animations:
              - name: lamp
                scenes:
                  green:
                    item: {type: PAPER, item-model: 'demo_signals:signal/head_green', brightness: {block: 15, sky: 15}}
                  dark: {}
                  red:
                    item: {type: PAPER, item-model: 'demo_signals:signal/head_red', brightness: {block: 15, sky: 15}}
        """)
        val after = home().definition.parts()[1] as ModelPart.Item
        fun targets(item: ModelPart.Item) = item.animations().single().frames().associate { it.scene() to it.appearance() }
        assertEquals(targets(before), targets(after))
        assertEquals(0.0, after.animations().single().frames().first().atSeconds())
        assertEquals("green", after.animations().single().frames().first().scene())
    }

    @Test
    fun `frame item replaces all appearance fields and missing item restores baseline`() {
        write("parts/head.yml", """
            type: item_display
            item:
              type: PAPER
              item-model: demo_signals:base
              custom-model-data: 5
              brightness: {block: 3, sky: 4}
              scale: {x: 2, y: 3, z: 4}
            animations:
              - name: lamp
                scenes:
                  dark: {}
                  red:
                    scale: {x: 0}
                    offset: {z: 1}
                    rotation: {z: 90}
                    item: {type: STICK}
                  green:
                    item: {type: AIR}
        """)
        val item = home().definition.parts()[1] as ModelPart.Item
        val frames = item.animations().single().frames().associateBy { it.scene() }
        val red = frames.getValue("red")
        assertEquals(ItemAppearance(Material.STICK, null, null, null), red.appearance())
        assertEquals(ModelScale(0.0, 1.0, 1.0), red.scale())
        assertEquals(1.0, red.pose().z())
        assertNull(frames.getValue("dark").appearance())
        assertEquals(ModelPose.IDENTITY, frames.getValue("dark").pose())
        assertEquals(ModelScale.UNIT, frames.getValue("dark").scale())
        assertEquals(ModelScale(2.0, 3.0, 4.0), item.scale())
        assertEquals(5, item.appearance().customModelData())
        assertEquals(ItemAppearance.Brightness(3, 4), item.appearance().brightness())
        assertEquals(Material.AIR, frames.getValue("green").appearance().material())
    }

    @Test
    fun `explicit empty default and variant are complete selections and lookup needs no files`() {
        write("models/home.yml", """
            parts: [{import: head}]
            default: {}
            variants:
              lit: {lamp: green}
              reset: {}
        """)
        val home = home()
        assertTrue(home.defaultScenes.isEmpty())
        assertTrue(home.variant("reset").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { home.variant("unknown") }
        Files.move(directory, temporary.resolve("moved-package"))
        assertEquals(mapOf("lamp" to "green"), home.variant("lit"))
        assertTrue(home.variant("reset").isEmpty())
    }

    @Test
    fun `model tilt resolves into presets and player choice wins with independent instances`() {
        write("models/home.yml", """
            parts: [{import: head}]
            default: {}
            rail-placement:
              follow-rail-tilt: true
              presets:
                inherited: {offset: {z: 2}}
                upright: {follow-rail-tilt: false}
        """)
        val home = home()
        val inherited = home.railPlacementPresets.getValue("inherited")
        val upright = home.railPlacementPresets.getValue("upright")
        assertTrue(home.followRailTilt)
        assertEquals(true, inherited.followRailTilt)
        assertEquals(false, upright.followRailTilt)
        assertFalse(load().models.getValue("demo_signals:wall").followRailTilt)
        val original = ModelPlacement.RailAttached(RailPositionSnapshot(UUID.randomUUID(), ViewDirection.BA, 5.0))
        val first = original.applyPreset(inherited)
        val second = original.applyPreset(inherited, false)
        assertTrue(first.followRailTilt)
        assertFalse(second.followRailTilt)
        assertTrue(original.applyPreset(upright, true).followRailTilt)
        val edited = first.copy(adjustment = ModelPose.IDENTITY)
        assertEquals(2.0, second.adjustment.z())
        assertEquals(0.0, edited.adjustment.z())
        assertSame(first.adjustment, second.adjustment)
    }

    @Test
    fun `all result maps and host collections reject mutation including map entries`() {
        val loaded = load()
        val home = loaded.models.getValue("demo_signals:home")
        val maps = listOf(loaded.models, home.defaultScenes, home.variants, home.variant("proceed"), home.railPlacementPresets)
        for (map in maps) {
            assertThrows(UnsupportedOperationException::class.java) { (map as MutableMap).clear() }
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (home.defaultScenes.entries.single() as MutableMap.MutableEntry).setValue("red")
        }
        assertThrows(UnsupportedOperationException::class.java) { (home.definition.parts() as MutableList).clear() }
        val item = home.definition.parts()[1] as ModelPart.Item
        assertThrows(UnsupportedOperationException::class.java) { (item.animations().single().frames() as MutableList).clear() }
    }

    @Test
    fun `loaded results copy caller owned maps including nested variant selections`() {
        val original = home()
        val defaults = linkedMapOf("lamp" to "dark")
        val selection = linkedMapOf("lamp" to "green")
        val variants = linkedMapOf<String, Map<String, String>>("proceed" to selection)
        val preset = original.railPlacementPresets.getValue("left")
        val presets = linkedMapOf("left" to preset)
        val model = LoadedPackageModel(original.id, original.definition, defaults, variants, false, presets)
        val models = linkedMapOf(model.id to model)
        val loaded = LoadedModelPackage("demo_signals", null, models)

        defaults.clear()
        selection["lamp"] = "red"
        variants.clear()
        presets.clear()
        models.clear()

        assertSame(model, loaded.models.getValue(model.id))
        assertSame(original.definition, model.definition)
        assertEquals(mapOf("lamp" to "dark"), model.defaultScenes)
        assertEquals(mapOf("lamp" to "green"), model.variant("proceed"))
        assertEquals(mapOf("left" to preset), model.railPlacementPresets)
    }

    @Test
    fun `manifest model membership and required field validation are strict`() {
        for ((yaml, field) in listOf(
            "name: demo_signals\nmodels: [home]" to "config-version",
            "config-version: 2\nname: demo_signals\nmodels: [home]" to "config-version",
            "config-version: 1.0\nname: demo_signals\nmodels: [home]" to "config-version",
            "config-version: 1\nname: Demo\nmodels: [home]" to "name",
            "config-version: 1\nname: demo_signals\nmodels: []" to "models",
            "config-version: 1\nname: demo_signals\nmodels: [home, home]" to "models[1]",
            "config-version: 1\nname: demo_signals\nmodels: [absent]" to "$"
        )) {
            write("manifest.yml", yaml)
            failure("manifest.yml", field, expectedFile = if ("absent" in yaml) "models/absent.yml" else "manifest.yml")
        }
    }

    @Test
    fun `default is required and known selections validate every variant`() {
        for ((body, field) in listOf(
            "parts: [{import: head}]" to "default",
            "parts: [{import: head}]\ndefault: null" to "default",
            "parts: [{import: head}]\ndefault: {other: dark}" to "default.other",
            "parts: [{import: head}]\ndefault: {lamp: Dark}" to "default.lamp",
            "parts: [{import: head}]\ndefault: {}\nvariants: {bad: {lamp: grean}}" to "variants.bad.lamp",
            "parts: []\ndefault: {}" to "parts"
        )) {
            write("models/home.yml", body)
            failure("models/home.yml", field)
        }
    }

    @Test
    fun `same part cannot select two animations in either default or a variant`() {
        write("parts/head.yml", """
            type: item_display
            item: {type: PAPER}
            animations:
              - name: lamp
                scenes: {dark: {}, red: {}, green: {}}
              - name: alternate
                scenes: {dark: {}}
        """)
        for (selection in listOf("default: {lamp: dark, alternate: dark}", "default: {}\nvariants: {bad: {lamp: dark, alternate: dark}}")) {
            write("models/home.yml", "parts: [{import: head}]\n$selection")
            val error = failure("models/home.yml", if (selection.startsWith("default: {}")) "variants.bad.alternate" else "default.alternate")
            assertTrue(error.message!!.contains("selects both"))
        }
    }

    @Test
    fun `broadcast requires matching scene sets even when an invalid animation is not selected`() {
        write("parts/other.yml", """
            type: item_display
            item: {type: PAPER}
            animations: [{name: lamp, scenes: {dark: {}, red: {}}}]
        """)
        write("models/home.yml", "parts: [{import: head}, {import: other}]\ndefault: {}")
        assertTrue(failure("models/home.yml", "parts").message!!.contains("same exact scene names"))
    }

    @Test
    fun `cycles fail on the active reference path but ordinary repeated use is valid`() {
        write("parts/a.yml", "type: dummy\nparts: [{import: b}]")
        write("parts/b.yml", "type: dummy\nparts: [{import: a}]")
        val error = assertThrows(ModelPackageException::class.java) { load() }
        assertTrue(error.message!!.contains("parts[0].import"))
        assertTrue(error.message!!.contains("a -> b -> a") || error.message!!.contains("b -> a -> b"))
        write("parts/b.yml", "type: dummy\nparts: [{import: head}, {import: head}]")
        assertEquals(2, load().models.size)
    }

    @Test
    fun `all shared definitions validate even if no model uses them`() {
        for (definition in listOf(
            "type: dummy\nparts: [{import: missing}]",
            "type: dummy\nparts: [{import: unused}]",
            "type: item_display\nitem: {type: WATER}",
            "type: item_display\nitem: {type: PAPER}\nunknown: true",
            "import: head"
        )) {
            write("parts/unused.yml", definition)
            failure("parts/unused.yml", if (definition.startsWith("import:")) "import" else "$")
        }
    }

    @Test
    fun `unused shared ancestors validate mismatched scene sets in nested inline descendants`() {
        write("parts/other.yml", """
            type: item_display
            item: {type: PAPER}
            animations: [{name: lamp, scenes: {dark: {}}}]
        """)
        write("parts/unused.yml", """
            type: dummy
            parts:
              - type: dummy
                parts: [{import: head}, {import: other}]
        """)
        write("parts/unused_ancestor.yml", "type: dummy\nparts: [{import: unused}]")

        val error = failure("parts/unused_ancestor.yml", "$")
        assertTrue(error.message!!.contains("same exact scene names"), error.message)
    }

    @Test
    fun `independent shared roots allow distinct scene sets and repeated subtree reuse`() {
        write("parts/other.yml", """
            type: item_display
            item: {type: PAPER}
            animations: [{name: lamp, scenes: {idle: {}}}]
        """)
        write("parts/shared/group.yml", "type: dummy\nparts: [{import: other}, {import: other}]")
        write("models/home.yml", "parts: [{import: shared/group}, {import: shared/group}]\ndefault: {lamp: idle}")

        val loaded = load()
        val home = loaded.models.getValue("demo_signals:home")
        val group = home.definition.parts()[0] as ModelPart.Group
        assertSame(group, home.definition.parts()[1])
        assertSame(group.children()[0], group.children()[1])
        assertEquals(mapOf("lamp" to "idle"), home.defaultScenes)
        assertEquals(mapOf("lamp" to "dark"), loaded.models.getValue("demo_signals:wall").defaultScenes)
    }

    @Test
    fun `names reject escaped malformed and cross package references`() {
        for (bad in listOf("../head", "a/../head", "./head", "/head", "a//head", "head/", "a\\head", "other:head", "Head")) {
            write("models/home.yml", "parts: [{import: '$bad'}]\ndefault: {}")
            failure("models/home.yml", "parts[0].import")
        }
        write("models/home.yml", "parts: [{import: head}]\ndefault: {}")
        write("manifest.yml", "config-version: 1\nname: demo_signals\nmodels: ['../home']")
        failure("manifest.yml", "models[0]")
    }

    @Test
    fun `shared filenames preserve and reject literal backslashes and invalid segments`() {
        for (file in listOf("parts/bad\\name.yml", "parts/Upper.yml", "parts/.yml")) {
            write(file, "type: item_display\nitem: {type: PAPER}")
            assertTrue(failure(file, "$").message!!.contains("invalid local part path"))
            Files.delete(directory.resolve(file))
        }
    }

    @Test
    fun `symlinks may resolve inside root and cannot read files or directories outside it`() {
        val external = temporary.resolve("external.yml")
        Files.writeString(external, "type: item_display\nitem: {type: PAPER}")
        Files.createSymbolicLink(directory.resolve("parts/escape.yml"), external)
        assertTrue(failure("parts/escape.yml", "$").message!!.contains("escapes"))
        Files.delete(directory.resolve("parts/escape.yml"))
        Files.createSymbolicLink(directory.resolve("parts/inside.yml"), directory.resolve("parts/head.yml"))
        assertEquals(2, load().models.size)
        Files.createSymbolicLink(directory.resolve("parts/outside"), temporary)
        assertTrue(failure("parts/outside", "$").message!!.contains("escapes"))
    }

    @Test
    fun `model and manifest symlinks cannot escape the root`() {
        for (file in listOf("models/home.yml", "manifest.yml")) {
            val target = directory.resolve(file)
            val outside = temporary.resolve("${target.fileName}.outside")
            Files.move(target, outside)
            Files.createSymbolicLink(target, outside)
            assertTrue(failure(file, "$").message!!.contains("escapes"))
            Files.delete(target)
            Files.move(outside, target)
        }
    }

    @Test
    fun `directory symlink loops fail before recursive walking`() {
        Files.createSymbolicLink(directory.resolve("parts/loop"), directory.resolve("parts"))
        assertTrue(failure("parts/loop", "$").message!!.contains("cyclic directory"))
    }

    @Test
    fun `ordinary YAML rejects duplicates null aliases merge and object tags without silent fallback`() {
        for ((yaml, field) in listOf(
            "parts: [{import: head}]\ndefault: {}\ndefault: {}" to "default",
            "parts: [{import: head}]\ndefault: {lamp: red, lamp: green}" to "default.lamp",
            "parts: [{import: head}]\ndefault: {}\nvariants: null" to "variants",
            "parts: &parts [{import: head}]\ndefault: {}\nvariants: {bad: *parts}" to "$",
            "parts: [{import: head}]\ndefault: {}\n<<: {variants: {}}" to "$",
            "!!java.util.HashMap {parts: [], default: {}}" to "$",
            "parts: [{import: head}]\ndefault: {}\nvariants: {bad: {lamp: !!java.lang.String green}}" to "variants.bad.lamp",
            "parts: [{import: head}]\ndefault: {}\nextra: true" to "extra"
        )) {
            write("models/home.yml", yaml)
            failure("models/home.yml", field)
        }
    }

    @Test
    fun `number appearance and transform validation uses public value invariants`() {
        for (item in listOf(
            "{type: NO_MATERIAL}", "{type: WATER}", "{type: LEGACY_STONE}",
            "{type: PAPER, item-model: lamp}", "{type: PAPER, item-model: 'bad:UPPER'}",
            "{type: PAPER, custom-model-data: -1}", "{type: PAPER, custom-model-data: 16777217}",
            "{type: PAPER, custom-model-data: 2147483648}", "{type: PAPER, custom-model-data: 1.0}",
            "{type: PAPER, brightness: {block: 15}}", "{type: PAPER, brightness: {block: 16, sky: 0}}",
            "{type: PAPER, brightness: {block: 1.0, sky: 0}}",
            "{type: PAPER, scale: {x: -1}}", "{type: PAPER, scale: {x: .nan}}",
            "{type: PAPER, scale: {y: .inf}}", "{type: PAPER, unknown: 1}"
        )) {
            write("parts/mast.yml", "type: item_display\nitem: $item")
            failure("parts/mast.yml", "item")
        }
        write("parts/mast.yml", "type: item_display\nitem: {type: PAPER, custom-model-data: 16777218, scale: {x: 0}}")
        assertEquals(16777218, (home().definition.parts()[0] as ModelPart.Item).appearance().customModelData())
        for (transform in listOf("offset: {x: .nan}", "rotation: {z: .inf}", "offset: {x: bad}", "rotation: {yaw: 90}")) {
            write("parts/mast.yml", "type: item_display\nitem: {type: PAPER}\n$transform")
            failure("parts/mast.yml", if (transform.startsWith("offset")) "offset" else "rotation")
        }
    }

    @Test
    fun `part and static scene schemas reject train fields time fields and frame item scale`() {
        for (definition in listOf(
            "type: seat", "type: dummy\nitem: {type: PAPER}",
            "type: item_display\nparts: []\nitem: {type: PAPER}",
            "type: item_display", "type: item_display\nitem: {type: PAPER}\nname: old_name",
            "type: item_display\nitem: {type: PAPER}\nanimations: [{name: lamp, scenes: {}}]",
            "type: item_display\nitem: {type: PAPER}\nanimations: [{name: lamp, scenes: {dark: {at: 0}}}]",
            "type: item_display\nitem: {type: PAPER}\nanimations: [{name: lamp, scenes: {dark: {item: {type: PAPER, scale: {x: 1}}}}}]",
            "type: item_display\nitem: {type: PAPER}\nanimations: [{name: lamp, scenes: {dark: {item: {item-model: 'demo_signals:lamp'}}}}]",
            "type: item_display\nitem: {type: PAPER}\nanimations: [{name: lamp, scenes: {dark: {}}}, {name: lamp, scenes: {dark: {}}}]",
            "type: dummy\nanimations: [{name: lamp, scenes: {dark: {scale: {x: 1}}}}]",
            "type: dummy\nanimations: [{name: lamp, scenes: {dark: {item: {type: PAPER}}}}]"
        )) {
            write("parts/mast.yml", definition)
            failure("parts/mast.yml", "$")
        }
        write("parts/mast.yml", "type: item_display\nitem: {type: PAPER}")
        write("models/home.yml", "parts: [{import: head, type: dummy}]\ndefault: {}")
        failure("models/home.yml", "parts[0].type")
    }

    @Test
    fun `preset fields and names and scalar types remain strict`() {
        for (placement in listOf(
            "{follow-rail-tilt: 1}", "{presets: {left: {follow-rail-tilt: null}}}",
            "{presets: {left: {rotation: {y: .nan}}}}", "{presets: {left: {offset: {z: '2'}}}}",
            "{presets: {left: {unknown: true}}}", "{presets: {' ': {}}}", "{unknown: true}"
        )) {
            write("models/home.yml", "parts: [{import: head}]\ndefault: {}\nrail-placement: $placement")
            failure("models/home.yml", "rail-placement")
        }
        write("models/home.yml", "parts: [{import: head}]\ndefault: {}\nrail-placement: {presets: {left: {}}}")
        assertEquals(false, home().railPlacementPresets.getValue("left").followRailTilt)
    }

    private fun load() = ModelPackageLoader.load(directory)
    private fun home() = load().models.getValue("demo_signals:home")

    private fun write(file: String, text: String) {
        val path = directory.resolve(file)
        Files.createDirectories(path.parent)
        Files.writeString(path, text.trimIndent() + "\n")
    }

    private fun failure(file: String, field: String, expectedFile: String = file): ModelPackageException {
        val error = assertThrows(ModelPackageException::class.java) { load() }
        assertTrue(error.message!!.contains("/$expectedFile:"), error.message)
        if (field != "$") assertTrue(error.message!!.contains(field), error.message)
        return error
    }

    private fun assertQuaternion(pose: ModelPose, x: Double, y: Double, z: Double, w: Double) {
        assertEquals(x, pose.rotationX(), 1e-12)
        assertEquals(y, pose.rotationY(), 1e-12)
        assertEquals(z, pose.rotationZ(), 1e-12)
        assertEquals(w, pose.rotationW(), 1e-12)
    }

    private fun quaternion(pose: ModelPose) = Quaterniond(pose.rotationX(), pose.rotationY(), pose.rotationZ(), pose.rotationW())

    companion object {
        @JvmStatic @BeforeAll
        fun bootstrapPaperItems() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
