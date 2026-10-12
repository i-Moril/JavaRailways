package work.art1st.javarailways.bukkit.extension

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class WorldModelOperationsTest {
    @Test
    fun `unload terminates queued operations and rejects late completion and new entries`() {
        val scope = WorldModelOperations()
        val queued = scope.track(CompletableFuture<Unit>())
        val accepted = scope.track(CompletableFuture<Unit>())
        accepted.complete(Unit)
        scope.close()
        scope.close()
        assertTrue(queued.isCompletedExceptionally)
        assertFalse(queued.complete(Unit))
        assertFalse(accepted.isCompletedExceptionally)
        assertTrue(scope.track(CompletableFuture<Unit>()).isCompletedExceptionally)
    }
}
