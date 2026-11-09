package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.bukkit.extension.train.SavedCarRenderState
import work.art1st.javarailways.bukkit.extension.train.SavedTrainRenderState
import work.art1st.javarailways.offline.DevelopmentWorldMigration
import work.art1st.javarailways.offline.RailJunctionMigration
import work.art1st.javarailways.simpleato.persistence.SavedActiveRunCheckpointV1
import work.art1st.javarailways.simpleato.persistence.SavedDispatcherV1
import work.art1st.javarailways.simpleato.persistence.SavedTimetableClockV1
import work.art1st.javarailways.simpleato.persistence.SavedTimetableV1
import work.art1st.javarailways.simpleato.persistence.SimpleATORuntimeSnapshotCodec
import work.art1st.javarailways.simpleato.persistence.SimpleATORuntimeSnapshotV1
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableService
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import work.art1st.javarailways.simpleato.timetable.TrainSelector
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.*

class JunctionWorldMigrationTest {
    @TempDir lateinit var directory: Path
    private val world = "00000000-0000-0000-0000-000000000010"
    private val a = "00000000-0000-0000-0000-000000000001"
    private val b = "00000000-0000-0000-0000-000000000002"
    private val c = "00000000-0000-0000-0000-000000000003"
    private val d = "00000000-0000-0000-0000-000000000004"

    @Test
    fun `same-side pair retains junction membership with no traversable paths and loads`() {
        val original = root(listOf(rail(a, 1, listOf(b), b), rail(b, 1, listOf(a), a)))
        val pair = DevelopmentWorldMigration.convertJunctions(infrastructure(original), runtime())
        val rails = pair.infrastructure.core().rails
        assertEquals(b, rails[0].joinedEndpointsAtA.single().railId)
        assertEquals(a, rails[1].joinedEndpointsAtA.single().railId)
        assertTrue(rails.all { it.connectionsAtA.isEmpty() && it.connectionsAtB.isEmpty() })
        assertTrue(rails.all { it.activeConnectionAtA == null && it.activeConnectionAtB == null })
        assertTrue(pair.repairs.any { "incompleteGroups=1" in it && "removedDirectedPaths=2" in it })
        assertEquals(original.getValue("rails").jsonArray.map { it.jsonObject.getValue("curveAB") },
            Json.parseToJsonElement(pair.infrastructure.coreSnapshotJson).jsonObject.getValue("rails").jsonArray.map { it.jsonObject.getValue("curveAB") })
        assertEquals(7L, pair.infrastructure.infrastructureRevision)
        assertEquals(9L, pair.runtime.physicsEpoch)
    }

    @Test
    fun `three-leg component removes side-to-side edge adds missing common path and repairs active uniquely`() {
        // A and B are branches; C is the common leg. The original graph was incorrectly A-B and A-C.
        val original = root(listOf(rail(a, 1, listOf(b, c), b), rail(b, 1, listOf(a), a), rail(c, -1, listOf(a), a)))
        val pair = DevelopmentWorldMigration.convertJunctions(infrastructure(original), runtime())
        val rails = pair.infrastructure.core().rails
        assertEquals(listOf(c), rails[0].connectionsAtA.map { it.railId })
        assertEquals(listOf(c), rails[1].connectionsAtA.map { it.railId })
        assertEquals(listOf(a, b), rails[2].connectionsAtA.map { it.railId })
        assertEquals(c, rails[0].activeConnectionAtA!!.railId)
        assertEquals(c, rails[1].activeConnectionAtA!!.railId)
        assertEquals(a, rails[2].activeConnectionAtA!!.railId)
        assertTrue(rails.all { it.joinedEndpointsAtA.size == 2 })
        assertTrue(pair.repairs.any { "removedDirectedPaths=2" in it && "addedDirectedPaths=2" in it && "repairedActives=2" in it })
    }

