package work.art1st.javarailways.simplesignals.authoring

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.entity.Player

/** Shared owner-thread choice/confirmation presentation; callbacks still validate their own intent. */
internal object WandDialogs {
    fun page(player: Player, page: SignalWandDialog) {
        fun button(option: Pair<String, () -> Unit>) = ActionButton.builder(Component.text(option.first.take(256)))
            .action(DialogAction.customClick({ _, audience -> if (audience === player) option.second() },
                ClickCallback.Options.builder().uses(1).lifetime(ClickCallback.DEFAULT_LIFETIME).build())).build()
        val buttons = page.options.map(::button) + listOfNotNull(page.clipboardText?.let {
            ActionButton.builder(Component.text(WandI18n.format(player, "signal.diagnostic.copy")))
                .action(DialogAction.staticAction(ClickEvent.copyToClipboard(it))).build()
        })
        player.showDialog(Dialog.create { factory -> factory.empty()
            .base(DialogBase.builder(Component.text(page.title)).body(page.body.map { DialogBody.plainMessage(Component.text(it)) })
                .canCloseWithEscape(true).build())
            .type(actionType(buttons, page.exit?.let(::button), page.columns)) })
    }
    internal fun actionType(buttons: List<ActionButton>, exit: ActionButton?, columns: Int): DialogType =
        if (buttons.isEmpty()) exit?.let(DialogType::notice) ?: DialogType.notice()
        else DialogType.multiAction(buttons, exit, columns)

    fun boundaryName(player: Player, description: String, initial: String, submitted: (String?) -> Unit) {
        fun button(key: String, submit: Boolean) = ActionButton.builder(Component.text(WandI18n.format(player, key)))
            .action(DialogAction.customClick({ view, audience -> if (audience === player)
                submitted(if (submit) view.getText("name").orEmpty() else null) },
                ClickCallback.Options.builder().uses(1).lifetime(ClickCallback.DEFAULT_LIFETIME).build())).build()
        player.showDialog(Dialog.create { factory -> factory.empty()
            .base(DialogBase.builder(Component.text(WandI18n.format(player, "boundary.edit")))
                .body(listOf(DialogBody.plainMessage(Component.text(description)),
                    DialogBody.plainMessage(Component.text(WandI18n.format(player, "boundary.help")))))
                .inputs(listOf(DialogInput.text("name", Component.text(WandI18n.format(player, "boundary.edit")))
                    .initial(initial).maxLength(128).build())).canCloseWithEscape(true).build())
            .type(DialogType.multiAction(listOf(button("boundary.save", true), button("cancel", false)), null, 2)) })
    }

    fun placement(player: Player, title: String, initial: PlacementWandInput,
        submitted: (PlacementWandInput?) -> Unit) {
        val inputs = initial.fields.map { (key, value) ->
            DialogInput.text(key, Component.text(WandI18n.format(player, if (key.startsWith("r")) "placement.degrees" else "placement.metres", key)))
                .initial(value).maxLength(64).build()
        } + if (initial.followRailTilt == null) emptyList() else listOf(
            DialogInput.bool("tilt", Component.text(WandI18n.format(player, "placement.tilt"))).initial(initial.followRailTilt).build())
        val submit = ActionButton.builder(Component.text(WandI18n.format(player, "placement.review"))).action(DialogAction.customClick(
            { view, audience -> if (audience === player) submitted(PlacementWandInput(
                initial.fields.keys.associateWith { view.getText(it).orEmpty() },
                if (initial.followRailTilt == null) null else view.getBoolean("tilt"))) },
            ClickCallback.Options.builder().uses(1).lifetime(ClickCallback.DEFAULT_LIFETIME).build())).build()
        val cancel = ActionButton.builder(Component.text(WandI18n.format(player, "cancel"))).action(DialogAction.customClick(
            { _, audience -> if (audience === player) submitted(null) },
            ClickCallback.Options.builder().uses(1).lifetime(ClickCallback.DEFAULT_LIFETIME).build())).build()
        player.showDialog(Dialog.create { factory -> factory.empty()
            .base(DialogBase.builder(Component.text(title)).body(listOf(DialogBody.plainMessage(
                Component.text(WandI18n.format(player, "placement.help")))))
                .inputs(inputs).canCloseWithEscape(true).build())
            .type(DialogType.multiAction(listOf(submit, cancel), null, 2)) })
    }

    fun show(player: Player, title: String, options: List<Pair<String, () -> Unit>>) {
        val buttons = options.map { (label, action) ->
            ActionButton.builder(Component.text(label.take(256))).action(DialogAction.customClick(
                { _, audience -> if (audience === player) action() },
                ClickCallback.Options.builder().uses(1).lifetime(ClickCallback.DEFAULT_LIFETIME).build())).build()
        }
        player.showDialog(Dialog.create { factory -> factory.empty()
            .base(DialogBase.builder(Component.text(title)).canCloseWithEscape(true).build())
            .type(actionType(buttons, null, 1)) })
    }
}

internal data class SignalWandDialog(val title: String, val body: List<String> = emptyList(),
    val options: List<Pair<String, () -> Unit>>, val exit: Pair<String, () -> Unit>? = null, val columns: Int = 2,
    val clipboardText: String? = null)
