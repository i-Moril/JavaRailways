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
    private val report: (String, Throwable) -> Unit,
    private val refreshAudience: (java.util.UUID) -> Unit = {},
    private val refreshRendering: () -> Unit = {}
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
    @Volatile private var session: PartExtensionRenderSession? = null
    @Volatile var audiencePolicy: PartExtensionAudiencePolicy? = null
        private set
    private var pending: PartExtensionFrame? = null
    private data class Request(val input: PartExtensionRenderInput, val generation: Long, val draw: Boolean = true)
    private var contextGeneration = 0L
    private var latest: Request? = null
    private var task: Work? = null
    private var running: Thread? = null
    private var failedEpisode = false
    private val sessionCleanup = CompletableFuture<Void>()
    private val cleanup = CompletableFuture<Void>()

    fun start(initialDraw: Boolean = true) = synchronized(lock) {
        if (!isActive || started) return@synchronized
        started = true
        if (initialDraw && latest == null) latest = Request(PartExtensionRenderInput(), contextGeneration)
        enqueue()
    }

    fun refreshAudience(viewerId: java.util.UUID) {
        if (isActive) refreshAudience.invoke(viewerId)
    }

    fun refreshRendering() {
        if (isActive) refreshRendering.invoke()
    }

    fun renderRevision(): Long? {
        if (!isActive) return null
        val owned = session ?: return null
        return owned.renderRevision.also { require(it >= 0L) { "Extension render revision must be nonnegative" } }
    }

    fun request(input: PartExtensionRenderInput, draw: Boolean = true) = synchronized(lock) {
        if (!isActive || unavailable) return@synchronized
        latest = Request(input, contextGeneration, draw)
        if (started && task == null && running == null) enqueue()
    }

    /** Retain the private session, but reject every queued/completed result from the old data identity. */
    fun invalidateContext() = synchronized(lock) {
        if (!isActive) return@synchronized
        contextGeneration = Math.incrementExact(contextGeneration)
        pending = null
        latest = null
        failedEpisode = false
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
                    val policy = session?.audiencePolicy
                    synchronized(lock) { if (isActive) audiencePolicy = policy }
                    if (session == null) synchronized(lock) { unavailable = true; latest = null }
                }
                val owned = session
                if (isActive && owned != null && input != null && acquireRenderer() != null) {
                    if (synchronized(lock) { input.generation != contextGeneration }) return
                    // Routing/binding remains current even while expensive drawing is paused.
                    owned.update(input.input)
                    if (!input.draw) return
                    val frame = owned.draw(input.input)
                    // Publish completed results even if a newer request arrived during this draw.
                    // Dropping every superseded completion would starve slower evaluators.
                    synchronized(lock) {
                        if (isActive && input.generation == contextGeneration) {
                            pending = frame
                            failedEpisode = false
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (!opened || session == null) synchronized(lock) { unavailable = true; latest = null; context = null }
                val reportFailure = synchronized(lock) {
                    if (isActive && (input == null || input.generation == contextGeneration) && !failedEpisode) {
                        failedEpisode = true
                        true
                    } else false
                }
                if (reportFailure) report("Extension rendering failed for $subject", failure)
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
        audiencePolicy = null
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
            audiencePolicy = null
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
