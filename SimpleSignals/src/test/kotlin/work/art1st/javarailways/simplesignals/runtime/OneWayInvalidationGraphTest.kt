package work.art1st.javarailways.simplesignals.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class OneWayInvalidationGraphTest {
    @Test
    fun `definition reachability includes providers and every consumer without output pruning`() {
        val graph = OneWayInvalidationGraph.compile(listOf("b", "a", "before-a", "other"), listOf(
            InvalidationDependency("b", "a"), InvalidationDependency("a", "before-a")))
        assertEquals(setOf("b", "a", "before-a"), graph.reachableConsumers(listOf("b", "a")))
    }

    @Test
    fun `unchanged output stops propagation before the next upstream consumer`() {
        val graph = OneWayInvalidationGraph.compile(
            listOf("block-b", "signal-a", "signal-before-a"),
            listOf(
                InvalidationDependency("block-b", "signal-a"),
                InvalidationDependency("signal-a", "signal-before-a")
            )
        )
        val callbacks = arrayListOf<String>()

        graph.propagate(listOf("block-b")) { node ->
            callbacks += node
            false
        }

        assertEquals(listOf("signal-a"), callbacks)
    }

    @Test
    fun `shared consumer runs once after all affected predecessors`() {
        val graph = OneWayInvalidationGraph.compile(
            listOf("block-b", "block-c", "signal-a", "signal-before-a"),
            listOf(
                InvalidationDependency("block-b", "signal-a"),
                InvalidationDependency("block-c", "signal-a"),
                InvalidationDependency("signal-a", "signal-before-a")
            )
        )
        val callbacks = arrayListOf<String>()

        graph.propagate(listOf("block-c", "block-b")) { node ->
            callbacks += node
            true
        }

        assertEquals(listOf("signal-a", "signal-before-a"), callbacks)
    }

    @Test
    fun `unaffected nodes remain idle`() {
        val graph = OneWayInvalidationGraph.compile(
            listOf("block-a", "block-b", "signal-a", "signal-b"),
            listOf(
                InvalidationDependency("block-a", "signal-a"),
                InvalidationDependency("block-b", "signal-b")
            )
        )
        val callbacks = arrayListOf<String>()

        graph.propagate(listOf("block-b")) { node ->
            callbacks += node
            true
        }

        assertEquals(listOf("signal-b"), callbacks)
    }

    @Test
    fun `compile rejects reverse feedback cycle`() {
        assertThrows(IllegalArgumentException::class.java) {
            OneWayInvalidationGraph.compile(
                listOf("signal-a", "signal-b"),
                listOf(
                    InvalidationDependency("signal-a", "signal-b"),
                    InvalidationDependency("signal-b", "signal-a")
                )
            )
        }
    }

    @Test
    fun `wave rejects a provider outside the compiled definition`() {
        val graph = OneWayInvalidationGraph.compile(
            listOf("block-a", "signal-a"),
            listOf(InvalidationDependency("block-a", "signal-a"))
        )

        assertThrows(IllegalArgumentException::class.java) {
            graph.propagate(listOf("missing:block")) { true }
        }
    }
}
