package work.art1st.mineRails.bukkit.tests

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * /mrdev 根命令。
 *
 * 子命令:
 * - configtest  → ConfigApiTest
 *
 * 同时作为 Dialog 点击事件的接收者（/mrdev config:<groupId> <action>）。
 */
class MrDevCommand : BasicCommand {

    override fun execute(ctx: CommandSourceStack, args: Array<out String>) {
        val sender = ctx.sender
        if (sender !is Player) {
            sender.sendMessage("Player only")
            return
        }

        if (args.isEmpty()) {
            sender.sendMessage(Component.text("=== MineRails Dev 命令 ===", NamedTextColor.GOLD))
            sender.sendMessage(Component.text("/mrdev configtest open [groupId]", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/mrdev configtest list", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/mrdev configtest info [groupId]", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/phys create|train|start|stop|info|clean", NamedTextColor.YELLOW))
            return
        }

        when (args[0].lowercase()) {
            "configtest" -> ConfigApiTest.INSTANCE.execute(ctx, args.drop(1).toTypedArray())
            else -> sender.sendMessage(Component.text("未知子命令: ${args[0]}", NamedTextColor.RED))
        }
    }
}
