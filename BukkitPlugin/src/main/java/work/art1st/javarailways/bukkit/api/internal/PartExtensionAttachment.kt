package work.art1st.javarailways.bukkit.api.internal

import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionFrame
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderContext
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRenderer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor

/** One immutable preparation result and one cancellable job; no Bukkit access from completion. */
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
    private var retireVisuals: (() -> CompletionStage<Void>)? = retireVisuals
    @Volatile var isActive = true
        private set
    private var started = false
    private var pending: PartExtensionFrame? = null
    private var job: FutureTask<Unit>? = null
    private val cleanup = CompletableFuture<Void>()

    fun start() {
        val task = synchronized(lock) {
            if (!isActive || started) return
            started = true
            val captured = context!!
            FutureTask<Unit> {
                try {
                    val renderer = acquireRenderer() ?: return@FutureTask Unit
                    if (!isActive) return@FutureTask Unit
                    val frame = renderer.prepare(captured)
                    // The DTO has no addon/runtime handles. Never schedule platform work here.
                    val stillCurrent = acquireRenderer() === renderer
                    synchronized(lock) { if (isActive && stillCurrent) pending = frame }
                } catch (failure: Throwable) {
                    if (isActive) report("Extension preparation failed for package '${captured.packageName}' part '${captured.part.name}'", failure)
                    if (failure !is Exception && failure !is LinkageError) throw failure
                } finally { synchronized(lock) { context = null; job = null } }
                Unit
            }.also { job = it }
        }
        try {
            executor.execute(task)
            // Close may have canceled between task capture and enqueue. Do not retain canceled history.
            synchronized(lock) { if (!isActive) { task.cancel(true); executor.remove(task) } }
        } catch (failure: RuntimeException) {
            synchronized(lock) { context = null; job = null }
            if (isActive) report("Extension preparation was rejected", failure)
        }
    }

    fun poll(): PartExtensionFrame? = synchronized(lock) {
        if (!isActive) null else pending.also { pending = null }
    }

    fun closeAsync(): CompletionStage<Void> {
        val retire = synchronized(lock) {
            if (!isActive) return cleanup
            isActive = false
            pending = null
            context = null
            job?.let { it.cancel(true); executor.remove(it) }
            job = null
            retireVisuals.also { retireVisuals = null }
        }
        detach(this)
        try {
            (retire?.invoke() ?: CompletableFuture.completedFuture(null)).whenComplete { _, failure ->
                if (failure == null) cleanup.complete(null) else cleanup.completeExceptionally(failure)
            }
        } catch (failure: Throwable) { cleanup.completeExceptionally(failure) }
        return cleanup
    }
}
