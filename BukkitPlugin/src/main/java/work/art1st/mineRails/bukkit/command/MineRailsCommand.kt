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
            "wand" -> return handleWand(sender, args.drop(1))
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
            return listOf("rail", "train", "wand", "undo", "redo", "help")
        }
        return emptyList()
    }

    private fun handleWand(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Player only"); return true }
        val wand = BukkitPlugin.INSTANCE.railWand
        when (args.firstOrNull()?.lowercase()) {
            "give" -> { wand.giveWand(sender); sender.sendMessage("RailWand 已给予") }
            else -> sender.sendMessage("用法: /mr wand give (设置请 Shift+右键打开 Dialog)")
        }
        return true
    }

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
        sender.sendMessage("/mr wand give - 获取 RailWand")
        sender.sendMessage("/mr wand set <key> <value> - 设置 RailWand 参数")
        sender.sendMessage("/mr undo - 撤销操作")
        sender.sendMessage("/mr redo - 重做操作")
    }
}