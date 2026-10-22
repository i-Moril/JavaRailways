package work.art1st.javarailways.simpleato.api.depot

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.persistence.SavedFixedFormationChange

class FixedFormationChangeTest {
    private val depot = DepotId("D")
    private val first = FixedEmuUnit("U1", depot, listOf("a", "b"), "emu")
    private val second = FixedEmuUnit("U2", depot, listOf("c", "d"), "emu")
    private val sources = listOf(FixedFormationOwner("T1", "duty1", first.carIds), FixedFormationOwner("T2", "duty2", second.carIds))
    private val combined = FixedFormationOwner("T1", "combined", first.carIds + second.carIds)
    private fun record(owner: FixedFormationOwner) = RollingStockRecord(owner.trainId, depot, "emu", emptySet(), owner.carIds.size, owner.carIds.size * 20.0, 30.0, emptySet())
    private fun empty() = DepotStore().also { store ->
        (1..2).forEach { store.addStablingPoint(depot, "Depot", DepotStablingPoint(StablingPointRef(depot, "p$it"), "point$it")) }
    }
    private fun active(owners: List<FixedFormationOwner> = sources) = empty().also { store ->
        store.registerFixedUnit(first, first.carIds)
        store.registerFixedUnit(second, second.carIds)
        owners.forEachIndexed { index, owner ->
            val point = StablingPointRef(depot, "p${index + 1}")
            store.registerRollingStock(record(owner), point)
            val allocation = checkNotNull(store.reservePhysicallySelected(owner.trainId, point, DepotTrainSelector(depot), owner.assignmentId, owner.carIds))
            assertTrue(store.confirmInService(allocation))
            assertTrue(store.releaseDeparture(allocation))
        }
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
        store.restoreOperationalLedger(original.allRollingStock(), original.allFixedUnits(), original.allFixedUnitAssignments(), physical, saved, physical.keys.associateWith { false }, original.heldFormationOutputs())
    }

    @Test fun `coupling retains source claims until all observed outputs are reconciled atomically`() {
        val store = active(); val change = couple()
        val before = store.allFixedUnitAssignments()
        store.prepareFixedFormationChange(change, physical(sources), directions(sources))
        assertEquals(before, store.allFixedUnitAssignments())
        assertFalse(store.releaseFixedFormation("T1", "duty1"))
        assertThrows(IllegalArgumentException::class.java) { store.claimFixedFormation("other", first.carIds, "other-duty") }
        assertTrue(store.reserveStorage("T1", depot, emptySet(), "duty1", "seed") is DepotStorageReservationResult.RollingStockUnavailable)
        assertThrows(IllegalArgumentException::class.java) { store.commitFixedFormationChange(change, physical(sources), listOf(record(combined)), directions(sources)) }
        assertEquals(before, store.allFixedUnitAssignments())
        val committed = store.commitFixedFormationChange(change, physical(listOf(combined)), listOf(record(combined)), directions(listOf(combined)))
        assertEquals(FixedFormationPhase.OUTPUT_OWNERS_COMMITTED, committed.phase)
        assertSame(committed, store.unresolvedFormationChanges().single())
        assertThrows(IllegalArgumentException::class.java) { store.finishFixedFormationChange(change, physical(listOf(combined)), directions(listOf(combined)), listOf(combined)) }
        store.finishFixedFormationChange(committed, physical(listOf(combined)), directions(listOf(combined)), listOf(combined))
        assertEquals(setOf("combined"), store.allFixedUnitAssignments().map { it.assignmentId }.toSet())
        assertEquals(listOf("T1"), store.allRollingStock().map { it.record.trainId })
        assertTrue(store.unresolvedFormationChanges().isEmpty())
        val storage = (store.reserveStorage("T1", depot, emptySet(), "combined", "seed") as DepotStorageReservationResult.Reserved).reservation
        assertTrue(store.completeStorage(storage))
        assertEquals(RollingStockState.AVAILABLE, store.rollingStock("T1")!!.state)
        assertTrue(store.allFixedUnitAssignments().isEmpty())
    }

