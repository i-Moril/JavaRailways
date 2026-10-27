package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.simpleato.api.depot.DepotTrainSelector
import work.art1st.javarailways.simpleato.api.timetable.TimetableCondition
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionCall

class TimetableProgramCursorTest {
    private data class Node(val step: TimetableStep, val yes: List<Node> = emptyList(), val no: List<Node> = emptyList())
    /** Finite timed-tree fixture until the shared model switch installs the production adapter. */
    private class Tree(private val roots: List<Node>) : TimetableProgramTraversal {
        override val rootSize get() = roots.size
        private fun node(address: TimetableStepAddress): Node {
            var node = roots[address.topLevelIndex]
            for (entry in address.branchPath) node = (if (entry.arm == TimetableBranchArm.THEN) node.yes else node.no)[entry.index]
            return node
        }
        override fun stepAt(address: TimetableStepAddress) = node(address).step
        override fun branchSize(address: TimetableStepAddress, arm: TimetableBranchArm) = node(address).let {
            if (arm == TimetableBranchArm.THEN) it.yes.size else it.no.size
        }
    }
    private fun leaf(tick: Long, action: TimetableAction = TimetableAction.WaitUntil) = Node(TimetableStep.At(tick, action))
    private fun branch(tick: Long, yes: List<Node> = emptyList(), no: List<Node> = emptyList()) = Node(
        TimetableStep.At(tick, TimetableAction.Conditional(TimetableCondition.Predicate(TimetableExtensionCall("test:choose")), emptyList())), yes, no)

    @Test fun `nested arms retain exact timed leaf and resume enclosing suffix once`() {
        val tree = Tree(listOf(branch(10, listOf(branch(20, no = listOf(leaf(30), leaf(40))), leaf(50))), leaf(60)))
        val start = TimetableProgramCursor.start(tree)
        assertEquals(10L, (start.currentStep() as TimetableStep.At).tick)
        val outer = start.enter(TimetableBranchArm.THEN)
        val nested = outer.enter(TimetableBranchArm.ELSE)
        assertEquals("step#0/then[0]/else[0]", nested.address.toString())
        assertEquals(30L, (nested.currentStep() as TimetableStep.At).tick)
        val capturedSuccessor = nested.advance()
        assertEquals("step#0/then[0]/else[1]", capturedSuccessor.address.toString())
        val afterNested = capturedSuccessor.advance()
        assertEquals("step#0/then[1]", afterNested.address.toString())
        assertEquals(50L, (afterNested.currentStep() as TimetableStep.At).tick)
        val suffix = afterNested.advance()
        assertEquals(TimetableStepAddress(1), suffix.address)
        assertEquals(60L, (suffix.currentStep() as TimetableStep.At).tick)
        assertTrue(suffix.advance().isComplete)
        // Captured continuations and original program positions cannot be advanced by another value.
        assertEquals("step#0/then[0]/else[0]", nested.address.toString())
        assertEquals(TimetableStepAddress(0), start.address)
    }

    @Test fun `empty nested arms pop to next enclosing child without evaluating another arm`() {
        val tree = Tree(listOf(branch(0, listOf(branch(1), leaf(2)), listOf(leaf(99))), leaf(3)))
        val selected = TimetableProgramCursor.start(tree).enter(TimetableBranchArm.THEN)
            .enter(TimetableBranchArm.ELSE)
        assertEquals("step#0/then[1]", selected.address.toString())
        assertEquals(2L, (selected.currentStep() as TimetableStep.At).tick)
        assertEquals(3L, (selected.advance().currentStep() as TimetableStep.At).tick)
    }

    @Test fun `addresses defensively copy path and distinguish branches`() {
        val path = arrayListOf(TimetableBranchEntry(TimetableBranchArm.THEN, 2))
        val address = TimetableStepAddress(4, path)
        path.clear()
        assertEquals("step#4/then[2]", address.toString())
        assertEquals(TimetableStepAddress(4), address.parent())
        assertNotEquals(address, TimetableStepAddress(4).child(TimetableBranchArm.ELSE, 2))
        assertThrows(UnsupportedOperationException::class.java) { (address.branchPath as MutableList).clear() }
        assertThrows(IllegalArgumentException::class.java) { TimetableStepAddress(-1) }
        assertThrows(IllegalArgumentException::class.java) { TimetableBranchEntry(TimetableBranchArm.THEN, -1) }
    }

    @Test fun `empty programs complete and leaf cannot be entered as conditional`() {
        val empty = TimetableProgramCursor.start(Tree(emptyList()))
        assertTrue(empty.isComplete)
        assertNull(empty.currentStep())
        assertThrows(IllegalStateException::class.java) { empty.advance() }
        assertThrows(IllegalStateException::class.java) { empty.enter(TimetableBranchArm.THEN) }
        val leaf = TimetableProgramCursor.start(Tree(listOf(leaf(1))))
        assertThrows(IllegalStateException::class.java) { leaf.enter(TimetableBranchArm.THEN) }
    }

