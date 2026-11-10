package work.art1st.javarailways.decorations.display

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID

/** Only the player's entity owner reads/sends player state; settings themselves are platform-free. */
class DisplayDistanceCommand(
    private val plugin: Plugin,
    private val settings: DisplayDistanceSettings,
    private val refreshAudience: (UUID) -> Unit,
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
            player.sendMessage("你没有权限调整车内屏幕视距。")
            return
        }
        if (args.size !in 1..2 || !args[0].equals("display-distance", ignoreCase = true)) {
            player.sendMessage("用法：/$label display-distance [方块数|reset]（0 关闭车内屏幕）")
            return
        }
        val id = player.uniqueId
        if (args.size == 1) {
            player.sendMessage("车内屏幕视距：${settings.distanceFor(id)} 格；默认 ${settings.defaultBlocks} 格，最大 ${settings.maxBlocks} 格。设置仅本次在线有效，0 为关闭。")
            return
        }
        if (args[1].equals("reset", ignoreCase = true)) {
            settings.reset(id)
        } else {
            val blocks = args[1].toDoubleOrNull()
            if (blocks == null || !blocks.isFinite() || blocks < 0 || blocks > settings.maxBlocks) {
                player.sendMessage("视距必须是 0 到 ${settings.maxBlocks} 之间的有限数字。")
                return
            }
            settings.set(id, blocks)
        }
        refreshAudience(id)
        player.sendMessage("车内屏幕视距已设为 ${settings.distanceFor(id)} 格；仅本次在线有效。")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        when {
            sender !is Player -> emptyList()
            args.size == 1 -> listOf("display-distance").filter { it.startsWith(args[0], ignoreCase = true) }
            args.size == 2 && args[0].equals("display-distance", ignoreCase = true) ->
                listOf("reset", "0", settings.defaultBlocks.toString(), settings.maxBlocks.toString())
                    .distinct().filter { it.startsWith(args[1], ignoreCase = true) }
            else -> emptyList()
        }

    companion object { const val PERMISSION = "decorations.display-distance" }
}
