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
import org.bukkit.entity.Player

/** Shared owner-thread choice/confirmation presentation; callbacks still validate their own intent. */
internal object WandDialogs {
    fun placement(player: Player, title: String, initial: PlacementWandInput,
        submitted: (PlacementWandInput?) -> Unit) {
        val inputs = initial.fields.map { (key, value) ->
            DialogInput.text(key, Component.text(if (key.startsWith("r")) "$key (degrees)" else "$key (metres)"))
                .initial(value).maxLength(64).build()
        } + if (initial.followRailTilt == null) emptyList() else listOf(
            DialogInput.bool("tilt", Component.text("Follow rail slope / cant")).initial(initial.followRailTilt).build())
        val submit = ActionButton.builder(Component.text("Review placement")).action(DialogAction.customClick(
            { view, audience -> if (audience === player) submitted(PlacementWandInput(
                initial.fields.keys.associateWith { view.getText(it).orEmpty() },
                if (initial.followRailTilt == null) null else view.getBoolean("tilt"))) },
            ClickCallback.Options.builder().uses(1).lifetime(ClickCallback.DEFAULT_LIFETIME).build())).build()
        val cancel = ActionButton.builder(Component.text("Cancel")).action(DialogAction.customClick(
            { _, audience -> if (audience === player) submitted(null) },
            ClickCallback.Options.builder().uses(1).lifetime(ClickCallback.DEFAULT_LIFETIME).build())).build()
        player.showDialog(Dialog.create { factory -> factory.empty()
            .base(DialogBase.builder(Component.text(title)).body(listOf(DialogBody.plainMessage(
                Component.text("Rotation: intrinsic XYZ degrees, applied in the model placement frame. " +
                    "Position offsets are not rotated by these angles."))))
                .inputs(inputs).canCloseWithEscape(true).build())
            .type(DialogType.multiAction(listOf(submit, cancel), null, 1)) })
    }

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
