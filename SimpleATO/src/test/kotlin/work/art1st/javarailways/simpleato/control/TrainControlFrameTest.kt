package work.art1st.javarailways.simpleato.control

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.constraint.*

class TrainControlFrameTest {
    private val owner = ConstraintOwner(ConstraintOwnerType.OPERATIONS, "test")
    private val profile = AtoSafetyProfile()
    @Test fun `operating stop geometry stays separate from the true authority boundary`() {
        val frame = controlFrame(mapOf(owner to listOf(
            StopConstraint(ConstraintKey("plugin", "mark"), -.02, operatingTarget = true),
            MovementAuthorityConstraint(ConstraintKey("plugin", "authority"), .28, .3))), profile, -.01)
        assertEquals(-.02, frame.referenceTargets[0].distanceMeters, 1e-12)
        assertEquals(-.02, frame.referenceTargets[1].distanceMeters, 1e-12)
        assertEquals(1, frame.protectionTargets.size)
        assertEquals(.28, frame.protectionTargets.single().distanceMeters)
    }
    @Test fun `passed external authority is not clamped into a safe held boundary`() {
        val frame = controlFrame(mapOf(owner to listOf(MovementAuthorityConstraint(
            ConstraintKey("external", "ma"), -.01))), profile, 0.0)
        assertEquals(-.01, frame.protectionTargets.single().distanceMeters)
    }
    @Test fun `owner scoped identities stay distinct even when key components contain separators`() {
        val frame = controlFrame(mapOf(owner to listOf(
            CurrentSpeedLimitConstraint(ConstraintKey("a/b", "c"), 2.0),
            CurrentSpeedLimitConstraint(ConstraintKey("a", "b/c"), 3.0))), profile, 0.0)
        assertEquals(2, frame.protectionTargets.map { it.id }.distinct().size)
    }
}
