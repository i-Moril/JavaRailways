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
            assertFalse(data.containsKey("destination"), "operational destinations must not be guessed from physics")
        }
    }

    @Test fun `transport detaches caller maps before region and worker handoff`() {
        val nested = linkedMapOf<String, JsonElement>("name" to JsonPrimitive("captured"))
        val root = linkedMapOf<String, JsonElement>("train" to JsonObject(nested))
        val snapshot = TrainPartRenderSnapshot(JsonObject(root), 0L)
        nested["name"] = JsonPrimitive("changed")
        root.clear()
        assertEquals("captured", snapshot.data.getValue("train").jsonObject.getValue("name").jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { TrainPartRenderSnapshot(JsonObject(emptyMap()), -1L) }
    }
}
