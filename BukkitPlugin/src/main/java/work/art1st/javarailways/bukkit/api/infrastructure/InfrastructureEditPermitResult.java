package work.art1st.javarailways.bukkit.api.infrastructure;

import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationResult;

import java.util.Objects;
import java.util.Optional;

/** Typed edit decision with lease/live-claim diagnostics and an optional held permit. */
public final class InfrastructureEditPermitResult {
    private final InfrastructureMutationResult result;
    private final InfrastructureEditPermit permit;

    private InfrastructureEditPermitResult(
        InfrastructureMutationResult result,
        InfrastructureEditPermit permit
    ) {
        this.result = Objects.requireNonNull(result, "result");
        this.permit = permit;
        if ((permit != null) != result.getSuccessful()) {
            throw new IllegalArgumentException("successful result and permit presence must agree");
        }
    }

    public static InfrastructureEditPermitResult granted(
        InfrastructureMutationResult result,
        InfrastructureEditPermit permit
    ) {
        return new InfrastructureEditPermitResult(result, Objects.requireNonNull(permit, "permit"));
    }

    public static InfrastructureEditPermitResult denied(InfrastructureMutationResult result) {
        return new InfrastructureEditPermitResult(result, null);
    }

    public InfrastructureMutationResult result() {
        return result;
    }

    public Optional<InfrastructureEditPermit> permit() {
        return Optional.ofNullable(permit);
    }
}
