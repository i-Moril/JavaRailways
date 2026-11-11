package work.art1st.javarailways.decorations.display

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DisplayStyleBundlesTest {
    @TempDir lateinit var directory: Path

    private fun bundle(manifest: String = "config-version: 1\ntemplates: {cabin-route.route: {file: displays/route.xml}}\n"): Path {
        val root = directory.resolve("cabin-route")
        Files.createDirectories(root.resolve("displays"))
        Files.writeString(root.resolve("displays.yml"), manifest)
        Files.writeString(root.resolve("displays/route.xml"), "original source")
        return root
    }

    @Test fun `sources are snapshotted and merging preserves local defaults without mutating either catalog`() {
        val root = bundle()
        val external = DisplayStyleBundles.load(directory)
        Files.writeString(root.resolve("displays/route.xml"), "changed after enable")
        val local = DisplayResources(mapOf("head" to DisplayTemplateSource("head.xml", "car source")), mapOf("exterior" to "head"))
        val merged = DisplayStyleBundles.merge(local, external, "car")!!
        assertEquals("original source", merged.templates.getValue("cabin-route.route").source)
        assertEquals(mapOf("exterior" to "head"), merged.defaults)
        assertEquals(setOf("head"), local.templates.keys)
        assertTrue(external.defaults.isEmpty())
        assertEquals(setOf("head", "cabin-route.route"), merged.templates.keys)
        assertThrows<UnsupportedOperationException> { (merged.templates as MutableMap<String, DisplayTemplateSource>).clear() }
    }

    @Test fun `missing directory is optional while explicit broken bundles fail`() {
        assertTrue(DisplayStyleBundles.load(directory.resolve("absent")).templates.isEmpty())
        bundle("config-version: 1\ntemplates: {cabin-route.route: {file: missing.xml}}\n")
        assertTrue(assertThrows<IllegalArgumentException> { DisplayStyleBundles.load(directory) }.message.orEmpty().contains("missing.xml"))
    }

    @Test fun `external defaults unqualified IDs duplicate IDs and unsafe paths are rejected`() {
        listOf(
            "config-version: 1\ntemplates: {cabin-route.route: {file: displays/route.xml}}\ndefaults: {cabin: cabin-route.route}",
            "config-version: 1\ntemplates: {route: {file: displays/route.xml}}",
            "config-version: 1\ntemplates: {cabin-route.route: {file: ../route.xml}}",
            "config-version: 1\ntemplates: {cabin-route.route: {file: displays/route.xml}, cabin-route.route: {file: displays/route.xml}}",
        ).forEach { manifest ->
            bundle(manifest)
            assertThrows<IllegalArgumentException> { DisplayStyleBundles.load(directory) }
        }
        bundle()
        val external = DisplayStyleBundles.load(directory)
        assertThrows<IllegalArgumentException> { DisplayStyleBundles.merge(external, external, "car") }
    }

    @Test fun `invalid UTF8 and symbolic links never become template source`() {
        val root = bundle()
        Files.write(root.resolve("displays/route.xml"), byteArrayOf(0xc3.toByte(), 0x28))
        assertTrue(assertThrows<IllegalArgumentException> { DisplayStyleBundles.load(directory) }.message.orEmpty().contains("UTF-8"))
        Files.delete(root.resolve("displays/route.xml"))
        val outside = Files.writeString(directory.resolve("outside.xml"), "outside")
        Files.createSymbolicLink(root.resolve("displays/route.xml"), outside)
        assertThrows<IllegalArgumentException> { DisplayStyleBundles.load(directory) }
    }
}
