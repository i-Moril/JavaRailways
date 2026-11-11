package work.art1st.javarailways.simpleato.restriction

import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime

internal val emptyTrainRestrictionSnapshots =
    TrainRestrictionSnapshotProvider { emptyList() }

/** Explicit empty Inspection composition for tests that do not exercise signal add-ons. */
internal fun emptyInspectionRuntime() = InspectionRuntime(
    publisher = { snapshot ->
        TrainRestrictionPublicationResult(
            TrainRestrictionPublicationStatus.ACCEPTED,
            snapshot.revision
        )
    },
    sourceUnavailable = { _, _, failure -> throw failure }
)
