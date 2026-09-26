package work.art1st.javarailways.simpleato.inspection

import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclaration
import work.art1st.javarailways.simpleato.api.inspection.InspectionDeclarationBinding
import work.art1st.javarailways.simpleato.api.inspection.InspectionUpdateHandler
import work.art1st.javarailways.simpleato.api.inspection.InspectionUpdateInput
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import java.util.TreeMap

/**
 * Serialized platform-neutral owner of compiled inspection bindings and activation state.
 *
 * Source replacement, dirty publication, update dispatch and removal must run on the same railway
 * owner; this class deliberately adds no second executor, retry loop or plugin registry.
 */
class InspectionRuntime(
    private val publisher: TrainRestrictionPublisher,
    private val sourceUnavailable: (sourceId: String, trainId: String, failure: Exception) -> Unit
) {
    private data class BindingKey(val sourceId: String, val trainId: String)

    private data class Session(
        val compiled: CompiledInspectionBinding,
        val scheduler: InspectionScheduler,
        val handler: InspectionUpdateHandler
    )

    private data class PendingUpdate(
        val key: BindingKey,
        val session: Session,
        val declarations: List<InspectionDeclaration>,
        val publication: TrainRestrictionSnapshot?
    )

    private val sessionsByTrain = linkedMapOf<String, TreeMap<String, Session>>()

    /** Validates and atomically replaces one source/train binding. */
    fun replaceBinding(
        binding: InspectionDeclarationBinding,
        route: RouteSnapshot,
        handler: InspectionUpdateHandler
    ) {
        val compiled = CompiledInspectionBinding.compile(binding, route)
        sessionsByTrain.getOrPut(binding.trainId, ::TreeMap)[binding.sourceId] = Session(
            compiled,
            InspectionScheduler(compiled),
            handler
        )
    }

    /** Marks declarations from the current complete binding for their next in-range update. */
    fun markDirty(sourceId: String, trainId: String, declarationIds: Collection<String>) {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        val session = requireNotNull(sessionsByTrain[trainId]?.get(sourceId)) {
            "inspection binding does not exist for $sourceId/$trainId"
        }
        session.scheduler.markDirty(declarationIds)
    }

    /** Maximum lead required by all sources currently bound to this train and frozen route. */
    fun maximumDeclaredDistanceMeters(trainId: String, route: FrozenRouteBinding): Double {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        return sessionsByTrain[trainId].orEmpty().values.asSequence()
            .filter { it.compiled.binding.route == route }
            .maxOfOrNull { it.scheduler.maximumDeclaredInspectionDistanceMeters }
            ?: 0.0
    }

    /**
     * Dispatches newly active or dirty declarations in stable source order.
     *
     * All callback results are validated before the first publication. Any failure notifies the
     * fail-closed owner for the affected source and is then rethrown to the serialized caller.
     */
    fun update(
        observationStamp: RailwayStateStamp,
        currentRoute: FrozenRouteBinding,
        routeProgressMeters: Double,
        dynamicLookaheadMeters: Double,
        perception: PerceptionSnapshot
    ) {
        val trainId = perception.train.trainId
        val trainSessions = sessionsByTrain[trainId].orEmpty().entries
            .map { (sourceId, session) -> BindingKey(sourceId, trainId) to session }
        if (trainSessions.isEmpty()) return

        val staleSessions = trainSessions.filter { (_, session) ->
            session.compiled.binding.route != currentRoute
        }
        if (staleSessions.isNotEmpty()) {
            val failure = IllegalStateException(
                "inspection binding does not match the current frozen route"
            )
            staleSessions.forEach { (key, _) -> notifyUnavailable(key, failure) }
            throw failure
        }
        require(observationStamp.topologyRevision == currentRoute.topologyRevision) {
            "observation and route must use the same topology revision"
        }
        require(perception.route.topologyRevision == currentRoute.topologyRevision) {
            "perception and route must use the same topology revision"
        }

        val pending = ArrayList<PendingUpdate>()
        trainSessions.forEach { (key, session) ->
            var declarations = emptyList<InspectionDeclaration>()
            try {
                declarations = session.scheduler.updatesFor(routeProgressMeters, dynamicLookaheadMeters)
                if (declarations.isEmpty()) return@forEach
                val input = InspectionUpdateInput.create(
                    observationStamp,
                    currentRoute,
                    routeProgressMeters,
                    perception,
                    declarations
                )
                val publications = java.util.List.copyOf(session.handler.update(input))
                validatePublications(key, observationStamp, currentRoute, publications)
                pending += PendingUpdate(key, session, declarations, publications.singleOrNull())
            } catch (failure: Exception) {
                pending.forEach(::requeue)
                if (declarations.isNotEmpty()) {
                    session.scheduler.markDirty(
                        declarations.map(InspectionDeclaration::declarationId)
                    )
                }
                failSource(key, failure)
            }
        }

        pending.forEachIndexed { index, update ->
            val snapshot = update.publication ?: return@forEachIndexed
            try {
                val result = publisher.publish(snapshot)
                check(result.status == TrainRestrictionPublicationStatus.ACCEPTED) {
                    "restriction publication for ${snapshot.sourceId}/${snapshot.trainId} was ${result.status}"
                }
            } catch (failure: Exception) {
                pending.subList(index, pending.size).forEach(::requeue)
                failSource(update.key, failure)
            }
        }
    }

    fun removeTrain(trainId: String) {
        require(trainId.isNotBlank()) { "trainId must not be blank" }
        sessionsByTrain.remove(trainId)
    }

    fun removeSource(sourceId: String) {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        sessionsByTrain.entries.removeIf { (_, bySource) ->
            bySource.remove(sourceId)
            bySource.isEmpty()
        }
    }

    private fun validatePublications(
        key: BindingKey,
        observationStamp: RailwayStateStamp,
        route: FrozenRouteBinding,
        publications: List<TrainRestrictionSnapshot>
    ) {
        require(publications.size <= 1) {
            "inspection source ${key.sourceId} returned more than one snapshot for ${key.trainId}"
        }
        publications.forEach { snapshot ->
            require(snapshot.sourceId == key.sourceId) {
                "inspection source ${key.sourceId} returned snapshot for ${snapshot.sourceId}"
            }
            require(snapshot.trainId == key.trainId) {
                "inspection source ${key.sourceId} returned snapshot for ${snapshot.trainId}"
            }
            require(snapshot.observationStamp == observationStamp) {
                "inspection source ${key.sourceId} returned a snapshot for another observation"
            }
            require(snapshot.route == route) {
                "inspection source ${key.sourceId} returned a snapshot for another frozen route"
            }
        }
    }

    private fun failSource(key: BindingKey, failure: Exception): Nothing {
        notifyUnavailable(key, failure)
        throw failure
    }

    private fun notifyUnavailable(key: BindingKey, failure: Exception) {
        try {
            sourceUnavailable(key.sourceId, key.trainId, failure)
        } catch (notificationFailure: Exception) {
            failure.addSuppressed(notificationFailure)
        }
    }

    private fun requeue(update: PendingUpdate) = update.session.scheduler.markDirty(
        update.declarations.map(InspectionDeclaration::declarationId)
    )
}
