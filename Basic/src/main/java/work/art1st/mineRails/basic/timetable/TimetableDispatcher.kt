package work.art1st.mineRails.basic.timetable

import work.art1st.mineRails.basic.api.ato.mission.AtoMissionPoint
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionRequest
import work.art1st.mineRails.basic.api.ato.mission.AtoMissionSubmissionResult
import work.art1st.mineRails.basic.api.ato.mission.WayPointRef
import work.art1st.mineRails.basic.controller.SimpleATO
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.basic.route.RouteSectionAllocationKey
import work.art1st.mineRails.basic.route.RouteSectionAllocationResult
import work.art1st.mineRails.basic.route.RouteSectionAllocator
import work.art1st.mineRails.basic.route.RouteSectionOwner
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.api.physics.train.TrainSnapshot
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.physics.train.CoreTrain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

fun interface TimetableAtoFactory {
    fun create(train: Train, world: World): SimpleATO
}

data class TimetableServiceRunId(val timetableId: String, val serviceId: String) {
    init {
        require(timetableId.isNotBlank() && serviceId.isNotBlank())
    }

    override fun toString(): String = "$timetableId/$serviceId"
}

enum class TimetableServiceRunState {
    WAITING_MISSION,
    WAITING_ROUTE,
    RUNNING,
    STOPPED,
    FAILED
}

data class TimetableServiceRunSnapshot(
    val id: TimetableServiceRunId,
    val trainId: String,
    val missionRevision: Long,
    val state: TimetableServiceRunState,
    val confirmedSectionCount: Int,
    val message: String? = null
)

sealed interface TimetableServiceStartResult {
    data class Started(val run: TimetableServiceRunSnapshot) : TimetableServiceStartResult
    data class Failure(val reason: String) : TimetableServiceStartResult
}

