package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.offline.DevelopmentWorldMigration
import java.nio.file.Path
import kotlin.io.path.*

class DevelopmentWorldMigrationTest {
    @TempDir lateinit var directory: Path
    private val worldId = "00000000-0000-0000-0000-000000000001"
    private val railId = "00000000-0000-0000-0000-000000000002"

    @Test
    fun `raw duplicates reject while opt-in repair strictly loads and preserves source and coordinates`() {
        val source = directory.resolve("source").createDirectory()
        val infra = source.resolve("$worldId-infrastructure.json").also { it.writeText(infrastructure()) }
        val runtime = source.resolve("$worldId-runtime.json").also { it.writeText(runtime()) }
        val oldInfra = infra.readBytes()
        val oldRuntime = runtime.readBytes()
        assertThrows(IllegalArgumentException::class.java) { DevelopmentWorldMigration.run(source, null, false) }
        val dryReport = DevelopmentWorldMigration.run(source, null, true)
        assertEquals(2, source.listDirectoryEntries().size)
        assertTrue(dryReport.any { "point=1" in it })
        val output = directory.resolve("output")
        DevelopmentWorldMigration.run(source, output, true)
        val restored = SplitWorldSnapshotCodec.decodeInfrastructure(output.resolve(infra.name).readText())
        assertEquals(7L, restored.infrastructureRevision)
        assertEquals(1, restored.core().rails.size)
        assertEquals(1, restored.basic().operatingDirections.size)
        val core = Json.parseToJsonElement(restored.coreSnapshotJson).jsonObject
        val rail = core.getValue("rails").jsonArray.single().jsonObject
        val points = rail.getValue("curveAB").jsonObject.getValue("pointList").jsonArray
        assertEquals(listOf(0.0, 10.0), points.map { it.jsonObject.getValue("arcLength").jsonPrimitive.double })
        assertEquals(railId, rail.getValue("id").jsonPrimitive.content)
        assertArrayEquals(oldInfra, infra.readBytes())
        assertArrayEquals(oldRuntime, runtime.readBytes())
        assertThrows(IllegalArgumentException::class.java) { DevelopmentWorldMigration.run(source, output, true) }
    }

    @Test
    fun `different position or material frame changes never qualify for duplicate repair`() {
        fun reject(changed: String) {
            assertThrows(IllegalArgumentException::class.java) {
                DevelopmentWorldMigration.repairCurvePoints(Json.parseToJsonElement(changed).jsonObject, true, mutableListOf())
            }
        }
        reject(core(duplicateTangentX = "0.5"))
        reject(core(duplicateX = "0.001"))
        // Repair must not erase unknown fields before the current strict reader sees them.
        reject(core().replace("\"arcLength\":0.0", "\"unknown\":true,\"arcLength\":0.0"))
    }

    @Test
    fun `revision mismatches and nonempty legacy operations reject without output`() {
        assertThrows(IllegalArgumentException::class.java) {
            DevelopmentWorldMigration.convert(infrastructure(), runtime().replace("\"physicsEpoch\":9", "\"physicsEpoch\":10"), true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DevelopmentWorldMigration.convert(infrastructure(), runtime().replace("\\\"trains\\\":[]", "\\\"trains\\\":[{}]"), true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DevelopmentWorldMigration.convert(infrastructure(), runtime().replace("\\\"runs\\\":[]", "\\\"runs\\\":[{}]"), true)
        }
    }

    private fun infrastructure(): String = JsonObject(mapOf(
        "formatVersion" to JsonPrimitive(1), "infrastructureRevision" to JsonPrimitive(7),
        "coreSnapshotJson" to JsonPrimitive(core()),
        "basicSnapshotJson" to JsonPrimitive("""{"formatVersion":1,"infrastructureRevision":7,"destinations":[],"wayPoints":[],"lines":[],"lineRevision":0,"railMetadata":[{"railId":"$railId","metadata":{"operating":{"allowedDirection":"AB"}}}]}""")
    )).toString()

    private fun runtime(): String = JsonObject(mapOf(
        "formatVersion" to JsonPrimitive(1), "infrastructureRevision" to JsonPrimitive(7), "physicsEpoch" to JsonPrimitive(9),
        "coreSnapshotJson" to JsonPrimitive("""{"formatVersion":1,"infrastructureRevision":7,"physicsEpoch":9,"trains":[]}"""),
        "basicSnapshotJson" to JsonPrimitive("""{"formatVersion":1,"infrastructureRevision":7,"physicsEpoch":9,"timetables":[],"rollingStock":[],"dispatcher":{"formatVersion":1,"dispatcherTick":12,"missionRevision":0,"clocks":[],"runs":[]}}""")
    )).toString()

    private fun core(duplicateX: String = "0.0", duplicateTangentX: String = "1.0"): String {
        fun point(x: String, arc: String, tangentX: String = "1.0") = """{"position":{"x":$x,"y":0.0,"z":0.0},"tangent":{"x":$tangentX,"y":0.0,"z":0.0},"normal":{"x":0.0,"y":1.0,"z":0.0},"arcLength":$arc,"segmentType":"DISCRETE"}"""
        return """{"formatVersion":2,"infrastructureRevision":7,"rails":[{"id":"$railId","curveAB":{"pointList":[${point("0.0", "0.0")},${point(duplicateX, "0.0", duplicateTangentX)},${point("10.0", "10.0")}]},"nextRailAtA":[],"nextRailAtB":[],"activeTrackAtA":null,"activeTrackAtB":null}]}"""
    }
}