    @Test
    fun `surviving connection order second active and explicit null are preserved without merging colocated rails`() {
        val original = root(listOf(rail(a, -1, listOf(c, b), b), rail(b, 1, listOf(a)),
            rail(c, 1, listOf(a), a), rail(d, 1)))
        val rails = DevelopmentWorldMigration.convertJunctions(infrastructure(original), runtime()).infrastructure.core().rails
        assertEquals(listOf(c, b), rails[0].connectionsAtA.map { it.railId })
        assertEquals(b, rails[0].activeConnectionAtA!!.railId)
        assertNull(rails[1].activeConnectionAtA)
        assertTrue(rails[3].joinedEndpointsAtA.isEmpty())
        assertTrue(rails[3].connectionsAtA.isEmpty())
    }

    @Test
    fun `ambiguous invalid active is refused rather than guessed`() {
        val original = root(listOf(rail(a, 1, listOf(b, c), b), rail(b, 1, listOf(a, d), a),
            rail(c, -1, listOf(a), a), rail(d, -1, listOf(b), b)))
        assertTrue(assertThrows(IllegalStateException::class.java) {
            DevelopmentWorldMigration.convertJunctions(infrastructure(original), runtime())
        }.message!!.contains("Ambiguous active repair"))
    }

    @Test
    fun `malformed references unknown fields and mixed membership reject`() {
        val valid = root(listOf(rail(a, 1, listOf(b), b), rail(b, -1, listOf(a), a)))
        val missing = root(listOf(rail(a, 1, listOf(c), c), rail(b, -1, listOf(a), a)))
        val asymmetric = root(listOf(rail(a, 1, listOf(b), b), rail(b, -1)))
        val wrongActive = root(listOf(rail(a, 1, listOf(b), c), rail(b, -1, listOf(a), a)))
        val duplicate = root(listOf(rail(a, 1, listOf(b, b), b), rail(b, -1, listOf(a), a)))
        val nonColocated = root(listOf(rail(a, 1, listOf(b), b), rail(b, -1, listOf(a), a, start = 1)))
        val future = JsonObject(valid + ("formatVersion" to JsonPrimitive(99)))
        val unknown = JsonObject(valid + ("extra" to JsonNull))
        val converted = RailJunctionMigration.convert(valid, mutableListOf())
        val mixed = root(listOf(valid.getValue("rails").jsonArray[0].jsonObject, converted.getValue("rails").jsonArray[1].jsonObject))
        val incomplete = root(listOf(JsonObject(rail(a, 1) + ("joinedEndpointsAtA" to JsonArray(emptyList())))))
        val unknownRef = root(listOf(JsonObject(rail(a, 1, listOf(b), b) + ("connectionsAtA" to JsonArray(listOf(
            buildJsonObject { put("railId", b); put("endpoint", "A"); put("extra", true) }
        )))), rail(b, -1, listOf(a), a)))
        for (bad in listOf(missing, asymmetric, wrongActive, duplicate, nonColocated, future, unknown, mixed, incomplete, unknownRef)) {
            assertThrows(Exception::class.java) { DevelopmentWorldMigration.convertJunctions(infrastructure(bad), runtime()) }
        }
    }

    @Test
    fun `fresh output preserves gzip runtime and all original bytes and current input is no-op`() {
        val source = directory.resolve("source").createDirectory()
        val input = root(listOf(rail(a, 1, listOf(b), b), rail(b, 1, listOf(a), a)))
        val infrastructureBytes = gzip(infrastructure(input))
        val runtimeBytes = gzip(runtime())
        for (suffix in listOf("", ".bak")) {
            source.resolve("$world-infrastructure.json$suffix").writeBytes(infrastructureBytes)
            source.resolve("$world-runtime.json$suffix").writeBytes(runtimeBytes)
        }
        source.resolve("sidecar.json").writeText("untouched")
        val output = directory.resolve("converted")
        DevelopmentWorldMigration.run(source, null, false, junctionsOnly = true)
        assertFalse(output.exists())
        DevelopmentWorldMigration.run(source, output, false, junctionsOnly = true)
        for (suffix in listOf("", ".bak")) {
            assertArrayEquals(infrastructureBytes, source.resolve("$world-infrastructure.json$suffix").readBytes())
            assertArrayEquals(runtimeBytes, source.resolve("$world-runtime.json$suffix").readBytes())
            assertArrayEquals(runtimeBytes, output.resolve("$world-runtime.json$suffix").readBytes())
            assertEquals(0x1f.toByte(), output.resolve("$world-infrastructure.json$suffix").readBytes()[0])
        }
        assertEquals("untouched", source.resolve("sidecar.json").readText())
        assertFalse(output.resolve("sidecar.json").exists())
        val again = directory.resolve("again")
        DevelopmentWorldMigration.run(output, again, false, junctionsOnly = true)
        output.listDirectoryEntries("*.json*").forEach { assertArrayEquals(it.readBytes(), again.resolve(it.name).readBytes()) }
        assertThrows(IllegalArgumentException::class.java) { DevelopmentWorldMigration.run(source, output, false, junctionsOnly = true) }
        assertThrows(IllegalArgumentException::class.java) { DevelopmentWorldMigration.run(source, source.resolve("nested"), false, junctionsOnly = true) }
        assertThrows(IllegalArgumentException::class.java) { DevelopmentWorldMigration.run(source, null, true, junctionsOnly = true) }
        assertThrows(IllegalArgumentException::class.java) { DevelopmentWorldMigration.run(source, null, false, endpointsOnly = true, junctionsOnly = true) }
    }

