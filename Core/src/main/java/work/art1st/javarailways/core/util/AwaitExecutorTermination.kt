package work.art1st.javarailways.core.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/** Cancellation is not callback completion: retain domain resources until their executor exits. */
internal fun awaitExecutorTermination(executor: ExecutorService, description: String, logger: Logger) {
    var interrupted = Thread.interrupted()
    var warningDelaySeconds = 5L
    try {
        while (true) {
            try {
                if (executor.awaitTermination(warningDelaySeconds, TimeUnit.SECONDS)) return
                logger.warning("Still waiting for $description to terminate; retaining its runtime resources")
                warningDelaySeconds = 30L
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}
