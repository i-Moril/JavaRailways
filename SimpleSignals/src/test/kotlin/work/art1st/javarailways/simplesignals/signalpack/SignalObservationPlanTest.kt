package work.art1st.javarailways.simplesignals.signalpack

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.simplesignals.runtime.RequiredActiveContinuation
import work.art1st.javarailways.simplesignals.runtime.SectionKind
import work.art1st.javarailways.simplesignals.runtime.SectionRef
import java.util.UUID

class SignalObservationPlanTest {
    @Test
    fun `straight short sections remain visible until their own branch reaches the distance limit`() {
        val a = state("a")
        val b = state("b")
        val c = state("c")
        val d = state("d")
        val graph = graph(
            node(a, 100.0, "test:signal-a", next(b)),
            node(b, 20.0, "test:signal-b", next(c)),
            node(c, 20.0, "test:signal-c", next(d)),
            node(d, 20.0, "test:signal-d")
        )

        val plan = SignalObservationPlanCompiler.compile(graph, a, 145.0)

        val bNode = child(plan.root, b)
        val cNode = child(bNode, c)
        val dNode = child(cNode, d)
        assertEquals(0.0, plan.root.distanceMeters)
        assertEquals(100.0, bNode.distanceMeters)
        assertEquals(120.0, cNode.distanceMeters)
        assertEquals(140.0, dNode.distanceMeters)
        assertEquals(SignalObservationEndReason.DISTANCE_LIMIT, dNode.termination?.reason)
        assertEquals(145.0, dNode.termination?.distanceMeters)
        assertEquals(setOf("test:signal-b", "test:signal-c", "test:signal-d"), plan.downstreamSignalIds)
    }

    @Test
    fun `turnout branches accumulate distance independently and retain every candidate`() {
        val root = state("root")
        val long = state("long")
        val longEnd = state("long-end")
        val short = state("short")
        val shortEnd = state("short-end")
        val turnout = endpoint(1, RailEndpoint.B)
        val toLong = endpoint(2, RailEndpoint.A)
        val toShort = endpoint(3, RailEndpoint.A)
        val graph = graph(
            node(
                root,
                10.0,
                continuations = listOf(
                    next(long, turnout(turnout, toLong)),
                    next(short, turnout(turnout, toShort))
                )
            ),
            node(long, 30.0, continuations = listOf(next(longEnd))),
            node(longEnd, 20.0),
            node(short, 5.0, continuations = listOf(next(shortEnd))),
            node(shortEnd, 5.0)
        )

        val plan = SignalObservationPlanCompiler.compile(graph, root, 50.0)

        assertEquals(2, plan.root.branches.size)
        val longNode = child(plan.root, long)
        val shortNode = child(plan.root, short)
        assertEquals(40.0, child(longNode, longEnd).distanceMeters)
        assertEquals(15.0, child(shortNode, shortEnd).distanceMeters)
        assertEquals(setOf(turnout), plan.watchedContinuationEntries)
        assertEquals(
            setOf(toLong, toShort),
            plan.root.branches.map { it.requirements.requiredContinuations.single().selectedExit }.toSet()
        )
    }

    @Test
    fun `protection entry exposes every authored route as the same section tree shape`() {
        val root = state("root")
        val entry = "test:entry"
        val routeA = protectionState("route-a", entry)
        val routeB = protectionState("route-b", entry)
        val graph = graph(
            node(
                root,
                10.0,
                continuations = listOf(
                    next(routeA, protection(entry, "test:route-a")),
                    next(routeB, protection(entry, "test:route-b"))
                )
            ),
            node(routeA, 25.0, "test:signal-route-a"),
            node(routeB, 30.0, "test:signal-route-b")
        )

        val plan = SignalObservationPlanCompiler.compile(graph, root, 100.0)

        assertEquals(setOf(routeA, routeB), plan.root.branches.mapNotNull { it.target?.state }.toSet())
        assertEquals(
            setOf(ProtectionRouteChoice(entry, "test:route-a"), ProtectionRouteChoice(entry, "test:route-b")),
            plan.protectionRoutes
        )
        assertTrue(plan.observedSections.contains(SectionRef(SectionKind.PROTECTION_ROUTE, "test:route-a")))
        assertTrue(plan.root.branches.all { it.requirements.protectionRoute != null })
        assertTrue(plan.root.branches.all { it.termination == null })
    }

