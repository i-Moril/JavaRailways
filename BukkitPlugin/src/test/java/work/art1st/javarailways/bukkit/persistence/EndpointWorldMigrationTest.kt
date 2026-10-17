package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.offline.DevelopmentWorldMigration
import work.art1st.javarailways.offline.RailEndpointMigration
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.*

class EndpointWorldMigrationTest {
    @TempDir lateinit var directory: Path
    private val world = "00000000-0000-0000-0000-000000000010"
    private val a = "00000000-0000-0000-0000-000000000001"
    private val b = "00000000-0000-0000-0000-000000000002"
    private val c = "00000000-0000-0000-0000-000000000003"

    @Test
    fun `fixed UUID input retains order second active and null and passes real restore`() {
        val pair = DevelopmentWorldMigration.convertEndpoints(infrastructure(core()), runtime())
        val rails = pair.infrastructure.core().rails
        assertEquals(listOf(b, c), rails[0].connectionsAtB.map { it.railId })
        assertEquals(listOf("A", "B"), rails[0].connectionsAtB.map { it.endpoint.name })
        assertEquals(rails[0].connectionsAtB[1], rails[0].activeConnectionAtB)
        assertNull(rails[1].activeConnectionAtA)
        assertEquals(7L, pair.infrastructure.infrastructureRevision)
        assertEquals(9L, pair.runtime.physicsEpoch)
        assertEquals(4, pair.repairs.size)
    }

