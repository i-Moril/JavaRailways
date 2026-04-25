package work.art1st.mineRails.bukkit.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.core.api.physics.World

object TrainCommand {
    fun onCommand(sender: CommandSender, args: List<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage("用法: /mr train <create|start|stop|info>")
            return true
        }

        when (args[0].lowercase()) {
            "create" -> return handleCreate(sender, args.drop(1))
            "start" -> return handleStart(sender)
            "stop" -> return handleStop(sender)
            "info" -> return handleInfo(sender)
            else -> sender.sendMessage("未知子命令: ${args[0]}")
        }
        return true
    }

    private fun handleCreate(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("此命令需要玩家执行")
            return true
        }

        val world = getPlayerWorld(sender) ?: return true

        sender.sendMessage("列车创建功能待实现")
        sender.sendMessage("需要: Core 内部 API 支持列车创建")

        return true
    }

    private fun handleStart(sender: CommandSender): Boolean {
        sender.sendMessage("列车启动功能待实现")
        return true
    }

    private fun handleStop(sender: CommandSender): Boolean {
        sender.sendMessage("列车停止功能待实现")
        return true
    }

    private fun handleInfo(sender: CommandSender): Boolean {
        sender.sendMessage("=== 列车状态 ===")
        sender.sendMessage("列车功能待实现")
        return true
    }

    private fun getPlayerWorld(player: Player): World? {
        val bukkitWorld = player.world
        val worldManager = BukkitPlugin.worldManager
        return worldManager.railWorlds[bukkitWorld]
    }
}