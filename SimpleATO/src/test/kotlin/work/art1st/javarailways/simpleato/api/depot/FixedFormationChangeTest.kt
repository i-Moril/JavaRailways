package work.art1st.javarailways.simpleato.api.depot

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.persistence.SavedFixedFormationChange

class FixedFormationChangeTest {
    private val depot = DepotId("D")
    private val first = FixedEmuUnit("U1", listOf("a", "b"), "emu")
    private val second = FixedEmuUnit("U2", listOf("c", "d"), "emu")
    private val sources = listOf(FixedFormationOwner("T1", "duty1", first.carIds), FixedFormationOwner("T2", "duty2", second.carIds))
    private val combined = FixedFormationOwner("T1", "combined", first.carIds + second.carIds)
    private fun empty() = DepotStore { listOf(first, second) }.also { store ->
        (1..2).forEach { store.addStablingPoint(depot, "Depot", DepotStablingPoint(StablingPointRef(depot, "p$it"), "point$it")) }
    }
    private fun active(owners: List<FixedFormationOwner> = sources) = empty().also { store ->
        owners.forEach { owner -> store.claimFixedFormation(owner.trainId, owner.carIds, owner.assignmentId) }
    }
    private fun couple() = FixedFormationChange("coupling", FixedFormationChangeKind.COUPLE, sources,
        listOf(FixedFormationOutput(combined, FixedFormationDisposition.CONTINUE_DUTY)))
    private fun split() = FixedFormationChange("separation", FixedFormationChangeKind.SPLIT, listOf(combined),
        sources.mapIndexed { index, owner -> FixedFormationOutput(owner, if (index == 0) FixedFormationDisposition.CONTINUE_DUTY else FixedFormationDisposition.HOLD) })
    private fun physical(owners: List<FixedFormationOwner>) = owners.associate { it.trainId to it.carIds }
    private fun directions(owners: List<FixedFormationOwner>) = owners.associate { it.trainId to it.reversed }
    private fun recover(original: DepotStore, physical: Map<String, List<String>>): DepotStore = empty().also { store ->
        val saved = original.unresolvedFormationChanges().map { change ->
            val text = Json.encodeToString(SavedFixedFormationChange.serializer(), SavedFixedFormationChange.capture(change))
            Json.decodeFromString(SavedFixedFormationChange.serializer(), text).restore()
        }
        store.restoreOperationalLedger(original.allFixedUnitAssignments(), physical, saved, physical.keys.associateWith { false }, original.heldFormationOutputs())
    }

    @Test fun `coupling retains source claims until all observed outputs are reconciled atomically`() {
        val store = active(); val change = couple()
        val before = store.allFixedUnitAssignments()
        store.prepareFixedFormationChange(change, physical(sources), directions(sources))
        assertEquals(before, store.allFixedUnitAssignments())
        assertFalse(store.releaseFixedFormation("T1", "duty1"))
        assertThrows(IllegalArgumentException::class.java) { store.claimFixedFormation("other", first.carIds, "other-duty") }
        assertFalse(store.releaseFixedFormation("T1", "duty1"))
        assertThrows(IllegalArgumentException::class.java) { store.commitFixedFormationChange(change, physical(sources), directions(sources)) }
        assertEquals(before, store.allFixedUnitAssignments())
        val committed = store.commitFixedFormationChange(change, physical(listOf(combined)), directions(listOf(combined)))
        assertEquals(FixedFormationPhase.OUTPUT_OWNERS_COMMITTED, committed.phase)
        assertSame(committed, store.unresolvedFormationChanges().single())
        assertThrows(IllegalArgumentException::class.java) { store.finishFixedFormationChange(change, physical(listOf(combined)), directions(listOf(combined)), listOf(combined)) }
        store.finishFixedFormationChange(committed, physical(listOf(combined)), directions(listOf(combined)), listOf(combined))
        assertEquals(setOf("combined"), store.allFixedUnitAssignments().map { it.assignmentId }.toSet())
        assertTrue(store.unresolvedFormationChanges().isEmpty())
        assertTrue(store.releaseFixedFormation("T1", "combined"))
        assertTrue(store.allFixedUnitAssignments().isEmpty())
    }

    @Test fun `prepared and committed output assignment cannot acquire unrelated physical formation`() {
        val store = active(); val change = couple()
        val spare = FixedFormationOwner("spare", "unused", listOf("spare-car"))
        store.prepareFixedFormationChange(change, physical(sources), directions(sources))
        fun assertOwned() {
            assertThrows(IllegalArgumentException::class.java) { store.claimFixedFormation(spare.trainId, first.carIds, combined.assignmentId) }
        }
        assertOwned()
        store.commitFixedFormationChange(change, physical(listOf(combined, spare)), directions(listOf(combined, spare)))
        assertOwned()
    }

