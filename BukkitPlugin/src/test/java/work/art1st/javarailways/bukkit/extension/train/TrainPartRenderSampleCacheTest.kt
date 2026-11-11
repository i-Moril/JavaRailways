package work.art1st.javarailways.bukkit.extension.train

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import work.art1st.javarailways.bukkit.util.LatestAffinityTaskDispatcher
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*
import java.util.UUID

class TrainPartRenderSampleCacheTest {
    @Test fun `blocked region coalesces geometry without losing pending metadata and cannot borrow another stream`() {
        TestWorldFixture.create().use { fixture ->
            val rail = createStraightRailFromSegments(fixture.world, PrimeCurveGenerator.buildStraight(500.0))
            val train = createTrainWithConfig("source", rail, fixture.world, listOf(10.0))
            val frame = train.publishedFrame
            val stream = train.publicationStreamId
            val cache = TrainPartRenderSampleCache()
            val delivered = mutableListOf<Pair<Int, TrainPartRenderSnapshot?>>()
            val completions = mutableListOf<() -> Unit>()
            val dispatcher = LatestAffinityTaskDispatcher<String, Pair<Int, TrainPartRenderSnapshot?>> { value, complete ->
                delivered += value
                completions += complete
            }
            dispatcher.offer("car", 0 to null) // Region callback is still in flight.
            val lines = kotlinx.serialization.json.buildJsonObject {
                put("transfer-line", kotlinx.serialization.json.JsonObject(emptyMap()))
            }
            val sampled = cache.capture(frame, 0, "fleet", 101L, OperationalPartRenderProjection.EMPTY, stream, lines, operatingTail = true)
            assertEquals(lines, sampled.data["lines"])
            assertEquals("tail", sampled.data.getValue("car").jsonObject.getValue("operatingEnd").jsonPrimitive.content)
            dispatcher.offer("car", 1 to sampled)
            dispatcher.offer("car", 2 to cache.latest(frame, 0, stream))
            assertEquals(1, delivered.size)
            completions[0]()
            assertEquals(2, delivered.last().first, "geometry must remain newest")
            assertSame(sampled, delivered.last().second, "pending reset/SET sample survives geometry coalescing")
            assertNull(cache.latest(frame, 0, UUID.randomUUID()), "split or replaced train stream cannot inherit metadata")
            cache.clear()
            assertNull(cache.latest(frame, 0, stream), "removed cars release their samples at the next capture")
            completions[1]()
        }
    }
}