    @Test fun `prepared and committed output assignment cannot acquire unrelated unregistered stock`() {
        val store = active(); val change = couple()
        val spare = FixedFormationOwner("spare", "unused", listOf("spare-car"))
        val point = StablingPointRef(depot, "p2")
        store.registerRollingStock(record(spare), point)
        store.prepareFixedFormationChange(change, physical(sources), directions(sources))
        fun assertOwned() {
            assertTrue(store.allocate(DepotTrainSelector(depot), combined.assignmentId, "seed", physical(listOf(spare))) is DepotTrainAllocationResult.AssignmentConflict)
            assertThrows(IllegalArgumentException::class.java) { store.reservePhysicallySelected(spare.trainId, point, DepotTrainSelector(depot), combined.assignmentId, spare.carIds) }
            assertEquals(RollingStockState.AVAILABLE, store.rollingStock(spare.trainId)!!.state)
        }
        assertOwned()
        store.commitFixedFormationChange(change, physical(listOf(combined, spare)), listOf(record(combined)), directions(listOf(combined, spare)))
        assertOwned()
    }

    @Test fun `split keeps the held output assigned and conserves permanent units`() {
        val store = active(listOf(combined)); val change = split()
        store.prepareFixedFormationChange(change, physical(listOf(combined)), directions(listOf(combined)))
        val committed = store.commitFixedFormationChange(change, physical(sources), sources.map(::record), directions(sources))
        assertThrows(IllegalArgumentException::class.java) {
            store.finishFixedFormationChange(committed, physical(sources), directions(sources), sources.take(1))
        }
        assertSame(committed, store.unresolvedFormationChanges().single())
        assertTrue(store.heldFormationOutputs().isEmpty())
        store.finishFixedFormationChange(committed, physical(sources), directions(sources), sources)
        assertEquals(listOf(FixedFormationHeldOutput(sources.last())), store.heldFormationOutputs())
        assertEquals(listOf(first, second), store.allFixedUnits())
        assertEquals(2, store.allRollingStock().size)
        assertTrue(store.allRollingStock().all { it.state == RollingStockState.IN_SERVICE && it.assignmentId != null })
        assertEquals(sources.map { it.assignmentId }.toSet(), store.allFixedUnitAssignments().map { it.assignmentId }.toSet())
        assertTrue(store.allocate(DepotTrainSelector(depot), "unrelated", "seed") is DepotTrainAllocationResult.NoMatchingTrain)
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
                assertThrows(IllegalStateException::class.java) { restored.commitFixedFormationChange(restoredChange, physical(change.outputs.map { it.owner }), change.outputs.map { record(it.owner) }, directions(change.outputs.map { it.owner })) }
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
            assertThrows(IllegalArgumentException::class.java) { restored.restoreOperationalLedger(original.allRollingStock(), original.allFixedUnits(), original.allFixedUnitAssignments(), observed, listOf(change), observed.keys.associateWith { false }) }
            assertTrue(restored.allRollingStock().isEmpty()); assertTrue(restored.allFixedUnits().isEmpty()); assertTrue(restored.unresolvedFormationChanges().isEmpty())
        }
        val restored = empty()
        val conflicting = FixedFormationChange("other", change.kind, change.sources, change.outputs)
        assertThrows(IllegalArgumentException::class.java) { restored.restoreOperationalLedger(original.allRollingStock(), original.allFixedUnits(), original.allFixedUnitAssignments(), physical(sources), listOf(change, conflicting), directions(sources)) }
        assertTrue(restored.allFixedUnits().isEmpty())
    }

    @Test fun `restore and commit reject unchanged car IDs with the wrong operating direction`() {
        val original = active(); val change = couple()
        original.prepareFixedFormationChange(change, physical(sources), directions(sources))
        assertThrows(IllegalArgumentException::class.java) {
            original.commitFixedFormationChange(change, physical(listOf(combined)), listOf(record(combined)), mapOf(combined.trainId to true))
        }
        val restored = empty()
        assertThrows(IllegalArgumentException::class.java) {
            restored.restoreOperationalLedger(original.allRollingStock(), original.allFixedUnits(), original.allFixedUnitAssignments(),
                physical(listOf(combined)), listOf(change), mapOf(combined.trainId to true))
        }
        assertTrue(restored.allRollingStock().isEmpty())
        assertEquals(change, original.unresolvedFormationChanges().single())
    }


    @Test fun `committed phase restores only output ownership and rejects missing or source claims atomically`() {
        val original = active(); val change = couple()
        original.prepareFixedFormationChange(change, physical(sources), directions(sources))
        val oldClaims = original.allFixedUnitAssignments()
        val committed = original.commitFixedFormationChange(change, physical(listOf(combined)), listOf(record(combined)), directions(listOf(combined)))
        val restored = recover(original, physical(listOf(combined)))
        assertEquals(FixedFormationPhase.OUTPUT_OWNERS_COMMITTED, restored.unresolvedFormationChanges().single().phase)
        assertEquals(RecoveredFormationComposition.EXPECTED_OUTPUTS, restored.recoveredFormationHolds().single().composition)
        assertEquals(original.allFixedUnitAssignments(), restored.allFixedUnitAssignments())
        assertThrows(IllegalArgumentException::class.java) { recover(original, physical(sources)) }
        listOf(oldClaims, original.allFixedUnitAssignments().drop(1)).forEach { invalid ->
            val target = empty()
            assertThrows(IllegalArgumentException::class.java) { target.restoreOperationalLedger(original.allRollingStock(),
                original.allFixedUnits(), invalid, physical(listOf(combined)), listOf(committed), directions(listOf(combined))) }
            assertTrue(target.allRollingStock().isEmpty()); assertTrue(target.allFixedUnits().isEmpty())
        }
        assertThrows(IllegalArgumentException::class.java) { original.abortFixedFormationChange(committed, physical(listOf(combined)), directions(listOf(combined))) }
    }

    @Test fun `finished held output survives continuing peer storage independently and rejects malformed held evidence`() {
        val original = active(listOf(combined)); val change = split()
        original.prepareFixedFormationChange(change, physical(listOf(combined)), directions(listOf(combined)))
        val committed = original.commitFixedFormationChange(change, physical(sources), sources.map(::record), directions(sources))
        original.finishFixedFormationChange(committed, physical(sources), directions(sources), sources)
        assertTrue(original.unresolvedFormationChanges().isEmpty())
        val continuing = sources.first()
        val storage = (original.reserveStorage(continuing.trainId, depot, emptySet(), continuing.assignmentId, "seed") as DepotStorageReservationResult.Reserved).reservation
        assertTrue(original.completeStorage(storage))
        val restored = recover(original, physical(sources))
        assertEquals(original.heldFormationOutputs(), restored.heldFormationOutputs())
        assertTrue(restored.recoveredFormationHolds().isEmpty())
        val held = sources.last()
        assertFalse(restored.releaseFixedFormation(held.trainId, held.assignmentId))
        assertTrue(restored.reserveStorage(held.trainId, depot, emptySet(), held.assignmentId, "seed") is DepotStorageReservationResult.RollingStockUnavailable)
        listOf(listOf(FixedFormationHeldOutput(FixedFormationOwner(held.trainId, held.assignmentId, held.carIds, true))), original.heldFormationOutputs() + original.heldFormationOutputs()).forEach { invalid ->
            val target = empty()
            assertThrows(IllegalArgumentException::class.java) { target.restoreOperationalLedger(original.allRollingStock(), original.allFixedUnits(),
                original.allFixedUnitAssignments(), physical(sources), physicalDirections = directions(sources), heldOutputs = invalid) }
            assertTrue(target.allRollingStock().isEmpty()); assertTrue(target.allFixedUnits().isEmpty())
        }
    }

    @Test fun `pending continuations release independently and restored pending owners never replay`() {
        val store = active(listOf(combined))
        val change = FixedFormationChange("two-departures", FixedFormationChangeKind.SPLIT, listOf(combined),
            sources.map { FixedFormationOutput(it, FixedFormationDisposition.CONTINUE_DUTY) })
        store.prepareFixedFormationChange(change, physical(listOf(combined)), directions(listOf(combined)))
        val committed = store.commitFixedFormationChange(change, physical(sources), sources.map(::record), directions(sources))
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
        val reservation = (store.reserveStorage(departing.trainId, depot, emptySet(), departing.assignmentId, "seed") as DepotStorageReservationResult.Reserved).reservation
        assertTrue(store.completeStorage(reservation), "departed peer is independent of the pending duty")
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
        val committed = store.commitFixedFormationChange(change, physical(sources), sources.map(::record), directions(sources))
        store.finishFixedFormationChange(committed, physical(sources), directions(sources), sources, sources)
        val token = store.validatePendingFormationOutput(sources.first(), physical(sources), directions(sources))
        val claims = store.allFixedUnitAssignments()
        val stock = store.allRollingStock()
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
        assertEquals(stock, store.allRollingStock())
        assertEquals(sources.last(), store.heldFormationOutputs().last().owner)
        assertThrows(IllegalArgumentException::class.java) {
            store.releasePendingFormationOutput(token, physical(sources), newDirections)
        }
        val restored = empty()
        restored.restoreOperationalLedger(stock, store.allFixedUnits(), claims, physical(sources), emptyList(),
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
        val committed = store.commitFixedFormationChange(change, physical(sources), sources.map(::record), directions(sources))
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
