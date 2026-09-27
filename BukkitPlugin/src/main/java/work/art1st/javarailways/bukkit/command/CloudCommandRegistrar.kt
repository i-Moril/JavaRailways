package work.art1st.javarailways.bukkit.command

import io.papermc.paper.command.brigadier.CommandSourceStack
import org.incendo.cloud.Command
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.paper.PaperCommandManager
import org.incendo.cloud.parser.standard.StringParser.greedyStringParser
import org.incendo.cloud.suggestion.BlockingSuggestionProvider
import work.art1st.javarailways.bukkit.BukkitPlugin

/** Registers the JavaRailways command tree through Cloud's native Paper/Brigadier manager. */
class CloudCommandRegistrar(plugin: BukkitPlugin) {
    private val delegate = JavaRailwaysCommand()
    val manager: PaperCommandManager<CommandSourceStack> = PaperCommandManager.builder()
        .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
        .buildOnEnable(plugin)

    init {
        val root = manager.commandBuilder("javarailways", "jr")
        manager.command(root.handler { context -> execute(context.sender(), emptyList()) })
        ROUTES.forEach { route -> register(root, route) }
    }

    private fun register(root: Command.Builder<CommandSourceStack>, route: Route) {
        var builder = root
        route.path.forEach { literal -> builder = builder.literal(literal) }
        if (!route.acceptsArguments) {
            manager.command(builder.handler { context -> execute(context.sender(), route.path) })
            return
        }
        val suggestions = BlockingSuggestionProvider.Strings<CommandSourceStack> { context, input ->
            delegate.suggest(
                context.sender().sender,
                (route.path + tokenize(input.remainingInput())).toTypedArray()
            )
        }
        manager.command(
            builder.optional("arguments", greedyStringParser(), suggestions)
                .handler { context ->
                    val raw = context.getOrDefault("arguments", "")
                    execute(context.sender(), route.path + tokenizeForExecution(raw))
                }
        )
    }

    private fun execute(source: CommandSourceStack, args: List<String>) {
        delegate.execute(source.sender, "javarailways", args.toTypedArray())
    }

    private data class Route(val path: List<String>, val acceptsArguments: Boolean = false)

    companion object {
        internal fun tokenize(input: String): List<String> =
            if (input.isEmpty()) listOf("") else input.split(' ')

        internal fun registeredRoutePaths(): Set<String> = ROUTES.map { it.path.joinToString(" ") }.toSet()

        private fun tokenizeForExecution(input: String): List<String> =
            if (input.isBlank()) emptyList() else input.split(' ')

        private val ROUTES = buildList {
            fun parent(vararg path: String) = add(Route(path.toList()))
            fun args(vararg path: String) = add(Route(path.toList(), acceptsArguments = true))

            parent("train")
            args("train", "create"); args("train", "rename"); parent("train", "key")
            args("train", "anim"); args("train", "animation"); args("train", "action"); args("train", "behavior")
            parent("train", "info"); parent("train", "list"); args("train", "delete")
            parent("train", "sit"); parent("train", "stand")
            parent("wand"); parent("wand", "rail"); parent("wand", "give")
            parent("wand", "railobject"); parent("wand", "object"); parent("wand", "ro")
            listOf("waypoint", "wp", "stop").forEach { name ->
                parent(name); parent(name, "list"); args(name, "info"); args(name, "remove")
            }
            parent("route"); args("route", "validate")
            args("navigate"); args("nav")
            parent("depot")
            args("depot", "assign"); parent("depot", "list"); args("depot", "reset"); args("depot", "reset-all")
            listOf("timetable", "tt").forEach { name ->
                parent(name)
                listOf("edit", "load", "dump", "start", "reset", "status", "stop", "replan")
                    .forEach { sub -> args(name, sub) }
                parent(name, "list")
            }
            parent("ato"); args("ato", "status"); args("ato", "dump"); args("ato", "reset")
            args("perf"); args("performance")
            parent("resources"); parent("resources", "reload")
            parent("resource"); parent("resource", "reload")
            parent("undo"); parent("redo"); parent("help")
        }
    }
}