    @Test
    fun `UUID spelling does not change active identity and duplicate aliases reject`() {
        val target = "abcdef00-0000-0000-0000-000000000002"
        val core = root(listOf(rail(a, 0, 10, "B", listOf(target), target.uppercase()),
            rail(target, 10, 20, "A", listOf(a), a)))
        val converted = DevelopmentWorldMigration.convertEndpoints(infrastructure(core), runtime()).infrastructure.core()
        assertEquals(target, converted.rails[0].activeConnectionAtB!!.railId)
        val duplicate = root(listOf(rail(a, 0, 10, "B", listOf(target, target.uppercase())), rail(target, 10, 20)))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            RailEndpointMigration.convert(duplicate, mutableListOf())
        }.message!!.contains("duplicate"))
    }

    @Test
    fun `AA AB BA BB are all inferred only in offline boundary`() {
        for (sourceEnd in listOf("A", "B")) for (targetEnd in listOf("A", "B")) {
            val source = rail(a, if (sourceEnd == "A") 10 else 0, if (sourceEnd == "A") 0 else 10,
                sourceEnd, listOf(b), b)
            val target = rail(b, if (targetEnd == "A") 10 else 20, if (targetEnd == "A") 20 else 10,
                targetEnd, listOf(a), a)
            val converted = DevelopmentWorldMigration.convertEndpoints(infrastructure(root(listOf(source, target))), runtime())
            val saved = converted.infrastructure.core().rails[0]
            val refs = if (sourceEnd == "A") saved.connectionsAtA else saved.connectionsAtB
            assertEquals(targetEnd, refs.single().endpoint.name)
        }
    }

    @Test
    fun `zero two missing targets bad active unknown and mixed shapes reject`() {
        val zero = root(listOf(rail(a, 0, 10, "B", listOf(b)), rail(b, 20, 30)))
        val ambiguous = root(listOf(rail(a, 0, 10, "B", listOf(b)), rail(b, 10, 10)))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            RailEndpointMigration.convert(zero, mutableListOf())
        }.message!!.contains("got []"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            RailEndpointMigration.convert(ambiguous, mutableListOf())
        }.message!!.contains("got [A, B]"))
        val missing = root(listOf(rail(a, 0, 10, "B", listOf(b))))
        val badActive = root(listOf(rail(a, 0, 10, "B", listOf(b), c), rail(b, 10, 20)))
        val duplicate = root(listOf(rail(a, 0, 10, "B", listOf(b, b)), rail(b, 10, 20)))
        val excessive = root(listOf(rail(a, 0, 10, "B", listOf(b, c, a)), rail(b, 10, 20), rail(c, 20, 10)))
        val asymmetric = root(listOf(rail(a, 0, 10, "B", listOf(b)), rail(b, 10, 20)))
        val mixed = JsonObject(core() + ("rails" to JsonArray(core().getValue("rails").jsonArray.mapIndexed { i, value ->
            if (i == 0) JsonObject(value.jsonObject + ("connectionsAtA" to JsonArray(emptyList()))) else value
        })))
        val unknown = JsonObject(core() + ("unexpected" to JsonPrimitive(true)))
        for (bad in listOf(zero, ambiguous, missing, badActive, duplicate, excessive, asymmetric, mixed, unknown)) {
            assertThrows(Exception::class.java) { DevelopmentWorldMigration.convertEndpoints(infrastructure(bad), runtime()) }
        }
        val converted = RailEndpointMigration.convert(core(), mutableListOf())
        val mixedRails = JsonObject(core() + ("rails" to JsonArray(listOf(core().getValue("rails").jsonArray[0], converted.getValue("rails").jsonArray[1]))))
        assertThrows(IllegalArgumentException::class.java) { RailEndpointMigration.convert(mixedRails, mutableListOf()) }
    }

    @Test
    fun `fresh output preserves gzip runtime source and backup and current byte identity`() {
        val source = directory.resolve("source").createDirectory()
        val infraBytes = gzip(infrastructure(core()))
        val runtimeBytes = gzip(runtime())
        for (suffix in listOf("", ".bak")) {
            source.resolve("$world-infrastructure.json$suffix").writeBytes(infraBytes)
            source.resolve("$world-runtime.json$suffix").writeBytes(runtimeBytes)
        }
        source.resolve("sidecar.json").writeText("untouched")
        val output = directory.resolve("converted")
        DevelopmentWorldMigration.run(source, null, false, endpointsOnly = true)
        assertFalse(output.exists())
        DevelopmentWorldMigration.run(source, output, false, endpointsOnly = true)
        for (suffix in listOf("", ".bak")) {
            assertArrayEquals(infraBytes, source.resolve("$world-infrastructure.json$suffix").readBytes())
            assertArrayEquals(runtimeBytes, source.resolve("$world-runtime.json$suffix").readBytes())
            assertArrayEquals(runtimeBytes, output.resolve("$world-runtime.json$suffix").readBytes())
            assertEquals(0x1f.toByte(), output.resolve("$world-infrastructure.json$suffix").readBytes()[0])
        }
        assertEquals("untouched", source.resolve("sidecar.json").readText())
        assertFalse(output.resolve("sidecar.json").exists())
        val again = directory.resolve("current-copy")
        DevelopmentWorldMigration.run(output, again, false, endpointsOnly = true)
        output.listDirectoryEntries("*.json*").forEach { assertArrayEquals(it.readBytes(), again.resolve(it.name).readBytes()) }
        assertThrows(IllegalArgumentException::class.java) { DevelopmentWorldMigration.run(source, output, false, true) }
        assertThrows(IllegalArgumentException::class.java) { DevelopmentWorldMigration.run(source, source.resolve("nested"), false, true) }
    }

    @Test
    fun `missing pair future versions and revision mismatches leave no output`() {
        val source = directory.resolve("source").createDirectory()
        source.resolve("$world-infrastructure.json").writeText(infrastructure(core()))
        val output = directory.resolve("output")
        assertThrows(IllegalArgumentException::class.java) { DevelopmentWorldMigration.run(source, output, false, true) }
        assertFalse(output.exists())
        assertThrows(IllegalArgumentException::class.java) { DevelopmentWorldMigration.convertEndpoints(infrastructure(core()).replace("\"formatVersion\":2", "\"formatVersion\":99"), runtime()) }
        assertThrows(IllegalArgumentException::class.java) { DevelopmentWorldMigration.convertEndpoints(infrastructure(core()), runtime().replace("\"infrastructureRevision\":7", "\"infrastructureRevision\":8")) }
        val current = RailEndpointMigration.convert(core(), mutableListOf())
        val wrongEnd = JsonObject(current + ("rails" to JsonArray(current.getValue("rails").jsonArray.mapIndexed { i, element ->
            if (i != 0) element else JsonObject(element.jsonObject + ("connectionsAtB" to JsonArray(listOf(
                buildJsonObject { put("railId", b); put("endpoint", "B") },
                buildJsonObject { put("railId", c); put("endpoint", "B") }
            ))))
        })))
        assertThrows(Exception::class.java) { DevelopmentWorldMigration.convertEndpoints(infrastructure(wrongEnd), runtime()) }
        val invalidCurrent = JsonObject(current + ("unknown" to JsonNull))
        assertThrows(Exception::class.java) { DevelopmentWorldMigration.convertEndpoints(infrastructure(invalidCurrent), runtime()) }
    }

    private fun gzip(text: String): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
    }.toByteArray()

    private fun core() = root(listOf(rail(a, 0, 10, "B", listOf(b, c), c),
        rail(b, 10, 20, "A", listOf(a)), rail(c, 20, 10, "B", listOf(a), a)))
    private fun root(rails: List<JsonObject>) = buildJsonObject {
        put("formatVersion", 2); put("infrastructureRevision", 7); put("rails", JsonArray(rails))
    }
    private fun rail(id: String, start: Int, end: Int, side: String = "A", next: List<String> = emptyList(), active: String? = null): JsonObject {
        fun point(x: Int, arc: Int) = """{"position":{"x":$x,"y":0,"z":0},"tangent":{"x":1,"y":0,"z":0},"normal":{"x":0,"y":1,"z":0},"arcLength":$arc,"segmentType":"DISCRETE"}"""
        return buildJsonObject {
            put("id", id)
            put("curveAB", Json.parseToJsonElement("""{"pointList":[${point(start, 0)},${point(end, 10)}]}"""))
            for (endpoint in listOf("A", "B")) {
                put("nextRailAt$endpoint", JsonArray(if (endpoint == side) next.map(::JsonPrimitive) else emptyList()))
                put("activeTrackAt$endpoint", if (endpoint == side && active != null) JsonPrimitive(active) else JsonNull)
            }
        }
    }
    private fun infrastructure(core: JsonObject) = buildJsonObject {
        put("formatVersion", 2); put("infrastructureRevision", 7); put("coreSnapshotJson", core.toString())
        put("basicSnapshotJson", """{"formatVersion":2,"infrastructureRevision":7,"wayPoints":[],"railMetadata":[],"operatingDirections":[]}""")
        put("simpleAtoSnapshotJson", """{"formatVersion":1,"infrastructureRevision":7,"destinations":[],"lines":[],"lineRevision":0}""")
    }.toString()
    private fun runtime() = buildJsonObject {
        put("formatVersion", 2); put("infrastructureRevision", 7); put("physicsEpoch", 9)
        put("coreSnapshotJson", """{"formatVersion":1,"infrastructureRevision":7,"physicsEpoch":9,"trains":[]}""")
        put("basicSnapshotJson", """{"formatVersion":3,"infrastructureRevision":7,"physicsEpoch":9}""")
        put("simpleAtoSnapshotJson", """{"formatVersion":1,"infrastructureRevision":7,"physicsEpoch":9,"dispatcher":{"formatVersion":1,"dispatcherTick":12,"missionRevision":0,"clocks":[],"runs":[]},"timetables":[],"rollingStock":[]}""")
    }.toString()
}
