package work.art1st.javarailways.bukkit.ui.dialog

import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback
import org.bukkit.entity.Player
import work.art1st.javarailways.bukkit.util.PlatformDispatcher

/** Capture response values now; execute UI/state changes through the creating enable's owner gate. */
internal fun <T> playerDialogCallback(
    dispatcher: PlatformDispatcher,
    capture: (DialogResponseView) -> T,
    action: (T, Player) -> Unit
): DialogActionCallback = DialogActionCallback { response, audience ->
    if (audience is Player) {
        val inputs = capture(response)
        dispatcher.runForEntity(audience, action = { action(inputs, audience) })
    }
}
