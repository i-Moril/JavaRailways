package work.art1st.javarailways.decorations.display

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import work.art1st.javarailways.decorations.api.display.DisplayStyleSelection
import work.art1st.javarailways.decorations.api.display.DisplayStyleService

/** Player world, permissions and messages are accessed only on the entity owner. */
class DisplayStyleCommand(
    private val plugin: Plugin,
    private val styles: DisplayStyleService,
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("该命令只能由玩家使用。")
            return true
        }
        val arguments = args.toList()
        sender.scheduler.run(plugin, { _ ->
            if (plugin.isEnabled) execute(sender, label, arguments)
        }, null)
        return true
    }

    private fun execute(player: Player, label: String, args: List<String>) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage("你没有权限调整固定单元屏幕样式。")
            return
        }
        if (args.size !in 3..4 || !args[0].equals("display-style", ignoreCase = true)) {
            player.sendMessage("用法：/$label display-style <单元ID> <类别> [模板ID|reset]")
            return
        }
        val worldId = player.world.uid
        try {
            val selection = when {
                args.size == 3 -> styles.inspect(worldId, args[1], args[2])
                args[3].equals("reset", ignoreCase = true) -> styles.reset(worldId, args[1], args[2])
                else -> styles.set(worldId, args[1], args[2], args[3])
            }
            report(player, selection)
        } catch (failure: IllegalArgumentException) {
            player.sendMessage("无法调整屏幕样式：${failure.message ?: "参数无效"}")
        } catch (failure: IllegalStateException) {
            player.sendMessage("无法调整屏幕样式：${failure.message ?: "服务当前不可用"}")
        }
    }

    private fun report(player: Player, selection: DisplayStyleSelection) {
        val current = selection.templateId?.let { "覆盖模板 $it" } ?: "使用各屏幕默认模板"
        val available = selection.availableTemplates.sorted().joinToString(", ").ifEmpty { "无" }
        player.sendMessage("单元 ${selection.unitId} / ${selection.category}：$current；已绑定 ${selection.screenCount} 个屏幕。")
        player.sendMessage("所有已绑定屏幕共有的模板：$available。覆盖随单元保留，仅本次插件运行有效。")
    }

    // Tab callbacks may be asynchronous: never read live player world/permission here.
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        when {
            sender !is Player -> emptyList()
            args.size == 1 -> listOf("display-style").filter { it.startsWith(args[0], ignoreCase = true) }
            args.size == 4 && args[0].equals("display-style", ignoreCase = true) ->
                listOf("reset").filter { it.startsWith(args[3], ignoreCase = true) }
            else -> emptyList()
        }

    companion object { const val PERMISSION = "decorations.display-style" }
}
