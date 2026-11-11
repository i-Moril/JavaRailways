package work.art1st.javarailways.bukkit.extension.train

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class TrainPartRenderSnapshotTest {
    @Test fun `projection uses same frame values and physical car order under reversal`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world, PrimeCurveGenerator.buildStraight(500.0))
            val train = createTrainWithConfig("source", rail, fixture.world, listOf(10.0, 10.0))
            val initial = train.publishedFrame
            val frame = TrainFrameSnapshot.create(
                initial.trainId, "Published name", 37L, 21L, 12.5, true, true, initial.cars,
                initial.head, initial.tail, initial.occupiedIntervals, initial.bodyTraversal,
                initial.sweptSpans, initial.headTraversal, initial.tailTraversal, "Published derail"
            )
            val snapshot = captureTrainPartRenderSnapshot(frame, 1, "fleet", 120L)
            val data = snapshot.data
            val facts = data.getValue("train").jsonObject
            val car = data.getValue("car").jsonObject
            assertEquals("Published name", facts.getValue("name").jsonPrimitive.content)
            assertEquals(12.5, facts.getValue("speedMetersPerSecond").jsonPrimitive.double)
            assertTrue(facts.getValue("reversed").jsonPrimitive.boolean)
            assertTrue(facts.getValue("derailed").jsonPrimitive.boolean)
            assertEquals(2, facts.getValue("carCount").jsonPrimitive.int)
            assertEquals(initial.cars[1].carId, car.getValue("id").jsonPrimitive.content)
            assertEquals(1, car.getValue("index").jsonPrimitive.int)
            assertEquals(2, car.getValue("number").jsonPrimitive.int)
            assertEquals("Published name", data.getValue("trainName").jsonPrimitive.content)
            assertEquals("fleet", data.getValue("trainType").jsonPrimitive.content)
            assertEquals("2", data.getValue("carNumber").jsonPrimitive.content)
            assertTrue(data.getValue("carNumber").jsonPrimitive.isString)
            assertEquals(120L, snapshot.tick)
            assertEquals(initial.trainName, train.publishedFrame.trainName, "projection must not mutate or reread the live train")
            val unknown = captureTrainPartRenderSnapshot(initial, 0, null, 121L)
            assertEquals(JsonNull, unknown.data.getValue("train").jsonObject.getValue("type"))
            assertEquals("", unknown.data.getValue("trainType").jsonPrimitive.content)
            assertEquals("", data.getValue("destination").jsonPrimitive.content, "operational destinations must not be guessed from physics")
            assertEquals(JsonNull, data.getValue("unit"))
            assertEquals(JsonNull, data.getValue("operational"))
            assertEquals(OperationalPartRenderProjection.EMPTY.contextKey, snapshot.contextKey)
        }
    }

    @Test fun `same-frame basic values combine with exact unit projection while physical changes preserve semantic key`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world, PrimeCurveGenerator.buildStraight(500.0))
            val train = createTrainWithConfig("source", rail, fixture.world, listOf(10.0, 10.0))
            val frame = train.publishedFrame
            val display = work.art1st.javarailways.simpleato.timetable.TimetableUnitDisplaySnapshot(
                "unit", "trip", 1L, buildJsonObject { put("destination", "Authored"); put("runType", "Express") }, emptyList(), emptyList(), emptyList())
            val context = work.art1st.javarailways.simpleato.timetable.TimetableUnitDisplayContext(
                frame.trainId, "unit", frame.cars.map { it.carId }, "table", display, null)
            val operational = projectOperationalPartRender(context) { null }
            val lineCatalog = buildJsonObject { put("line", buildJsonObject { put("metadata", buildJsonObject { put("nameEn", "Airport Line") }) }) }
            val first = captureTrainPartRenderSnapshot(frame, 1, "fleet", 100L, operational, lineCatalog)
            assertEquals(lineCatalog, first.data["lines"])
            val moved = TrainFrameSnapshot.create(frame.trainId, "New name", 80L, 21L, 20.0, true, false, frame.cars,
                frame.head, frame.tail, frame.occupiedIntervals, frame.bodyTraversal, frame.sweptSpans, frame.headTraversal, frame.tailTraversal, null)
            val second = captureTrainPartRenderSnapshot(moved, 1, "fleet", 120L, operational)
            assertEquals("Authored", first.data["destination"]?.jsonPrimitive?.content)
            assertEquals("Express", first.data["runType"]?.jsonPrimitive?.content)
            assertEquals(frame.cars.map { it.carId }, second.data["unit"]?.jsonObject?.get("carIds")?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals(first.contextKey, second.contextKey)
            assertNotEquals(first.data.getValue("train"), second.data.getValue("train"))
            val cleared = captureTrainPartRenderSnapshot(moved, 1, "fleet", 121L)
            assertNotEquals(second.contextKey, cleared.contextKey)
            assertEquals(JsonNull, cleared.data["operational"])
            assertEquals("", cleared.data["destination"]?.jsonPrimitive?.content)
        }
    }

    @Test fun `car operating end is captured explicitly and changes the stale-output key`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world, PrimeCurveGenerator.buildStraight(500.0))
            val train = createTrainWithConfig("source", rail, fixture.world, listOf(10.0))
            val frame = train.publishedFrame
            val head = captureTrainPartRenderSnapshot(frame, 0, "fleet", 1L, operatingTail = false)
            val tail = captureTrainPartRenderSnapshot(frame, 0, "fleet", 2L, operatingTail = true)
            val unknown = captureTrainPartRenderSnapshot(frame, 0, "fleet", 3L)
            assertEquals(JsonPrimitive("head"), head.data.getValue("car").jsonObject["operatingEnd"])
            assertEquals(JsonPrimitive("tail"), tail.data.getValue("car").jsonObject["operatingEnd"])
            assertEquals(JsonNull, unknown.data.getValue("car").jsonObject["operatingEnd"])
            assertEquals(head.data.getValue("train"), tail.data.getValue("train"), "operating end is not guessed from train.reversed")
            assertNotEquals(head.contextKey, tail.contextKey, "old-side frames must be invalidated even if operational values match")
            assertEquals(JsonPrimitive("head"), head.data.getValue("car").jsonObject["operatingEnd"], "a later sample cannot mutate captured direction")
        }
    }

    @Test fun `transport detaches caller maps before region and worker handoff`() {
        val nested = linkedMapOf<String, JsonElement>("name" to JsonPrimitive("captured"))
        val root = linkedMapOf<String, JsonElement>("train" to JsonObject(nested))
        val mutableKey = linkedMapOf<String, JsonElement>("unit" to JsonObject(nested))
        val snapshot = TrainPartRenderSnapshot(JsonObject(root), 0L, JsonObject(mutableKey))
        nested["name"] = JsonPrimitive("changed")
        root.clear(); mutableKey.clear()
        assertEquals("captured", snapshot.contextKey.getValue("unit").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("captured", snapshot.data.getValue("train").jsonObject.getValue("name").jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { TrainPartRenderSnapshot(JsonObject(emptyMap()), -1L) }
    }
}
