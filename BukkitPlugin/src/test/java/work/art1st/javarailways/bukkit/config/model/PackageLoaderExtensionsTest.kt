package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class PackageLoaderExtensionsTest {
    @TempDir lateinit var directory: File

    @Test fun `absent providers still capture every declared raw UTF8 file immutably in stable order`() {
        val pkg = packageDirectory()
        assertTrue(PackageLoader(pkg).loadTrainPackage()!!.extensions.isEmpty())
        declare(pkg)
        val resources = pkg.resolve("addon").apply { mkdirs() }
        resources.resolve("z.xml").writeText("<screen>车站<script>raw</script></screen>\r\n")
        resources.resolve("head:wide.txt").writeText("colon name")
        resources.resolve("nested").mkdir()
        resources.resolve("nested/a.yml").writeText("unknown: {raw: [1, 2]}\n")
        val captured = load(pkg)
        assertEquals(listOf("head:wide.txt", "nested/a.yml", "z.xml"), captured.files.keys.toList())
        assertEquals("<screen>车站<script>raw</script></screen>\r\n", captured.files.getValue("z.xml"))
        resources.resolve("z.xml").writeText("changed")
        assertTrue(captured.files.getValue("z.xml").contains("车站"))
        assertEquals("changed", load(pkg).files.getValue("z.xml"))
        assertThrows<UnsupportedOperationException> { (captured.files as MutableMap).clear() }
    }

    @Test fun `declarations and paths reject unsafe malformed and missing resources`() {
        val pkg = packageDirectory()
        for (path in listOf("", "../outside", "./addon", "sub/../addon", "sub//addon", "/addon", "C:/addon", "sub\\addon", "missing")) {
            declare(pkg, path)
            assertThrows<Exception>(path) { load(pkg) }
        }
        pkg.resolve("file").writeText("not a directory"); declare(pkg, "file")
        assertThrows<Exception> { load(pkg) }
        for (value in listOf("schema-version: 0", "schema-version: '1'", "unknown: true")) {
            val manifest = pkg.resolve("manifest.yml").readText().substringBefore("extensions:")
            pkg.resolve("manifest.yml").writeText(manifest + "extensions: {unknown:part: {directory: addon, $value}}\n")
            assertThrows<Exception> { load(pkg) }
        }
    }

    @Test fun `real path checks reject escape dangling and cyclic links while safe aliases preserve text`() {
        val pkg = packageDirectory(); declare(pkg)
        val resources = pkg.resolve("addon").apply { mkdir() }
        val outside = directory.resolve("outside.txt").apply { writeText("outside") }
        val link = resources.resolve("link.txt").toPath()
        Files.createSymbolicLink(link, outside.toPath())
        assertTrue(assertThrows<IllegalArgumentException> { load(pkg) }.message!!.contains("outside package"))
        Files.delete(link)
        Files.createSymbolicLink(link, resources.resolve("missing.txt").toPath())
        assertThrows<Exception> { load(pkg) }; Files.delete(link)
        Files.createSymbolicLink(resources.resolve("cycle").toPath(), resources.toPath())
        assertThrows<IllegalArgumentException> { load(pkg) }; Files.delete(resources.resolve("cycle").toPath())
        resources.resolve("inner.txt").writeText("inside")
        Files.createSymbolicLink(link, resources.resolve("inner.txt").toPath())
        assertEquals("inside", load(pkg).files.getValue("link.txt"))
        // A declaration's intermediate symlink must also remain inside the package.
        val externalDir = directory.resolve("external").apply { mkdir() }
        Files.createSymbolicLink(externalDir.resolve("back").toPath(), resources.toPath())
        Files.createSymbolicLink(pkg.resolve("escape").toPath(), externalDir.toPath())
        declare(pkg, "escape/back")
        assertThrows<IllegalArgumentException> { load(pkg) }
    }

    @Test fun `strict UTF8 failures identify extension type and offending path`() {
        val pkg = packageDirectory(); declare(pkg)
        pkg.resolve("addon").mkdir()
        pkg.resolve("addon/bad.xml").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        val failure = assertThrows<IllegalArgumentException> { load(pkg) }
        assertTrue(failure.message!!.contains("unknown:part"))
        assertTrue(failure.message!!.contains("bad.xml"))
    }

    private fun declare(pkg: File, path: String = "addon") {
        val manifest = pkg.resolve("manifest.yml").readText().substringBefore("extensions:")
        pkg.resolve("manifest.yml").writeText(manifest + "extensions: {unknown:part: {directory: '$path'}}\n")
    }
    private fun load(pkg: File) = PackageLoader(pkg).loadTrainPackage()!!.extensions.getValue("unknown:part")
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
