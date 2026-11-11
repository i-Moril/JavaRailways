package work.art1st.javarailways.bukkit.api.diagnostics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorldIssueServiceTest {
    @Test fun `source replacement preserves surviving identity and isolates worlds and producers`() {
        val service = WorldIssueService()
        val world = UUID.randomUUID()
        val otherWorld = UUID.randomUUID()
        val input = WorldIssueInput(WorldIssueKind.SIGNAL, "test:signal", "rule", "failed")
        service.report(world, "rules", input)
        val first = service.snapshot(world).single()
        service.report(world, "models", input)
        service.report(otherWorld, "rules", input)
        service.replace(world, "rules", listOf(input.copy(message = "failed again")))
        val updated = service.snapshot(world).single { it.source == "rules" }
        assertEquals(first.firstSeen, updated.firstSeen)
        assertFalse(updated.lastSeen.isBefore(first.lastSeen))
        assertEquals("failed again", updated.message)
        service.replace(world, "rules", emptyList())
        assertEquals("models", service.snapshot(world).single().source)
        assertEquals(1, service.snapshot(otherWorld).size)
        service.clearSource("rules")
        assertTrue(service.snapshot(otherWorld).isEmpty())
        service.clearWorld(world)
        assertTrue(service.snapshot(world).isEmpty())
        assertEquals("failed", first.message)
    }

    @Test fun `concurrent reports deduplicate without losing distinct objects`() {
        val service = WorldIssueService()
        val world = UUID.randomUUID()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 200).map { index -> workers.submit {
                service.report(world, "producer", WorldIssueInput(WorldIssueKind.TRAIN,
                    "train-${index % 20}", "missing", "Missing configuration"))
            } }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(20, service.snapshot(world).size)
        } finally { workers.shutdownNow() }
    }

    @Test fun `resolve uses object kind and snapshots cannot be mutated`() {
        val service = WorldIssueService()
        val world = UUID.randomUUID()
        val signal = WorldIssueInput(WorldIssueKind.SIGNAL, "test:object", "failed", "Failed")
        service.report(world, "producer", signal)
        service.report(world, "producer", signal.copy(kind = WorldIssueKind.BLOCK))
        val snapshot = service.snapshot(world)
        assertThrows(UnsupportedOperationException::class.java) { (snapshot as MutableList<*>).clear() }
        service.resolve(world, "producer", WorldIssueKind.SIGNAL, "test:object", "failed")
        assertEquals(WorldIssueKind.BLOCK, service.snapshot(world).single().kind)
        assertEquals(2, snapshot.size)
    }

    @Test fun `invalid replacement cannot delete existing diagnostics`() {
        val service = WorldIssueService()
        val world = UUID.randomUUID()
        service.report(world, "producer", WorldIssueInput(WorldIssueKind.BLOCK, "test:block", "invalid", "Invalid block"))
        assertThrows(IllegalArgumentException::class.java) {
            service.replace(world, "producer", listOf(WorldIssueInput(WorldIssueKind.BLOCK, "", "invalid", "bad")))
        }
        assertEquals(1, service.snapshot(world).size)
    }
}