    @Test fun `split keeps the held output assigned and conserves permanent units`() {
        val store = active(listOf(combined)); val change = split()
        store.prepareFixedFormationChange(change, physical(listOf(combined)), directions(listOf(combined)))
        val committed = store.commitFixedFormationChange(change, physical(sources), directions(sources))
        assertThrows(IllegalArgumentException::class.java) {
            store.finishFixedFormationChange(committed, physical(sources), directions(sources), sources.take(1))
        }
        assertSame(committed, store.unresolvedFormationChanges().single())
        assertTrue(store.heldFormationOutputs().isEmpty())
        store.finishFixedFormationChange(committed, physical(sources), directions(sources), sources)
        assertEquals(listOf(FixedFormationHeldOutput(sources.last())), store.heldFormationOutputs())
        assertEquals(listOf(first, second), store.allFixedUnits())
        assertEquals(sources.map { it.assignmentId }.toSet(), store.allFixedUnitAssignments().map { it.assignmentId }.toSet())
    }

    @Test fun `recovery infers before or after publication solely from actual Core membership without replay`() {
        listOf(couple() to sources, split() to listOf(combined)).forEach { (change, initial) ->
            val original = active(initial)
            original.prepareFixedFormationChange(change, physical(initial), directions(initial))
            listOf(initial to RecoveredFormationComposition.UNCHANGED_SOURCES,
                change.outputs.map { it.owner } to RecoveredFormationComposition.EXPECTED_OUTPUTS).forEach { (observed, expected) ->
                val restored = recover(original, physical(observed))
                assertEquals(expected, restored.recoveredFormationHolds().single().composition)
                assertEquals(original.allFixedUnitAssignments(), restored.allFixedUnitAssignments())
                assertFalse(restored.releaseFixedFormation(initial.first().trainId, initial.first().assignmentId))
                assertThrows(IllegalArgumentException::class.java) { restored.claimFixedFormation("other", first.carIds, "other") }
                val restoredChange = restored.unresolvedFormationChanges().single()
                assertThrows(IllegalStateException::class.java) { restored.commitFixedFormationChange(restoredChange, physical(change.outputs.map { it.owner }), directions(change.outputs.map { it.owner })) }
            }
        }
    }

    @Test fun `invalid interrupted compositions and overlapping events fail before publishing any ledger`() {
        val original = active(); val change = couple()
        original.prepareFixedFormationChange(change, physical(sources), directions(sources))
        val invalid = listOf(mapOf("T1" to listOf("a", "b", "c")), mapOf("T1" to combined.carIds, "T2" to second.carIds),
            mapOf("unknown" to combined.carIds), mapOf("T1" to listOf("a", "c", "b", "d")))
        invalid.forEach { observed ->
            val restored = empty()
            assertThrows(IllegalArgumentException::class.java) { restored.restoreOperationalLedger(original.allFixedUnitAssignments(), observed, listOf(change), observed.keys.associateWith { false }) }
            assertTrue(restored.allFixedUnitAssignments().isEmpty())
            assertTrue(restored.unresolvedFormationChanges().isEmpty())
            assertEquals(listOf(first, second), restored.allFixedUnits())
        }
        val restored = empty()
        val conflicting = FixedFormationChange("other", change.kind, change.sources, change.outputs)
        assertThrows(IllegalArgumentException::class.java) { restored.restoreOperationalLedger(original.allFixedUnitAssignments(), physical(sources), listOf(change, conflicting), directions(sources)) }
        assertEquals(listOf(first, second), restored.allFixedUnits())
    }

    @Test fun `restore and commit reject unchanged car IDs with the wrong operating direction`() {
        val original = active(); val change = couple()
        original.prepareFixedFormationChange(change, physical(sources), directions(sources))
        assertThrows(IllegalArgumentException::class.java) {
            original.commitFixedFormationChange(change, physical(listOf(combined)), mapOf(combined.trainId to true))
        }
        val restored = empty()
        assertThrows(IllegalArgumentException::class.java) {
            restored.restoreOperationalLedger(original.allFixedUnitAssignments(),
                physical(listOf(combined)), listOf(change), mapOf(combined.trainId to true))
        }
        assertEquals(change, original.unresolvedFormationChanges().single())
    }


