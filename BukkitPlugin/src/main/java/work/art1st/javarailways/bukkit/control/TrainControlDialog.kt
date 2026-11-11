package work.art1st.javarailways.bukkit.control

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
import work.art1st.javarailways.basic.control.RealisticManualController
import work.art1st.javarailways.basic.control.TrainDriveDirection
import work.art1st.javarailways.simpleato.timetable.TimetableControlMode
import work.art1st.javarailways.simpleato.timetable.TimetableControlTransferResult
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.extension.train.renderer
import work.art1st.javarailways.bukkit.ui.dialog.playerDialogCallback
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.bukkit.behavior.TrainBehaviorActionDefinition
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge

class TrainControlDialog(
    plugin: BukkitPlugin,
    private val manager: TrainControlManager
) {
    private val dispatcher = plugin.platformDispatcher

    fun show(player: Player, train: Train) {
        BukkitThreadGuardBridge.runInSimulationContext(train.world) { train.renderer.loadBehaviorActions() }.whenComplete { actions, failure ->
            plugin.platformDispatcher.runForEntity(player, action = {
                if (failure != null) player.sendMessage(Component.text("动作列表加载失败: ${failure.message}", NamedTextColor.RED))
                else BukkitThreadGuardBridge.runInSimulationContext(train.world) { showLoaded(player, train, actions) }
            })
        }
    }

    private fun showLoaded(player: Player, train: Train, actions: List<TrainBehaviorActionDefinition>) {
        val ownerId = manager.ownerOf(train)
        val ownedByPlayer = ownerId == player.uniqueId
        val ownerName = ownerId?.let { Bukkit.getOfflinePlayer(it).name ?: it.toString().take(8) } ?: "无"
        val speedKmh = train.speed * 3.6
        val controller = manager.sessionFor(train)?.controller
        val executableActions = actions
            .filter { it.executable && (!it.requiresControl || ownedByPlayer) }
            .sortedBy { it.displayName }

        val bodies = mutableListOf<DialogBody>()
        bodies.add(DialogBody.plainMessage(Component.text("列车: ${train.id}", NamedTextColor.GRAY)))
        bodies.add(DialogBody.plainMessage(Component.text("控制者: $ownerName", NamedTextColor.GRAY)))
        bodies.add(DialogBody.plainMessage(Component.text("速度: ${"%.1f".format(speedKmh)} km/h", NamedTextColor.GRAY)))
        manager.sessionFor(train)?.let { session ->
            bodies.add(
                DialogBody.plainMessage(
                    Component.text("时刻表模式: ${session.timetableMode}", NamedTextColor.GRAY)
                )
            )
        }
        if (controller != null) {
            bodies.add(DialogBody.plainMessage(Component.text("方向: ${controller.direction.label}  挡位: ${controller.handle}", NamedTextColor.GRAY)))
        }
        if (executableActions.isEmpty()) {
            bodies.add(DialogBody.plainMessage(Component.text("没有可执行动作", NamedTextColor.DARK_GRAY)))
        }

        val buttons = mutableListOf<ActionButton>()
        if (ownedByPlayer) {
            buttons.add(actionButton("释放控制权", NamedTextColor.YELLOW) { p ->
                manager.releaseManualControl(p).whenComplete { _, failure ->
                    plugin.platformDispatcher.runForEntity(p, action = {
                        p.sendActionBar(Component.text(
                            failure?.message ?: "已释放列车控制权；需要显式 replan", NamedTextColor.YELLOW
                        ))
                    })
                }
            })
            if (manager.sessionFor(train)?.timetableMode == TimetableControlMode.MANUAL_ROUTED) {
                buttons.add(actionButton("关闭时刻表进路辅助", NamedTextColor.RED) { p ->
                    manager.switchToFullManual(p).whenComplete { result, failure ->
                        plugin.platformDispatcher.runForEntity(p, action = {
                            if (failure != null) {
                                p.sendActionBar(Component.text(failure.message.orEmpty(), NamedTextColor.RED))
                                return@runForEntity
                            }
                            when (result) {
                                is TimetableControlTransferResult.Suspended -> p.sendActionBar(
                                    Component.text("已进入 FULL_MANUAL，不再申请新进路", NamedTextColor.YELLOW)
                                )
                                is TimetableControlTransferResult.Failure -> p.sendActionBar(
                                    Component.text(result.reason, NamedTextColor.RED)
                                )
                                else -> p.sendActionBar(
                                    Component.text("列车没有可切换的时刻表进路辅助", NamedTextColor.YELLOW)
                                )
                            }
                        })
                    }
                })
            }
        } else {
            buttons.add(actionButton("获取控制权", NamedTextColor.GREEN) { p ->
                manager.acquireManualControl(p, train).whenComplete { result, failure ->
                    plugin.platformDispatcher.runForEntity(p, action = {
                        if (failure != null) {
                            p.sendActionBar(Component.text(failure.message.orEmpty(), NamedTextColor.RED))
                            return@runForEntity
                        }
                        when (result) {
                            is TrainControlManager.AcquireResult.Acquired -> {
                                p.sendActionBar(Component.text("已获取列车控制权", NamedTextColor.GREEN))
                            }
                            is TrainControlManager.AcquireResult.AlreadyControlled -> {
                                val owner = Bukkit.getOfflinePlayer(result.ownerId).name ?: result.ownerId.toString().take(8)
                                p.sendActionBar(Component.text("列车正由 $owner 控制", NamedTextColor.RED))
                            }
                            is TrainControlManager.AcquireResult.ConfigError -> {
                                p.sendActionBar(Component.text(result.message, NamedTextColor.RED))
                            }
                            TrainControlManager.AcquireResult.MissingKey -> p.sendActionBar(Component.text("需要绑定的列车钥匙", NamedTextColor.RED))
                            TrainControlManager.AcquireResult.NotDriverSeat -> p.sendActionBar(Component.text("请坐在该列车驾驶座上", NamedTextColor.RED))
                            TrainControlManager.AcquireResult.TrainUnavailable -> p.sendActionBar(Component.text("列车暂不可控", NamedTextColor.RED))
                            null -> Unit
                        }
                    })
                }
            })
        }
        buttons.add(actionButton("解绑此钥匙", NamedTextColor.RED) { p ->
            val unbound = manager.unbindKeyFor(p, train)
            if (unbound) {
                if (manager.ownerOf(train) == p.uniqueId) manager.releaseManualControl(p)
                p.sendActionBar(Component.text("列车钥匙已解绑", NamedTextColor.YELLOW))
            } else {
                p.sendActionBar(Component.text("手上没有绑定此列车的钥匙", NamedTextColor.RED))
            }
        })
        if (ownedByPlayer && controller != null) {
            buttons.add(directionButton(controller, TrainDriveDirection.FORWARD, "方向 F"))
            buttons.add(directionButton(controller, TrainDriveDirection.NEUTRAL, "方向 N"))
            buttons.add(directionButton(controller, TrainDriveDirection.REVERSE, "方向 R"))
        }
        executableActions.forEach { action ->
            buttons.add(actionButton(action.displayName, NamedTextColor.AQUA) { p ->
                BukkitThreadGuardBridge.runInSimulationContext(train.world) {
                    train.renderer.executeModelAction(action.name, hasControl = manager.ownerOf(train) == p.uniqueId)
                }.whenComplete { _, failure ->
                    plugin.platformDispatcher.runForEntity(p, action = {
                        p.sendActionBar(Component.text(
                            if (failure == null) "已执行: ${action.displayName}" else "动作失败: ${failure.message}",
                            if (failure == null) NamedTextColor.GREEN else NamedTextColor.YELLOW
                        ))
                    })
                }
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

    private fun directionButton(controller: RealisticManualController, direction: TrainDriveDirection, label: String): ActionButton {
        return actionButton(label, NamedTextColor.LIGHT_PURPLE) { p ->
            controller.coast()
            controller.setDirection(direction).whenComplete { _, failure ->
                plugin.platformDispatcher.runForEntity(p, action = {
                    p.sendActionBar(Component.text(
                        failure?.message ?: "方向已切换为 ${direction.label}",
                        if (failure == null) NamedTextColor.GREEN else NamedTextColor.RED
                    ))
                })
            }
        }
    }

    private fun actionButton(label: String, color: NamedTextColor, action: (Player) -> Unit): ActionButton {
        val click = DialogAction.customClick(
            playerDialogCallback(dispatcher, { Unit }) { _, player ->
                player.closeInventory()
                action(player)
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


}
