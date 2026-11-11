package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.control.signal.Signal
import work.art1st.javarailways.core.api.physics.exception.InvalidActiveRailException
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.exception.CannotConnectRailException
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.physics.rail.RailView as ApiRailView
import work.art1st.javarailways.core.physics.train.TrainEnd
import java.util.concurrent.CopyOnWriteArrayList

class RailView(
    override val rail: CoreRail,
    override val viewDirection: ViewDirection,
    override val curve: PrimeCurve,
    mutableNextRails: MutableList<RailView>,
    nextActiveRail: RailView? = null
) : ApiRailView {
    internal val mutableNextRails: MutableList<RailView> = CopyOnWriteArrayList(mutableNextRails)
    internal val mutableJoinedEndpoints: MutableSet<RailEndpointRef> = java.util.concurrent.CopyOnWriteArraySet()

    /** Explicit peer members of this endpoint's authored group; never implies a legal route. */
    val joinedEndpoints: Set<RailEndpointRef> = java.util.Collections.unmodifiableSet(mutableJoinedEndpoints)

    init {
        if (nextActiveRail != null && (mutableNextRails.isEmpty() || !mutableNextRails.contains(nextActiveRail))) {
            throw InvalidActiveRailException(this, nextActiveRail)
        }
    }

    override val nextRails: List<RailView> = java.util.Collections.unmodifiableList(this.mutableNextRails)

    /** Restores exact legal paths and independently declared peer membership. */
    fun restoreConnections(nextRails: List<RailView>, activeRail: RailView?, joinedEndpoints: Collection<RailEndpointRef>) {
        rail.world.railTopologyTransaction {
            val affected = (listOf(rail.id) + this.joinedEndpoints.map { it.railId } + joinedEndpoints.map { it.railId }).toSet()
            rail.world.ensureRailEditProtected(affected)
            require(rail.world.getRail(rail.id) === rail) { "Rail is not the registered instance" }
            require(nextRails.size <= 2 && nextRails.distinct().size == nextRails.size) {
                "A rail endpoint supports at most two distinct continuations"
            }
            require(nextRails.all { it.rail.world === rail.world && rail.world.getRail(it.rail.id) === it.rail }) {
                "Connections must refer to registered rails in the same world"
            }
            require(activeRail == null || activeRail in nextRails) { "Active rail must be a continuation" }
            require(joinedEndpoints.distinct().size == joinedEndpoints.size && endpointRef() !in joinedEndpoints) {
                "Joined endpoints must be unique peer ports"
            }
            val legal = RailEndpointGeometry.legalContinuations(listOf(endpointRef()) + joinedEndpoints) { id ->
                requireNotNull(rail.world.getRail(id)) { "Joined endpoint references a missing rail $id" }.curve
            }
            require(nextRails.mapTo(linkedSetOf(), ::endpointOfAwayView) == legal.getValue(endpointRef())) {
                "Restored connections do not match the authored endpoint group geometry"
            }
            require(rail.world.occupyingTrainIds(affected).isEmpty()) {
                "Cannot restore connections on occupied rails"
            }
            this.activeRail = null
            mutableNextRails.clear()
            mutableNextRails.addAll(nextRails)
            mutableJoinedEndpoints.clear()
            mutableJoinedEndpoints.addAll(joinedEndpoints)
            this.activeRail = activeRail
        }
    }

    /** Highest tangent cosine is the straightest continuation; exact ties retain connection order. */
    internal fun selectDefaultConnection() {
        val incoming = endPoint.tangent
        activeRail = mutableNextRails.maxByOrNull { next ->
            val outgoing = next.startPoint.tangent
            incoming.dot(outgoing) / (incoming.length() * outgoing.length())
        }
    }

    @Volatile
    private var activeRailValue: RailView? = nextActiveRail

    var activeRail: RailView?
        get() = activeRailValue
        set(value) {
            if (value != null && !mutableNextRails.contains(value)) {
                throw InvalidActiveRailException(this, value)
            }
            if (activeRailValue !== value) rail.world.withRailSelectionProtection(this) {
                rail.world.updateActiveRailSelection(
                    shouldChange = { activeRailValue !== value },
                    change = { activeRailValue = value }
                )
            }
        }

    override val nextActiveRail: ApiRailView?
        get() = activeRail

    // 曲线起点（弧长 0）
    override val startPoint: Curve.Point
        get() = curve.getPointAt(0.0)

    // 曲线终点（弧长 totalArcLength）
    override val endPoint: Curve.Point
        get() = curve.getPointAt(curve.totalArcLength)

    val staticRailObjects: Collection<Signal>
        get() = when (viewDirection) {
            ViewDirection.AB -> rail.signals.filter { it.appliesTo(viewDirection) }
            ViewDirection.BA -> rail.signals.reversed().filter { it.appliesTo(viewDirection) }
        }

    val dynamicRailObjects: Collection<TrainEnd>
        get() = rail.trains

    override val reversed: RailView
        get() = when (viewDirection) {
            ViewDirection.AB -> rail.viewBA
            ViewDirection.BA -> rail.viewAB
        }

    internal fun endpointRef(): RailEndpointRef = RailEndpointRef(
        rail.id, if (viewDirection == ViewDirection.AB) RailEndpoint.B else RailEndpoint.A
    )

    override fun selectNextRail(nextRail: ApiRailView?) {
        activeRail = nextRail?.let {
            it as? RailView ?: throw IllegalArgumentException("nextRail must be a Core RailView")
        }
    }

    // 根据弧长获取 RailLocationWithFacing（唯一的公共入口）
    override fun getRailLocationAt(arcLength: Double) : RailLocationWithFacing {
        require(arcLength in 0.0..curve.totalArcLength)
        return RailLocationWithFacing(this, arcLength)
    }

    companion object {
        /**
         * I ---> J <--- K
         * 然后连接上新的轨道
         */
        @Throws(CannotConnectRailException::class)
        fun connect(viewIJ: RailView, viewKJ: RailView) {
            require(viewIJ.rail.world === viewKJ.rail.world) {
                "cannot connect rails from different worlds"
            }
            viewIJ.rail.world.railTopologyTransaction {
                val members = (viewIJ.joinedEndpoints + viewKJ.joinedEndpoints + viewIJ.endpointRef() + viewKJ.endpointRef())
                val affected = members.mapTo(linkedSetOf()) { it.railId }
                viewIJ.rail.world.ensureRailEditProtected(affected)
                require(viewIJ.rail.world.occupyingTrainIds(affected).isEmpty()) {
                    "Cannot connect occupied rails"
                }
                connectInTransaction(viewIJ, viewKJ)
            }
        }

        private fun connectInTransaction(viewIJ: RailView, viewKJ: RailView) {
            require(viewIJ.rail.world.getRail(viewIJ.rail.id) === viewIJ.rail &&
                viewKJ.rail.world.getRail(viewKJ.rail.id) === viewKJ.rail) { "Connection references an unregistered rail" }
            val j1 = viewIJ.endPoint.position
            val j2 = viewKJ.endPoint.position
            if (j1.distance(j2) > Curve.TOLERANCE) {
                throw CannotConnectRailException()
            }
            val world = viewIJ.rail.world
            val ports = (viewIJ.joinedEndpoints + viewKJ.joinedEndpoints + viewIJ.endpointRef() + viewKJ.endpointRef()).sorted()
            val legal = RailEndpointGeometry.legalContinuations(ports) { id -> requireNotNull(world.getRail(id)).curve }
            fun incoming(port: RailEndpointRef): RailView {
                val rail = requireNotNull(world.getRail(port.railId))
                return if (port.endpoint == RailEndpoint.A) rail.viewBA else rail.viewAB
            }
            ports.forEach { port ->
                val view = incoming(port)
                val oldActive = view.activeRail
                val hadLegalRoutes = view.mutableNextRails.isNotEmpty()
                val oldOrder = view.mutableNextRails.filter { endpointOfAwayView(it) in legal.getValue(port) }
                val next = oldOrder + legal.getValue(port).filterNot { target -> oldOrder.any { endpointOfAwayView(it) == target } }
                    .map { incoming(it).reversed }
                if (oldActive !in next) view.activeRail = null
                view.mutableNextRails.clear()
                view.mutableNextRails.addAll(next)
                view.mutableJoinedEndpoints.clear()
                view.mutableJoinedEndpoints.addAll(ports.filter { it != port })
                if (oldActive != null && oldActive in next) view.activeRail = oldActive
                else if (oldActive == null && hadLegalRoutes && next.isNotEmpty()) view.activeRail = null
                else view.selectDefaultConnection()
            }
        }

        private fun endpointOfAwayView(view: RailView): RailEndpointRef = view.reversed.endpointRef()
    }
}
