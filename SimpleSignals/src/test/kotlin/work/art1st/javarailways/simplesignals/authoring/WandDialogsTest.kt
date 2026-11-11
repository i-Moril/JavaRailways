package work.art1st.javarailways.simplesignals.authoring

import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.type.MultiActionType
import io.papermc.paper.registry.data.dialog.type.NoticeType
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class WandDialogsTest {
    @Test fun `empty choices preserve the supplied exit on a real notice type`() {
        val exit = ActionButton.builder(Component.text("Cancel creation")).build()
        val type = assertInstanceOf(NoticeType::class.java, WandDialogs.actionType(emptyList(), exit, 2))
        assertEquals(exit, type.action())
    }

    @Test fun `empty choices without an exit use the native notice close action`() {
        val type = assertInstanceOf(NoticeType::class.java, WandDialogs.actionType(emptyList(), null, 1))
        assertNotNull(type.action())
    }

    @Test fun `available choices retain their order exit and columns`() {
        val buttons = listOf("First", "Second").map { ActionButton.builder(Component.text(it)).build() }
        val exit = ActionButton.builder(Component.text("Back")).build()
        val type = assertInstanceOf(MultiActionType::class.java, WandDialogs.actionType(buttons, exit, 2))
        assertEquals(buttons, type.actions())
        assertEquals(exit, type.exitAction())
        assertEquals(2, type.columns())
    }
}
