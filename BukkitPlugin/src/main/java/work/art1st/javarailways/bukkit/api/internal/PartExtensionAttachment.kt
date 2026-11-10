package work.art1st.javarailways.bukkit.api.internal

import work.art1st.javarailways.bukkit.api.model.extension.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ThreadPoolExecutor
import work.art1st.javarailways.core.api.util.DestructionSteps

/** One serial session, latest request and latest completed value. Workers never access Bukkit. */
internal class PartExtensionAttachment(
    context: PartExtensionRenderContext,
    private val executor: ThreadPoolExecutor,
    private val acquireRenderer: () -> PartExtensionRenderer?,
    retireVisuals: () -> CompletionStage<Void>,
    private val detach: (PartExtensionAttachment) -> Unit,
    private val report: (String, Throwable) -> Unit
) {
    private val lock = Any()
    private var context: PartExtensionRenderContext? = context
    private val subject = "package '${context.packageName}' part '${context.part.name}'"
    private var retireVisuals: (() -> CompletionStage<Void>)? = retireVisuals
    @Volatile var isActive = true
        private set
    private var started = false
    val canRender: Boolean get() = synchronized(lock) { isActive && !unavailable }
    private var opened = false
    private var unavailable = false
    private var session: PartExtensionRenderSession? = null
    private var pending: PartExtensionFrame? = null
    private var latest: PartExtensionRenderInput? = null
    private var task: Work? = null
    private var running: Thread? = null
    private var failedEpisode = false
    private val sessionCleanup = CompletableFuture<Void>()
    private val cleanup = CompletableFuture<Void>()

    fun start() = synchronized(lock) {
        if (!isActive || started) return@synchronized
        started = true
        if (latest == null) latest = PartExtensionRenderInput()
        enqueue()
    }

    fun request(input: PartExtensionRenderInput) = synchronized(lock) {
        if (!isActive || unavailable) return@synchronized
        latest = input
        if (started && task == null && running == null) enqueue()
    }

    /** Called under lock; execute cannot race retirement and leave canceled queue history. */
    private fun enqueue() {
        val next = Work()
        task = next
        try { executor.execute(next) }
        catch (failure: RuntimeException) {
            task = null
            latest = null
            unavailable = true
            // An owned session has not been closed merely because its task was rejected.
            // Keep its cleanup barrier pending; service shutdown ordering prevents this case.
            if (session == null) sessionCleanup.completeExceptionally(failure)
        }
    }

    private inner class Work : Runnable {
        override fun run() {
            val input = synchronized(lock) {
                if (task !== this) return
                task = null
                running = Thread.currentThread()
                latest.also { latest = null }
            }
            try {
                if (isActive && !opened) {
                    opened = true
                    val captured = context
                    val renderer = acquireRenderer()
                    session = if (captured != null && renderer != null && isActive) renderer.open(captured) else null
                    context = null
                    if (session == null) synchronized(lock) { unavailable = true; latest = null }
                }
                val owned = session
                if (isActive && owned != null && input != null && acquireRenderer() != null) {
                    val frame = owned.draw(input)
                    // Publish completed results even if a newer request arrived during this draw.
                    // Dropping every superseded completion would starve slower evaluators.
                    synchronized(lock) { if (isActive) pending = frame }
                    failedEpisode = false
                }
            } catch (failure: Throwable) {
                if (!opened || session == null) synchronized(lock) { unavailable = true; latest = null; context = null }
                if (isActive && !failedEpisode) {
                    failedEpisode = true
                    report("Extension rendering failed for $subject", failure)
                }
            } finally {
                val retire = synchronized(lock) {
                    if (isActive) {
                        running = null
                        if (latest != null && !unavailable) enqueue()
                        false
                    } else true
                }
                if (retire) {
                    // Cancellation is a request; completion tracks callback EXIT and real close.
                    Thread.interrupted()
                    closeSession()
                    synchronized(lock) { running = null }
                }
            }
        }
    }

    private fun closeSession() {
        val owned = session
        session = null
        context = null
        try { owned?.close(); sessionCleanup.complete(null) }
        catch (failure: Throwable) { sessionCleanup.completeExceptionally(failure) }
    }

    fun poll(): PartExtensionFrame? = synchronized(lock) {
        if (!isActive) null else pending.also { pending = null }
    }

    fun closeAsync(): CompletionStage<Void> {
        val retire = synchronized(lock) {
            if (!isActive) return cleanup
            isActive = false
            pending = null
            latest = null
            context = null
            task?.let { executor.remove(it) }
            task = null
            if (running != null) running!!.interrupt()
            else if (session != null) enqueue() // Work closes it on a worker; shutdown() drains this task.
            else sessionCleanup.complete(null)
            retireVisuals.also { retireVisuals = null }
        }
        detach(this)
        val steps = DestructionSteps()
        steps.await { sessionCleanup }
        steps.await { retire?.invoke() ?: CompletableFuture.completedFuture(null) }
        steps.finish().whenComplete { _, failure ->
            if (failure == null) cleanup.complete(null) else cleanup.completeExceptionally(failure)
        }
        return cleanup
    }
}
