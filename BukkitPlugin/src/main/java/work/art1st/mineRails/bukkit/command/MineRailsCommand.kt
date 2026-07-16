package work.art1st.mineRails.bukkit.command

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.basic.route.RouteBuildResult
import work.art1st.mineRails.basic.route.RoutePlanner
import work.art1st.mineRails.basic.timetable.TimetableDumper
import work.art1st.mineRails.basic.timetable.TimetableServiceRunId
import work.art1st.mineRails.basic.timetable.TimetableServiceStartResult
import work.art1st.mineRails.basic.timetable.js.TimetableScriptLoadResult
import work.art1st.mineRails.basic.timetable.js.TimetableScriptLoader
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.debug.AtoStallDiagnostics
import work.art1st.mineRails.core.physics.CoreWorld
import java.io.File

class MineRailsCommand : CommandExecutor, TabCompleter {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
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
            "timetable", "tt" -> return handleTimetable(sender, args.drop(1))
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
    ): List<String> {
        if (args.size == 1) {
            return listOf("rail", "train", "wand", "waypoint", "route", "timetable", "ato", "stop", "perf", "undo", "redo", "help")
        }
        if (args[0].equals("ato", ignoreCase = true)) {
            if (args.size == 2) return listOf("status", "dump", "reset")
            if (args.size == 3) return BukkitPlugin.INSTANCE.worldManager.railWorlds.keys.map { it.name }
        }
        if (args[0].equals("timetable", ignoreCase = true) || args[0].equals("tt", ignoreCase = true)) {
            if (args.size == 2) return listOf("load", "dump", "list", "start", "status", "stop")
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
            else -> sender.sendMessage("用法: /mr wand <rail|railobject> (设置请 Shift+右键打开 Dialog)")
        }
        return true
    }

    private fun handleWayPoint(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Player only"); return true }
        val world = BukkitPlugin.INSTANCE.worldManager.railWorlds[sender.world] ?: run {
            sender.sendMessage("错误: 无法获取 MineRails 世界")
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
                        sender.sendMessage("${wayPoint.id} (${wayPoint.displayName}) rail=${wayPoint.railId} s=${"%.2f".format(wayPoint.arcLength)}")
                    }
                }
            }
            "info" -> {
                val id = args.getOrNull(1)
                if (id == null) {
                    sender.sendMessage("用法: /mr waypoint info <id>")
                } else {
                    val wayPoint = store[id]
                    if (wayPoint == null) {
                        sender.sendMessage("路径点不存在: $id")
                    } else {
                        sender.sendMessage("=== 路径点 ${wayPoint.id} ===")
                        sender.sendMessage("显示名: ${wayPoint.displayName}")
                        sender.sendMessage("轨道: ${wayPoint.railId}")
                        sender.sendMessage("弧长: ${"%.2f".format(wayPoint.arcLength)}")
                    }
                }
            }
            "remove" -> {
                val id = args.getOrNull(1)
                if (id == null) {
                    sender.sendMessage("用法: /mr waypoint remove <id>")
                } else {
                    val removed = store.remove(id)
                    if (removed == null) {
                        sender.sendMessage("路径点不存在: $id")
                    } else {
                        BukkitPlugin.INSTANCE.railObjectWand.removeMarker(sender.world.uid, id)
                        sender.sendMessage("已删除路径点: $id")
                    }
                }
            }
            else -> sender.sendMessage("用法: /mr waypoint <list|info|remove> (旧别名: /mr stop)")
        }
        return true
    }

    private fun handleRoute(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Player only"); return true }
        val world = BukkitPlugin.INSTANCE.worldManager.railWorlds[sender.world] ?: run {
            sender.sendMessage("错误: 无法获取 MineRails 世界")
            return true
        }
        when (args.firstOrNull()?.lowercase()) {
            "validate" -> {
                val from = args.getOrNull(1)
                val to = args.getOrNull(2)
                if (from == null || to == null) {
                    sender.sendMessage("用法: /mr route validate <fromWaypoint> <toWaypoint>")
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
            else -> sender.sendMessage("用法: /mr route validate <fromWaypoint> <toWaypoint>")
        }
        return true
    }

    private fun handleTimetable(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Player only"); return true }
        val world = BukkitPlugin.INSTANCE.worldManager.railWorlds[sender.world] ?: run {
            sender.sendMessage("错误: 无法获取 MineRails 世界")
            return true
        }
        when (args.firstOrNull()?.lowercase()) {
            "load" -> {
                val scriptPath = args.getOrNull(1)
                val id = args.getOrNull(2)
                if (scriptPath == null) {
                    sender.sendMessage("用法: /mr timetable load <file.js> [id]")
                    return true
                }
                val scriptFile = resolveTimetableScript(scriptPath)
                when (val result = TimetableScriptLoader(world).load(scriptFile, id ?: scriptFile.nameWithoutExtension)) {
                    is TimetableScriptLoadResult.Success -> {
                        world.basicWorldData.timetables.add(result.timetable)
                        sender.sendMessage("时刻表加载成功: ${result.timetable.id}")
                        sender.sendMessage("services=${result.timetable.services.size}, routes=${result.validation.routes.size}")
                    }
                    is TimetableScriptLoadResult.Failure -> {
                        sender.sendMessage("时刻表加载失败:")
                        result.message.lines().forEach { sender.sendMessage(it) }
                    }
                }
            }
            "dump" -> {
                val id = args.getOrNull(1)
                if (id == null) {
                    sender.sendMessage("用法: /mr timetable dump <id>")
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
                    timetables.forEach { sender.sendMessage("${it.id}: services=${it.services.size}, period=${it.periodTicks ?: "none"}") }
                }
            }
            "start" -> {
                val timetableId = args.getOrNull(1)
                val requestedService = args.getOrNull(2)
                if (timetableId == null) {
                    sender.sendMessage("用法: /mr timetable start <id> [service]")
                    return true
                }
                val timetable = world.basicWorldData.timetables[timetableId]
                if (timetable == null) {
                    sender.sendMessage("时刻表不存在: $timetableId")
                    return true
                }
                (world as? CoreWorld)?.physicsCoordinator?.captureCurrentTicket()?.await()
                val serviceIds = requestedService?.let(::listOf) ?: timetable.services.map { it.id }
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
            "status" -> {
                val timetableId = args.getOrNull(1)
                val requestedService = args.getOrNull(2)
                if (timetableId == null) {
                    sender.sendMessage("用法: /mr timetable status <id> [service]")
                    return true
                }
                val runs = world.basicWorldData.dispatcher.statuses().filter {
                    it.id.timetableId == timetableId &&
                        (requestedService == null || it.id.serviceId == requestedService)
                }
                if (runs.isEmpty()) {
                    sender.sendMessage("没有匹配的运行中或历史 service")
                } else {
                    runs.forEach { run ->
                        sender.sendMessage(
                            "${run.id}: state=${run.state} train=${run.trainId} " +
                                "revision=${run.missionRevision} confirmed=${run.confirmedSectionCount} " +
                                "head=${run.headProgressMeters ?: "-"} tail=${run.tailProgressMeters ?: "-"} " +
                                "call=${run.stationCallId ?: "-"} berth=${run.berthRef ?: "-"} " +
                                "berthHeld=${run.berthHeld} operation=${run.operationTicketId ?: "-"} " +
                                "operationState=${run.operationTicketState ?: "-"}" +
                                run.message?.let { " message=$it" }.orEmpty()
                        )
                    }
                }
            }
            "stop" -> {
                val timetableId = args.getOrNull(1)
                val requestedService = args.getOrNull(2)
                if (timetableId == null) {
                    sender.sendMessage("用法: /mr timetable stop <id> [service]")
                    return true
                }
                (world as? CoreWorld)?.physicsCoordinator?.captureCurrentTicket()?.await()
                val ids = if (requestedService != null) {
                    listOf(TimetableServiceRunId(timetableId, requestedService))
                } else {
                    world.basicWorldData.dispatcher.statuses()
                        .filter { it.id.timetableId == timetableId }
                        .map { it.id }
                }
                if (ids.isEmpty()) sender.sendMessage("没有匹配的 service")
                ids.forEach { id ->
                    if (world.basicWorldData.dispatcher.stopService(world, id)) {
                        sender.sendMessage("已请求受控停车 $id；经路将在车尾通过释放点后释放")
                    } else {
                        sender.sendMessage("service 不存在: $id")
                    }
                }
            }
            else -> sender.sendMessage("用法: /mr timetable <load|dump|list|start|status|stop>")
        }
        return true
    }

    private fun resolveTimetableScript(path: String): File {
        val requested = File(path)
        if (requested.isAbsolute) return requested
        val timetableDir = File(BukkitPlugin.INSTANCE.dataFolder, "timetables")
        val inTimetableDir = File(timetableDir, path)
        return if (inTimetableDir.exists()) inTimetableDir else File(BukkitPlugin.INSTANCE.dataFolder, path)
    }

    private fun handleAto(sender: CommandSender, args: List<String>): Boolean {
        if (!sender.isOp && !sender.hasPermission("minerails.admin")) {
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
                    sender.sendMessage("没有匹配的 MineRails 世界${requestedWorld?.let { ": $it" }.orEmpty()}")
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
                    sender.sendMessage("用法: /mr ato dump <world>")
                    return true
                }
                val selection = resolveRailWorld(requestedWorld)
                if (selection == null) {
                    sender.sendMessage("MineRails 世界不存在: $requestedWorld")
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
                    sender.sendMessage("用法: /mr ato reset <world>")
                    return true
                }
                val selection = resolveRailWorld(requestedWorld)
                if (selection == null) {
                    sender.sendMessage("MineRails 世界不存在: $requestedWorld")
                    return true
                }
                val coordinator = selection.value.basicWorldData.atoCoordinator
                if (!coordinator.isStalled) {
                    sender.sendMessage("世界 ${selection.key.name} 的 ATO 已处于 RUNNING")
                    return true
                }
                (selection.value as? CoreWorld)?.physicsCoordinator?.captureCurrentTicket()?.await()
                coordinator.reset()
                sender.sendMessage("已解除世界 ${selection.key.name} 的 ATO Stall；列车位置不会自动恢复")
            }
            else -> sender.sendMessage("用法: /mr ato <status [world]|dump <world>|reset <world>>")
        }
        return true
    }

    private fun resolveRailWorld(requestedWorld: String): Map.Entry<org.bukkit.World, work.art1st.mineRails.core.api.physics.World>? {
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
            sender.sendMessage("没有可用的 MineRails 世界")
            return true
        }

        sender.sendMessage("=== MineRails Performance: ${bukkitWorld.name} ===")
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
        sender.sendMessage("=== MineRails 命令帮助 ===")
        sender.sendMessage("/mr rail create <length> - 在玩家位置创建直线轨道")
        sender.sendMessage("/mr rail connect - 连接选中的轨道")
        sender.sendMessage("/mr train create - 在轨道上创建列车")
        sender.sendMessage("/mr train start - 启动列车（手动驾驶）")
        sender.sendMessage("/mr train stop - 停止列车")
        sender.sendMessage("/mr train anim <name> [scene] - 触发列车部件动画")
        sender.sendMessage("/mr train action <name> - 触发列车动作（动画/声音等）")
        sender.sendMessage("/mr wand rail - 获取 RailWand")
        sender.sendMessage("/mr wand railobject - 获取 RailObjectWand")
        sender.sendMessage("/mr waypoint list|info|remove - 管理路径点")
        sender.sendMessage("/mr route validate <from> <to> - 验证路径点之间的展开经路")
        sender.sendMessage("/mr timetable load <file.js> [id] - 加载 JS 时刻表")
        sender.sendMessage("/mr timetable dump <id> - 输出时刻表内部结构")
        sender.sendMessage("/mr timetable start|status|stop <id> [service] - 管理显式列车 service")
        sender.sendMessage("/mr ato status [world] - 查看 World-local ATO 状态")
        sender.sendMessage("/mr ato dump <world> - 写出最近一次 ATO Stall 报告")
        sender.sendMessage("/mr ato reset <world> - 手动解除 ATO Stall（不恢复列车位置）")
        sender.sendMessage("/mr perf [world] - 查看 Tick、物理和渲染耗时")
        sender.sendMessage("/mr undo - 撤销操作")
        sender.sendMessage("/mr redo - 重做操作")
    }
}