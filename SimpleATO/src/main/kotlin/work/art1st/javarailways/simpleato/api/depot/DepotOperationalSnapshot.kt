package work.art1st.javarailways.simpleato.api.depot

/** Value-only coherent ledger captured under DepotStore's single operational lock. */
internal class DepotOperationalSnapshot(
    units: List<FixedEmuUnit>, points: List<StablingPointSnapshot>,
    claims: List<FixedEmuUnitAssignment>, heldOutputs: List<FixedFormationHeldOutput>,
    unresolvedChanges: List<FixedFormationChange>
) {
    val units = java.util.List.copyOf(units)
    val points = java.util.List.copyOf(points.map { it.copy(point = it.point.copy(allowedTags = java.util.Set.copyOf(it.point.allowedTags))) })
    val claims = java.util.List.copyOf(claims)
    val heldOutputs = java.util.List.copyOf(heldOutputs)
    val unresolvedChanges = java.util.List.copyOf(unresolvedChanges)
}
