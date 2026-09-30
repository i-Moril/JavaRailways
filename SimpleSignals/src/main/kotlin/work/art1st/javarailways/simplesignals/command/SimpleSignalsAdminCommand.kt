package work.art1st.javarailways.simplesignals.command

import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsDefinitionDrafts
import work.art1st.javarailways.simplesignals.persistence.definitionDraftPatch
import work.art1st.javarailways.simplesignals.persistence.definitionDraftSummary
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionAuthoringResult
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionAuthoringStatus
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntimeManager
import java.util.UUID

/** One command consumer of the existing manager. All document work runs on Paper's worker. */
internal class SimpleSignalsAdminCommand(
    private val plugin: Plugin,
    private val manager: SimpleSignalsWorldRuntimeManager,
    private val drafts: SimpleSignalsDefinitionDrafts,
    private val enabled: () -> Boolean = { plugin.isEnabled },
    private val resolveWorld: (String) -> UUID? = { input ->
        val uuid = runCatching { UUID.fromString(input) }.getOrNull()
        if (uuid != null && uuid.toString() == input) plugin.server.getWorld(uuid)?.uid
        else if (uuid != null) null else plugin.server.getWorld(input)?.uid
    },
    private val worker: (() -> Unit) -> Unit = { action ->
        plugin.server.asyncScheduler.runNow(plugin) { if (plugin.isEnabled) action() }
    },
    private val reply: (CommandSender, Component) -> Unit = { sender, message ->
        if (plugin.isEnabled) {
            if (sender is Player) {
                sender.scheduler.run(plugin, { if (plugin.isEnabled && sender.isOnline) sender.sendMessage(message) }, {})
            } else {
                plugin.server.globalRegionScheduler.run(plugin) { if (plugin.isEnabled) sender.sendMessage(message) }
            }
        }
    }
) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!enabled()) return true
        if (sender !is Player && sender !is ConsoleCommandSender) {
            sender.sendMessage(Component.text("SimpleSignals commands require a player or console sender"))
            return true
        }
        fun respond(text: String) = reply(sender, Component.text(text))
        if (!sender.hasPermission(PERMISSION)) {
            respond("SimpleSignals: missing permission $PERMISSION")
            return true
        }
        val operation = args.firstOrNull()
        if (operation !in OPERATIONS || args.size != (if (operation == "inspect") 2 else 3)) {
            respond(USAGE)
            return true
        }
        val worldId = resolveWorld(args[1])
        if (worldId == null) {
            respond("SimpleSignals: loaded world name or canonical UUID required: ${args[1]}")
            return true
        }
        val stem = args.getOrNull(2)
        if (stem != null && !SimpleSignalsDefinitionDrafts.validStem(stem)) {
            respond("SimpleSignals: draft must be a simple filename stem (letters, digits, _ or -)")
            return true
        }
        val runtime = manager.authoringRuntime(worldId)
        if (runtime == null) {
            respond("WORLD_UNAVAILABLE: SimpleSignals runtime is unavailable for $worldId; repair the world definitions first")
            return true
        }
        worker {
            if (!enabled()) return@worker
            if (manager.authoringRuntime(worldId) !== runtime) {
                respond("WORLD_UNAVAILABLE: captured world runtime is no longer available: $worldId")
                return@worker
            }
            try {
                val publication = runtime.authoringPublication()
                val current = publication.definitions
                when (operation) {
                    "inspect" -> respond("SimpleSignals $worldId: installed, definitionRevision=${current.definitionRevision}, " +
                        "topologyRevision=${publication.topology.revision}; regions=${current.regions.size}, " +
                        "tables=${current.routeTables.size}, blocks=${current.fixedBlocks.size}, " +
                        "fixedBlockSignals=${current.fixedBlockSignals.size}, protectionSignals=${current.protectionSignals.size}, " +
                        "routeIndicators=${current.routeIndicators.size}")
                    "export" -> {
                        drafts.export(stem!!, current)
                        respond("Exported drafts/$stem.json for $worldId at baseline ${current.definitionRevision}. " +
                            "Omitted existing IDs mean deletion; raise each changed object's revision.")
                    }
                    else -> {
                        val draft = drafts.read(stem!!, worldId)
                        val patch = definitionDraftPatch(current, draft)
                        val result = if (operation == "validate") {
                            manager.validateDefinitionPatch(worldId, draft.definitionRevision, patch, runtime)
                        } else {
                            manager.applyDefinitionPatch(worldId, draft.definitionRevision, patch, runtime)
                        }
                        respond(formatResult(result) + "; " + definitionDraftSummary(current, draft))
                    }
                }
            } catch (failure: Exception) {
                respond("SimpleSignals $operation failed for $worldId: ${failure.message ?: failure.javaClass.simpleName}")
            }
        }
        return true
    }

    companion object {
        const val PERMISSION = "simplesignals.admin"
        val OPERATIONS = setOf("inspect", "export", "validate", "apply")
        const val USAGE = "/simplesignals inspect <world> | export/validate/apply <world> <draft>. Omitted existing IDs mean deletion."
        private const val MAX_REPLY_ENTRIES = 8
        private const val MAX_REPLY_VALUE_CHARS = 256
        private const val MAX_REPLY_CHARS = 8192

        private fun shortValue(value: String): String =
            if (value.length <= MAX_REPLY_VALUE_CHARS) value else value.take(MAX_REPLY_VALUE_CHARS) + "..."

        private fun identityPreview(values: Collection<*>): String {
            val sample = values.map { it.toString() }.sorted().take(MAX_REPLY_ENTRIES).map(::shortValue)
            val omitted = values.size - sample.size
            return "count=${values.size}, sample=$sample" + if (omitted > 0) " ($omitted omitted)" else ""
        }

        fun formatResult(result: SimpleSignalsDefinitionAuthoringResult): String = buildString {
            append(result.status)
            if (result.status == SimpleSignalsDefinitionAuthoringStatus.APPLIED) {
                append(": definitionRevision=${result.definitions!!.definitionRevision}; draft retained (baseline is now stale)")
            } else if (result.status == SimpleSignalsDefinitionAuthoringStatus.VALIDATED) {
                append(": affected rails ${identityPreview(result.affectedRailIds)}; no resources reserved, live blockers checked only during apply")
            }
            result.diagnostics.take(MAX_REPLY_ENTRIES).forEach { append("; ${shortValue(it)}") }
            if (result.diagnostics.size > MAX_REPLY_ENTRIES) {
                append("; ${result.diagnostics.size - MAX_REPLY_ENTRIES} diagnostics omitted")
            }
            val blockers = result.editDecision?.blockers.orEmpty()
            blockers.take(MAX_REPLY_ENTRIES).forEach { blocker ->
                append("; ${blocker.kind}: owner=${shortValue(blocker.owner.ownerId)}, revision=${blocker.owner.revision}")
                append(", overlap rails ${identityPreview(blocker.overlap.railIds)}")
                append(", nodes ${identityPreview(blocker.overlap.topologyNodeKeys)}")
                append(", resources ${identityPreview(blocker.overlap.resourceIds)}")
            }
            if (blockers.size > MAX_REPLY_ENTRIES) {
                append("; ${blockers.size - MAX_REPLY_ENTRIES} blockers omitted")
            }
        }.let { text ->
            if (text.length <= MAX_REPLY_CHARS) text else text.take(MAX_REPLY_CHARS) + "; additional details omitted"
        }
    }
}
