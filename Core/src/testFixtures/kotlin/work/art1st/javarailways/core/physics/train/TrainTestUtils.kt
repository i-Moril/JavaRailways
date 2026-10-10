package work.art1st.javarailways.core.physics.train

import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.di.ensureJavaRailwaysKoinStarted
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.Executors

/**
 * 创建用于测试的 World：空轨道/列车，单线程 TickManager，使用 NoOpWorldParentContext 而非运行时宿主父 scope。
 */
fun createTestWorld(physicsCoordinator: work.art1st.javarailways.core.physics.tick.PhysicsCoordinator =
    work.art1st.javarailways.core.physics.tick.PhysicsCoordinator(1)): CoreWorld {
    ensureJavaRailwaysKoinStarted()
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    val tickManager = TickManagerImpl(scheduler, System.currentTimeMillis())
    return CoreWorld.createEmpty(tickManager, NoOpWorldParentContext, physicsCoordinator)
}

/**
 * 用多段 PrimeCurve 创建多段 Rail 并接成闭环：seg0.viewAB -> seg1.viewAB -> ... -> segLast.viewAB -> seg0.viewAB。
 * 返回第一段 Rail（用于在弧长 0 处放车）。
 */
fun createLoopRailFromSegments(world: CoreWorld, segments: List<PrimeCurve>): CoreRail {
    if (segments.isEmpty()) error("至少需要一段轨道")
    val rails = segments.map { curve ->
        world.createRailForTest(curve)
    }
    for (i in rails.indices) {
        val nextView = if (i == rails.lastIndex) rails.first().viewAB else rails[i + 1].viewAB
        CoreRail.connect(rails[i], nextView.rail)
    }
    return rails.first()
}

/**
 * 用多段 PrimeCurve 创建直线多段 Rail（不闭环）：seg0.viewAB -> seg1.viewAB -> ... -> segLast.viewAB，末端 next 为空。
 * 返回第一段 Rail。
 */
fun createStraightRailFromSegments(world: CoreWorld, segments: List<PrimeCurve>): CoreRail {
    if (segments.isEmpty()) error("至少需要一段轨道")
    val rails = segments.map { curve ->
        world.createRailForTest(curve)
    }
    for (i in 0 until rails.lastIndex) {
        CoreRail.connect(rails[i], rails[i + 1])
    }
    return rails.first()
}

/**
 * 在 rail 上创建一列由多节指定长度车厢组成的列车。
 *
 * @param rail 轨道
 * @param world World
 * @param carLengths 每节车厢长度（米），如 listOf(10.0, 10.0) 表示 2 节 10m
 * @param isLoop 是否为闭环轨道。true 时从起点尝试向前选取安全车头位置；false（直线）时从起点向前移动列车总长度，避免向后放置时超出轨道起点
 * @return 创建的列车
 */
fun createTrainWithConfig(id: String, rail: CoreRail, world: CoreWorld, carLengths: List<Double>, isLoop: Boolean = false,
    connectorLength: Double = 0.0): CoreTrain {
    // 计算列车总长度（所有车厢长度之和）
    val totalTrainLength = carLengths.sum()

    val startLocation = RailLocationWithFacing(rail.viewAB, 0.0)
    val offsetDistance = totalTrainLength + 10.0 // 额外10米防止误差导致初始化失败
    val headLocation = if (isLoop) {
        // 闭环/曲线测试也优先使用一个离起点足够远的位置，避免规范化时向后排布跨过未连接的起点。
        startLocation.forward(offsetDistance) ?: startLocation
    } else {
        // 直线轨道：从起点向前移动（列车总长度 + 10米安全余量），确保列车向后放置时不会超出轨道起点
        startLocation.forward(offsetDistance) ?: error("直线轨道长度不足，无法放置列车（需要至少 $offsetDistance 米）")
    }

    val cars = carLengths.mapIndexed { index, len ->
        val bogieA = CoreBogie.create(headLocation)
        val bogieB = CoreBogie.create(headLocation)
        CoreCar(
            id = "car-$index",
            bogies = listOf(bogieA, bogieB),
            bogiesOffsets = listOf(len / 2, -len / 2),
            length = len,
            floorHeight = 0.0,
            connectorLengthAtA = connectorLength,
            connectorLengthAtB = connectorLength,
            world = world,
            savedExtensionData = emptyMap()
        )
    }
    return CoreTrain(id, cars, world, emptyMap()).also { train ->
        train.setLocation(headLocation)
        train.commit()
    }
}

/**
 * 从第一段 Rail 起沿 nextActiveRail 收集顺序的 RailView 列表（用于多段轨道）。
 * @param isLoop true 时沿环走直到回到起点；false 时走到末端为止。
 */
fun getViewsInOrder(firstRail: CoreRail, isLoop: Boolean): List<RailView> {
    val views = mutableListOf<RailView>()
    var v = firstRail.viewAB
    val start = v
    do {
        views.add(v)
        val next = v.activeRail ?: break
        if (isLoop && next == start) break
        v = next
    } while (true)
    return views
}

/**
 * 多段轨道总弧长（从第一段起沿 nextActiveRail 累加）。
 */
fun totalArcLengthFromFirstRail(firstRail: CoreRail, isLoop: Boolean): Double =
    getViewsInOrder(firstRail, isLoop).sumOf { it.curve.totalArcLength }

/**
 * 单段轨道时，当前 head 在曲线上的弧长位置 [0, totalArcLength)。
 * viewAB 时即 arcLength；viewBA 时为 totalArcLength - arcLength。
 */
fun headCurveArcLengthInRange(train: CoreTrain, totalArcLength: Double): Double {
    val loc = train.head.location
    return when (loc.railView.viewDirection) {
        ViewDirection.AB -> loc.arcLength
        ViewDirection.BA -> totalArcLength - loc.arcLength
    }
}

/**
 * 多段轨道时，当前 head 从起点起算的弧长位置 [0, totalArcLength)。
 * 需与 [totalArcLengthFromFirstRail] 配合使用。
 */
fun headCurveArcLengthInRangeMultiSegment(train: CoreTrain, firstRail: CoreRail, isLoop: Boolean): Double {
    val views = getViewsInOrder(firstRail, isLoop)
    val totalArcLength = views.sumOf { it.curve.totalArcLength }
    val loc = train.head.location
    val idx = views.indexOfFirst { it.rail == loc.rail && it.viewDirection == loc.railView.viewDirection }
    if (idx < 0) return 0.0
    val segmentStart = views.take(idx).sumOf { it.curve.totalArcLength }
    val localArc = when (loc.railView.viewDirection) {
        ViewDirection.AB -> loc.arcLength
        ViewDirection.BA -> views[idx].curve.totalArcLength - loc.arcLength
    }
    val position = segmentStart + localArc
    return if (isLoop && totalArcLength > 0) position % totalArcLength else position
}
