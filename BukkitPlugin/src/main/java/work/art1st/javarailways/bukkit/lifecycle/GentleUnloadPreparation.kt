package work.art1st.javarailways.bukkit.lifecycle

import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger

/** PlugManX has a synchronous veto, so preparation must happen after that request returns. */
internal class GentleUnloadPreparation(
    private val scheduleGlobal: (() -> Unit) -> Unit,
    private val prepare: () -> CompletableFuture<Unit>,
    private val logger: Logger
) {
    private enum class State { IDLE, PREPARING, READY, FAILED }
    private var state = State.IDLE

    @Synchronized
    fun request(): Boolean {
        when (state) {
            State.READY -> return true
            State.FAILED -> logger.warning("JavaRailways unload refused: preparation failed; inspect the errors and restart the server.")
            State.PREPARING -> logger.info("JavaRailways unload preparation is still running; retry after the ready message.")
            State.IDLE -> {
                state = State.PREPARING
                logger.info("JavaRailways unload deferred: preparing Folia region cleanup. Retry the original command after the ready message.")
                try {
                    scheduleGlobal {
                        synchronized(this) {
                            if (state != State.PREPARING) return@scheduleGlobal
                        }
                        try {
                            prepare().whenComplete { _, failure -> complete(failure) }
                        } catch (failure: Throwable) {
                            complete(failure)
                        }
                    }
                } catch (failure: Throwable) {
                    complete(failure)
                }
            }
        }
        return false
    }

    @Synchronized
    private fun complete(failure: Throwable?) {
        if (state != State.PREPARING) return
        state = if (failure == null) State.READY else State.FAILED
        if (failure == null) logger.info("JavaRailways unload preparation completed. Run the original PlugManX command again to unload/reload.")
        else logger.log(Level.SEVERE, "JavaRailways unload preparation failed; further unload requests are refused. Restart the server after inspecting the errors.", failure)
    }

    @Synchronized
    fun interrupted() {
        if (state == State.PREPARING) {
            state = State.FAILED
            logger.warning("JavaRailways was disabled before GentleUnload preparation completed; the two-request unload was bypassed.")
        }
    }
}
