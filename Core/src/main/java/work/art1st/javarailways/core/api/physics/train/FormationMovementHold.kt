package work.art1st.javarailways.core.api.physics.train

/**
 * Capability for one exact output of a physically published formation change.
 *
 * Core installs the hold before the complete output publication. It prevents controller ticks
 * and explicit distance movement until the issuing world's serialized owner releases this exact
 * token. This hold is independent of unavailable-configuration quarantine. Tokens have no
 * serialized representation; unresolved operational recovery must reestablish a hold on load.
 * Possession does not authorize changing infrastructure claims or operational assignments.
 */
class FormationMovementHold internal constructor(
    val trainId: String,
    internal val source: Train
)

sealed interface FormationMovementHoldRestoration {
    data class Accepted(val hold: FormationMovementHold) : FormationMovementHoldRestoration
    data class Rejected(val reason: String) : FormationMovementHoldRestoration {
        init { require(reason.isNotBlank()) }
    }
}
