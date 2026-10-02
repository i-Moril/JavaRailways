package work.art1st.javarailways.simplesignals.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID

class ModelPlacementTest {
    private val position = RailPositionSnapshot(UUID.randomUUID(), ViewDirection.BA, 7.0)

    @Test
    fun `preset changes deletion and instance edits do not change other instances`() {
        val originalAdjustment = ModelPose(1.0, 2.0, 2.0, 0.0, 0.0, 0.0, 1.0)
        val presets = mutableMapOf("right" to RailPlacementPreset(originalAdjustment, true))
        val first = ModelPlacement.RailAttached(position).applyPreset(presets.getValue("right"))
        val second = ModelPlacement.RailAttached(position).applyPreset(presets.getValue("right"))
        val editedPosition = position.copy(railId = UUID.randomUUID(), facing = ViewDirection.AB, arcLengthInAbMeters = 3.0)
        val edited = first.copy(
            position = editedPosition,
            adjustment = ModelPose(-1.0, 4.0, 5.0, 1.0, 0.0, 0.0, 0.0),
            followRailTilt = false
        )
        val newAdjustment = ModelPose(6.0, 7.0, 3.0, 0.0, 1.0, 0.0, 0.0)
        presets["right"] = RailPlacementPreset(newAdjustment, false)

        assertEquals(originalAdjustment, first.adjustment)
        assertEquals(originalAdjustment, second.adjustment)
        assertEquals(true, second.followRailTilt)
        assertEquals(position, second.position)
        val reapplied = edited.applyPreset(presets.getValue("right"))
        assertEquals(newAdjustment, reapplied.adjustment)
        assertFalse(reapplied.followRailTilt)
        assertEquals(editedPosition, reapplied.position)
        assertEquals(5.0, edited.adjustment.z())

        presets.clear()
        assertEquals(originalAdjustment, second.adjustment)
        assertEquals(newAdjustment, reapplied.adjustment)
        assertEquals(editedPosition, reapplied.position)
    }

    @Test
    fun `selection resolves player tilt before model preset default before upright`() {
        val adjustment = ModelPose(1.0, 2.0, 3.0, 0.0, 0.0, 1.0, 0.0)
        val cases = listOf(
            Triple(null, null, false), Triple(null, false, false), Triple(null, true, true),
            Triple(false, null, false), Triple(false, false, false), Triple(false, true, false),
            Triple(true, null, true), Triple(true, false, true), Triple(true, true, true)
        )
        for ((playerChoice, modelDefault, expected) in cases) {
            // Reapplication resolves the current selection, not a stale prior effective value.
            val placement = ModelPlacement.RailAttached(position, followRailTilt = !expected)
                .applyPreset(RailPlacementPreset(adjustment, modelDefault), playerChoice)
            assertEquals(expected, placement.followRailTilt, "player=$playerChoice default=$modelDefault")
            assertEquals(adjustment, placement.adjustment)
            assertEquals(position, placement.position)
        }
    }

    @Test
    fun `placement inputs reuse finite position and unit rotation validation`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { position.copy(arcLengthInAbMeters = bad) }
            assertThrows(IllegalArgumentException::class.java) { ModelPose(bad, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0) }
            assertThrows(IllegalArgumentException::class.java) { ModelPose(0.0, bad, 0.0, 0.0, 0.0, 0.0, 1.0) }
            assertThrows(IllegalArgumentException::class.java) { ModelPose(0.0, 0.0, bad, 0.0, 0.0, 0.0, 1.0) }
            assertThrows(IllegalArgumentException::class.java) { ModelPose(0.0, 0.0, 0.0, bad, 0.0, 0.0, 1.0) }
        }
        assertThrows(IllegalArgumentException::class.java) { position.copy(arcLengthInAbMeters = -0.01) }
        assertThrows(IllegalArgumentException::class.java) { ModelPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { ModelPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 2.0) }
    }
}