    @Test
    fun `cycle ends only the repeated branch and removes recursive signal dependencies`() {
        val a = state("a")
        val b = state("b")
        val c = state("c")
        val sibling = state("sibling")
        val graph = graph(
            node(a, 10.0, "test:signal-a", next(b)),
            node(b, 10.0, "test:signal-b", listOf(next(c), next(sibling))),
            node(c, 10.0, "test:signal-c", next(a)),
            node(sibling, 10.0, "test:signal-sibling")
        )

        val plan = SignalObservationPlanCompiler.compile(graph, a, 100.0)

        val bNode = child(plan.root, b)
        val cNode = child(bNode, c)
        val repeatedA = child(cNode, a)
        assertEquals(SignalObservationEndReason.CYCLE, repeatedA.termination?.reason)
        assertEquals(30.0, repeatedA.termination?.distanceMeters)
        assertEquals(0.0, repeatedA.termination?.cycleStartDistanceMeters)
        assertEquals(30.0, repeatedA.termination?.cycleLengthMeters)
        assertFalse(plan.root.exposesDownstreamSignal)
        assertFalse(bNode.exposesDownstreamSignal)
        assertFalse(cNode.exposesDownstreamSignal)
        assertFalse(repeatedA.exposesDownstreamSignal)
        assertEquals(setOf("test:signal-sibling"), plan.downstreamSignalIds)

        val siblingNode = child(bNode, sibling)
        assertTrue(siblingNode.exposesDownstreamSignal)
        assertEquals(SignalObservationEndReason.TRACK_END, siblingNode.termination?.reason)
    }

    @Test
    fun `cycle identity distinguishes direction entry and protection route context`() {
        val root = protectionState("shared", "test:entry-a", "test:route-a")
        val otherContext = protectionState("shared", "test:entry-a", "test:route-b")
        val otherEntry = SignalObservationScanState(
            root.section,
            ViewDirection.BA,
            "test:entry-b",
            "test:route-a"
        )
        val graph = graph(
            node(root, 5.0, continuations = listOf(next(otherContext))),
            node(otherContext, 5.0, continuations = listOf(next(otherEntry))),
            node(otherEntry, 5.0, continuations = listOf(next(root)))
        )

        val plan = SignalObservationPlanCompiler.compile(graph, root, 100.0)

        val second = child(plan.root, otherContext)
        val third = child(second, otherEntry)
        val repeated = child(third, root)
        assertNull(second.termination)
        assertNull(third.termination)
        assertEquals(SignalObservationEndReason.CYCLE, repeated.termination?.reason)
        assertEquals(15.0, repeated.termination?.cycleLengthMeters)
    }

    @Test
    fun `a physical merge is exposed as distinct path occurrences`() {
        val root = state("root")
        val left = state("left")
        val right = state("right")
        val merged = state("merged")
        val graph = graph(
            node(root, 5.0, continuations = listOf(next(left), next(right))),
            node(left, 5.0, continuations = listOf(next(merged))),
            node(right, 10.0, continuations = listOf(next(merged))),
            node(merged, 5.0, signal = "test:merged")
        )

        val plan = SignalObservationPlanCompiler.compile(graph, root, 100.0)

        val first = child(child(plan.root, left), merged)
        val second = child(child(plan.root, right), merged)
        assertNotSame(first, second)
        assertEquals(10.0, first.distanceMeters)
        assertEquals(15.0, second.distanceMeters)
    }

    @Test
    fun `unresolved protection and unavailable topology keep their exact terminal reasons`() {
        val unresolved = protectionState("unresolved", "test:entry")
        val unavailable = state("unavailable")
        val graph = graph(
            node(unresolved, 20.0, terminal = SignalObservationEndReason.ROUTE_UNRESOLVED),
            node(unavailable, 20.0, terminal = SignalObservationEndReason.TOPOLOGY_UNAVAILABLE)
        )

        val unresolvedPlan = SignalObservationPlanCompiler.compile(graph, unresolved, 100.0)
        val unavailablePlan = SignalObservationPlanCompiler.compile(graph, unavailable, 100.0)

        assertEquals(SignalObservationEndReason.ROUTE_UNRESOLVED, unresolvedPlan.root.termination?.reason)
        assertEquals(0.0, unresolvedPlan.root.termination?.distanceMeters)
        assertEquals(SignalObservationEndReason.TOPOLOGY_UNAVAILABLE, unavailablePlan.root.termination?.reason)
        assertEquals(0.0, unavailablePlan.root.termination?.distanceMeters)
    }

