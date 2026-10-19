package work.art1st.javarailways.bukkit.render.backend

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Presentation failed; [cleanup] tracks all cleanup already started before the failure escaped. */
internal class DisplayCleanupException(
    cause: Throwable,
    val cleanup: CompletionStage<Void> = CompletableFuture.failedFuture(cause)
) : RuntimeException("Display cleanup failed", cause)