    @Test fun `fixed obligations and acquisition cannot hide in either nested branch`() {
        val forbidden = listOf(TimetableAction.StoreAtDepot(DepotId("B")), TimetableAction.Couple("join"),
            TimetableAction.Split("split"), TimetableAction.TakeFromDepot(DepotTrainSelector(DepotId("A"))),
            TimetableAction.TakeFromWaypoint(listOf("start")), TimetableAction.TakeFromFormationOutput("split", "rest"),
            TimetableAction.Halt)
        val tree = Tree(listOf(branch(0, no = listOf(branch(1, yes = forbidden.map { leaf(2, it) })))))
        val errors = TimetableBranchGuard.validate(tree)
        assertEquals(forbidden.size, errors.size)
        assertEquals(forbidden.indices.map { "step#0/else[0]/then[$it]" }, errors.map { it.address.toString() })
        assertEquals(setOf("CONDITIONAL_FIXED_EVENT", "CONDITIONAL_ACQUISITION", "CONDITIONAL_TERMINATION"), errors.map { it.code }.toSet())
        assertThrows(UnsupportedOperationException::class.java) { (errors as MutableList).clear() }
    }

    @Test fun `fixed root events allowed but conditional activation rejects their bypass`() {
        val tree = Tree(listOf(leaf(0, TimetableAction.TakeFromDepot(DepotTrainSelector(DepotId("A")))),
            leaf(10, TimetableAction.Split("split")), leaf(20, TimetableAction.StoreAtDepot(DepotId("B")))))
        assertTrue(TimetableBranchGuard.validate(tree).isEmpty())
        val errors = TimetableBranchGuard.validate(tree, conditionalActivation = true)
        assertEquals(listOf(TimetableStepAddress(1), TimetableStepAddress(2)), errors.map { it.address })
    }

    @Test fun `movement waits and reversal are legal guards while nested checks are rejected`() {
        val tree = Tree(listOf(Node(TimetableStep.Check("start")), branch(0, listOf(
            leaf(0, TimetableAction.GoTo("other")), leaf(10, TimetableAction.Stop(20)),
            leaf(10, TimetableAction.Reverse), leaf(30), Node(TimetableStep.Check("hidden"))))))
        val errors = TimetableBranchGuard.validate(tree)
        assertEquals(1, errors.size)
        assertEquals("BRANCH_CHECK_NOT_ALLOWED", errors.single().code)
        assertEquals("step#1/then[4]", errors.single().address.toString())
    }

    @Test fun `depth and node ceilings reject rather than silently truncate`() {
        var node = leaf(0)
        repeat(100) { node = branch(0, listOf(node)) }
        val depth = TimetableBranchGuard.validate(Tree(listOf(node)), limits = TimetableProgramLimits(maximumBranchDepth = 8))
        assertEquals("PROGRAM_LIMIT", depth.single().code)
        assertEquals(8, depth.single().address.branchPath.size)
        val count = TimetableBranchGuard.validate(Tree(List(10) { leaf(0) }), limits = TimetableProgramLimits(maximumNodes = 3))
        assertEquals("PROGRAM_LIMIT", count.single().code)
        assertEquals(TimetableStepAddress(3), count.single().address)
    }

    @Test fun `production traversal keeps child ticks immutable and supports consumed acquisition start`() {
        val extension = TimetableAction.Extension(TimetableExtensionCall("test:action"))
        val children = arrayListOf(TimetableStep.At(20, extension), TimetableStep.At(30, extension))
        val conditional = TimetableAction.Conditional(TimetableCondition.Predicate(TimetableExtensionCall("test:choose")), children)
        children.clear()
        val program = TimetableServiceTraversal(TimetableService("duty", steps = listOf(
            TimetableStep.Check("start"), TimetableStep.At(10, conditional), TimetableStep.At(40, extension))))
        val cursor = TimetableProgramCursor.start(program, 1).enter(TimetableBranchArm.THEN)
        assertEquals(20L, (cursor.currentStep() as TimetableStep.At).tick)
        assertEquals(TimetableStepAddress(2), cursor.advance().advance().address)
        assertTrue(TimetableProgramCursor.start(program, 3).isComplete)
        assertThrows(IllegalArgumentException::class.java) { TimetableProgramCursor.start(program, 4) }
        assertTrue(TimetableProgramSchedule.validate(program, 100).isEmpty())
    }

    @Test fun `schedule validates both arms and requires suffix no earlier than either authored branch`() {
        val program = Tree(listOf(branch(10, listOf(leaf(20)), listOf(leaf(5), leaf(100))), leaf(50)))
        val errors = TimetableProgramSchedule.validate(program, 100)
        assertEquals(listOf("step#0/else[0]", "step#0/else[1]", "step#1"), errors.map { it.address.toString() })
        assertTrue(errors.all { it.code == "INVALID_STEP_TICK" })
    }

}
