package work.art1st.javarailways.simplesignals.runtime

import java.util.PriorityQueue

/** One directed provider-to-consumer dependency compiled outside the signal update path. */
internal data class InvalidationDependency<N : Comparable<N>>(
    val provider: N,
    val consumer: N
) {
    init {
        require(provider != consumer) { "an invalidation dependency cannot point to itself" }
    }
}

/**
 * Precompiled one-way invalidation graph for plugin-local signal rules.
 *
 * Runtime updates begin with providers whose externally owned state already changed. Only their
 * consumers are recomputed. A consumer is forwarded as a changed provider only when [propagate]'s
 * callback reports a different output, so an unchanged result terminates that branch. The
 * topological order guarantees that a node with several affected providers is recomputed once,
 * after every affected predecessor that can change during the same wave.
 *
 * Construction rejects cycles instead of introducing a fixed-point solver. It is a low-frequency
 * definition-compilation operation; [propagate] visits only nodes and edges reached by this wave.
 */
internal class OneWayInvalidationGraph<N : Comparable<N>> private constructor(
    private val nodes: Set<N>,
    private val consumersByProvider: Map<N, List<N>>,
    private val topologicalIndex: Map<N, Int>
) {
    fun propagate(
        changedProviders: Collection<N>,
        recompute: (N) -> Boolean
    ) {
        require(changedProviders.all(nodes::contains)) {
            "changed providers must belong to the compiled invalidation graph"
        }
        if (changedProviders.isEmpty()) return

        val pending = PriorityQueue<N>(compareBy(topologicalIndex::getValue))
        val scheduled = hashSetOf<N>()

        fun scheduleConsumers(provider: N) {
            consumersByProvider[provider].orEmpty().forEach { consumer ->
                if (scheduled.add(consumer)) pending += consumer
            }
        }

        changedProviders.forEach(::scheduleConsumers)
        while (pending.isNotEmpty()) {
            val node = pending.remove()
            if (recompute(node)) {
                scheduleConsumers(node)
            }
        }
    }

    companion object {
        fun <N : Comparable<N>> compile(
            nodes: Collection<N>,
            dependencies: Collection<InvalidationDependency<N>>
        ): OneWayInvalidationGraph<N> {
            require(nodes.toSet().size == nodes.size) {
                "invalidation graph nodes must be unique"
            }
            val orderedNodes = nodes.toSortedSet()
            val nodeSet = java.util.Collections.unmodifiableSet(orderedNodes)
            val uniqueDependencies = dependencies.toSet()
            require(uniqueDependencies.size == dependencies.size) {
                "invalidation dependencies must be unique"
            }
            uniqueDependencies.forEach { dependency ->
                require(dependency.provider in nodeSet && dependency.consumer in nodeSet) {
                    "invalidation dependency endpoints must belong to the compiled graph"
                }
            }

            val mutableConsumers = orderedNodes.associateWith { sortedSetOf<N>() }.toMutableMap()
            val inDegree = orderedNodes.associateWith { 0 }.toMutableMap()
            uniqueDependencies.forEach { dependency ->
                mutableConsumers.getValue(dependency.provider) += dependency.consumer
                inDegree[dependency.consumer] = inDegree.getValue(dependency.consumer) + 1
            }

            val ready = PriorityQueue<N>()
            inDegree.filterValues { it == 0 }.keys.forEach(ready::add)
            val topologicalOrder = arrayListOf<N>()
            while (ready.isNotEmpty()) {
                val provider = ready.remove()
                topologicalOrder += provider
                mutableConsumers.getValue(provider).forEach { consumer ->
                    val remaining = inDegree.getValue(consumer) - 1
                    inDegree[consumer] = remaining
                    if (remaining == 0) ready += consumer
                }
            }
            require(topologicalOrder.size == orderedNodes.size) {
                "signal invalidation dependencies must be acyclic"
            }

            val consumers = linkedMapOf<N, List<N>>()
            orderedNodes.forEach { provider ->
                consumers[provider] = java.util.List.copyOf(mutableConsumers.getValue(provider))
            }
            return OneWayInvalidationGraph(
                nodeSet,
                java.util.Collections.unmodifiableMap(consumers),
                java.util.Collections.unmodifiableMap(
                    topologicalOrder.withIndex().associate { (index, node) -> node to index }
                )
            )
        }
    }
}
