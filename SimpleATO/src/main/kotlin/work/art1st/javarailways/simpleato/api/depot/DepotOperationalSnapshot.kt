package work.art1st.javarailways.simpleato.api.depot

/** Value-only coherent ledger captured under DepotStore's single operational lock. */
internal class DepotOperationalSnapshot(
    units: List<FixedEmuUnit>, stock: List<RollingStockSnapshot>, points: List<StablingPointSnapshot>,
    claims: List<FixedEmuUnitAssignment>, heldOutputs: List<FixedFormationHeldOutput>,
    unresolvedChanges: List<FixedFormationChange>
) {
    val units = java.util.List.copyOf(units)
    val stock = java.util.List.copyOf(stock.map { it.copy(record = it.record.copy(
        tags = java.util.Set.copyOf(it.record.tags), capabilities = java.util.Set.copyOf(it.record.capabilities))) })
    val points = java.util.List.copyOf(points.map { it.copy(point = it.point.copy(allowedTags = java.util.Set.copyOf(it.point.allowedTags))) })
    val claims = java.util.List.copyOf(claims)
    val heldOutputs = java.util.List.copyOf(heldOutputs)
    val unresolvedChanges = java.util.List.copyOf(unresolvedChanges)
}
