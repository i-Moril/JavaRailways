package work.art1st.javarailways.simplesignals.authoring

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.entity.Player

/** Shared owner-thread choice/confirmation presentation; callbacks still validate their own intent. */
internal object WandDialogs {
    fun show(player: Player, title: String, options: List<Pair<String, () -> Unit>>) {
        val buttons = options.map { (label, action) ->
            ActionButton.builder(Component.text(label.take(256))).action(DialogAction.customClick(
                { _, audience -> if (audience === player) action() },
                ClickCallback.Options.builder().uses(1).lifetime(ClickCallback.DEFAULT_LIFETIME).build())).build()
        }
        player.showDialog(Dialog.create { factory -> factory.empty()
            .base(DialogBase.builder(Component.text(title)).canCloseWithEscape(true).build())
            .type(DialogType.multiAction(buttons, null, 1)) })
    }
}
