package work.art1st.mineRails.bukkit.control

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.train.CarExtension
import work.art1st.mineRails.bukkit.extension.train.renderer
import work.art1st.mineRails.core.api.physics.train.Train

class TrainControlDialog(
    private val plugin: BukkitPlugin,
    private val manager: TrainControlManager
) {
    fun show(player: Player, train: Train) {
        val ownerId = manager.ownerOf(train)
        val ownedByPlayer = ownerId == player.uniqueId
        val ownerName = ownerId?.let { Bukkit.getOfflinePlayer(it).name ?: it.toString().take(8) } ?: "无"
        val speedKmh = train.speed * 3.6
        val packageName = resolvePackageName(train)
        val executableActions = plugin.modelManager.getTrainActions(packageName)
            .values
            .filter { it.executable && (!it.requiresControl || ownedByPlayer) }
            .sortedBy { it.displayName }

        val bodies = mutableListOf<DialogBody>()
        bodies.add(DialogBody.plainMessage(Component.text("列车: ${train.id}", NamedTextColor.GRAY)))
        bodies.add(DialogBody.plainMessage(Component.text("控制者: $ownerName", NamedTextColor.GRAY)))
        bodies.add(DialogBody.plainMessage(Component.text("速度: ${"%.1f".format(speedKmh)} km/h", NamedTextColor.GRAY)))
        if (executableActions.isEmpty()) {
            bodies.add(DialogBody.plainMessage(Component.text("没有可执行动作", NamedTextColor.DARK_GRAY)))
        }

        val buttons = mutableListOf<ActionButton>()
        buttons.add(actionButton("尝试获取控制权", NamedTextColor.GREEN) { p ->
            p.sendActionBar(Component.text("获取控制权将在下一阶段实现", NamedTextColor.YELLOW))
            show(p, train)
        })
        executableActions.forEach { action ->
            buttons.add(actionButton(action.displayName, NamedTextColor.AQUA) { p ->
                val applied = train.renderer.applyAction(action.name)
                p.sendActionBar(Component.text(
                    if (applied) "已执行: ${action.displayName}" else "动作未生效: ${action.displayName}",
                    if (applied) NamedTextColor.GREEN else NamedTextColor.YELLOW
                ))
                show(p, train)
            })
        }

        val exit = ActionButton.builder(Component.text("关闭", NamedTextColor.GRAY)).build()
        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(Component.text("列车操控", NamedTextColor.GOLD))
                        .body(bodies)
                        .canCloseWithEscape(true)
                        .build()
                )
                .type(DialogType.multiAction(buttons, exit, 1))
        }
        player.showDialog(dialog)
    }

    private fun actionButton(label: String, color: NamedTextColor, action: (Player) -> Unit): ActionButton {
        val click = DialogAction.customClick(
            { _, audience ->
                if (audience is Player) action(audience)
            },
            ClickCallback.Options.builder()
                .uses(1)
                .lifetime(ClickCallback.DEFAULT_LIFETIME)
                .build()
        )
        return ActionButton.builder(Component.text(label, color))
            .action(click)
            .build()
    }

    private fun resolvePackageName(train: Train): String? {
        val firstModelName = train.cars.firstOrNull()?.let { car ->
            runCatching { (car.renderer as? CarExtension)?.carModelName }.getOrNull()
        } ?: return null
        return plugin.modelManager.getCar(firstModelName)?.packageName
    }
}