    @Test fun `committed phase restores only output ownership and rejects missing or source claims atomically`() {
        val original = active(); val change = couple()
        original.prepareFixedFormationChange(change, physical(sources), directions(sources))
        val oldClaims = original.allFixedUnitAssignments()
        val committed = original.commitFixedFormationChange(change, physical(listOf(combined)), directions(listOf(combined)))
        val restored = recover(original, physical(listOf(combined)))
        assertEquals(FixedFormationPhase.OUTPUT_OWNERS_COMMITTED, restored.unresolvedFormationChanges().single().phase)
        assertEquals(RecoveredFormationComposition.EXPECTED_OUTPUTS, restored.recoveredFormationHolds().single().composition)
        assertEquals(original.allFixedUnitAssignments(), restored.allFixedUnitAssignments())
        assertThrows(IllegalArgumentException::class.java) { recover(original, physical(sources)) }
        listOf(oldClaims, original.allFixedUnitAssignments().drop(1)).forEach { invalid ->
            val target = empty()
            assertThrows(IllegalArgumentException::class.java) { target.restoreOperationalLedger(
                invalid, physical(listOf(combined)), listOf(committed), directions(listOf(combined))) }
            assertTrue(target.allFixedUnitAssignments().isEmpty())
            assertTrue(target.unresolvedFormationChanges().isEmpty())
        }
        assertThrows(IllegalArgumentException::class.java) { original.abortFixedFormationChange(committed, physical(listOf(combined)), directions(listOf(combined))) }
    }

    @Test fun `finished held output survives continuing peer storage independently and rejects malformed held evidence`() {
        val original = active(listOf(combined)); val change = split()
        original.prepareFixedFormationChange(change, physical(listOf(combined)), directions(listOf(combined)))
        val committed = original.commitFixedFormationChange(change, physical(sources), directions(sources))
        original.finishFixedFormationChange(committed, physical(sources), directions(sources), sources)
        assertTrue(original.unresolvedFormationChanges().isEmpty())
        val continuing = sources.first()
        assertTrue(original.releaseFixedFormation(continuing.trainId, continuing.assignmentId))
        val restored = recover(original, physical(sources))
        assertEquals(original.heldFormationOutputs(), restored.heldFormationOutputs())
        assertTrue(restored.recoveredFormationHolds().isEmpty())
        val held = sources.last()
        assertFalse(restored.releaseFixedFormation(held.trainId, held.assignmentId))
        assertThrows(IllegalArgumentException::class.java) { restored.claimFixedFormation(held.trainId, held.carIds, held.assignmentId) }
        listOf(listOf(FixedFormationHeldOutput(FixedFormationOwner(held.trainId, held.assignmentId, held.carIds, true))), original.heldFormationOutputs() + original.heldFormationOutputs()).forEach { invalid ->
            val target = empty()
            assertThrows(IllegalArgumentException::class.java) { target.restoreOperationalLedger(                 original.allFixedUnitAssignments(), physical(sources), physicalDirections = directions(sources), heldOutputs = invalid) }
            assertTrue(target.allFixedUnitAssignments().isEmpty())
            assertTrue(target.heldFormationOutputs().isEmpty())
        }
    }

    @Test fun `pending continuations release independently and restored pending owners never replay`() {
        val store = active(listOf(combined))
        val change = FixedFormationChange("two-departures", FixedFormationChangeKind.SPLIT, listOf(combined),
            sources.map { FixedFormationOutput(it, FixedFormationDisposition.CONTINUE_DUTY) })
        store.prepareFixedFormationChange(change, physical(listOf(combined)), directions(listOf(combined)))
        val committed = store.commitFixedFormationChange(change, physical(sources), directions(sources))
        assertThrows(IllegalArgumentException::class.java) {
            store.finishFixedFormationChange(committed, physical(sources), directions(sources), sources, sources + sources.first())
        }
        assertSame(committed, store.unresolvedFormationChanges().single())
        store.finishFixedFormationChange(committed, physical(sources), directions(sources), sources, sources)
        assertTrue(store.unresolvedFormationChanges().isEmpty())
        assertEquals(sources.map { FixedFormationHeldOutput(it, FixedFormationDisposition.CONTINUE_DUTY) }, store.heldFormationOutputs())
        val firstToken = store.validatePendingFormationOutput(sources.first(), physical(sources), directions(sources))
        assertThrows(IllegalArgumentException::class.java) {
            store.releasePendingFormationOutput(firstToken.copy(), physical(sources), directions(sources))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.releasePendingFormationOutput(firstToken, physical(sources), directions(sources) + (sources.first().trainId to true))
        }
        store.releasePendingFormationOutput(firstToken, physical(sources), directions(sources))
        assertThrows(IllegalArgumentException::class.java) { store.releasePendingFormationOutput(firstToken, physical(sources), directions(sources)) }
        val departing = sources.first()
        assertTrue(store.releaseFixedFormation(departing.trainId, departing.assignmentId), "departed peer is independent of the pending duty")
        val remaining = store.validatePendingFormationOutput(sources.last(), physical(sources), directions(sources))
        assertEquals(sources.last(), remaining.owner)
        val restored = recover(store, physical(sources))
        assertEquals(store.heldFormationOutputs(), restored.heldFormationOutputs())
        assertThrows(IllegalStateException::class.java) {
            restored.validatePendingFormationOutput(sources.last(), physical(sources), directions(sources))
        }
        val saved = work.art1st.javarailways.simpleato.persistence.SavedFixedFormationHeldOutput.capture(remaining)
        assertEquals(remaining, Json.decodeFromString(work.art1st.javarailways.simpleato.persistence.SavedFixedFormationHeldOutput.serializer(),
            Json.encodeToString(work.art1st.javarailways.simpleato.persistence.SavedFixedFormationHeldOutput.serializer(), saved)).restore())
        assertEquals(FixedFormationDisposition.HOLD,
            Json.decodeFromString(work.art1st.javarailways.simpleato.persistence.SavedFixedFormationHeldOutput.serializer(),
                """{"trainId":"held","assignmentId":"held-duty","carIds":["a"]}""").restore().disposition)
    }

