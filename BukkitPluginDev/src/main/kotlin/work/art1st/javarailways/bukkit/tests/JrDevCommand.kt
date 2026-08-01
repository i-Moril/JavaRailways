package work.art1st.javarailways.bukkit.tests

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * /jrdev 根命令。
 *
 * 子命令:
 * - configtest  → ConfigApiTest
 *
 * 同时作为 Dialog 点击事件的接收者（/jrdev config:<groupId> <action>）。
 */
class JrDevCommand : BasicCommand {

    override fun execute(ctx: CommandSourceStack, args: Array<out String>) {
        val sender = ctx.sender
        if (sender !is Player) {
            sender.sendMessage("Player only")
            return
        }

        if (args.isEmpty()) {
            sender.sendMessage(Component.text("=== JavaRailways Dev 命令 ===", NamedTextColor.GOLD))
            sender.sendMessage(Component.text("/jrdev configtest open [groupId]", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/jrdev configtest list", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/jrdev configtest info [groupId]", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("/phys create|train|start|stop|info|clean", NamedTextColor.YELLOW))
            return
        }

        when (args[0].lowercase()) {
            "configtest" -> ConfigApiTest.INSTANCE.execute(ctx, args.drop(1).toTypedArray())
            else -> sender.sendMessage(Component.text("未知子命令: ${args[0]}", NamedTextColor.RED))
        }
    }
}
