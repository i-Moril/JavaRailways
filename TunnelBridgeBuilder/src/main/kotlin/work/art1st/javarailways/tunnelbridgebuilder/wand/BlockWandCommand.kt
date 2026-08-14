package work.art1st.javarailways.tunnelbridgebuilder.wand

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class BlockWandCommand(private val wandItem: BlockWandItem) : CommandExecutor {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("This command can only be used by a player")
            return true
        }
        player.inventory.addItem(wandItem.create())
        player.sendMessage("已获得 Tunnel/Bridge Builder Wand")
        return true
    }
}