    @Test fun `pending reversal preserves exact assignment and persists new direction without releasing peer`() {
        val store = active(listOf(combined))
        val change = FixedFormationChange("reverse-pending", FixedFormationChangeKind.SPLIT, listOf(combined),
            sources.map { FixedFormationOutput(it, FixedFormationDisposition.CONTINUE_DUTY) })
        store.prepareFixedFormationChange(change, physical(listOf(combined)), directions(listOf(combined)))
        val committed = store.commitFixedFormationChange(change, physical(sources), directions(sources))
        store.finishFixedFormationChange(committed, physical(sources), directions(sources), sources, sources)
        val token = store.validatePendingFormationOutput(sources.first(), physical(sources), directions(sources))
        val claims = store.allFixedUnitAssignments()
        val newDirections = directions(sources) + (sources.first().trainId to true)
        assertThrows(IllegalArgumentException::class.java) {
            store.recordPendingFormationReversal(token.copy(), physical(sources), newDirections)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.recordPendingFormationReversal(token, physical(sources), directions(sources))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.recordPendingFormationReversal(token, physical(sources) + ("duplicate" to first.carIds), newDirections)
        }
        assertEquals(token, store.heldFormationOutputs().first())
        val updated = store.recordPendingFormationReversal(token, physical(sources), newDirections)
        assertTrue(updated.owner.reversed)
        assertEquals(claims, store.allFixedUnitAssignments())
        assertEquals(sources.last(), store.heldFormationOutputs().last().owner)
        assertThrows(IllegalArgumentException::class.java) {
            store.releasePendingFormationOutput(token, physical(sources), newDirections)
        }
        val restored = empty()
        restored.restoreOperationalLedger(claims, physical(sources), emptyList(),
            newDirections, store.heldFormationOutputs())
        assertEquals(store.heldFormationOutputs(), restored.heldFormationOutputs())
        assertThrows(IllegalStateException::class.java) {
            restored.recordPendingFormationReversal(restored.heldFormationOutputs().first(), physical(sources), directions(sources))
        }
        store.releasePendingFormationOutput(updated, physical(sources), newDirections)
        assertEquals(listOf(sources.last()), store.heldFormationOutputs().map { it.owner })
    }

    @Test fun `HOLD disposition cannot be mistaken for a pending continuation`() {
        val store = active(listOf(combined)); val change = split()
        store.prepareFixedFormationChange(change, physical(listOf(combined)), directions(listOf(combined)))
        val committed = store.commitFixedFormationChange(change, physical(sources), directions(sources))
        assertThrows(IllegalArgumentException::class.java) {
            store.finishFixedFormationChange(committed, physical(sources), directions(sources), sources, listOf(sources.last()))
        }
        store.finishFixedFormationChange(committed, physical(sources), directions(sources), sources)
        assertThrows(IllegalArgumentException::class.java) {
            store.validatePendingFormationOutput(sources.last(), physical(sources), directions(sources))
        }
        assertEquals(listOf(FixedFormationHeldOutput(sources.last())), store.heldFormationOutputs())
    }

    @Test fun `internal car partitions and unrelated output identities are rejected without mutation`() {
        val store = active(listOf(combined))
        val internalSplit = FixedFormationChange("bad", FixedFormationChangeKind.SPLIT, listOf(combined), listOf(
            FixedFormationOutput(FixedFormationOwner("T1", "a", listOf("a")), FixedFormationDisposition.HOLD),
            FixedFormationOutput(FixedFormationOwner("T2", "b", listOf("b", "c", "d")), FixedFormationDisposition.HOLD)))
        assertThrows(IllegalArgumentException::class.java) { store.prepareFixedFormationChange(internalSplit, physical(listOf(combined)), directions(listOf(combined))) }
        assertTrue(store.unresolvedFormationChanges().isEmpty())
        val split = split()
        assertThrows(IllegalArgumentException::class.java) { store.prepareFixedFormationChange(split, physical(listOf(combined)) + ("T2" to listOf("unrelated")), mapOf("T1" to false, "T2" to false)) }
        assertTrue(store.unresolvedFormationChanges().isEmpty())
    }
}