    @Test
    fun `invalid graph and distance are rejected before a partial plan exists`() {
        val root = state("root")
        val missing = state("missing")
        assertThrows(IllegalArgumentException::class.java) {
            graph(node(root, 10.0, continuations = listOf(next(missing))))
        }
        val graph = graph(node(root, 10.0))
        assertThrows(IllegalArgumentException::class.java) {
            SignalObservationPlanCompiler.compile(graph, root, 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalObservationPlanCompiler.compile(graph, missing, 10.0)
        }
    }

    @Test
    fun `protection route identity and selector metadata must describe the same target`() {
        val root = state("root")
        val route = protectionState("route", "test:entry")
        assertThrows(IllegalArgumentException::class.java) {
            SignalObservationScanState(
                SectionRef(SectionKind.PROTECTION_ROUTE, "not-namespaced"),
                ViewDirection.AB,
                "test:entry",
                "test:route"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            graph(
                node(
                    root,
                    10.0,
                    continuations = listOf(next(route, protection("test:entry", "test:other-route")))
                ),
                node(route, 10.0)
            )
        }
    }

    @Test
    fun `prepared graph and compiled plan detach mutable input collections`() {
        val root = state("root")
        val target = state("target")
        val turnoutEntry = endpoint(1, RailEndpoint.B)
        val selectedExit = endpoint(2, RailEndpoint.A)
        val mutableRequirements = arrayListOf(RequiredActiveContinuation(turnoutEntry, selectedExit))
        val requirements = SignalObservationBranchRequirements(mutableRequirements)
        val mutableContinuations = arrayListOf(next(target, requirements))
        val rootNode = SignalObservationGraphNode(root, 10.0, continuations = mutableContinuations)
        mutableRequirements.clear()
        mutableContinuations.clear()

        val plan = SignalObservationPlanCompiler.compile(
            graph(rootNode, node(target, 10.0)),
            root,
            100.0
        )

        assertEquals(listOf(RequiredActiveContinuation(turnoutEntry, selectedExit)), requirements.requiredContinuations)
        assertEquals(1, plan.root.branches.size)
        assertEquals(setOf(turnoutEntry), plan.watchedContinuationEntries)
        assertThrows(UnsupportedOperationException::class.java) {
            (plan.root.branches as MutableList<SignalObservationPlanBranch>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (plan.observedSections as MutableSet<SectionRef>).clear()
        }
    }

    private fun graph(vararg nodes: SignalObservationGraphNode) = SignalObservationGraph(nodes.asList())

    private fun node(
        state: SignalObservationScanState,
        length: Double,
        signal: String? = null,
        continuation: SignalObservationContinuation
    ) = node(state, length, signal, listOf(continuation))

    private fun node(
        state: SignalObservationScanState,
        length: Double,
        signal: String? = null,
        continuations: List<SignalObservationContinuation> = emptyList(),
        terminal: SignalObservationEndReason? = null
    ) = SignalObservationGraphNode(state, length, signal, continuations, terminal)

    private fun state(id: String) = SignalObservationScanState(
        SectionRef(SectionKind.FIXED_BLOCK, "test:$id"),
        ViewDirection.AB,
        "test:${id}-entry"
    )

    private fun protectionState(id: String, entry: String, route: String = "test:$id") =
        SignalObservationScanState(
            SectionRef(SectionKind.PROTECTION_ROUTE, "test:$id"),
            ViewDirection.AB,
            entry,
            route
        )

    private fun next(
        target: SignalObservationScanState,
        requirements: SignalObservationBranchRequirements = SignalObservationBranchRequirements()
    ) = SignalObservationContinuation(target, requirements = requirements)

    private fun turnout(entry: RailEndpointRef, exit: RailEndpointRef) =
        SignalObservationBranchRequirements(listOf(RequiredActiveContinuation(entry, exit)))

    private fun protection(entry: String, route: String) =
        SignalObservationBranchRequirements(protectionRoute = ProtectionRouteChoice(entry, route))

    private fun child(node: SignalObservationPlanNode, state: SignalObservationScanState): SignalObservationPlanNode =
        node.branches.single { it.target?.state == state }.target!!

    private fun endpoint(number: Int, endpoint: RailEndpoint) = RailEndpointRef(
        UUID(0, number.toLong()),
        endpoint
    )
}
