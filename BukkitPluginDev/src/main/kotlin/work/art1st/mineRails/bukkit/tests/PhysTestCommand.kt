package work.art1st.mineRails.bukkit.tests

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.joml.Vector3d
import org.joml.Vector3dc
import work.art1st.mineRails.basic.controller.ManualController
import work.art1st.mineRails.bukkit.BukkitPlugin
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

class PhysTestCommand : CommandExecutor {

    private val rails = mutableListOf<CoreRail>()
    private var controller: ManualController? = null
    private var train: CoreTrain? = null

    private fun world(player: Player): CoreWorld? =
        BukkitPlugin.INSTANCE.worldManager.railWorlds[player.world] as? CoreWorld

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Player only"); return true }
        val world = world(sender) ?: run { sender.sendMessage("No MineRails world"); return true }

        when (args.firstOrNull()) {
            "create" -> createPlayground(sender, world)
            "train" -> spawnTrain(sender, world, args.getOrNull(1)?.toIntOrNull() ?: 2)
            "start" -> startTrain(sender, world)
            "info" -> showInfo(sender, world)
            "stop" -> stopTrain(sender)
            "clean" -> cleanAll(sender, world)
            else -> sender.sendMessage("/phys {create | train [cars] | start | stop | info | clean}")
        }
        return true
    }

    private fun createPlayground(sender: Player, world: CoreWorld) {
        cleanAll(sender, world)
        val o = Vector3d(sender.location.x, sender.location.y, sender.location.z)
        val oval = makeOval(o, 40.0, 16.0)
        rails.add(CoreRail(world, oval, mutableListOf(), mutableListOf()))
        sender.sendMessage("Oval: ${rails.last().id}  L=${"%.1f".format(oval.totalArcLength)}m")
    }

    @OptIn(Heavy::class)
    private fun spawnTrain(sender: Player, world: CoreWorld, carCount: Int) {
        val rail = rails.firstOrNull() ?: run { sender.sendMessage("No rail. /phys create"); return }
        val pos = Vector3d(sender.location.x, sender.location.y, sender.location.z)
        val view = rail.getView(work.art1st.mineRails.core.physics.rail.RailView.ViewDirection.AB)
        val start = RailLocationWithFacing(view, view.curve.getNearestPoint(pos).idx)

        val carLen = 2.0
        var loc = start
        val cars = mutableListOf<CoreCar>()

        repeat(carCount) { i ->
            val bA = CoreBogie.create(loc)
            val next = loc.forward(carLen) ?: loc
            val bB = CoreBogie.create(next)
            cars.add(CoreCar.create("phys-car-$i", bA, bB, carLen, world = world))
            loc = next
        }

        val t = CoreTrain.create("phys-${UUID.randomUUID().toString().take(6)}", cars, world)
        t.setLocation(start)
        t.commit()
        train = t
        sender.sendMessage("Train ${t.id} on ${rail.id}  cars=$carCount")
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

    private fun cleanAll(sender: Player, world: CoreWorld) {
        controller?.controlledTrain?.setController(null)
        controller = null
        train?.destroy()
        train = null
        rails.forEach { world.removeRail(it); it.destroy() }
        rails.clear()
        sender.sendMessage("Cleaned up")
    }

    // ---- oval generator ----
    // Two straights (east/west) + two 180° semicircles (right-turn)

    private fun makeOval(start: Vector3dc, straightLen: Double, radius: Double): PrimeCurve {
        val step = Curve.PRECISION
        val normal = Vector3d(0.0, 1.0, 0.0)
        val east = Vector3d(1.0, 0.0, 0.0)
        val west = Vector3d(-1.0, 0.0, 0.0)
        val south = Vector3d(0.0, 0.0, -1.0)
        val north = Vector3d(0.0, 0.0, 1.0)

        val pts = mutableListOf<Curve.RawPoint>()
        var arclen = 0.0

        fun add(p: Vector3dc, t: Vector3dc) {
            if (pts.isNotEmpty()) arclen += p.distance(pts.last().position)
            pts.add(Curve.RawPoint(Vector3d(p), Vector3d(t), normal, arclen))
        }

        // Straight 1: east
        val nS = maxOf(2, (straightLen / step).toInt())
        for (i in 0..nS)
            add(start.add(east.mul(i * straightLen / nS, Vector3d()), Vector3d()), east)

        // Right turn 180° (center south, sweep from -90° to +90°)
        val c1 = pts.last().position.add(south.mul(radius, Vector3d()), Vector3d())
        val nC = maxOf(100, (PI * radius / step).toInt())
        for (i in 0..nC) {
            val a = -PI / 2 + PI * i / nC
            add(c1.add(radius * cos(a), 0.0, radius * sin(a), Vector3d()), Vector3d(-sin(a), 0.0, cos(a)))
        }

        // Straight 2: west
        val s2 = Vector3d(pts.last().position)
        for (i in 0..nS)
            add(s2.add(west.mul(i * straightLen / nS, Vector3d()), Vector3d()), west)

        // Right turn 180° (center north, sweep from +90° to -90°)
        val c2 = pts.last().position.add(north.mul(radius, Vector3d()), Vector3d())
        for (i in 0..nC) {
            val a = PI / 2 + PI * i / nC
            add(c2.add(radius * cos(a), 0.0, radius * sin(a), Vector3d()), Vector3d(-sin(a), 0.0, cos(a)))
        }

        return PrimeCurve.fromPoints(pts)
    }
}
