package work.art1st.javarailways.bukkit.logic

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PartRenderSamplingClockTest {
    @Test fun `operational changes wake within same clock bucket and skipped buckets do not replay history`() {
        val clock = PartRenderSamplingClock()
        assertTrue(clock.sample(1L, 0L))
        assertFalse(clock.sample(2L, 0L))
        assertTrue(clock.sample(3L, 1L), "stationary SET or reset must publish without waiting for tick10")
        assertFalse(clock.sample(4L, 1L))
        assertTrue(clock.sample(10L, 1L))
        assertTrue(clock.sample(87L, 1L), "latest world wake samples once, without replaying missed buckets")
        assertFalse(clock.sample(88L, 1L))
        assertTrue(PartRenderSamplingClock().sample(88L, 1L), "a replacement world owns its own clock")
    }
}
