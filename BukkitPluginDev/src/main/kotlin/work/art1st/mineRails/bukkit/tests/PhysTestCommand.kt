package work.art1st.mineRails.bukkit.tests

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.basic.controller.ManualController
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.tests.physics.CurveEndpointTest
import work.art1st.mineRails.bukkit.logic.RailVisualBootstrap
import work.art1st.mineRails.bukkit.persistence.RailRenderMetaStore
import work.art1st.mineRails.core.api.physics.curve.Curve
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.CoreRail
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.train.CoreBogie
import work.art1st.mineRails.core.physics.train.CoreCar
import work.art1st.mineRails.core.physics.train.CoreTrain
import work.art1st.mineRails.core.util.Heavy
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PhysTestCommand : BasicCommand {

    private val rails = mutableListOf<CoreRail>()
    private var controller: ManualController? = null
    private var train: CoreTrain? = null

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
        val world = world(sender) ?: run { sender.sendMessage("No MineRails world"); return }

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
        cleanAll(sender, world)
        val o = Vector3d(sender.location.x, sender.location.y, sender.location.z)
        val normal = Vector3d(0.0, 1.0, 0.0)
        val straightLen = 200.0
        val radius = 80.0
        val step = 0.05

        // Build curves, using each segment's actual endpoint as next segment's start
        val s1 = makeStraight(o, Vector3d(1.0, 0.0, 0.0), straightLen, normal, step)
        val p1 = s1.lastPoint().position   // end of straight 1
        val a1 = makeArcRightTurn(Vector3d(p1), radius, fromEast = true, normal, step)
        val p2 = a1.lastPoint().position    // end of arc 1 (heading west)
        val s2 = makeStraight(Vector3d(p2), Vector3d(-1.0, 0.0, 0.0), straightLen, normal, step)
        val p3 = s2.lastPoint().position    // end of straight 2
        val a2 = makeArcRightTurn(Vector3d(p3), radius, fromEast = false, normal, step)

        // Create rails
        val r1 = CoreRail(world, s1, mutableListOf(), mutableListOf())
        val r2 = CoreRail(world, a1, mutableListOf(), mutableListOf())
        val r3 = CoreRail(world, s2, mutableListOf(), mutableListOf())
        val r4 = CoreRail(world, a2, mutableListOf(), mutableListOf())

        // Connect into loop: r1→r2→r3→r4→r1
        CoreRail.connect(r1, r2)
        CoreRail.connect(r2, r3)
        CoreRail.connect(r3, r4)
        CoreRail.connect(r4, r1)

        for (r in listOf(r1, r2, r3, r4)) {
            val meta = RailVisualBootstrap.registerRail(r)
            RailRenderMetaStore.upsertRail(sender.world, meta)
            rails.add(r)
        }
        val total = s1.totalArcLength + a1.totalArcLength + s2.totalArcLength + a2.totalArcLength
        sender.sendMessage("Oval: 4 rails  L=%.1fm".format(total))
    }

    @OptIn(Heavy::class)
    private fun spawnTrain(sender: Player, world: CoreWorld, carCount: Int) {
        val rail = rails.firstOrNull() ?: run { sender.sendMessage("No rail. /phys create"); return }
        val pos = Vector3d(sender.location.x, sender.location.y, sender.location.z)
        val view = rail.getView(work.art1st.mineRails.core.physics.rail.RailView.ViewDirection.AB)
        val start = RailLocationWithFacing(view, view.curve.getNearestPoint(pos).idx)

        val carLen = 2.0
        var loc: RailLocationWithFacing? = start
        val cars = mutableListOf<CoreCar>()

        repeat(carCount) { i ->
            val bA = CoreBogie.create(loc!!)
            val next = loc!!.forward(carLen)
            if (next == null) {
                sender.sendMessage("forward() failed at car $i — rail too short or disconnected")
                return@repeat
            }
            val bB = CoreBogie.create(next)
            cars.add(CoreCar.create("phys-car-$i", bA, bB, carLen, world = world))
            loc = next
        }

        if (cars.isEmpty()) { sender.sendMessage("No cars created"); return }
        val t = CoreTrain.create("phys-${UUID.randomUUID().toString().take(6)}", cars, world)
        t.setLocation(start)
        t.commit()
        train = t
        sender.sendMessage("Train ${t.id} on ${rail.id}  cars=${cars.size}")
    }

    private fun startTrain(sender: Player, world: CoreWorld) {
        val t = train ?: run { sender.sendMessage("No train"); return }
        controller = ManualController(t, world)
        t.setController(controller!!)
        controller!!.setThrottle(0.5)
        sender.sendMessage("${t.id} started @ 0.5 throttle")
    }

    private fun stopTrain(sender: Player) {
        controller?.controlledTrain?.setController(null)
        controller = null
        sender.sendMessage("Stopped")
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

    private fun cleanAll(sender: Player, world: CoreWorld) {
        controller?.controlledTrain?.setController(null)
        controller = null
        train?.destroy()
        train = null
        rails.forEach { world.removeRail(it); it.destroy() }
        rails.clear()
        sender.sendMessage("Cleaned up")
    }

    // ---- curve generators ----

    private fun makeStraight(start: Vector3dc, dir: Vector3dc, length: Double, normal: Vector3dc, step: Double): PrimeCurve {
        val n = maxOf(2, (length / step).toInt())
        val pts = mutableListOf<Curve.RawPoint>()
        // First point: exact start reference for endpoint matching
        pts.add(Curve.RawPoint(Vector3d(start), Vector3d(dir), normal, 0.0))
        var arclen = 0.0
        for (i in 1..n) {
            val t = i.toDouble() / n
            val p = Vector3d(start).add(dir.mul(t * length, Vector3d()), Vector3d())
            arclen += p.distance(pts.last().position)
            pts.add(Curve.RawPoint(p, Vector3d(dir), normal, arclen))
        }
        return PrimeCurve.fromPoints(pts)
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

    private fun PrimeCurve.lastPoint(): Curve.Point = getPointAt(totalPoints - 1)
}
