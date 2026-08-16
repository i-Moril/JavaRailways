package work.art1st.javarailways.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.curve.RailCurveTemplateType
import work.art1st.javarailways.basic.curve.StandardTurnout
import java.util.UUID

class WandStateCodecTest {
    @Test
    fun `rail wand state round trips all settings and selections`() {
        val state = WandPlayerState(
            mode = WandMode.SPLIT,
            selectedAnchors = mutableListOf("A1", "A2"),
            selectedRails = mutableListOf(UUID.randomUUID()),
            pendingDeleteAnchor = "A3",
            pendingDeleteRail = UUID.randomUUID(),
            pendingSplitRail = UUID.randomUUID(),
            pendingSplitArcLength = 42.125,
            pendingDecorationDeleteRail = UUID.randomUUID(),
            parallelCount = 4,
            parallelSpacing = 6.5,
            snapToAngle = false,
            snapToBlock = false,
            openConfigAfterCreate = true,
            autoDecorate = false,
            decorationModel = "metro-track",
            curveRadius = 450.0,
            curveTemplate = RailCurveTemplateType.FIVE_SEGMENT,
            turnoutStandard = StandardTurnout.N18,
            designSpeedKmH = 160.0,
            curveSpeedLimitKmH = 95.0,
            allowableCantDeficiencyMm = 95.0,
            automaticSuperelevation = false,
            maxSuperelevationMm = 180.0
        )

        val restored = requireNotNull(RailWandStateCodec.decode(RailWandStateCodec.encode(state)))

        assertEquals(state, restored)
    }

    @Test
    fun `state persisted before curve slider defaults to automatic`() {
        val current = RailWandStateCodec.encode(WandPlayerState())
        val old = current.replace(",\"curveSpeedLimitKmH\":0.0}", "}")

        val restored = requireNotNull(RailWandStateCodec.decode(old))

        assertEquals(0.0, restored.curveSpeedLimitKmH)
    }

    @Test
    fun `grade smoothing mode round trips without a new state schema`() {
        val state = WandPlayerState(
            mode = WandMode.GRADE_SMOOTH,
            selectedAnchors = mutableListOf("rail-a:A")
        )

        val restored = requireNotNull(RailWandStateCodec.decode(RailWandStateCodec.encode(state)))

        assertEquals(WandMode.GRADE_SMOOTH, restored.mode)
        assertEquals(listOf("rail-a:A"), restored.selectedAnchors)
    }

    @Test
    fun `rail wand decoder rejects removed fields`() {
        assertNull(RailWandStateCodec.decode(
            """{"mode":"REMOVED","parallelCount":99,"parallelSpacing":-4.0,"straightUsesChordDirection":true,"designSpeedKmH":9999.0}"""
        ))
    }

    @Test
    fun `rail object wand state round trips`() {
        val state = RailObjectWandState(
            mode = RailObjectWandMode.DIRECTION_RESTRICTION,
            pendingDeleteWayPoint = "station-a",
            defaultWayPointType = work.art1st.javarailways.basic.api.destination.DestinationRoleType.STATION_BERTH
        )

        val restored = requireNotNull(RailObjectWandStateCodec.decode(RailObjectWandStateCodec.encode(state)))

        assertEquals(state, restored)
    }
}
