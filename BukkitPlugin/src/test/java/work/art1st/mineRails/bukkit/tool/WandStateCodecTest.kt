package work.art1st.mineRails.bukkit.tool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.curve.RailCurveTemplateType
import work.art1st.mineRails.basic.curve.StandardTurnout
import java.util.UUID

class WandStateCodecTest {
    @Test
    fun `rail wand state round trips all settings and selections`() {
        val state = WandPlayerState(
            mode = WandMode.TURNOUT,
            selectedAnchors = mutableListOf("A1", "A2"),
            selectedRails = mutableListOf(UUID.randomUUID()),
            pendingDeleteAnchor = "A3",
            pendingDeleteRail = UUID.randomUUID(),
            parallelCount = 4,
            parallelSpacing = 6.5,
            snapToAngle = false,
            snapToBlock = false,
            openConfigAfterCreate = true,
            autoDecorate = false,
            curveRadius = 450.0,
            curveTemplate = RailCurveTemplateType.FIVE_SEGMENT,
            turnoutStandard = StandardTurnout.N18,
            designSpeedKmH = 160.0,
            allowableCantDeficiencyMm = 95.0,
            automaticSuperelevation = false,
            maxSuperelevationMm = 180.0
        )

        val restored = requireNotNull(RailWandStateCodec.decode(RailWandStateCodec.encode(state)))

        assertEquals(state, restored)
    }

    @Test
    fun `rail wand decoder clamps invalid persisted values`() {
        val restored = requireNotNull(RailWandStateCodec.decode(
            """{"mode":"REMOVED","parallelCount":99,"parallelSpacing":-4.0,"straightUsesChordDirection":true,"designSpeedKmH":9999.0}"""
        ))

        assertEquals(WandMode.CREATE, restored.mode)
        assertEquals(10, restored.parallelCount)
        assertEquals(1.0, restored.parallelSpacing)
        assertEquals(500.0, restored.designSpeedKmH)
    }

    @Test
    fun `rail object wand state round trips`() {
        val state = RailObjectWandState(
            mode = RailObjectWandMode.DIRECTION_RESTRICTION,
            pendingDeleteWayPoint = "station-a"
        )

        val restored = requireNotNull(RailObjectWandStateCodec.decode(RailObjectWandStateCodec.encode(state)))

        assertEquals(state, restored)
    }
}
