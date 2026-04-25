package work.art1st.mineRails.bukkit.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.BukkitPlugin

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
            return listOf("rail", "train", "help")
        }
        return emptyList()
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("=== MineRails 命令帮助 ===")
        sender.sendMessage("/mr rail create <length> - 在玩家位置创建直线轨道")
        sender.sendMessage("/mr rail connect - 连接选中的轨道")
        sender.sendMessage("/mr train create - 在轨道上创建列车")
        sender.sendMessage("/mr train start - 启动列车（手动驾驶）")
        sender.sendMessage("/mr train stop - 停止列车")
        sender.sendMessage("/mr debug - 显示调试信息")
    }
}