    @Test
    fun `bad pair or current membership fails before output exists`() {
        val source = directory.resolve("source").createDirectory()
        val invalid = root(listOf(rail(a, 1, listOf(b), b), rail(b, 1)))
        source.resolve("$world-infrastructure.json").writeText(infrastructure(invalid))
        val output = directory.resolve("output")
        assertThrows(IllegalArgumentException::class.java) { DevelopmentWorldMigration.run(source, output, false, junctionsOnly = true) }
        assertFalse(output.exists())
        source.resolve("$world-runtime.json").writeText(runtime())
        assertThrows(IllegalArgumentException::class.java) { DevelopmentWorldMigration.run(source, output, false, junctionsOnly = true) }
        assertFalse(output.exists())
        assertThrows(IllegalArgumentException::class.java) {
            DevelopmentWorldMigration.convertJunctions(infrastructure(root(listOf(rail(a, 1)))), runtime().replace("\"infrastructureRevision\":7", "\"infrastructureRevision\":8"))
        }
        val valid = RailJunctionMigration.convert(root(listOf(rail(a, 1, listOf(b), b), rail(b, 1, listOf(a), a))), mutableListOf())
        val corrupt = root(valid.getValue("rails").jsonArray.mapIndexed { index, value ->
            if (index == 0) JsonObject(value.jsonObject + ("joinedEndpointsAtA" to JsonArray(emptyList()))) else value.jsonObject
        })
        assertThrows(Exception::class.java) { DevelopmentWorldMigration.convertJunctions(infrastructure(corrupt), runtime()) }
    }

