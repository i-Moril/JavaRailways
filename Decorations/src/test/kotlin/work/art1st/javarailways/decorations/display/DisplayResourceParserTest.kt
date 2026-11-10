package work.art1st.javarailways.decorations.display

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DisplayResourceParserTest {
    private fun config(path: String = "displays/main.xml") =
        "config-version: 1\ntemplates: {main: {file: '${path.replace("'", "''")}'}}\ndefaults: {cabin: main, destination: main}\n"

    @Test fun `missing captured displays document and empty declarations represent no templates`() {
        assertEquals(DisplayResources(), DisplayResourceParser.parse(emptyMap(), "test"))
        assertEquals(DisplayResources(), DisplayResourceParser.parse(mapOf("other.txt" to "value"), "test"))
        assertEquals(DisplayResources(), DisplayResourceParser.parse(mapOf("displays.yml" to "config-version: 1\ntemplates: {}\n"), "test"))
        assertEquals(DisplayResources(), DisplayResourceParser.parse(mapOf("displays.yml" to "config-version: 1\ntemplates: {}\ndefaults: {}"), "test"))
    }

    @Test fun `raw captured XML including inline JS stays unchanged without compilation or filesystem lookup`() {
        val xml = "<screen width='256' height='128'>\r\n<script><![CDATA[const text=ctx.data.name; // 车站\n]]></script></screen>\r\n"
        val files = mutableMapOf("displays.yml" to config(), "displays/main.xml" to xml)
        val parsed = DisplayResourceParser.parse(files, "fleet")
        assertEquals(DisplayTemplateSource("displays/main.xml", xml), parsed.templates.getValue("main"))
        assertEquals(mapOf("cabin" to "main", "destination" to "main"), parsed.defaults)
        files["displays/main.xml"] = "modified"
        assertEquals(xml, parsed.templates.getValue("main").source)
        assertEquals("modified", DisplayResourceParser.parse(files, "fleet").templates.getValue("main").source)
        // This stage preserves even syntactically invalid XML; provider compilation comes later.
        files["displays/main.xml"] = "raw incomplete XML"
        assertEquals("raw incomplete XML", DisplayResourceParser.parse(files, "fleet").templates.getValue("main").source)
    }

    @Test fun `resource values copy sorted input maps and preserve reference invariants`() {
        val templates = linkedMapOf("z" to DisplayTemplateSource("z.xml", "z"), "a" to DisplayTemplateSource("a.xml", "a"))
        val defaults = linkedMapOf("z" to "z", "a" to "a")
        val resources = DisplayResources(templates, defaults)
        templates.clear(); defaults.clear()
        assertEquals(listOf("a", "z"), resources.templates.keys.toList())
        assertEquals(listOf("a", "z"), resources.defaults.keys.toList())
        assertThrows<UnsupportedOperationException> { (resources.templates as MutableMap).clear() }
        assertThrows<UnsupportedOperationException> { (resources.defaults as MutableMap).clear() }
        assertThrows<IllegalArgumentException> { DisplayResources(defaults = mapOf("cabin" to "missing")) }
        assertThrows<IllegalArgumentException> { DisplayResources(mapOf("bad id" to DisplayTemplateSource("a.xml", ""))) }
    }

    @Test fun `strict display document rejects versions types IDs references unknown fields and duplicate YAML keys`() {
        val invalid = listOf(
            "", "[]", "templates: {}", "config-version: 2\ntemplates: {}", "config-version: '1'\ntemplates: {}",
            "config-version: 1", "config-version: 1\ntemplates: []", "config-version: 1\ntemplates: null",
            "config-version: 1\ntemplates: {}\nunknown: true", "config-version: 1\ntemplates: {}\ndefaults: []",
            "config-version: 1\ntemplates: {}\ndefaults: {cabin: missing}",
            "config-version: 1\ntemplates: {}\ndefaults: {cabin: 1}",
            "config-version: 1\ntemplates: {'': {file: main.xml}}",
            "config-version: 1\ntemplates: {'bad id': {file: main.xml}}",
            "config-version: 1\ntemplates: {good: {file: main.xml}}\ndefaults: {'bad category': good}",
            "config-version: 1\ntemplates: {good: {file: main.xml}}\ndefaults: {'': good}",
            "config-version: 1\ntemplates: {good: {file: main.xml}}\ndefaults: {cabin: 'bad id'}",
            "config-version: 1\ntemplates: {good: {file: main.xml, type: xml}}",
            "config-version: 1\ntemplates: {good: {file: main.xml, width: 256}}",
            "config-version: 1\ntemplates: {good: {}}", "config-version: 1\ntemplates: {good: main.xml}",
            "config-version: 1\ntemplates: {good: {file: 1}}", "config-version: 1\ntemplates: {good: {file: null}}",
            "config-version: 1\nconfig-version: 1\ntemplates: {}",
            "config-version: 1\ntemplates: {good: {file: main.xml}, good: {file: main.xml}}",
            "config-version: 1\ntemplates: {good: {file: main.xml, file: main.xml}}",
            "config-version: 1\ntemplates: {good: {file: main.xml}}\ndefaults: {cabin: good, cabin: good}",
        )
        invalid.forEach { source ->
            val failure = assertThrows<IllegalArgumentException>(source) {
                DisplayResourceParser.parse(mapOf("displays.yml" to source, "main.xml" to "XML"), "broken-fleet")
            }
            assertTrue(failure.message.orEmpty().contains("broken-fleet"))
            assertTrue(failure.message.orEmpty().contains("displays.yml"))
        }
    }

    @Test fun `XML references stay relative to captured root and require exact captured membership`() {
        val paths = listOf("", "../outside.xml", "./main.xml", "sub/../main.xml", "sub//main.xml", "/main.xml",
            "C:/main.xml", "C:main.xml", "C:\\main.xml", "sub\\main.xml", "main.js", "main.XML", "main.xml/", "main.xml\n")
        paths.forEach { path ->
            assertThrows<IllegalArgumentException>(path) {
                DisplayResourceParser.parse(mapOf("displays.yml" to config(path), path to "XML"), "fleet")
            }
        }
        val failure = assertThrows<IllegalArgumentException> { DisplayResourceParser.parse(mapOf("displays.yml" to config()), "fleet") }
        assertTrue(failure.message.orEmpty().contains("templates.main.file 'displays/main.xml'"))
        assertTrue(failure.message.orEmpty().contains("not captured"))
        val localColon = "displays/head:wide.xml"
        assertEquals("local", DisplayResourceParser.parse(mapOf("displays.yml" to config(localColon), localColon to "local"), "fleet").templates.getValue("main").source)
    }
}
