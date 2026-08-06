package work.art1st.javarailways.bukkit.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.depot.DepotId
import work.art1st.javarailways.basic.api.depot.DepotResetCoordinator
import work.art1st.javarailways.basic.api.depot.DepotResetResult
import work.art1st.javarailways.basic.api.depot.RollingStockRecord
import work.art1st.javarailways.basic.api.depot.StablingPointRef
import work.art1st.javarailways.basic.api.destination.DestinationRole
import work.art1st.javarailways.basic.api.destination.OperationalDestination
import work.art1st.javarailways.basic.api.destination.OperationalDestinationService
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.route.RouteBuildResult
import work.art1st.javarailways.basic.route.RoutePlanner
import work.art1st.javarailways.basic.timetable.ManualNavigationParser
import work.art1st.javarailways.basic.timetable.ManualNavigationTimetableFactory
import work.art1st.javarailways.basic.timetable.TimetableDumper
import work.art1st.javarailways.basic.timetable.TimetableEpochStartResult
import work.art1st.javarailways.basic.timetable.TimetableReplanResult
import work.art1st.javarailways.basic.timetable.TimetableServiceRunId
import work.art1st.javarailways.basic.timetable.TimetableServiceStartResult
import work.art1st.javarailways.basic.timetable.TimetableValidator
import work.art1st.javarailways.basic.timetable.TimetableWaypointProgressListener
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.diagnostics.PluginDiagnosticLog
import work.art1st.javarailways.bukkit.config.TimetableWebUiMode
import work.art1st.javarailways.bukkit.http.TimetableScriptNormalizer
import work.art1st.javarailways.bukkit.debug.AtoStallDiagnostics
import work.art1st.javarailways.bukkit.debug.DepotResetDiagnostics
import work.art1st.javarailways.bukkit.extension.renderChunkManager
import work.art1st.javarailways.bukkit.control.TrainKeyItem
import work.art1st.javarailways.bukkit.extension.train.rendererOrNull
import work.art1st.javarailways.bukkit.render.backend.PacketPlatformDiagnostics
import work.art1st.javarailways.bukkit.render.model.SeatModel
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.core.api.physics.World as RailWorld
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.train.CoreTrain
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class JavaRailwaysCommand : CommandExecutor, TabCompleter {
    private val navigationCounter = AtomicLong()
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean = BukkitThreadGuardBridge.runInSimulationContext {
        try {
            executeCommand(sender, args)
        } catch (failure: Throwable) {
            val path = PluginDiagnosticLog.command(
                "COMMAND_FAILED",
                "sender=${sender.name}\ncommand=/$label ${args.joinToString(" ")}",
                failure
            )
            sender.sendMessage(
                "JavaRailways 命令执行失败；诊断已写入 ${path?.toString() ?: "服务器日志"}"
            )
            true
        }
    }

    private fun executeCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "rail" -> return RailCommand.onCommand(sender, args.drop(1))
            "train" -> return TrainCommand.onCommand(sender, args.drop(1))
            "wand" -> return handleWand(sender, args.drop(1))
            "waypoint", "wp", "stop" -> return handleWayPoint(sender, args.drop(1))
            "route" -> return handleRoute(sender, args.drop(1))
            "navigate", "nav" -> return handleNavigate(sender, args.drop(1))
            "timetable", "tt" -> return handleTimetable(sender, args.drop(1))
            "depot" -> return handleDepot(sender, args.drop(1))
            "ato" -> return handleAto(sender, args.drop(1))
            "perf", "performance" -> return handlePerf(sender, args.drop(1))
            "undo" -> return handleUndo(sender)
            "redo" -> return handleRedo(sender)
            "help" -> sendHelp(sender)
            else -> sender.sendMessage("未知命令: ${args[0]}")
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> = BukkitThreadGuardBridge.runInSimulationContext {
        completeCommand(sender, args)
    }

    private fun completeCommand(sender: CommandSender, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("rail", "train", "wand", "waypoint", "route", "navigate", "timetable", "depot", "ato", "stop", "perf", "undo", "redo", "help")
        }
        if (args[0].equals("navigate", ignoreCase = true) ||
            args[0].equals("nav", ignoreCase = true)
        ) {
            val candidates = ArrayList<String>()
            if (args.none { it == "--measure" || it == "-m" }) {
                candidates += "--measure"
                candidates += "-m"
            }
            if (sender is Player) {
                BukkitPlugin.INSTANCE.worldManager.railWorlds[sender.world]?.basicWorldData
                    ?.wayPoints?.all()?.forEach { waypoint ->
                        candidates += waypoint.id
                        candidates += "+${waypoint.id}"
                    }
            }
            val prefix = args.lastOrNull().orEmpty()
            return candidates.filter { it.startsWith(prefix, ignoreCase = true) }
        }
        if (args[0].equals("ato", ignoreCase = true)) {
            if (args.size == 2) return listOf("status", "dump", "reset")
            if (args.size == 3) return BukkitPlugin.INSTANCE.worldManager.railWorlds.keys.map { it.name }
        }
        if (args[0].equals("timetable", ignoreCase = true) || args[0].equals("tt", ignoreCase = true)) {
            if (args.size == 2) return listOf("edit", "load", "dump", "list", "start", "status", "stop", "replan")
            if (args.size == 3 && args[1].equals("load", ignoreCase = true)) {
                return BukkitPlugin.INSTANCE.timetableScriptSandbox.listScripts().map { it.name }
            }
        }
        return emptyList()
    }

    private fun handleWand(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Player only"); return true }
        when (args.firstOrNull()?.lowercase()) {
            "rail", "give" -> {
                BukkitPlugin.INSTANCE.railWand.giveWand(sender)
                sender.sendMessage("RailWand 已给予")
            }
            "railobject", "object", "ro" -> {
                BukkitPlugin.INSTANCE.railObjectWand.giveWand(sender)
                sender.sendMessage("RailObjectWand 已给予")
            }
            else -> sender.sendMessage("用法: /jr wand <rail|railobject> (设置请 Shift+右键打开 Dialog)")
        }
        return true
    }

    private fun handleWayPoint(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Player only"); return true }
        val world = BukkitPlugin.INSTANCE.worldManager.railWorlds[sender.world] ?: run {
            sender.sendMessage("错误: 无法获取 JavaRailways 世界")
            return true
        }
        val store = world.basicWorldData.wayPoints
        when (args.firstOrNull()?.lowercase()) {
            "list" -> {
                val wayPoints = store.all()
                if (wayPoints.isEmpty()) {
                    sender.sendMessage("当前没有路径点")
                } else {
                    sender.sendMessage("=== 路径点列表 ===")
                    wayPoints.forEach { wayPoint ->
                        val destination = world.basicWorldData.destinations[wayPoint.id]
                        sender.sendMessage(
                            "${wayPoint.id} (${wayPoint.displayName}) rail=${wayPoint.railId} " +
                                "s=${"%.2f".format(wayPoint.arcLength)} " +
                                "role=${destination?.role?.type ?: "LEGACY"} " +
                                "direction=${destination?.approachDirection ?: "any"}"
                        )
                    }
                }
            }
            "info" -> {
                val id = args.getOrNull(1)
                if (id == null) {
                    sender.sendMessage("用法: /jr waypoint info <id>")
                } else {
                    val wayPoint = store[id]
                    if (wayPoint == null) {
                        sender.sendMessage("路径点不存在: $id")
                    } else {
                        sender.sendMessage("=== 路径点 ${wayPoint.id} ===")
                        sender.sendMessage("显示名: ${wayPoint.displayName}")
                        sender.sendMessage("轨道: ${wayPoint.railId}")
                        sender.sendMessage("弧长: ${"%.2f".format(wayPoint.arcLength)}")
                        val destination = world.basicWorldData.destinations[wayPoint.id]
                        sender.sendMessage("角色: ${destination?.role?.type ?: "LEGACY"}")
                        sender.sendMessage("接近方向: ${destination?.approachDirection ?: "any"}")
                    }
                }
            }
            "remove" -> {
                val id = args.getOrNull(1)
                if (id == null) {
                    sender.sendMessage("用法: /jr waypoint remove <id>")
                } else {
                    val removed = if (world.basicWorldData.destinations[id] != null) {
                        OperationalDestinationService(world).remove(id)?.toWayPoint()
                    } else {
                        store.remove(id)
                    }
                    if (removed == null) {
                        sender.sendMessage("路径点不存在: $id")
                    } else {
                        BukkitPlugin.INSTANCE.railObjectWand.removeMarker(sender.world.uid, id)
                        sender.sendMessage("已删除路径点: $id")
                    }
                }
            }
            else -> sender.sendMessage("用法: /jr waypoint <list|info|remove> (旧别名: /jr stop)")
        }
        return true
    }

    private fun handleRoute(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Player only"); return true }
        val world = BukkitPlugin.INSTANCE.worldManager.railWorlds[sender.world] ?: run {
            sender.sendMessage("错误: 无法获取 JavaRailways 世界")
            return true
        }
        when (args.firstOrNull()?.lowercase()) {
            "validate" -> {
                val from = args.getOrNull(1)
                val to = args.getOrNull(2)
                if (from == null || to == null) {
                    sender.sendMessage("用法: /jr route validate <fromWaypoint> <toWaypoint>")
                    return true
                }
                when (val result = RoutePlanner(world).buildRoute(from, to)) {
                    is RouteBuildResult.Success -> {
                        val route = result.route
                        sender.sendMessage("=== 经路验证成功 ===")
                        sender.sendMessage("${route.fromWayPointId} -> ${route.toWayPointId}")
                        sender.sendMessage("总长度: ${"%.2f".format(route.totalLength)} m")
                        sender.sendMessage("轨道段数: ${route.segments.size}")
                        route.segments.forEachIndexed { index, segment ->
                            sender.sendMessage(
                                "${index + 1}. rail=${segment.railViewRef.railId} dir=${segment.railViewRef.direction} " +
                                    "s=${"%.2f".format(segment.startArcLength)}..${"%.2f".format(segment.endArcLength)} " +
                                    "len=${"%.2f".format(segment.length)}"
                            )
                        }
                    }
                    is RouteBuildResult.Failure -> sender.sendMessage("经路验证失败: ${result.reason}")
                }
            }
            else -> sender.sendMessage("用法: /jr route validate <fromWaypoint> <toWaypoint>")
        }
        return true
    }

    private fun handleNavigate(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要驾驶员执行")
            return true
        }
        val measure = args.any { it == "--measure" || it == "-m" }
        val waypointTokens = args.filterNot { it == "--measure" || it == "-m" }
        if (waypointTokens.any { it.startsWith('-') }) {
            sender.sendMessage("未知选项；用法: /jr navigate [--measure|-m] <waypoints...>")
            return true
        }
        val points = try {
            ManualNavigationParser.parse(waypointTokens)
        } catch (failure: IllegalArgumentException) {
            sender.sendMessage("导航参数错误: ${failure.message}")
            return true
        }
        val world = BukkitPlugin.INSTANCE.worldManager.railWorlds[sender.world] as? CoreWorld ?: run {
            sender.sendMessage("错误: 无法获取 JavaRailways CoreWorld")
            return true
        }
        val train = SeatModel.SeatInteractionListener.drivingTrainFor(sender) as? CoreTrain ?: run {
            sender.sendMessage("导航失败：请坐在当前列车的驾驶座上")
            return true
        }
        if (!TrainKeyItem.playerHasKey(sender)) {
            sender.sendMessage("导航失败：需要持有当前列车钥匙")
            return true
        }
        val head = train.headLocation as? RailLocationWithFacing ?: run {
            sender.sendMessage("导航失败：无法确定列车当前方向")
            return true
        }
        val data = world.basicWorldData
        if (data.atoCoordinator.isStalled) {
            sender.sendMessage("导航失败：当前世界 ATO 已 Stall，请先检查诊断并执行 ato reset")
            return true
        }
        var sequence: Long
        var originId: String
        var timetableId: String
        do {
            sequence = navigationCounter.incrementAndGet()
            originId = "__navigate_origin_$sequence"
            timetableId = "manual-navigate-$sequence"
        } while (data.wayPoints[originId] != null || data.destinations[originId] != null ||
            data.timetables[timetableId] != null)
        val serviceId = "navigate"
        data.wayPoints.add(
            WayPoint(
                id = originId,
                displayName = originId,
                railId = head.rail.id,
                arcLength = head.arcLengthIn(ViewDirection.AB)
            )
        )
        data.destinations.add(
            OperationalDestination(
                id = originId,
                displayName = originId,
                railId = head.rail.id,
                arcLengthInAbMeters = head.arcLengthIn(ViewDirection.AB),
                approachDirection = head.facing,
                role = DestinationRole.Regular
            )
        )
        val timetable = try {
            ManualNavigationTimetableFactory.create(
                world = world,
                timetableId = timetableId,
                serviceId = serviceId,
                trainId = train.id,
                originWayPointId = originId,
                points = points
            )
        } catch (failure: Exception) {
            data.destinations.remove(originId)
            data.wayPoints.remove(originId)
            sender.sendMessage("导航构建失败: ${failure.message}")
            return true
        }
        val validation = TimetableValidator(world).validate(timetable)
        if (!validation.isSuccess) {
            data.destinations.remove(originId)
            data.wayPoints.remove(originId)
            sender.sendMessage("导航验证失败:")
            validation.errors.forEach { sender.sendMessage("- $it") }
            return true
        }
        val trainControlManager = BukkitPlugin.INSTANCE.trainControlManager
        val managedSession = trainControlManager.sessionFor(train)
        if (managedSession != null && managedSession.playerId != sender.uniqueId) {
            data.destinations.remove(originId)
            data.wayPoints.remove(originId)
            sender.sendMessage("导航失败：列车正由另一名驾驶员控制")
            return true
        }
        if (managedSession != null) trainControlManager.releaseTrain(train)
        data.dispatcher.discardReleasedManualNavigation(world, train.id)
        val controlPreparation = TrainCommand.prepareForAtoNavigation(sender, train)
        controlPreparation.failureReason?.let { reason ->
            data.destinations.remove(originId)
            data.wayPoints.remove(originId)
            sender.sendMessage("导航失败: $reason")
            return true
        }
        fun restorePreviousManualControl() {
            TrainCommand.restoreAfterFailedAtoNavigation(
                sender,
                train,
                controlPreparation.releasedController
            )
            if (managedSession != null && train.controller == null) {
                train.setController(managedSession.controller)
                trainControlManager.registerManualControl(
                    sender,
                    train,
                    managedSession.controller,
                    managedSession.controlEnd,
                    managedSession.timetableMode
                )
            }
        }
        data.timetables.add(timetable)
        val startAttempt = runCatching {
            data.dispatcher.startService(world, timetableId, serviceId)
        }
        data.dispatcher.stopFutureCycles(timetableId)
        data.timetables.remove(timetableId)
        data.destinations.remove(originId)
        data.wayPoints.remove(originId)
        val start = startAttempt.getOrElse { failure ->
            restorePreviousManualControl()
            sender.sendMessage("导航启动异常: ${failure.message ?: failure::class.java.simpleName}")
            return true
        }
        if (start !is TimetableServiceStartResult.Started) {
            restorePreviousManualControl()
            sender.sendMessage(
                "导航启动失败: ${(start as TimetableServiceStartResult.Failure).reason}"
            )
            return true
        }
        val runId = start.run.id
        if (measure) {
            val labels = points.map { if (it.pass) "+${it.id}" else it.id }
            val segments = ArrayList<String>()
            var previousLabel = "current"
            var previousTick = 0L
            var reachedCount = 0
            var subscription: AutoCloseable? = null
            subscription = data.dispatcher.observeWaypointProgress(
                runId,
                start.run.cycleIndex,
                TimetableWaypointProgressListener { progress ->
                    if (reachedCount >= labels.size) return@TimetableWaypointProgressListener
                    val label = labels[reachedCount]
                    val duration = (progress.timetableElapsedTicks - previousTick).coerceAtLeast(0L)
                    segments += "$previousLabel -> $label: $duration ticks (" +
                        "${String.format(Locale.ROOT, "%.2f", duration / 20.0)} s)"
                    previousLabel = label
                    previousTick = progress.timetableElapsedTicks
                    reachedCount++
                    if (reachedCount == labels.size) {
                        subscription?.close()
                        BukkitPlugin.INSTANCE.platformDispatcher.runGlobal {
                            if (sender.isOnline) {
                                sender.sendMessage("导航实测耗时（可复制）:")
                                sender.sendMessage(segments.joinToString("; "))
                            }
                        }
                    }
                }
            )
        }
        sender.sendMessage(
            "已启动 ATO 导航: ${points.joinToString(" ") { if (it.pass) "+${it.id}" else it.id }}" +
                if (measure) "（正在实测分段耗时）" else ""
        )
        return true
    }

    private fun handleDepot(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Player only"); return true }
        if (!sender.hasPermission("javarailways.admin")) {
            sender.sendMessage("没有权限管理车辆段")
            return true
        }
        val world = BukkitPlugin.INSTANCE.worldManager.railWorlds[sender.world] ?: run {
            sender.sendMessage("错误: 无法获取 JavaRailways 世界")
            return true
        }
        val store = world.basicWorldData.depots
        when (args.firstOrNull()?.lowercase()) {
            "assign" -> {
                val trainId = args.getOrNull(1)
                val depotValue = args.getOrNull(2)
                val pointValue = args.getOrNull(3)
                if (trainId == null || depotValue == null || pointValue == null) {
                    sender.sendMessage(
                        "用法: /jr depot assign <train> <depot> <point> [tagsCsv] [capabilitiesCsv]"
                    )
                    return true
                }
                val train = world.getTrain(trainId) ?: run {
                    sender.sendMessage("列车不存在: $trainId")
                    return true
                }
                if (train.speed > 0.05) {
                    sender.sendMessage("列车必须停止后才能登记到车辆段")
                    return true
                }
                val depotId = runCatching { DepotId(depotValue) }.getOrElse {
                    sender.sendMessage("Depot ID 无效: ${it.message}")
                    return true
                }
                val pointRef = runCatching { StablingPointRef(depotId, pointValue) }.getOrElse {
                    sender.sendMessage("StablingPoint ID 无效: ${it.message}")
                    return true
                }
                val point = store.stablingPoint(pointRef) ?: run {
                    sender.sendMessage("车辆段停车点不存在: $pointRef")
                    return true
                }
                val destination = world.basicWorldData.destinations[point.point.destinationId] ?: run {
                    sender.sendMessage("停车点 Destination 不存在: ${point.point.destinationId}")
                    return true
                }
                val location = train.headLocation as? RailLocationWithFacing
                val arcInAb = location?.arcLengthIn(ViewDirection.AB)
                if (location == null || location.rail.id != destination.railId ||
                    destination.approachDirection?.let { location.facing != it } == true ||
                    arcInAb == null || kotlin.math.abs(arcInAb - destination.arcLengthInAbMeters) > 1.0
                ) {
                    sender.sendMessage("列车不在 directed StablingPoint $pointRef")
                    return true
                }
                val packageName = train.rendererOrNull?.modelPackageName() ?: run {
                    sender.sendMessage("列车没有可解析的模型包")
                    return true
                }
                val maximumSpeed = runCatching {
                    BukkitPlugin.INSTANCE.modelManager.getTrainControl(packageName).maxSpeed
                }.getOrElse {
                    sender.sendMessage("列车控制配置无效: ${it.message}")
                    return true
                }
                val explicitTags = parseCsv(args.getOrNull(4))
                val explicitCapabilities = parseCsv(args.getOrNull(5))
                val inferredCapabilities = train.rendererOrNull?.behaviorActions().orEmpty()
                    .map { it.name }
                    .filter { it.startsWith("door-") }
                    .takeIf { it.isNotEmpty() }
                    ?.let { setOf("doors") }
                    .orEmpty()
                val record = RollingStockRecord(
                    trainId = train.id,
                    homeDepotId = depotId,
                    modelPackageId = packageName,
                    tags = explicitTags.ifEmpty { setOf(packageName) },
                    carCount = train.cars.size,
                    lengthMeters = train.cars.sumOf { it.length },
                    maximumSpeedMetersPerSecond = maximumSpeed,
                    capabilities = explicitCapabilities + inferredCapabilities
                )
                runCatching { store.registerRollingStock(record, pointRef) }
                    .onSuccess {
                        sender.sendMessage(
                            "已登记列车 ${train.id}: depot=$depotId point=$pointRef " +
                                "tags=${record.tags.sorted()} capabilities=${record.capabilities.sorted()}"
                        )
                    }
                    .onFailure { sender.sendMessage("车辆段登记失败: ${it.message}") }
            }
            "reset" -> {
                val depotValue = args.getOrNull(1)
                if (depotValue == null || args.getOrNull(2) != "confirm") {
                    sender.sendMessage("用法: /jr depot reset <depot> confirm")
                    return true
                }
                val depotId = runCatching { DepotId(depotValue) }.getOrElse {
                    sender.sendMessage("Depot ID 无效: ${it.message}")
                    return true
                }
                runDepotReset(sender, world, setOf(depotId))
            }
            "reset-all" -> {
                if (args.getOrNull(1) != "confirm") {
                    sender.sendMessage("用法: /jr depot reset-all confirm")
                    return true
                }
                runDepotReset(sender, world, null)
            }
            "list" -> {
                val requestedDepot = args.getOrNull(1)?.let { value ->
                    runCatching { DepotId(value) }.getOrNull()
                }
                val stock = store.allRollingStock().filter {
                    requestedDepot == null || it.record.homeDepotId == requestedDepot
                }
                if (stock.isEmpty()) sender.sendMessage("没有匹配的车辆段列车")
                stock.forEach {
                    sender.sendMessage(
                        "${it.record.trainId}: depot=${it.record.homeDepotId} " +
                            "state=${it.state} point=${it.stablingPointRef ?: "-"} " +
                            "model=${it.record.modelPackageId} tags=${it.record.tags.sorted()} " +
                            "capabilities=${it.record.capabilities.sorted()}"
                    )
                }
            }
            else -> sender.sendMessage("用法: /jr depot <assign|list|reset|reset-all>")
        }
        return true
    }

    private fun runDepotReset(
        sender: Player,
        world: RailWorld,
        depotIds: Set<DepotId>?
    ) {
        if (!world.basicWorldData.atoCoordinator.isStalled) {
            sender.sendMessage("Depot Reset 只允许在当前 World 已 Stall 时执行")
            return
        }
        (world as? CoreWorld)?.physicsCoordinator?.captureCurrentTicket()?.await()
        val result = DepotResetCoordinator(world).reset(depotIds)
        val reportFile = runCatching {
            DepotResetDiagnostics.write(BukkitPlugin.INSTANCE.dataFolder, result.report)
        }.onFailure { failure ->
            sender.sendMessage("Reset 报告写入失败: ${failure.message}")
        }.getOrNull()
        when (result) {
            is DepotResetResult.Succeeded -> sender.sendMessage(
                "Depot Reset 成功: trains=${result.report.assignments.size} " +
                    "runs=${result.report.removedRunCount} " +
                    "routes=${result.report.releasedRouteSectionCount} " +
                    "berths=${result.report.releasedBerthCount}；World 仍保持 Stall"
            )
            is DepotResetResult.Failed -> sender.sendMessage(
                "Depot Reset 失败: stage=${result.report.failureStage} " +
                    "message=${result.report.message}"
            )
            is DepotResetResult.PartiallyCommitted -> sender.sendMessage(
                "Depot Reset 部分提交: stage=${result.report.failureStage} " +
                    "message=${result.report.message}；World 保持 Stall，请检查报告后重试 Reset"
            )
        }
        if (reportFile != null) sender.sendMessage("报告: ${reportFile.absolutePath}")
    }

    private fun parseCsv(value: String?): Set<String> = value
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
        .orEmpty()

    private fun handleTimetable(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Player only"); return true }
        val world = BukkitPlugin.INSTANCE.worldManager.railWorlds[sender.world] ?: run {
            sender.sendMessage("错误: 无法获取 JavaRailways 世界")
            return true
        }
        when (args.firstOrNull()?.lowercase()) {
            "load" -> {
                if (!sender.hasPermission("javarailways.admin")) {
                    sender.sendMessage(Component.text("没有权限加载 JS 时刻表", NamedTextColor.RED))
                    return true
                }
                val scriptName = args.getOrNull(1) ?: run {
                    sender.sendMessage("用法: /jr timetable load <file.js> [id]")
                    return true
                }
                val timetableId = args.getOrNull(2) ?: scriptName.removeSuffix(".js")
                val playerId = sender.uniqueId
                val sourceWorldId = sender.world.uid
                PluginDiagnosticLog.timetable(
                    "LOAD_REQUESTED",
                    "player=${sender.name}\nworld=$sourceWorldId\nscript=$scriptName\ntimetable=$timetableId"
                )
                sender.sendMessage(Component.text("正在沙盒 Worker 中编译 $scriptName ...", NamedTextColor.GRAY))
                BukkitPlugin.INSTANCE.timetableScriptSandbox.compileScriptAsync(scriptName, timetableId)
                    .whenComplete { saved, failure ->
                        val plugin = BukkitPlugin.INSTANCE
                        plugin.platformDispatcher.runGlobal {
                            BukkitThreadGuardBridge.runInSimulationContext {
                                val player = Bukkit.getPlayer(playerId)
                                val messageTarget: (Component) -> Unit = { message ->
                                    if (player != null) player.sendMessage(message) else plugin.logger.info(message.toString())
                                }
                                if (failure != null) {
                                    val cause = generateSequence(failure) { it.cause }.last()
                                    val path = PluginDiagnosticLog.timetable(
                                        "LOAD_COMPILE_FAILED",
                                        "player=${player?.name ?: playerId}\nworld=$sourceWorldId\n" +
                                            "script=$scriptName\ntimetable=$timetableId",
                                        cause
                                    )
                                    messageTarget(Component.text(
                                        "JS 时刻表编译失败；详见 ${path?.toString() ?: "服务器日志"}",
                                        NamedTextColor.RED
                                    ))
                                    return@runInSimulationContext
                                }
                                if (player == null) {
                                    PluginDiagnosticLog.timetable(
                                        "LOAD_DISCARDED",
                                        "player=$playerId disconnected\nworld=$sourceWorldId\n" +
                                            "script=$scriptName\ntimetable=$timetableId"
                                    )
                                    return@runInSimulationContext
                                }
                                val sourceWorld = Bukkit.getWorld(sourceWorldId)
                                val world = sourceWorld?.let(plugin.worldManager.railWorlds::get) ?: run {
                                    messageTarget(Component.text("发起加载的世界已卸载或尚未初始化 JavaRailways", NamedTextColor.RED))
                                    return@runInSimulationContext
                                }
                                val normalized = TimetableScriptNormalizer.normalize(saved, world)
                                val timetable = runCatching(normalized::restore).getOrElse { invalid ->
                                    val path = PluginDiagnosticLog.timetable(
                                        "LOAD_RESTORE_FAILED",
                                        "player=${player.name}\nworld=$sourceWorldId\n" +
                                            "script=$scriptName\ntimetable=$timetableId",
                                        invalid
                                    )
                                    messageTarget(Component.text(
                                        "JS 输出无效；详见 ${path?.toString() ?: "服务器日志"}",
                                        NamedTextColor.RED
                                    ))
                                    return@runInSimulationContext
                                }
                                val validation = TimetableValidator(world).validate(timetable)
                                if (!validation.isSuccess) {
                                    val path = PluginDiagnosticLog.timetable(
                                        "LOAD_VALIDATION_FAILED",
                                        buildString {
                                            append("player=${player.name}\nworld=$sourceWorldId\n")
                                            append("script=$scriptName\ntimetable=$timetableId\n")
                                            append("errors=${validation.errors.size}\n")
                                            validation.errors.forEachIndexed { index, error ->
                                                append(index + 1).append(". ").append(error).append('\n')
                                            }
                                        }
                                    )
                                    messageTarget(Component.text(
                                        "JS 时刻表验证失败（${validation.errors.size} 项）；详见 " +
                                            (path?.toString() ?: "服务器日志"),
                                        NamedTextColor.RED
                                    ))
                                    return@runInSimulationContext
                                }
                                world.basicWorldData.timetables.add(timetable)
                                PluginDiagnosticLog.timetable(
                                    "LOAD_SUCCEEDED",
                                    "player=${player.name}\nworld=$sourceWorldId\nscript=$scriptName\n" +
                                        "timetable=${timetable.id}\nservices=${timetable.services.size}\n" +
                                        "initialPositions=${timetable.initialPositions.size}"
                                )
                                messageTarget(Component.text("已加载 JS 时刻表 ${timetable.id}", NamedTextColor.GREEN))
                            }
                        }
                    }
            }
            "edit" -> {
                if (!sender.hasPermission("javarailways.admin")) {
                    sender.sendMessage(Component.text("没有权限编辑时刻表", NamedTextColor.RED))
                    return true
                }
                val config = BukkitPlugin.INSTANCE.javaRailwaysConfig.timetableWebUi
                if (config.mode == TimetableWebUiMode.DISABLED) {
                    sender.sendMessage(Component.text("时刻表 WebUI 已禁用", NamedTextColor.RED))
                    return true
                }
                val (token, grant) = BukkitPlugin.INSTANCE.webUiLoginTokens.issue(
                    sender.uniqueId,
                    sender.name,
                    sender.world.name
                )
                // URL fragments are not sent in HTTP requests or Referer headers. The React
                // client exchanges this one-time token with a same-origin POST, then erases it.
                val url = "${config.publicUrl}/#token=$token"
                sender.sendMessage(
                    Component.text("[点击打开 JavaRailways 时刻表编辑器]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(url))
                )
                sender.sendMessage(
                    Component.text(
                        "链接将在 ${(grant.expiresAtEpochMillis - System.currentTimeMillis()) / 1000} 秒内失效，且只能使用一次。",
                        NamedTextColor.GRAY
                    )
                )
            }
            "dump" -> {
                if (!sender.hasPermission("javarailways.admin")) {
                    sender.sendMessage("没有权限查看时刻表完整配置")
                    return true
                }
                val id = args.getOrNull(1)
                if (id == null) {
                    sender.sendMessage("用法: /jr timetable dump <id>")
                    return true
                }
                val timetable = world.basicWorldData.timetables[id]
                if (timetable == null) {
                    sender.sendMessage("时刻表不存在: $id")
                    return true
                }
                sender.sendMessage("=== Timetable $id ===")
                TimetableDumper.dumpToJson(timetable).lines().forEach { sender.sendMessage(it) }
            }
            "list" -> {
                val timetables = world.basicWorldData.timetables.all()
                if (timetables.isEmpty()) {
                    sender.sendMessage("当前没有已加载时刻表")
                } else {
                    sender.sendMessage("=== 已加载时刻表 ===")
                    timetables.forEach {
                        sender.sendMessage(
                            "${it.id}: services=${it.services.size}, periodDays=${it.periodDays}, " +
                                "periodTicks=${it.periodTicks}"
                        )
                    }
                }
            }
            "start" -> {
                val timetableId = args.getOrNull(1)
                val requestedService = args.getOrNull(2)
                if (timetableId == null) {
                    sender.sendMessage("用法: /jr timetable start <id> [service]")
                    return true
                }
                val timetable = world.basicWorldData.timetables[timetableId]
                if (timetable == null) {
                    sender.sendMessage("时刻表不存在: $timetableId")
                    return true
                }
                (world as? CoreWorld)?.physicsCoordinator?.captureCurrentTicket()?.await()
                if (requestedService == null) {
                    when (val epoch = world.basicWorldData.dispatcher.beginTimetable(world, timetableId)) {
                        is TimetableEpochStartResult.Started -> {
                            check(world.basicWorldData.dispatcher.scheduleInitialCycle(timetableId))
                            sender.sendMessage(
                                "已建立 timetable epoch=${epoch.epoch.epochDispatcherTick} " +
                                    "period=${epoch.epoch.periodTicks} ticks；自动 service 将按启动条件调度"
                            )
                        }
                        is TimetableEpochStartResult.Failure -> {
                            sender.sendMessage("启动 timetable $timetableId 失败: ${epoch.reason}")
                            return true
                        }
                    }
                    return true
                }
                val serviceIds = listOf(requestedService)
                serviceIds.forEach { serviceId ->
                    when (val result = world.basicWorldData.dispatcher.startService(world, timetableId, serviceId)) {
                        is TimetableServiceStartResult.Started -> sender.sendMessage(
                            "已启动 ${result.run.id}: train=${result.run.trainId} revision=${result.run.missionRevision}"
                        )
                        is TimetableServiceStartResult.Failure -> sender.sendMessage(
                            "启动 $timetableId/$serviceId 失败: ${result.reason}"
                        )
                    }
                }
            }
            "reset" -> {
                if (!sender.hasPermission("javarailways.admin")) {
                    sender.sendMessage("没有权限重置时刻表")
                    return true
                }
                val timetableId = args.getOrNull(1)
                val force = args.getOrNull(2) == "force" && args.getOrNull(3) == "confirm"
                val confirmed = args.getOrNull(2) == "confirm"
                if (timetableId == null || (!confirmed && !force)) {
                    sender.sendMessage(
                        "用法: /jr timetable reset <id> confirm | " +
                            "/jr timetable reset <id> force confirm"
                    )
                    return true
                }
                val timetable = world.basicWorldData.timetables[timetableId]
                if (timetable == null) {
                    sender.sendMessage("时刻表不存在: $timetableId")
                    return true
                }
                PluginDiagnosticLog.timetable(
                    "RESET_REQUESTED",
                    "player=${sender.name}\nworld=${sender.world.uid}\ntimetable=$timetableId\nforce=$force"
                )
                (world as? CoreWorld)?.physicsCoordinator?.captureCurrentTicket()?.await()
                val dispatcher = world.basicWorldData.dispatcher
                val coordinator = world.basicWorldData.atoCoordinator
                val discarded = if (force) {
                    val selectedTrainIds = dispatcher.statuses().asSequence()
                        .filter { it.id.timetableId == timetableId }
                        .map { it.trainId }
                        .toMutableSet()
                    world.allTrains.filterIsInstance<CoreTrain>()
                        .filter { train ->
                            timetable.initialPositions.any { it.trainSelector.matches(train.name) }
                        }
                        .forEach { selectedTrainIds += it.id }
                    selectedTrainIds.forEach { trainId ->
                        world.getTrain(trainId)?.let { train ->
                            BukkitPlugin.INSTANCE.trainControlManager.releaseTrain(train)
                            (train as? CoreTrain)?.setController(null)
                        }
                    }
                    dispatcher.forceDiscardTimetable(world, timetableId).also {
                        if (coordinator.isStalled) coordinator.reset()
                    }
                } else if (coordinator.isStalled) {
                    dispatcher.discardFailedRunsForAtoReset(world).also { coordinator.reset() }
                } else {
                    0
                }
                when (val result = dispatcher.beginTimetable(world, timetableId)) {
                    is TimetableEpochStartResult.Started -> {
                        check(dispatcher.scheduleInitialCycle(timetableId))
                        PluginDiagnosticLog.timetable(
                            "RESET_SUCCEEDED",
                            "player=${sender.name}\nworld=${sender.world.uid}\ntimetable=$timetableId\n" +
                                "force=$force\ndiscardedRuns=$discarded\n" +
                                "epoch=${result.epoch.epochDispatcherTick}"
                        )
                        sender.sendMessage(
                            if (force) {
                                "时刻表 $timetableId 已强制释放控制器/运行资源，丢弃 $discarded 个 run，" +
                                    "并原子重置初始位置、建立新 epoch"
                            } else {
                                "时刻表 $timetableId 已原子重置初始位置并建立新 epoch；" +
                                    "清理 $discarded 个失败 run"
                            }
                        )
                    }
                    is TimetableEpochStartResult.Failure -> {
                        val path = PluginDiagnosticLog.timetable(
                            "RESET_FAILED",
                            "player=${sender.name}\nworld=${sender.world.uid}\ntimetable=$timetableId\n" +
                                "force=$force\ndiscardedRuns=$discarded\nreason=${result.reason}"
                        )
                        sender.sendMessage(
                            "重置时刻表 $timetableId 失败；详见 " +
                                (path?.toString() ?: "服务器日志")
                        )
                    }
                }
            }
            "status" -> {
                val timetableId = args.getOrNull(1)
                val requestedService = args.getOrNull(2)
                if (timetableId == null) {
                    sender.sendMessage("用法: /jr timetable status <id> [service]")
                    return true
                }
                val runs = world.basicWorldData.dispatcher.statuses().filter {
                    it.id.timetableId == timetableId &&
                        (requestedService == null || it.id.serviceId == requestedService)
                }
                val recovery = world.basicWorldData.dispatcher.recoveryRequiredRuns().filter {
                    it.timetableId == timetableId &&
                        (requestedService == null || it.serviceId == requestedService)
                }
                if (runs.isEmpty() && recovery.isEmpty()) {
                    sender.sendMessage("没有匹配的运行中、历史或待恢复 service")
                } else {
                    recovery.forEach { run ->
                        sender.sendMessage(
                            "${run.timetableId}/${run.serviceId}: state=RECOVERY_REQUIRED " +
                                "originalState=${run.originalState} train=${run.trainId} " +
                                "cycle=${run.cycleIndex} elapsed=${run.timetableElapsedTicks} " +
                                "current=${run.currentWayPointId} target=${run.targetWayPointId} " +
                                "nextStep=${run.nextStepIndex}"
                        )
                    }
                    runs.forEach { run ->
                        sender.sendMessage(
                            "${run.id}: state=${run.state} mode=${run.controlMode} train=${run.trainId} " +
                                "revision=${run.missionRevision} confirmed=${run.confirmedSectionCount} " +
                                "cycle=${run.cycleIndex} elapsed=${run.timetableElapsedTicks} " +
                                "scheduled=${run.scheduledActionTick ?: "-"} late=${run.lastLatenessTicks} " +
                                "head=${run.headProgressMeters ?: "-"} tail=${run.tailProgressMeters ?: "-"} " +
                                "call=${run.stationCallId ?: "-"} berth=${run.berthRef ?: "-"} " +
                                "berthHeld=${run.berthHeld} operation=${run.operationTicketId ?: "-"} " +
                                "operationState=${run.operationTicketState ?: "-"} " +
                                "operationError=${run.operationTicketError ?: "-"} " +
                                "transferOwner=${run.controlTransferOwner ?: "-"}" +
                                run.message?.let { " message=$it" }.orEmpty()
                        )
                    }
                }
            }
            "replan" -> {
                if (!sender.hasPermission("javarailways.admin")) {
                    sender.sendMessage("没有权限重新规划车次")
                    return true
                }
                val timetableId = args.getOrNull(1)
                val serviceId = args.getOrNull(2)
                val originWayPointId = args.getOrNull(3)
                if (timetableId == null || serviceId == null || originWayPointId == null) {
                    sender.sendMessage(
                        "用法: /jr timetable replan <id> <service> <current-waypoint> [cycle]"
                    )
                    return true
                }
                val cycleIndex = args.getOrNull(4)?.toLongOrNull()
                if (args.getOrNull(4) != null && (cycleIndex == null || cycleIndex < 0L)) {
                    sender.sendMessage("cycle 必须是非负整数")
                    return true
                }
                val id = TimetableServiceRunId(timetableId, serviceId)
                val status = world.basicWorldData.dispatcher.status(id, cycleIndex)
                if (status == null) {
                    sender.sendMessage("service 不存在: $id")
                    return true
                }
                val train = world.getTrain(status.trainId)
                if (train != null && BukkitPlugin.INSTANCE.trainControlManager.sessionFor(train) != null) {
                    sender.sendMessage("列车仍由人工控制；请先释放驾驶权")
                    return true
                }
                (world as? CoreWorld)?.physicsCoordinator?.captureCurrentTicket()?.await()
                when (val result = world.basicWorldData.dispatcher.replanService(
                    world,
                    id,
                    originWayPointId,
                    cycleIndex
                )) {
                    is TimetableReplanResult.Resumed -> sender.sendMessage(
                        "已从 $originWayPointId 重新规划 ${result.run.id}: " +
                            "revision=${result.run.missionRevision} state=${result.run.state}"
                    )
                    is TimetableReplanResult.Failure -> sender.sendMessage(
                        "重新规划 $id 失败: ${result.reason}"
                    )
                }
            }
            "stop" -> {
                val timetableId = args.getOrNull(1)
                val requestedService = args.getOrNull(2)
                if (timetableId == null) {
                    sender.sendMessage("用法: /jr timetable stop <id> [service]")
                    return true
                }
                (world as? CoreWorld)?.physicsCoordinator?.captureCurrentTicket()?.await()
                world.basicWorldData.dispatcher.stopFutureCycles(timetableId, requestedService)
                val targets = world.basicWorldData.dispatcher.statuses().filter {
                    it.id.timetableId == timetableId &&
                        (requestedService == null || it.id.serviceId == requestedService)
                }
                if (targets.isEmpty()) sender.sendMessage("没有匹配的 service")
                targets.forEach { target ->
                    if (world.basicWorldData.dispatcher.stopService(
                            world,
                            target.id,
                            target.cycleIndex
                        )
                    ) {
                        sender.sendMessage(
                            "已请求受控停车 ${target.id}@cycle-${target.cycleIndex}；" +
                                "经路将在车尾通过释放点后释放"
                        )
                    } else {
                        sender.sendMessage(
                            "service 不存在: ${target.id}@cycle-${target.cycleIndex}"
                        )
                    }
                }
            }
            else -> sender.sendMessage("用法: /jr timetable <edit|load|dump|list|start|status|stop|replan>")
        }
        return true
    }

    private fun handleAto(sender: CommandSender, args: List<String>): Boolean {
        if (!sender.hasPermission("javarailways.admin")) {
            sender.sendMessage("没有权限管理 ATO")
            return true
        }
        val plugin = BukkitPlugin.INSTANCE
        when (args.firstOrNull()?.lowercase()) {
            "status" -> {
                val requestedWorld = args.getOrNull(1)
                val worlds = if (requestedWorld == null) {
                    plugin.worldManager.railWorlds.entries.toList()
                } else {
                    listOfNotNull(resolveRailWorld(requestedWorld))
                }
                if (worlds.isEmpty()) {
                    sender.sendMessage("没有匹配的 JavaRailways 世界${requestedWorld?.let { ": $it" }.orEmpty()}")
                    return true
                }
                worlds.forEach { (bukkitWorld, railWorld) ->
                    val coordinator = railWorld.basicWorldData.atoCoordinator
                    val stall = coordinator.lastStall
                    sender.sendMessage("ATO ${bukkitWorld.name}: ${coordinator.state}")
                    if (stall != null) {
                        sender.sendMessage(
                            "  train=${stall.trainId} tick=${stall.tick} " +
                                "error=${stall.errorType}: ${stall.message.orEmpty()}"
                        )
                    }
                }
            }
            "dump" -> {
                val requestedWorld = args.getOrNull(1)
                if (requestedWorld == null) {
                    sender.sendMessage("用法: /jr ato dump <world>")
                    return true
                }
                val selection = resolveRailWorld(requestedWorld)
                if (selection == null) {
                    sender.sendMessage("JavaRailways 世界不存在: $requestedWorld")
                    return true
                }
                val report = selection.value.basicWorldData.atoCoordinator.lastStall
                if (report == null) {
                    sender.sendMessage("世界 ${selection.key.name} 没有 ATO Stall 报告")
                    return true
                }
                runCatching { AtoStallDiagnostics.write(plugin.dataFolder, report) }
                    .onSuccess { sender.sendMessage("ATO Stall 报告已写入: ${it.absolutePath}") }
                    .onFailure { sender.sendMessage("写入 ATO Stall 报告失败: ${it.message.orEmpty()}") }
            }
            "reset" -> {
                val requestedWorld = args.getOrNull(1)
                if (requestedWorld == null) {
                    sender.sendMessage("用法: /jr ato reset <world>")
                    return true
                }
                val selection = resolveRailWorld(requestedWorld)
                if (selection == null) {
                    sender.sendMessage("JavaRailways 世界不存在: $requestedWorld")
                    return true
                }
                val coordinator = selection.value.basicWorldData.atoCoordinator
                if (!coordinator.isStalled) {
                    sender.sendMessage("世界 ${selection.key.name} 的 ATO 已处于 RUNNING")
                    return true
                }
                (selection.value as? CoreWorld)?.physicsCoordinator?.captureCurrentTicket()?.await()
                val discardedRuns = selection.value.basicWorldData.dispatcher
                    .discardFailedRunsForAtoReset(selection.value)
                coordinator.reset()
                sender.sendMessage(
                    "已解除世界 ${selection.key.name} 的 ATO Stall；" +
                        "已清理 $discardedRuns 个失败 service，列车位置不会自动恢复"
                )
            }
            else -> sender.sendMessage("用法: /jr ato <status [world]|dump <world>|reset <world>>")
        }
        return true
    }

    private fun resolveRailWorld(requestedWorld: String): Map.Entry<org.bukkit.World, work.art1st.javarailways.core.api.physics.World>? {
        return BukkitPlugin.INSTANCE.worldManager.railWorlds.entries.firstOrNull { (bukkitWorld, _) ->
            bukkitWorld.name.equals(requestedWorld, ignoreCase = true) ||
                bukkitWorld.uid.toString().equals(requestedWorld, ignoreCase = true)
        }
    }

    private fun handlePerf(sender: CommandSender, args: List<String>): Boolean {
        val plugin = BukkitPlugin.INSTANCE
        val requestedWorld = args.firstOrNull()?.let(Bukkit::getWorld)
        if (args.isNotEmpty() && requestedWorld == null) {
            sender.sendMessage("世界不存在: ${args.first()}")
            return true
        }
        val bukkitWorld = requestedWorld
            ?: (sender as? Player)?.world
            ?: plugin.worldManager.railWorlds.keys.firstOrNull()
        val coreWorld = bukkitWorld?.let { plugin.worldManager.railWorlds[it] as? CoreWorld }
        if (bukkitWorld == null || coreWorld == null) {
            sender.sendMessage("没有可用的 JavaRailways 世界")
            return true
        }

        sender.sendMessage("=== JavaRailways Performance: ${bukkitWorld.name} ===")
        plugin.worldManager.latestServerTickMetrics()?.let { metrics ->
            sender.sendMessage(
                "server tick=${metrics.tickIndex} impact=${formatMillis(metrics.totalImpactNanos)}ms " +
                    "compute=${formatMillis(metrics.computeNanos)}ms wait=${formatMillis(metrics.blockedWaitAsyncNanos)}ms"
            )
            metrics.phaseBreakdownNanos.entries
                .filter { (phase, _) -> phase.startsWith("world:${bukkitWorld.name}:") || phase == "seat_extension" }
                .sortedByDescending { it.value }
                .take(8)
                .forEach { (phase, nanos) -> sender.sendMessage("  $phase=${formatMillis(nanos)}ms") }
        } ?: sender.sendMessage("server metrics: 尚无样本")

        coreWorld.physicsCoordinator.latestMetrics()?.let { metrics ->
            sender.sendMessage(
                "physics epoch=${metrics.epoch} wall=${formatMillis(metrics.wallNanos)}ms " +
                    "workerMax=${formatMillis(metrics.workerMaxNanos)}ms coordinator=${formatMillis(metrics.coordinatorNanos)}ms"
            )
            sender.sendMessage(
                "  active=${metrics.activeTrainCount}/${metrics.totalTrainCount} spans=${metrics.spanCount} " +
                    "bucketRefs=${metrics.bucketReferenceCount} candidates=${metrics.candidateChecks} " +
                    "collisions=${metrics.collisionClaims} overloads=${metrics.overloadCountInWindow}"
            )
        } ?: sender.sendMessage("physics metrics: 尚无样本")

        coreWorld.trainFrameMailboxMetrics().let { metrics ->
            sender.sendMessage(
                "visual mailbox=${metrics.size}/${metrics.capacity} peak=${metrics.peakSize} " +
                    "coalesced=${metrics.coalescedFrames} stale=${metrics.droppedStaleFrames} " +
                    "capacityDrops=${metrics.droppedCapacityFrames}"
            )
        }
        plugin.worldManager.carFrameDispatchMetrics().let { metrics ->
            sender.sendMessage(
                "visual region dispatch pendingCars=${metrics.pendingCars} " +
                    "coalesced=${metrics.coalescedFrames} tasks=${metrics.scheduledTasks}"
            )
        }
        coreWorld.renderChunkManager.audienceMetrics().let { metrics ->
            sender.sendMessage(
                "audience candidates=${metrics.candidateUpdates} coalesced=${metrics.coalescedUpdates} " +
                    "drains=${metrics.scheduledDrains} processed=${metrics.processedUpdates} " +
                    "authorityRefreshes=${metrics.authoritativePassengerRefreshes}/" +
                    "${metrics.authoritativePassengerRefreshRequests} " +
                    "pendingPlayers=${metrics.pendingPlayers}"
            )
        }
        coreWorld.tickManager.pendingCommitMetrics().let { metrics ->
            sender.sendMessage(
                "tick commit mailbox=${metrics.size}/${metrics.capacity} peak=${metrics.peakSize} " +
                    "coalesced=${metrics.coalescedRequests} capacityDrops=${metrics.droppedCapacityRequests}"
            )
        }
        plugin.platformDispatcher.metrics().let { metrics ->
            sender.sendMessage(
                "platform dispatch entity=${metrics.entitySubmissions}/${metrics.entityExecutions} " +
                    "region=${metrics.regionSubmissions}/${metrics.regionExecutions} " +
                    "direct=${metrics.directExecutions} globalRuns=${metrics.globalExecutions} " +
                    "asyncRuns=${metrics.asyncExecutions} retired=${metrics.retiredTasks}"
            )
        }
        PacketPlatformDiagnostics.snapshot().let { metrics ->
            sender.sendMessage(
                "packet platform active=${metrics.activeCarriers} created=${metrics.createdCarriers} " +
                    "destroyed=${metrics.destroyedCarriers} viewers=+${metrics.viewerAdds}/-${metrics.viewerRemoves} " +
                    "teleports=${metrics.teleportCalls} packets~=${metrics.estimatedTeleportPackets}"
            )
        }
        plugin.trainPacketBundles.metrics().let { metrics ->
            sender.sendMessage(
                "train bundles sent=${metrics.bundlesSent} packets=${metrics.packetsBundled}"
            )
        }
        plugin.foliaPacketEntityMotion?.let { packetMotion ->
            packetMotion.metrics().let { metrics ->
                sender.sendMessage(
                    "folia packet motion managed=${metrics.managedEntities} " +
                        "virtualSeats=${metrics.virtualSeats} " +
                        "authorityUpdates=${metrics.seatAuthorityMoveUpdates} " +
                        "authorityCorrections=${metrics.seatAuthorityCorrections} " +
                        "maxAuthorityError=${"%.2f".format(Locale.ROOT, metrics.maxSeatAuthorityError)} " +
                        "playerCorrectionsSuppressed=${metrics.suppressedPlayerCorrections}"
                )
            }
            val seatTrace = packetMotion.recentSeatTrace()
            val nativeTrace = packetMotion.recentPacketTrace()
            val bundleTrace = plugin.trainPacketBundles.recentTrace()
            if (seatTrace.isNotEmpty() || nativeTrace.isNotEmpty() || bundleTrace.isNotEmpty()) {
                val traceFile = plugin.dataFolder.resolve("diagnostics/folia-seat-packet-trace.txt")
                traceFile.parentFile.mkdirs()
                traceFile.writeText(
                    buildString {
                        appendLine("Folia Seat authority trace (oldest -> newest)")
                        seatTrace.forEach(::appendLine)
                        appendLine()
                        appendLine("Folia Seat native packet trace (oldest -> newest)")
                        nativeTrace.forEach(::appendLine)
                        appendLine()
                        appendLine("JavaRailways Bundle trace (sampled, oldest -> newest)")
                        bundleTrace.forEach(::appendLine)
                    }
                )
                sender.sendMessage("folia seat packet trace: ${traceFile.absolutePath}")
                nativeTrace.takeLast(30).forEach { sender.sendMessage("  $it") }
            }
        }
        return true
    }

    private fun formatMillis(nanos: Long): String = "%.3f".format(java.util.Locale.US, nanos / 1_000_000.0)

    private fun handleUndo(sender: CommandSender): Boolean {
        if (sender !is Player) { sender.sendMessage("Player only"); return true }
        BukkitPlugin.INSTANCE.railWand.undo(sender)
        return true
    }

    private fun handleRedo(sender: CommandSender): Boolean {
        if (sender !is Player) { sender.sendMessage("Player only"); return true }
        BukkitPlugin.INSTANCE.railWand.redo(sender)
        return true
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("=== JavaRailways 命令帮助 ===")
        sender.sendMessage("/jr rail create <length> - 在玩家位置创建直线轨道")
        sender.sendMessage("/jr rail connect - 连接选中的轨道")
        sender.sendMessage("/jr train create - 在轨道上创建列车")
        sender.sendMessage("/jr train start - 启动列车（手动驾驶）")
        sender.sendMessage("/jr train stop - 停止列车")
        sender.sendMessage("/jr train anim <name> [scene] - 触发列车部件动画")
        sender.sendMessage("/jr train action <name> - 触发列车动作（动画/声音等）")
        sender.sendMessage("/jr wand rail - 获取 RailWand")
        sender.sendMessage("/jr wand railobject - 获取 RailObjectWand")
        sender.sendMessage("/jr waypoint list|info|remove - 管理路径点")
        sender.sendMessage("/jr route validate <from> <to> - 验证路径点之间的展开经路")
        sender.sendMessage("/jr navigate [--measure|-m] <waypoints...> - 为驾驶中的列车提交临时 ATO 导航")
        sender.sendMessage("/jr depot assign|list ... - 登记和查看车辆段列车")
        sender.sendMessage("/jr depot reset <depot> confirm | reset-all confirm - Stall 中原子重置")
        sender.sendMessage("/jr timetable replan <id> <service> <current-waypoint> [cycle] - 人工接管后显式重新规划")
        sender.sendMessage("/jr timetable edit - 获取短期有效的 WebUI 登录链接")
        sender.sendMessage("/jr timetable load <file.js> [id] - 在隔离 Worker 中编译并加载 JS 时刻表")
        sender.sendMessage("/jr timetable dump <id> - 输出时刻表内部结构")
        sender.sendMessage("/jr timetable reset <id> confirm | reset <id> force confirm - 安全/强制重置")
        sender.sendMessage("/jr timetable start|status|stop <id> [service] - 管理显式列车 service")
        sender.sendMessage("/jr ato status [world] - 查看 World-local ATO 状态")
        sender.sendMessage("/jr ato dump <world> - 写出最近一次 ATO Stall 报告")
        sender.sendMessage("/jr ato reset <world> - 手动解除 ATO Stall（不恢复列车位置）")
        sender.sendMessage("/jr perf [world] - 查看 Tick、物理和渲染耗时")
        sender.sendMessage("/jr undo - 撤销操作")
        sender.sendMessage("/jr redo - 重做操作")
    }
}