    @Test
    fun `offline entry registers production render codecs before decoding and restores a real nonempty train`() {
        // Raw envelope fixture deliberately avoids calling any registry/serializer before the tool.
        val input = infrastructure(root(listOf(rail(a, 1))))
        val savedRuntime = runtimeWithRenderTrain()
        val source = directory.resolve("render-source").createDirectory()
        val infrastructureBytes = gzip(input)
        val runtimeBytes = gzip(savedRuntime)
        source.resolve("$world-infrastructure.json").writeBytes(infrastructureBytes)
        source.resolve("$world-runtime.json").writeBytes(runtimeBytes)
        val output = directory.resolve("render-output")
        DevelopmentWorldMigration.run(source, output, false, junctionsOnly = true)
        val pair = DevelopmentWorldMigration.convertJunctions(input, savedRuntime)
        val train = pair.runtime.core().trains.single()
        val car = train.cars.single()
        assertEquals("render-fixture", train.name)
        assertEquals(SavedTrainRenderState(packageName = "fixture", packageSnapshotHash = "package-hash"),
            train.extension.getValue("BUKKIT"))
        assertEquals(SavedCarRenderState("fixture/head", mapOf("doors_l" to "closed"), "model-hash", "package-hash", "fixture"),
            car.config.extension.getValue("BUKKIT"))
        assertEquals(7.0, car.bogieA.location.arcLengthInCurveAB)
        assertEquals(3.0, car.bogieB.location.arcLengthInCurveAB)
        assertEquals(8.0, train.headLocation.arcLengthInCurveAB)
        assertEquals(2.0, train.tailLocation.arcLengthInCurveAB)
        assertArrayEquals(runtimeBytes, output.resolve("$world-runtime.json").readBytes())
        assertArrayEquals(runtimeBytes, source.resolve("$world-runtime.json").readBytes())
        assertArrayEquals(infrastructureBytes, source.resolve("$world-infrastructure.json").readBytes())
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            DevelopmentWorldMigration.convertJunctions(input, savedRuntime.replace("javarailways:car_render_state", "fixture:unregistered"))
        }
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            DevelopmentWorldMigration.convertJunctions(input, runtimeWithRenderTrain(unknownPayloadField = true))
        }
    }

    @Test
    fun `nonempty dispatcher checkpoint uses isolated real dependencies and rejects a route lost by junction repair`() {
        val timetable = Timetable("offline-checkpoint", services = listOf(TimetableService(
            "S1", TrainSelector.TrainId("00000000-0000-0000-0000-000000000020"), listOf(
                TimetableStep.Check("central@one"),
                TimetableStep.At(0L, TimetableAction.GoTo("terminal@one"))
            )
        )))
        val checkpoint = SavedDispatcherV1(1, 500L, 12L,
            listOf(SavedTimetableClockV1(timetable.id, 100L, 24_000L, 2L, emptySet(), emptyMap())),
            listOf(SavedActiveRunCheckpointV1(timetable.id, "S1", 1L,
                "00000000-0000-0000-0000-000000000020", 1, "central@one", "terminal@one", "RUNNING", 400L, 0L)))
        val atoRuntime = SimpleATORuntimeSnapshotV1(1, 7L, 9L, checkpoint,
            timetables = listOf(SavedTimetableV1.capture(timetable)))
        val runtimeFile = SplitWorldSnapshotCodec.decodeRuntime(runtimeWithRenderTrain()).copy(
            simpleAtoSnapshotJson = SimpleATORuntimeSnapshotCodec.encode(atoRuntime)
        )
        val savedRuntime = SplitWorldSnapshotCodec.encode(runtimeFile)
        fun withWayPoints(core: JsonObject, terminalRail: String): String {
            val outer = Json.parseToJsonElement(infrastructure(core)).jsonObject
            val basic = Json.parseToJsonElement(outer.getValue("basicSnapshotJson").jsonPrimitive.content).jsonObject
            val points = Json.parseToJsonElement("""[
              {"id":"central@one","railId":"$a","arcLengthInAbMeters":2},
              {"id":"terminal@one","railId":"$terminalRail","arcLengthInAbMeters":15}
            ]""")
            return JsonObject(outer + ("basicSnapshotJson" to JsonPrimitive(JsonObject(basic + ("wayPoints" to points)).toString()))).toString()
        }
        // Restoring this clock reinstalls a selected physical route through the production registry.
        // The original active run is checked by the real dispatcher and retained as recovery data;
        // the migration does not tick it, grant a moving authority or rewrite its checkpoint bytes.
        val input = withWayPoints(root(listOf(rail(a, 1, length = 30))), a)
        val pair = DevelopmentWorldMigration.convertJunctions(input, savedRuntime)
        assertEquals(atoRuntime, pair.runtime.simpleAto())
        assertEquals(checkpoint, pair.runtime.simpleAto().dispatcher)
        val source = directory.resolve("checkpoint-source").createDirectory()
        val runtimeBytes = gzip(savedRuntime)
        source.resolve("$world-infrastructure.json").writeText(input)
        source.resolve("$world-runtime.json").writeBytes(runtimeBytes)
        val output = directory.resolve("checkpoint-output")
        DevelopmentWorldMigration.run(source, output, false, junctionsOnly = true)
        assertArrayEquals(runtimeBytes, output.resolve("$world-runtime.json").readBytes())

        // The same timetable cannot cross two same-side branches after removing the old false path.
        // It must fail, retaining the input clock/run instead of silently clearing them to pass.
        val disconnected = withWayPoints(root(listOf(rail(a, 1, listOf(b), b, length = 30),
            rail(b, 1, listOf(a), a, length = 30))), b)
        val badSource = directory.resolve("unreachable-checkpoint-source").createDirectory()
        val badInfrastructureBytes = disconnected.toByteArray()
        badSource.resolve("$world-infrastructure.json").writeBytes(badInfrastructureBytes)
        badSource.resolve("$world-runtime.json").writeBytes(runtimeBytes)
        val rejectedOutput = directory.resolve("unreachable-checkpoint-output")
        val failure = assertThrows(IllegalStateException::class.java) {
            DevelopmentWorldMigration.run(badSource, rejectedOutput, false, junctionsOnly = true)
        }
        assertTrue(failure.message.orEmpty().contains("could not restore applied timetable"))
        assertFalse(rejectedOutput.exists())
        assertArrayEquals(badInfrastructureBytes, badSource.resolve("$world-infrastructure.json").readBytes())
        assertArrayEquals(runtimeBytes, badSource.resolve("$world-runtime.json").readBytes())
    }

    private fun runtimeWithRenderTrain(unknownPayloadField: Boolean = false): String {
        fun location(arc: Int) = """{"railId":"$a","arcLengthInCurveAB":$arc,"direction":"AB"}"""
        fun bogie(arc: Int) = """{"location":${location(arc)},"isSameDirectionAsFacing":true,"extension":{}}"""
        val extra = if (unknownPayloadField) ",\"unknown\":true" else ""
        val train = Json.parseToJsonElement("""{
          "id":"00000000-0000-0000-0000-000000000020","name":"render-fixture","reversed":false,
          "headLocation":${location(8)},"tailLocation":${location(2)},
          "extension":{"BUKKIT":{"typeId":"javarailways:train_render_state","schemaVersion":1,
            "payload":{"version":1,"packageName":"fixture","packageSnapshotHash":"package-hash"}}},
          "cars":[{"id":"render-car","bogieA":${bogie(7)},"bogieB":${bogie(3)},"installedReversed":false,
            "config":{"length":6,"floorHeight":1,"bogieAType":null,"bogieAOffset":2,
              "bogieBType":null,"bogieBOffset":-2,"connectorLengthAtA":0.4,"connectorLengthAtB":0.4,
              "extension":{"BUKKIT":{"typeId":"javarailways:car_render_state","schemaVersion":1,
                "payload":{"carModelName":"fixture/head","heldScenes":{"doors_l":"closed"},
                  "modelSnapshotHash":"model-hash","packageSnapshotHash":"package-hash","resourceType":"fixture"$extra}}}}}]
        }""")
        val base = Json.parseToJsonElement(runtime()).jsonObject
        val core = Json.parseToJsonElement(base.getValue("coreSnapshotJson").jsonPrimitive.content).jsonObject
        return JsonObject(base + ("coreSnapshotJson" to JsonPrimitive(JsonObject(core + ("trains" to JsonArray(listOf(train)))).toString()))).toString()
    }

    private fun root(rails: List<JsonObject>) = buildJsonObject {
        put("formatVersion", 2); put("infrastructureRevision", 7); put("rails", JsonArray(rails))
    }

    private fun rail(id: String, direction: Int, next: List<String> = emptyList(), active: String? = null, start: Int = 0,
                     length: Int = 10): JsonObject {
        fun point(x: Int, arc: Int) = """{"position":{"x":$x,"y":0,"z":0},"tangent":{"x":$direction,"y":0,"z":0},"normal":{"x":0,"y":1,"z":0},"arcLength":$arc,"segmentType":"DISCRETE"}"""
        fun ref(target: String) = buildJsonObject { put("railId", target); put("endpoint", "A") }
        return buildJsonObject {
            put("id", id)
            put("curveAB", Json.parseToJsonElement("""{"pointList":[${point(start, 0)},${point(start + length * direction, length)}]}"""))
            put("connectionsAtA", JsonArray(next.map(::ref)))
            put("connectionsAtB", JsonArray(emptyList()))
            put("activeConnectionAtA", active?.let(::ref) ?: JsonNull)
            put("activeConnectionAtB", JsonNull)
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

    private fun gzip(text: String): ByteArray = java.io.ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(text.toByteArray()) }
    }.toByteArray()
}