/** Main-thread runtime for explicit-train timetable services. */
class TimetableDispatcher(
    private val atoFactory: TimetableAtoFactory? = null
) {
    private val initialTrains = ConcurrentHashMap<String, TrainSnapshot>()
    private val missionRevision = AtomicLong(0L)
    private val runs = LinkedHashMap<TimetableServiceRunId, ActiveRun>()
    private var allocatorWorld: World? = null
    private var allocator: RouteSectionAllocator? = null

    fun recordInitialTrain(snapshot: TrainSnapshot) {
        initialTrains[snapshot.trainId] = snapshot
    }

    fun initialTrain(trainId: String): TrainSnapshot? = initialTrains[trainId]

    fun initialTrains(): Map<String, TrainSnapshot> = initialTrains.toMap()

    @MainThread
    fun startService(
        world: World,
        timetableId: String,
        serviceId: String
    ): TimetableServiceStartResult {
        val runId = TimetableServiceRunId(timetableId, serviceId)
        runs[runId]?.takeIf {
            it.state != TimetableServiceRunState.STOPPED && it.state != TimetableServiceRunState.FAILED
        }?.let { return TimetableServiceStartResult.Failure("service is already active: $runId") }

        val timetable = world.basicWorldData.timetables[timetableId]
            ?: return TimetableServiceStartResult.Failure("timetable not found: $timetableId")
        val service = timetable.services.firstOrNull { it.id == serviceId }
            ?: return TimetableServiceStartResult.Failure("service not found: $runId")
        val trainId = (service.trainSelector as? TrainSelector.TrainId)?.trainId
            ?: return TimetableServiceStartResult.Failure(
                "only TrainSelector.TrainId is executable in C1: $runId"
            )
        if (runs.values.any {
            it.train.id == trainId &&
                it.state != TimetableServiceRunState.STOPPED &&
                it.state != TimetableServiceRunState.FAILED
        }) {
            return TimetableServiceStartResult.Failure("train is already assigned to a service: $trainId")
        }
        val train = world.getTrain(trainId) as? CoreTrain
            ?: return TimetableServiceStartResult.Failure("train not found: $trainId")
        if (train.isDerailed) return TimetableServiceStartResult.Failure("train is derailed: $trainId")

        val existingController = train.controller
        if (existingController != null && existingController !is SimpleATO) {
            return TimetableServiceStartResult.Failure("train already has another controller: $trainId")
        }
        val revision = nextMissionRevision()
        val request = buildMissionRequest(runId, service, revision)
            ?: return TimetableServiceStartResult.Failure(
                "service requires an initial Check and at least one GoTo/GoVia target: $runId"
            )
        val origin = world.basicWorldData.wayPoints[request.origin.id]
            ?: return TimetableServiceStartResult.Failure("mission origin not found: ${request.origin.id}")
        val trainArc = train.headLocation.arcLengthIn(ViewDirection.AB)
        if (train.headLocation.rail.id != origin.railId ||
            kotlin.math.abs(trainArc - origin.arcLength) > ORIGIN_TOLERANCE_METERS
        ) {
            return TimetableServiceStartResult.Failure(
                "train $trainId is not at mission origin ${request.origin.id}"
            )
        }
        val ato = existingController ?: atoFactory?.create(train, world)
            ?: return TimetableServiceStartResult.Failure("timetable ATO factory is not configured")
        val accepted = when (val result = ato.submitMission(request)) {
            is AtoMissionSubmissionResult.Accepted -> result
            is AtoMissionSubmissionResult.StaleRevision -> {
                return TimetableServiceStartResult.Failure(
                    "mission revision ${result.submittedRevision} is stale; latest is ${result.latestAcceptedRevision}"
                )
            }
            is AtoMissionSubmissionResult.TargetUnresolved -> {
                return TimetableServiceStartResult.Failure("mission target not found: ${result.target.id}")
            }
            is AtoMissionSubmissionResult.RouteUnavailable -> {
                return TimetableServiceStartResult.Failure(
                    "route unavailable ${result.from.id} -> ${result.to.id}: ${result.reason}"
                )
            }
        }

        val controllerOwned = existingController == null
        if (controllerOwned) train.setController(ato)
        val run = ActiveRun(
            id = runId,
            train = train,
            ato = ato,
            controllerOwned = controllerOwned,
            missionRevision = accepted.mission.revision,
            state = TimetableServiceRunState.WAITING_MISSION
        )
        runs[runId] = run
        return TimetableServiceStartResult.Started(run.snapshot())
    }

    /** Called on the main thread after the current physics epoch has published. */
    @MainThread
    fun tick(world: World) {
        if (world.basicWorldData.atoCoordinator.isStalled) return
        val sectionAllocator = allocator(world)
        runs.values.toList().forEach { run ->
            if (run.state == TimetableServiceRunState.STOPPED ||
                run.state == TimetableServiceRunState.FAILED
            ) return@forEach
            if (run.train.isDerailed) {
                fail(run, "train derailed", sectionAllocator)
                return@forEach
            }
            if (run.train.controller !== run.ato) {
                fail(run, "train controller was replaced", sectionAllocator)
                return@forEach
            }
            val mission = run.ato.currentMission
            if (mission?.revision != run.missionRevision) {
                run.state = TimetableServiceRunState.WAITING_MISSION
                return@forEach
            }
            if (run.confirmedKeys.isNotEmpty()) {
                run.state = TimetableServiceRunState.RUNNING
                return@forEach
            }

            val firstRoute = mission.legs.first().plannedRoute
            when (val result = sectionAllocator.allocate(run.owner, firstRoute, sectionIndex = 0)) {
                is RouteSectionAllocationResult.Confirmed -> {
                    run.confirmedKeys += result.window.key
                    run.ato.confirmRouteWindow(result.window)
                    run.state = TimetableServiceRunState.RUNNING
                    run.message = null
                }
                is RouteSectionAllocationResult.Conflict -> {
                    run.state = TimetableServiceRunState.WAITING_ROUTE
                    run.message = "route conflict at ${result.resource}, held by ${result.heldBy.owner}"
                }
                is RouteSectionAllocationResult.Invalid -> {
                    fail(run, result.reason, sectionAllocator)
                }
            }
        }
    }

    @MainThread
    fun stopService(world: World, id: TimetableServiceRunId): Boolean {
        val run = runs[id] ?: return false
        val sectionAllocator = allocator(world)
        releaseAllocations(run, sectionAllocator)
        if (run.controllerOwned && run.train.controller === run.ato) {
            run.train.setController(null)
        }
        run.state = TimetableServiceRunState.STOPPED
        run.message = null
        return true
    }

    @MainThread
    fun status(id: TimetableServiceRunId): TimetableServiceRunSnapshot? = runs[id]?.snapshot()

    @MainThread
    fun statuses(): List<TimetableServiceRunSnapshot> = runs.values.map(ActiveRun::snapshot)

    private fun fail(run: ActiveRun, reason: String, allocator: RouteSectionAllocator) {
        releaseAllocations(run, allocator)
        run.state = TimetableServiceRunState.FAILED
        run.message = reason
    }

    private fun releaseAllocations(run: ActiveRun, allocator: RouteSectionAllocator) {
        run.confirmedKeys.forEach { key ->
            allocator.release(key)
            run.ato.releaseRouteWindow(key)
        }
        run.confirmedKeys.clear()
    }

    private fun allocator(world: World): RouteSectionAllocator {
        val currentWorld = allocatorWorld
        check(currentWorld == null || currentWorld === world) {
            "TimetableDispatcher cannot be shared between worlds"
        }
        if (currentWorld == null) {
            allocatorWorld = world
            allocator = RouteSectionAllocator(world)
        }
        return allocator!!
    }

    private fun nextMissionRevision(): Long {
        val revision = missionRevision.incrementAndGet()
        check(revision > 0L) { "mission revision overflow" }
        return revision
    }

    private fun buildMissionRequest(
        runId: TimetableServiceRunId,
        service: TimetableService,
        revision: Long
    ): AtoMissionRequest? {
        var origin: WayPointRef? = null
        val points = ArrayList<AtoMissionPoint>()
        steps@ for (step in service.steps) {
            when (step) {
                is TimetableStep.Check -> {
                    val checked = WayPointRef(step.wayPointId)
                    if (points.isEmpty()) origin = checked
                }
                is TimetableStep.At -> when (val action = step.action) {
                    is TimetableAction.GoTo -> {
                        val target = WayPointRef(action.wayPointId)
                        points += AtoMissionPoint.Stop(target)
                        break@steps
                    }
                    is TimetableAction.GoVia -> {
                        action.wayPointIds.forEachIndexed { index, id ->
                            val target = WayPointRef(id)
                            points += if (index == action.wayPointIds.lastIndex) {
                                AtoMissionPoint.Stop(target)
                            } else {
                                AtoMissionPoint.Via(target)
                            }
                        }
                        break@steps
                    }
                    is TimetableAction.Stop -> Unit
                }
            }
        }
        return AtoMissionRequest(
            id = runId.toString(),
            revision = revision,
            origin = origin ?: return null,
            points = points.takeIf { it.isNotEmpty() } ?: return null
        )
    }

    private companion object {
        const val ORIGIN_TOLERANCE_METERS = 1.0
    }

    private data class ActiveRun(
        val id: TimetableServiceRunId,
        val train: CoreTrain,
        val ato: SimpleATO,
        val controllerOwned: Boolean,
        val missionRevision: Long,
        var state: TimetableServiceRunState,
        var message: String? = null,
        val confirmedKeys: MutableList<RouteSectionAllocationKey> = ArrayList()
    ) {
        val owner = RouteSectionOwner(id.toString(), train.id)

        fun snapshot(): TimetableServiceRunSnapshot = TimetableServiceRunSnapshot(
            id = id,
            trainId = train.id,
            missionRevision = missionRevision,
            state = state,
            confirmedSectionCount = confirmedKeys.size,
            message = message
        )
    }
}
