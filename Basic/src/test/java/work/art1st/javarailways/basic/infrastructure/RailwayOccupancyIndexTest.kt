package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.observation.RailwayTrainObservation
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import java.util.UUID

class RailwayOccupancyIndexTest {
    private val firstRail = UUID(0L, 1L)
    private val secondRail = UUID(0L, 2L)
    private val stamp = RailwayStateStamp("test-world", 7L, 11L)

    @Test
    fun `index retains the exact observation stamp and excludes the requesting train`() {
        val observation = observation(
            train("requesting", span(firstRail, 4.0, 8.0)),
            train("blocking", span(firstRail, 6.0, 9.0))
        )

        val index = RailwayOccupancyIndex(observation)

        assertEquals(stamp, index.stamp)
        assertEquals(
            listOf("blocking"),
            index.blockingTrainIds(listOf(interval(firstRail, 5.0, 7.0)), "requesting")
        )
    }

    @Test
    fun `queries only matching rails and returns distinct train IDs in stable order`() {
        val index = RailwayOccupancyIndex(
            observation(
                train(
                    "z-train",
                    span(firstRail, 1.0, 4.0),
                    span(firstRail, 6.0, 9.0)
                ),
                train("a-train", span(secondRail, 2.0, 5.0)),
                train("other-rail", span(UUID(0L, 3L), 0.0, 100.0))
            )
        )

        val blockers = index.blockingTrainIds(
            listOf(
                interval(firstRail, 3.0, 7.0),
                interval(secondRail, 4.0, 6.0)
            ),
            "requesting"
        )

        assertEquals(listOf("a-train", "z-train"), blockers)
    }

    @Test
    fun `prefix maximum search retains a long earlier span behind shorter entries`() {
        val index = RailwayOccupancyIndex(
            observation(
                train("long-span", span(firstRail, 0.0, 100.0)),
                train("short-one", span(firstRail, 10.0, 11.0)),
                train("short-two", span(firstRail, 20.0, 21.0))
            )
        )

        assertEquals(
            listOf("long-span"),
            index.blockingTrainIds(listOf(interval(firstRail, 90.0, 91.0)), "requesting")
        )
    }

    @Test
    fun `closed interval contact within curve tolerance is occupied`() {
        val index = RailwayOccupancyIndex(
            observation(train("blocking", span(firstRail, 10.0, 12.0)))
        )

        assertEquals(
            listOf("blocking"),
            index.blockingTrainIds(
                listOf(interval(firstRail, 12.0 + Curve.TOLERANCE, 13.0)),
                "requesting"
            )
        )
        assertEquals(
            emptyList<String>(),
            index.blockingTrainIds(
                listOf(interval(firstRail, 12.0 + Curve.TOLERANCE * 2.0, 13.0)),
                "requesting"
            )
        )
    }

    private fun observation(vararg trains: RailwayTrainObservation): RailwayObservation =
        RailwayObservation.create(stamp, trains.asList())

    private fun train(
        id: String,
        vararg spans: SweptRailSpanSnapshot
    ): RailwayTrainObservation {
        val position = RailPositionSnapshot(firstRail, ViewDirection.AB, 0.0)
        return RailwayTrainObservation.create(
            trainId = id,
            trainName = id,
            frameRevision = 1L,
            speedMetersPerSecond = 0.0,
            derailed = false,
            reversed = false,
            head = position,
            tail = position,
            sweptSpans = spans.asList()
        )
    }

    private fun span(
        railId: UUID,
        startAbMeters: Double,
        endAbMeters: Double
    ) = SweptRailSpanSnapshot(railId, startAbMeters, endAbMeters)

    private fun interval(
        railId: UUID,
        startAbMeters: Double,
        endAbMeters: Double
    ) = RailIntervalResource(railId, startAbMeters, endAbMeters)
}
