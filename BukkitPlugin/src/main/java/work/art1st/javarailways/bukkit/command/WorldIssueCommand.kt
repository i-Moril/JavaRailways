package work.art1st.javarailways.bukkit.command

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.api.diagnostics.WorldIssue
import work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueKind

/** /jr issues [world] [number]. Console callers must specify the world. */
class WorldIssueCommand(private val plugin: BukkitPlugin) {
    fun execute(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("javarailways.admin")) {
            sender.sendMessage("没有权限查看世界问题。")
            return
        }
        val explicitWorld = args.firstOrNull()?.takeUnless { it.toIntOrNull() != null }
        val world = if (explicitWorld == null) (sender as? Player)?.world
            else plugin.server.getWorld(explicitWorld) ?: runCatching { plugin.server.getWorld(java.util.UUID.fromString(explicitWorld)) }.getOrNull()
        if (world == null) {
            sender.sendMessage("请指定已加载的世界：/jr issues <世界> [编号]")
            return
        }
        val issues = plugin.worldIssues.snapshot(world.uid)
        val selection = args.getOrNull(if (explicitWorld == null) 0 else 1)
        if (selection == null) {
            sender.sendMessage("${world.name}：${issues.size} 个当前问题")
            issues.forEachIndexed { index, issue ->
                sender.sendMessage(Component.text("${index + 1}. ${kindLabel(issue.kind)} ${issue.objectId}：${issue.message}")
                    .clickEvent(ClickEvent.runCommand("/jr issues ${world.uid} @${issueKey(issue)}")))
            }
            return
        }
        val issue = if (selection.startsWith("@")) issues.firstOrNull { issueKey(it) == selection.drop(1) }
            else selection.toIntOrNull()?.let { issues.getOrNull(it - 1) }
        if (issue == null) {
            sender.sendMessage("问题编号不存在，请重新查看 /jr issues。")
            return
        }
        val report = diagnostics(world.name, world.uid.toString(), issue)
        if (sender !is Player) {
            sender.sendMessage(report)
            return
        }
        sender.showDialog(Dialog.create { factory -> factory.empty()
            .base(DialogBase.builder(Component.text("世界问题"))
                .body(listOf(DialogBody.plainMessage(Component.text(report))))
                .canCloseWithEscape(true).build())
            .type(DialogType.multiAction(listOf(ActionButton.builder(Component.text("复制诊断"))
                .action(DialogAction.staticAction(ClickEvent.copyToClipboard(report))).build()), null, 1)) })
    }

    private fun kindLabel(kind: WorldIssueKind): String = when (kind) {
        WorldIssueKind.WORLD -> "世界"
        WorldIssueKind.TRAIN -> "列车"
        WorldIssueKind.PROTECTION_REGION -> "保护区域"
        WorldIssueKind.BLOCK -> "区间"
        WorldIssueKind.SIGNAL -> "信号机"
    }

    private fun issueKey(issue: WorldIssue): String = java.util.UUID.nameUUIDFromBytes(
        "${issue.source}\u0000${issue.kind}\u0000${issue.objectId}\u0000${issue.code}".toByteArray(Charsets.UTF_8)).toString()

    private fun diagnostics(worldName: String, worldId: String, issue: WorldIssue): String =
        "世界：$worldName ($worldId)\n对象：${kindLabel(issue.kind)} ${issue.objectId}\n来源：${issue.source}\n" +
            "错误：${issue.code}\n${issue.message}\n${issue.details.orEmpty()}\n" +
            "首次发现：${issue.firstSeen}\n最近更新：${issue.lastSeen}"
}
