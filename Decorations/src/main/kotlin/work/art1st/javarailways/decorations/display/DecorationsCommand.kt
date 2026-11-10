package work.art1st.javarailways.decorations.display

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/** Each subcommand owns its permission check and entity scheduling. */
class DecorationsCommand(
    private val distance: DisplayDistanceCommand,
    private val style: DisplayStyleCommand,
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean =
        when (args.firstOrNull()?.lowercase()) {
            "display-distance" -> distance.onCommand(sender, command, label, args)
            "display-style" -> style.onCommand(sender, command, label, args)
            else -> distance.onCommand(sender, command, label, args)
        }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        when {
            sender !is Player -> emptyList()
            args.size == 1 -> listOf("display-distance", "display-style").filter { it.startsWith(args[0], ignoreCase = true) }
            args.firstOrNull()?.equals("display-style", ignoreCase = true) == true -> style.onTabComplete(sender, command, alias, args)
            else -> distance.onTabComplete(sender, command, alias, args)
        }
}
