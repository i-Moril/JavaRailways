package work.art1st.mineRails.core.physics.train

import work.art1st.mineRails.core.api.render.Asset
import work.art1st.mineRails.core.control.train.TrainController
import work.art1st.mineRails.core.physics.World
import work.art1st.mineRails.core.physics.rail.Rail
import work.art1st.mineRails.core.physics.rail.RailView
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurve
import java.lang.reflect.Constructor
import java.util.concurrent.Executors

private val dummyAsset = object : Asset {}

/**
 * 创建用于测试的 World：rails 可写，trains 可写，空 signals，单线程 scheduler。
 */
fun createTestWorld(): World {
    val rails = mutableListOf<Rail>()
    val trains = mutableMapOf<String, Train>()
    val signals = mutableSetOf<work.art1st.mineRails.core.control.signal.Signal>()
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    return World(rails, trains, signals, scheduler)
}

/**
 * 用多段 PrimeCurve 创建多段 Rail 并接成闭环：seg0.viewAB -> seg1.viewAB -> ... -> segLast.viewAB -> seg0.viewAB。
 * 返回第一段 Rail（用于在弧长 0 处放车）。
 */
fun createLoopRailFromSegments(world: World, segments: List<PrimeCurve>): Rail {
    if (segments.isEmpty()) error("至少需要一段轨道")
    val rails = segments.map { curve ->
        Rail(world, curve, mutableListOf(), mutableListOf(), emptyList())
    }
    rails.forEach { world.rails.add(it) }
    for (i in rails.indices) {
        val nextView = if (i == rails.lastIndex) rails.first().viewAB else rails[i + 1].viewAB
        rails[i].viewAB.nextRails.add(nextView)
    }
    return rails.first()
}

/**
 * 用多段 PrimeCurve 创建直线多段 Rail（不闭环）：seg0.viewAB -> seg1.viewAB -> ... -> segLast.viewAB，末端 next 为空。
 * 返回第一段 Rail。
 */
fun createStraightRailFromSegments(world: World, segments: List<PrimeCurve>): Rail {
    if (segments.isEmpty()) error("至少需要一段轨道")
    val rails = segments.map { curve ->
        Rail(world, curve, mutableListOf(), mutableListOf(), emptyList())
    }
    rails.forEach { world.rails.add(it) }
    for (i in 0 until rails.lastIndex) {
        rails[i].viewAB.nextRails.add(rails[i + 1].viewAB)
    }
    return rails.first()
}

/**
 * 在 rail 上创建一列由多节指定长度车厢组成的列车所需的所有 Car。
 * 车头在 arcLength 0 方向（viewAB 起点）。
 *
 * @param rail 轨道
 * @param world World
 * @param carLengths 每节车厢长度（米），如 listOf(10.0, 10.0) 表示 2 节 10m
 * @return 按顺序的 Car 列表（车头在前）
 */
fun createCarsOnRail(
    rail: Rail,
    world: World,
    carLengths: List<Double>
): List<Car> {
    val view = rail.viewAB
    var currentLoc: RailLocationWithFacing = RailLocationWithFacing(view, 0)
    val cars = mutableListOf<Car>()
    for ((carIndex, len) in carLengths.withIndex()) {
        val nextLoc = currentLoc.forward(len) ?: error("轨道长度不足，无法放置车厢 (car $carIndex, need $len m)")
        val bogieA = Bogie(currentLoc, true, dummyAsset)
        val bogieB = Bogie(nextLoc, true, dummyAsset)
        val half = len / 2
        val car = Car(
            id = "car-$carIndex",
            bogies = listOf(bogieA, bogieB),
            bogiesOffsets = listOf(-half, half),
            length = len,
            floorHeight = 0.0,
            connectorLengthAtA = 0.0,
            connectorLengthAtB = 0.0,
            world = world,
            asset = dummyAsset
        )
        cars.add(car)
        currentLoc = nextLoc
    }
    return cars
}

/**
 * 通过反射调用 Train 的私有构造，创建列车（controller 先传 null，创建后再设）。
 */
fun createTrain(id: String, cars: MutableList<Car>, world: World): Train {
    // JVM 上 Kotlin 的 MutableList<Car> 对应 List
    val ctor: Constructor<Train> = Train::class.java.getDeclaredConstructor(
        String::class.java,
        List::class.java,
        TrainController::class.java,
        World::class.java
    )
    ctor.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return ctor.newInstance(id, cars, null, world) as Train
}

/**
 * 从第一段 Rail 起沿 nextActiveRail 收集顺序的 RailView 列表（用于多段轨道）。
 * @param isLoop true 时沿环走直到回到起点；false 时走到末端为止。
 */
fun getViewsInOrder(firstRail: Rail, isLoop: Boolean): List<RailView> {
    val views = mutableListOf<RailView>()
    var v = firstRail.viewAB
    val start = v
    do {
        views.add(v)
        val next = v.nextActiveRail ?: break
        if (isLoop && next == start) break
        v = next
    } while (true)
    return views
}

/**
 * 多段轨道总弧长（从第一段起沿 nextActiveRail 累加）。
 */
fun totalArcLengthFromFirstRail(firstRail: Rail, isLoop: Boolean): Double =
    getViewsInOrder(firstRail, isLoop).sumOf { it.curve.totalArcLength }

/**
 * 单段轨道时，当前 head 在曲线上的弧长位置 [0, totalArcLength)。
 * viewAB 时即 arcLength；viewBA 时为 totalArcLength - arcLength。
 */
fun headCurveArcLengthInRange(train: Train, totalArcLength: Double): Double {
    val loc = train.head.location
    return when (loc.railView.direction) {
        RailView.ViewDirection.AB -> loc.arcLength
        RailView.ViewDirection.BA -> totalArcLength - loc.arcLength
    }
}

/**
 * 多段轨道时，当前 head 从起点起算的弧长位置 [0, totalArcLength)。
 * 需与 [totalArcLengthFromFirstRail] 配合使用。
 */
fun headCurveArcLengthInRangeMultiSegment(train: Train, firstRail: Rail, isLoop: Boolean): Double {
    val views = getViewsInOrder(firstRail, isLoop)
    val totalArcLength = views.sumOf { it.curve.totalArcLength }
    val loc = train.head.location
    val idx = views.indexOfFirst { it.rail == loc.rail && it.direction == loc.railView.direction }
    if (idx < 0) return 0.0
    val segmentStart = views.take(idx).sumOf { it.curve.totalArcLength }
    val localArc = when (loc.railView.direction) {
        RailView.ViewDirection.AB -> loc.arcLength
        RailView.ViewDirection.BA -> views[idx].curve.totalArcLength - loc.arcLength
    }
    val position = segmentStart + localArc
    return if (isLoop && totalArcLength > 0) position % totalArcLength else position
}
