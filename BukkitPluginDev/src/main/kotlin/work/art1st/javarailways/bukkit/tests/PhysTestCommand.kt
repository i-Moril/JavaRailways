package work.art1st.javarailways.bukkit.tests

import java.util.concurrent.CompletableFuture
import work.art1st.javarailways.core.api.util.DestructionSteps
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditImpact
import work.art1st.javarailways.core.api.physics.infrastructure.TopologyEditRequest
import work.art1st.javarailways.basic.controller.ManualController
import work.art1st.javarailways.basic.infrastructure.PhysicalTopologyExplicitConnectionImpactResolver
import work.art1st.javarailways.basic.infrastructure.PhysicalTopologyReplacementOrRemovalImpactResolver
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.tests.physics.CurveEndpointTest
import work.art1st.javarailways.bukkit.logic.RailVisualBootstrap
import work.art1st.javarailways.bukkit.persistence.RailRenderMetaStore
import work.art1st.javarailways.bukkit.util.BukkitAsyncTasks
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.PreparedRailCreationBatch
import work.art1st.javarailways.core.physics.rail.PreparedRailConnectionPlan
import work.art1st.javarailways.core.physics.rail.PreparedRailEndpointConnection
import work.art1st.javarailways.core.physics.rail.PreparedRailCreationRejectedException
import work.art1st.javarailways.core.physics.train.CoreCar
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.core.api.util.annotation.Heavy
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PhysTestCommand(
    private val ownerPlugin: Plugin
) : BasicCommand {

    private data class PreparedPlayground(
        val curves: List<PrimeCurve>,
        val rails: PreparedRailCreationBatch,
        val connections: PreparedRailConnectionPlan,
        val impact: TopologyEditImpact?
    )

    private val rails = mutableListOf<CoreRail>()
    @Volatile private var controller: ManualController? = null
    @Volatile private var train: CoreTrain? = null

    private fun world(player: Player): CoreWorld {
        val mgr = BukkitPlugin.INSTANCE.worldManager
        val w = mgr.railWorlds[player.world] as? CoreWorld
        if (w != null) return w
        mgr.registerRailWorld(player.world)
        return mgr.railWorlds[player.world] as CoreWorld
    }

    override fun execute(ctx: CommandSourceStack, args: Array<out String>) {
        val sender = ctx.sender
        if (sender !is Player) { sender.sendMessage("Player only"); return }
        val world = world(sender) ?: run { sender.sendMessage("No JavaRailways world"); return }

        when (args.firstOrNull()) {
            "create" -> createPlayground(sender, world)
            "train" -> spawnTrain(sender, world, args.getOrNull(1)?.toIntOrNull() ?: 2)
            "start" -> startTrain(sender, world)
            "info" -> showInfo(sender, world)
            "stop" -> stopTrain(sender)
            "clean" -> cleanAll(sender, world)
            "check" -> checkRail(sender, world)
            "test" -> runUnitTests(sender)
            else -> sender.sendMessage("/phys {create | train | start | stop | info | clean | check | test}")
        }
    }

    private fun createPlayground(sender: Player, world: CoreWorld) {
        cleanAll(sender, world) { preparePlayground(sender, world) }
    }

    private fun preparePlayground(sender: Player, world: CoreWorld) {
        val o = Vector3d(sender.location.x, sender.location.y, sender.location.z)
        val normal = Vector3d(0.0, 1.0, 0.0)
        val straightLen = 200.0
        val radius = 80.0
        val step = 0.005  // 5mm to match Curve.TOLERANCE

        BukkitAsyncTasks.supply(
            plugin = BukkitPlugin.INSTANCE,
            completionEntity = sender,
            operation = "PhysTestCommand.createPlayground",
            supplier = {
                // Use each segment's actual endpoint as the next segment's start.
                val s1 = makeStraight(o, Vector3d(1.0, 0.0, 0.0), straightLen, normal, step)
                val a1 = makeArcRightTurn(Vector3d(s1.lastPoint().position), radius, true, normal, step)
                val s2 = makeStraight(
                    Vector3d(a1.lastPoint().position),
                    Vector3d(-1.0, 0.0, 0.0),
                    straightLen,
                    normal,
                    step
                )
                val a2 = makeArcRightTurn(Vector3d(s2.lastPoint().position), radius, false, normal, step)
                val curves = listOf(s1, a1, s2, a2)
                val preparedRails = world.prepareRailCreationBatch(curves)
                val railIds = preparedRails.creations.map { it.railId }
                val endpointStates = railIds.flatMapIndexed { index, railId ->
                    val previous = railIds[(index + railIds.size - 1) % railIds.size]
                    val next = railIds[(index + 1) % railIds.size]
                    listOf(
                        PreparedRailEndpointConnection(
                            RailEndpointRef(railId, RailEndpoint.A),
                            listOf(RailEndpointRef(previous, RailEndpoint.B))
                        ),
                        PreparedRailEndpointConnection(
                            RailEndpointRef(railId, RailEndpoint.B),
                            listOf(RailEndpointRef(next, RailEndpoint.A))
                        )
                    )
                }
                val connections = PreparedRailConnectionPlan.capture(world, preparedRails, endpointStates)
                val impact = PhysicalTopologyExplicitConnectionImpactResolver.resolve(
                    connections.topologyRoot,
                    connections.affectedExistingEndpoints,
                    connections.prospectiveCrossingExistingRailIds
                )
                PreparedPlayground(curves, preparedRails, connections, impact)
            },
            onSuccess = { prepared ->
                if (!sender.isValid || world(sender) !== world) return@supply
                val editPermit = acquireEditPermit(sender, world, prepared.rails.topologyRevision, prepared.impact)
                    ?: return@supply
                try {
                    editPermit.use {
                        prepared.connections.requireReadyToApply(world)
                        world.railTopologyTransaction {
                            world.installPreparedRails(prepared.rails).also { rails ->
                                prepared.connections.apply(world, rails)
                                rails.forEach { rail ->
                                    val meta = RailVisualBootstrap.registerRail(rail)
                                    RailRenderMetaStore.upsertRail(sender.world, meta)
                                    this@PhysTestCommand.rails += rail
                                }
                            }
                        }
                    }
                } catch (_: PreparedRailCreationRejectedException) {
                    sender.sendMessage("Rail topology changed while preparing the oval; run /phys create again")
                    return@supply
                } catch (failure: Exception) {
                    sender.sendMessage("Oval creation failed: ${failure.message ?: failure::class.simpleName}")
                    return@supply
                }
                sender.sendMessage("Oval: 4 rails  L=%.1fm".format(prepared.curves.sumOf { it.totalArcLength }))
            },
            onFailure = { failure ->
                sender.sendMessage("Oval preparation failed: ${failure.message ?: failure::class.simpleName}")
            }
        )
    }

    @OptIn(Heavy::class)
    private fun spawnTrain(sender: Player, world: CoreWorld, carCount: Int) {
        val rail = rails.firstOrNull() ?: run { sender.sendMessage("No rail. /phys create"); return }
        val pos = Vector3d(sender.location.x, sender.location.y, sender.location.z)
        val view = rail.getView(work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB)
        val start = RailLocationWithFacing(view, view.curve.getNearestPoint(pos).arcLength)

        world.physicsCoordinator.submitBetweenFrames {
            check(world.getRail(rail.id) === rail) { "Rail changed; spawn again" }
            val carLen = 2.0
            var loc: RailLocationWithFacing? = start
            val cars = mutableListOf<CoreCar>()
            CoreTrain.createOwned(world) {
                repeat(carCount) { i ->
                    val bA = bogie(loc!!)
                    val next = loc!!.forward(carLen)
                    if (next == null) {
                        error("forward() failed at car $i — rail too short or disconnected")
                    }
                    val bB = bogie(next)
                    cars.add(car("phys-car-$i", bA, bB, carLen))
                    loc = next
                }

                check(cars.isNotEmpty()) { "No cars created" }
                val t = train("phys-${UUID.randomUUID().toString().take(6)}", cars)
                t.setLocation(start)
                publish()
                this@PhysTestCommand.train = t
                "Train ${t.id} on ${rail.id}  cars=${cars.size}"
            }

        }.whenComplete { message, failure ->
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(sender, action = {
                sender.sendMessage(if (failure == null) message else "Spawn failed: ${failure.message}")
            })
        }
    }

    private fun startTrain(sender: Player, world: CoreWorld) {
        val t = train ?: run { sender.sendMessage("No train"); return }
        world.physicsCoordinator.submitBetweenFrames {
            check(world.getTrain(t.id) === t) { "Train changed" }
            controller = ManualController(t).also {
                t.setController(it)
                it.setThrottle(0.5)
            }
        }.whenComplete { _, failure ->
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(sender, action = {
                sender.sendMessage(if (failure == null) "${t.id} started @ 0.5 throttle" else "Start failed: ${failure.message}")
            })
        }
    }

    private fun stopTrain(sender: Player) {
        val oldController = controller ?: run { sender.sendMessage("Stopped"); return }
        val train = oldController.controlledTrain as CoreTrain
        train.world.physicsCoordinator.submitBetweenFrames {
            if (train.controller === oldController) train.setController(null)
            if (controller === oldController) controller = null
        }.whenComplete { _, failure ->
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(sender, action = {
                sender.sendMessage(if (failure == null) "Stopped" else "Stop failed: ${failure.message}")
            })
        }
    }

    private fun showInfo(sender: Player, world: CoreWorld) {
        val t = train
        if (t == null) { sender.sendMessage("No train"); return }
        sender.sendMessage("--- ${t.id} ---")
        sender.sendMessage("  speed=${"%.2f".format(t.speed)} m/s  derailed=${t.isDerailed}")
        for ((i, car) in t.cars.withIndex()) {
            sender.sendMessage("  car$i pos=(%.1f,%.1f,%.1f) dir=(%.2f,%.2f,%.2f)".format(
                car.position.x(), car.position.y(), car.position.z(),
                car.direction.x(), car.direction.y(), car.direction.z()))
            sender.sendMessage("    bA=(%.1f,%.1f,%.1f)  bB=(%.1f,%.1f,%.1f)".format(
                car.bogieA.position.x(), car.bogieA.position.y(), car.bogieA.position.z(),
                car.bogieB.position.x(), car.bogieB.position.y(), car.bogieB.position.z()))
        }
    }

    @OptIn(Heavy::class)
    private fun checkRail(sender: Player, world: CoreWorld) {
        val pos = Vector3d(sender.location.x, sender.location.y, sender.location.z)
        val nearest = world.getNearestRail(pos)
        if (nearest != null)
            sender.sendMessage("Nearest: ${nearest.id} dist=%.1f".format(nearest.curve.getNearestPoint(pos).position.distance(pos)))
        else
            sender.sendMessage("No rail found. Phys rails: ${rails.size}")
    }

    private fun runUnitTests(sender: CommandSender) {
        var passed = 0
        var failed = 0
        try {
            CurveEndpointTest().testEndpointsMatch()
            passed++
            sender.sendMessage("PASS: curve_endpoints")
        } catch (e: Exception) {
            failed++
            sender.sendMessage("FAIL: curve_endpoints - ${e.message}")
        }
        sender.sendMessage("Tests: ${passed + failed} run, $passed passed, $failed failed")
    }

    private fun cleanAll(sender: Player, world: CoreWorld, afterCleanup: () -> Unit = {}) {
        val previousTrain = train
        val previousController = controller
        world.physicsCoordinator.submitBetweenFrames {
            check(previousTrain == null || previousTrain.world === world) { "Playground belongs to another world" }
            val cleanup = if (previousTrain != null && world.getTrain(previousTrain.id) === previousTrain) {
                previousTrain.destroy()
            } else CompletableFuture.completedFuture<Void>(null)
            if (controller === previousController) controller = null
            if (train === previousTrain) train = null
            cleanup
        }.thenCompose { it }.whenComplete { _, failure ->
            BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(sender, action = {
                if (failure != null) sender.sendMessage("Cleanup failed: ${failure.message}")
                else cleanRails(sender, world).whenComplete { cleaned, railFailure ->
                    BukkitPlugin.INSTANCE.platformDispatcher.runForEntity(sender, action = {
                        if (railFailure != null) sender.sendMessage("Cleanup failed: ${railFailure.message}")
                        else if (cleaned) {
                            sender.sendMessage("Cleaned up")
                            afterCleanup()
                        }
                    })
                }
            })
        }
    }

    private fun cleanRails(sender: Player, world: CoreWorld): java.util.concurrent.CompletionStage<Boolean> {
        val cleanup = DestructionSteps()
        val liveRails = rails.mapNotNull { rail -> world.getRail(rail.id)?.takeIf { it === rail } }
        if (liveRails.isNotEmpty()) {
            val topology = world.topologyState
            val impact = PhysicalTopologyReplacementOrRemovalImpactResolver.resolve(
                topology,
                liveRails.map(CoreRail::id)
            )
            val editPermit = acquireEditPermit(sender, world, topology.revision, impact)
                ?: return CompletableFuture.completedFuture(false)
            editPermit.use {
                check(world.topologyState === topology) { "Rail topology changed before playground cleanup" }
                world.railTopologyTransaction {
                    liveRails.forEach { rail ->
                        RailVisualBootstrap.unregisterRail(rail.id)
                        cleanup.await { world.removeRail(rail) }
                        RailRenderMetaStore.removeRail(sender.world, rail.id)
                    }
                }
            }
        }
        rails.clear()
        return cleanup.finish().thenApply { true }
    }

    private fun acquireEditPermit(
        sender: Player,
        world: CoreWorld,
        topologyRevision: Long,
        impact: TopologyEditImpact?
    ): AutoCloseable? {
        if (impact == null) return NO_OP_EDIT_PERMIT
        val result = BukkitPlugin.INSTANCE.infrastructureResourceService.acquireEditPermit(
            ownerPlugin,
            sender.world.uid,
            TopologyEditRequest(
                topologyRevision,
                InfrastructureOwner("javarailways-dev:phys/${UUID.randomUUID()}", topologyRevision),
                impact
            )
        )
        return result.permit().orElse(null).also { permit ->
            if (permit == null) {
                sender.sendMessage(
                    "Infrastructure edit denied: ${result.decision().status} ${result.decision().detail}"
                )
            } else if (world.topologyRevision != topologyRevision) {
                permit.close()
                sender.sendMessage("Rail topology changed while acquiring the edit permit; retry")
            }
        }?.takeIf { world.topologyRevision == topologyRevision }
    }

    private companion object {
        val NO_OP_EDIT_PERMIT = object : AutoCloseable {
            override fun close() = Unit
        }
    }

    // ---- curve generators ----

    // P1: 直线段仅用 2 点 (LINE_START + LINE_END)，getPointAt 自动线性插值
    private fun makeStraight(start: Vector3dc, dir: Vector3dc, length: Double, normal: Vector3dc, step: Double): PrimeCurve {
        val endPos = Vector3d(start).add(Vector3d(dir).mul(length), Vector3d())
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(start), Vector3d(dir), normal, 0.0,
                Curve.SegmentType.LINE_START),
            Curve.RawPoint(endPos, Vector3d(dir), normal, length,
                Curve.SegmentType.LINE_END)
        ))
    }

    /** Semicircle right turn from startPoint (heading east→west, or west→east). */
    private fun makeArcRightTurn(startPoint: Vector3dc, radius: Double, fromEast: Boolean, normal: Vector3dc, step: Double): PrimeCurve {
        val (startAngle, endAngle) = if (fromEast)
            -PI / 2 to PI / 2
        else
            PI / 2 to 3 * PI / 2

        val sinStart = sin(startAngle)
        val center = Vector3d(startPoint).add(0.0, 0.0, -radius * sinStart, Vector3d())

        val arcLen = radius * PI
        val n = maxOf(200, (arcLen / step).toInt())
        val pts = mutableListOf<Curve.RawPoint>()
        // First point: use exact startPoint reference for endpoint matching
        pts.add(Curve.RawPoint(Vector3d(startPoint), Vector3d(-sin(startAngle), 0.0, cos(startAngle)), normal, 0.0))
        var arclen = 0.0
        for (i in 1..n) {
            val a = startAngle + (endAngle - startAngle) * i / n
            val p = Vector3d(center).add(radius * cos(a), 0.0, radius * sin(a), Vector3d())
            val t = Vector3d(-sin(a), 0.0, cos(a))
            arclen += p.distance(pts.last().position)
            pts.add(Curve.RawPoint(p, t, normal, arclen))
        }
        return PrimeCurve.fromPoints(pts)
    }

    private fun PrimeCurve.lastPoint(): Curve.Point = getPointAt(totalArcLength)
}
