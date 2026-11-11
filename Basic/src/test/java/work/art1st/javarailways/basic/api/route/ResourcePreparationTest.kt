package work.art1st.javarailways.basic.api.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ResourcePreparationTest {
    @Test
    fun `unsafe state requires a useful diagnostic`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResourcePreparationSnapshot(ResourcePreparationState.UNSAFE)
        }
        assertEquals(
            "device position is unknown",
            ResourcePreparationSnapshot(
                ResourcePreparationState.UNSAFE,
                "device position is unknown"
            ).detail
        )
    }
}
