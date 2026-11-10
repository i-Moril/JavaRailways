package work.art1st.javarailways.decorations.integration.simpledisplays

import simpledisplays.api.DisplayTemplate

/** Memoized validation only. Mounted rendering will need a separate resource lease contract. */
internal class CompiledTemplateCache(
    private val compile: (String) -> DisplayTemplate,
    private val reportCleanupFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val templates = LinkedHashMap<String, DisplayTemplate>(64, 0.75f, true)
    private var closed = false

    fun validate(source: String) {
        synchronized(lock) {
            check(!closed) { "SimpleDisplays connection is retired" }
            if (templates[source] != null) return
        }
        // External compilation can block or reenter lifecycle code. Never hold the cache monitor.
        val candidate = compile(source)
        val release = mutableListOf<DisplayTemplate>()
        val retired = synchronized(lock) {
            if (closed) {
                release.add(candidate)
                true
            } else {
                if (templates[source] != null) release.add(candidate)
                else {
                    templates[source] = candidate
                    if (templates.size > CAPACITY) {
                        val oldest = templates.entries.iterator()
                        release.add(oldest.next().value)
                        oldest.remove()
                    }
                }
                false
            }
        }
        releaseAll(release)
        check(!retired) { "SimpleDisplays connection retired during template compilation" }
    }

    override fun close() {
        val release = synchronized(lock) {
            if (closed) return
            closed = true
            templates.values.toList().also { templates.clear() }
        }
        releaseAll(release)
    }

    private fun releaseAll(release: List<DisplayTemplate>) {
        var failure: Throwable? = null
        var fatal: Throwable? = null
        release.forEach { template ->
            try { template.close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
                if (error !is Exception && error !is LinkageError && fatal == null) fatal = error
            }
        }
        failure?.let(reportCleanupFailure)
        fatal?.let { throw it }
    }

    private companion object { const val CAPACITY = 64 }
}
