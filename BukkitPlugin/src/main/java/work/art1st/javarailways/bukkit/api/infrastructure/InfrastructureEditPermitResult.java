package work.art1st.javarailways.bukkit.api.infrastructure;

import work.art1st.javarailways.basic.api.infrastructure.TopologyEditDecision;

import java.util.Objects;
import java.util.Optional;

/** Typed edit decision with dependency/live-claim diagnostics and an optional held permit. */
public final class InfrastructureEditPermitResult {
    private final TopologyEditDecision decision;
    private final InfrastructureEditPermit permit;

    private InfrastructureEditPermitResult(
        TopologyEditDecision decision,
        InfrastructureEditPermit permit
    ) {
        this.decision = Objects.requireNonNull(decision, "decision");
        this.permit = permit;
        if ((permit != null) != decision.getSuccessful()) {
            throw new IllegalArgumentException("successful result and permit presence must agree");
        }
    }

    public static InfrastructureEditPermitResult granted(
        TopologyEditDecision decision,
        InfrastructureEditPermit permit
    ) {
        return new InfrastructureEditPermitResult(decision, Objects.requireNonNull(permit, "permit"));
    }

    public static InfrastructureEditPermitResult denied(TopologyEditDecision decision) {
        return new InfrastructureEditPermitResult(decision, null);
    }

    public TopologyEditDecision decision() {
        return decision;
    }

    public Optional<InfrastructureEditPermit> permit() {
        return Optional.ofNullable(permit);
    }
}
