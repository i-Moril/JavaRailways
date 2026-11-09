package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class PackageLoaderDisplaysTest {
    @TempDir lateinit var directory: File

    @Test fun `optional absent and explicit empty displays load without a provider`() {
        val pkg = packageDirectory()
        assertEquals(TrainDisplayResources(), load(pkg))
        pkg.resolve("displays.yml").writeText("config-version: 1\ntemplates: {}\n")
        assertEquals(TrainDisplayResources(), load(pkg))
        pkg.resolve("displays.yml").appendText("defaults: {}\n")
        assertEquals(TrainDisplayResources(), load(pkg))
    }

    @Test fun `raw UTF8 XML and embedded scripts are preserved with category reuse`() {
        val pkg = packageDirectory()
        val raw = "<screen width='256' height='128'>\r\n<script><![CDATA[const label=ctx.data.name; // 车站\n]]></script></screen>\r\n"
        pkg.resolve("displays").mkdir()
        pkg.resolve("displays/stops.xml").writeText(raw, Charsets.UTF_8)
        pkg.resolve("displays.yml").writeText("""
            config-version: 1
            templates:
              stops: {file: displays/stops.xml}
            defaults:
              cabin: stops
              custom-category: stops
        """.trimIndent())
        val displays = load(pkg)
        assertEquals(TrainDisplayTemplate("displays/stops.xml", raw), displays.templates.getValue("stops"))
        assertEquals(mapOf("cabin" to "stops", "custom-category" to "stops"), displays.defaults)
        assertThrows<UnsupportedOperationException> { (displays.templates as MutableMap).clear() }
        assertThrows<UnsupportedOperationException> { (displays.defaults as MutableMap).clear() }
        // Raw source is read at load, never lazily from a File.
        pkg.resolve("displays/stops.xml").writeText("not XML yet")
        assertEquals(raw, displays.templates.getValue("stops").source)
        assertEquals("not XML yet", load(pkg).templates.getValue("stops").source)
    }

    @Test fun `strict display YAML rejects bad versions types IDs references fields and duplicates`() {
        val pkg = packageDirectory()
        pkg.resolve("screen.xml").writeText("raw XML")
        val invalid = listOf(
            "", "[]", "templates: {}", "config-version: 2\ntemplates: {}", "config-version: '1'\ntemplates: {}",
            "config-version: 1", "config-version: 1\ntemplates: []", "config-version: 1\ntemplates: null",
            "config-version: 1\ntemplates: {}\nunknown: true", "config-version: 1\ntemplates: {}\ndefaults: []",
            "config-version: 1\ntemplates: {}\ndefaults: {cabin: missing}",
            "config-version: 1\ntemplates: {}\ndefaults: {cabin: 1}",
            "config-version: 1\ntemplates: {'': {file: screen.xml}}",
            "config-version: 1\ntemplates: {'bad id': {file: screen.xml}}",
            "config-version: 1\ntemplates: {good: {file: screen.xml}}\ndefaults: {'bad category': good}",
            "config-version: 1\ntemplates: {good: {file: screen.xml}}\ndefaults: {'': good}",
            "config-version: 1\ntemplates: {good: {file: screen.xml}}\ndefaults: {cabin: 'bad id'}",
            "config-version: 1\ntemplates: {good: {file: screen.xml, type: xml}}",
            "config-version: 1\ntemplates: {good: {file: screen.xml, width: 256}}",
            "config-version: 1\ntemplates: {good: {}}", "config-version: 1\ntemplates: {good: screen.xml}",
            "config-version: 1\ntemplates: {good: {file: 1}}", "config-version: 1\ntemplates: {good: {file: null}}",
            "config-version: 1\nconfig-version: 1\ntemplates: {}",
            "config-version: 1\ntemplates: {good: {file: screen.xml}, good: {file: screen.xml}}",
            "config-version: 1\ntemplates: {good: {file: screen.xml, file: screen.xml}}",
            "config-version: 1\ntemplates: {good: {file: screen.xml}}\ndefaults: {cabin: good, cabin: good}",
        )
        for (source in invalid) {
            pkg.resolve("displays.yml").writeText(source)
            val failure = assertThrows<IllegalArgumentException>(source) { load(pkg) }
            assertTrue(failure.message.orEmpty().contains("displays.yml"), failure.message)
        }
        // Other formats keep their prior duplicate-key policy; strictness is opt-in.
        val yaml = directory.resolve("other.yml").apply { writeText("value: first\nvalue: last\n") }
        assertEquals("last", loadYamlMap(yaml, "other")["value"])
    }

    @Test fun `template paths must be contained XML regular files including symlink resolution`() {
        val pkg = packageDirectory()
        pkg.resolve("screen.xml").writeText("XML")
        val outside = directory.resolve("outside.xml").apply { writeText("outside") }
        val invalidPaths = listOf("", "../outside.xml", "./screen.xml", "sub/../screen.xml", "sub//screen.xml", "/screen.xml", outside.absolutePath,
            "C:/screen.xml", "C:\\screen.xml", "sub\\screen.xml", "screen.js", "screen.XML", "screen.xml/", "missing.xml")
        for (path in invalidPaths) {
            writeFileReference(pkg, path)
            val failure = assertThrows<IllegalArgumentException>(path) { load(pkg) }
            assertTrue(failure.message.orEmpty().contains("displays.yml"))
        }
        pkg.resolve("directory.xml").mkdir()
        writeFileReference(pkg, "directory.xml"); assertThrows<IllegalArgumentException> { load(pkg) }
        Files.createSymbolicLink(pkg.resolve("escape.xml").toPath(), outside.toPath())
        writeFileReference(pkg, "escape.xml")
        assertTrue(assertThrows<IllegalArgumentException> { load(pkg) }.message.orEmpty().contains("outside package"))
        Files.createSymbolicLink(pkg.resolve("inside.xml").toPath(), pkg.resolve("screen.xml").toPath())
        writeFileReference(pkg, "inside.xml"); assertEquals("XML", load(pkg).templates.getValue("screen").source)
        pkg.resolve("head:wide.xml").writeText("colon filename")
        writeFileReference(pkg, "head:wide.xml"); assertEquals("colon filename", load(pkg).templates.getValue("screen").source)
        Files.createSymbolicLink(pkg.resolve("dangling.xml").toPath(), directory.resolve("absent.xml").toPath())
        writeFileReference(pkg, "dangling.xml"); assertThrows<IllegalArgumentException> { load(pkg) }
    }

    @Test fun `declared displays configuration cannot be a directory dangling or escaping symlink`() {
        val pkg = packageDirectory()
        val config = pkg.resolve("displays.yml")
        config.mkdir(); assertThrows<IllegalArgumentException> { load(pkg) }; config.delete()
        Files.createSymbolicLink(config.toPath(), directory.resolve("missing.yml").toPath())
        assertThrows<IllegalArgumentException> { load(pkg) }; Files.delete(config.toPath())
        val outside = directory.resolve("outside.yml").apply { writeText("config-version: 1\ntemplates: {}") }
        Files.createSymbolicLink(config.toPath(), outside.toPath())
        assertThrows<IllegalArgumentException> { load(pkg) }
    }

    @Test fun `malformed UTF8 identifies package template and declared path`() {
        val pkg = packageDirectory()
        pkg.resolve("invalid.xml").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        writeFileReference(pkg, "invalid.xml")
        val failure = assertThrows<IllegalArgumentException> { load(pkg) }
        assertTrue(failure.message.orEmpty().contains("templates.screen.file 'invalid.xml'"))
        assertTrue(failure.message.orEmpty().contains(pkg.path))
    }

    private fun writeFileReference(pkg: File, path: String) = pkg.resolve("displays.yml").writeText(
        "config-version: 1\ntemplates: {screen: {file: '${path.replace("'", "''")}'}}\n")
    private fun load(pkg: File) = requireNotNull(PackageLoader(pkg).loadTrainPackage()).displays
    private fun packageDirectory(): File = directory.resolve("package").apply {
        mkdirs()
        resolve("manifest.yml").writeText("config-version: 2\nname: test\ntype: train\ncomposition: []\n")
        resolve("control.yml").writeText("""
            config-version: 1
            max-speed: 20
            controller: {power-notches: [P1], brake-notches: [B1]}
            traction: {notch-scales: {P1: 1.0}}
            brake: {apply-time: 1.0, release-time: 1.0, notch-scales: {B1: 1.0}}
            resistance: {a: 0.0, b: 0.0, c: 0.0}
            adhesion: {coefficient: 0.2}
            jerk: {traction: 1.0, brake: 1.0}
        """.trimIndent())
    }
}
