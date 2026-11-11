package work.art1st.javarailways.bukkit.ui.dialog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.TextDialogInput
import work.art1st.javarailways.bukkit.api.wand.config.*
import work.art1st.javarailways.bukkit.ui.config.ConfigUIRegistry
import work.art1st.javarailways.bukkit.util.PlatformDispatcher
import work.art1st.javarailways.bukkit.render.model.ModelRuntimeTestSupport
import java.lang.reflect.Proxy
import java.util.UUID

class DialogRendererNumberTextTest {
    @Test
    fun `real renderer inputs use the authoritative filtered list once`() {
        ModelRuntimeTestSupport.bootstrap()
        val (renderer, built, session) = filteredFixture()
        @Suppress("UNCHECKED_CAST")
        session.setValue(built.effectiveItems.single().key as ConfigKey<Double>, 7.0)
        val method = DialogRenderer::class.java.getDeclaredMethod("buildInputs", BuiltGroup::class.java,
            Function1::class.java).also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val inputs = method.invoke(renderer, built, { text: String -> text }) as List<DialogInput>
        assertEquals(listOf("visible"), inputs.map { it.key() })
        assertEquals("1", (inputs.single() as TextDialogInput).initial()) // Built values, not later session edits.
    }

    @Test
    fun `real renderer capture never validates raw hidden subgroup inputs or writes duplicates`() {
        val (renderer, built, session) = filteredFixture()
        val reads = arrayListOf<String>()
        val view = Proxy.newProxyInstance(DialogResponseView::class.java.classLoader,
            arrayOf(DialogResponseView::class.java)) { _, method, args ->
            check(method.name == "getText")
            val key = args!![0] as String
            reads += key
            if (key == "visible") "12.5" else "invalid hidden number"
        } as DialogResponseView
        val captured = captureResponse(renderer, view, built)
        val method = DialogRenderer::class.java.getDeclaredMethod("captureInputs", captured.javaClass,
            BuiltGroup::class.java, PlayerConfigSession::class.java, Function1::class.java,
            Set::class.java).also { it.isAccessible = true }
        assertFalse(method.invoke(renderer, captured, built, session, { text: String -> text }, emptySet<String>()) as Boolean)
        assertEquals(listOf("visible"), reads)
        assertEquals(setOf("visible"), session.getDirtyKeyIds())
        @Suppress("UNCHECKED_CAST")
        assertEquals(12.5, session.getValue(built.effectiveItems.single().key as ConfigKey<Double>))
    }

    @Test
    fun `commit feedback distinguishes rejected writes from failed notifications`() {
        val messages = mutableListOf<net.kyori.adventure.text.Component>()
        val player = Proxy.newProxyInstance(org.bukkit.entity.Player::class.java.classLoader,
            arrayOf(org.bukkit.entity.Player::class.java)) { _, method, args ->
            check(method.name == "sendMessage")
            messages += args!!.single() as net.kyori.adventure.text.Component
            null
        } as org.bukkit.entity.Player
        val renderer = renderer(ConfigUIRegistry())
        val method = DialogRenderer::class.java.getDeclaredMethod("reportCommitFailures",
            org.bukkit.entity.Player::class.java, ConfigCommitResult::class.java,
            Function1::class.java).also { it.isAccessible = true }
        val result = ConfigCommitResult(setOf("written"),
            listOf(ConfigCommitFailure("rejected", "invalid value")),
            listOf(ConfigCommitFailure("written", "listener failed")))
        val resolver: (String) -> String = { "$it: %s" }

        assertFalse(method.invoke(renderer, player, result, resolver) as Boolean)
        assertEquals(listOf(
            net.kyori.adventure.text.Component.text(
                "dialog.commit_failed: rejected: invalid value", net.kyori.adventure.text.format.NamedTextColor.RED),
            net.kyori.adventure.text.Component.text(
                "dialog.commit_notification_failed: written: listener failed", net.kyori.adventure.text.format.NamedTextColor.RED)
        ), messages)
    }

    @Test
    fun `expired dialog confirmation never validates captured input mutates session or invokes save`() {
        val (renderer, built, session) = filteredFixture()
        var responseAvailable = true
        val view = Proxy.newProxyInstance(DialogResponseView::class.java.classLoader,
            arrayOf(DialogResponseView::class.java)) { _, method, _ ->
            check(responseAvailable) { "Paper response must not escape primitive capture" }
            check(method.name == "getText")
            "invalid captured number"
        } as DialogResponseView
        val captured = captureResponse(renderer, view, built)
        responseAvailable = false
        val player = Proxy.newProxyInstance(org.bukkit.entity.Player::class.java.classLoader,
            arrayOf(org.bukkit.entity.Player::class.java)) { _, _, _ -> error("Stale player must not be used")
        } as org.bukkit.entity.Player
        val method = DialogRenderer::class.java.getDeclaredMethod("onConfirm", captured.javaClass,
            BuiltGroup::class.java, PlayerConfigSession::class.java, org.bukkit.entity.Player::class.java,
            Function1::class.java, List::class.java, Function1::class.java, Function1::class.java)
            .also { it.isAccessible = true }
        var saved = false
        method.invoke(renderer, captured, built, session, player, { text: String -> text },
            emptyList<net.kyori.adventure.text.Component>(), { _: org.bukkit.entity.Player -> false },
            { _: org.bukkit.entity.Player -> saved = true })
        assertFalse(saved)
        assertTrue(session.getDirtyKeyIds().isEmpty())
        @Suppress("UNCHECKED_CAST")
        assertEquals(1.0, session.getValue(built.effectiveItems.single().key as ConfigKey<Double>))
    }

    private fun renderer(registry: ConfigUIRegistry): DialogRenderer {
        val dispatcher = Proxy.newProxyInstance(PlatformDispatcher::class.java.classLoader,
            arrayOf(PlatformDispatcher::class.java)) { _, method, _ ->
            error("These synchronous renderer checks must not dispatch: ${method.name}")
        } as PlatformDispatcher
        return DialogRenderer(registry, dispatcher)
    }

    private fun captureResponse(renderer: DialogRenderer, view: DialogResponseView, built: BuiltGroup): Any =
        DialogRenderer::class.java.getDeclaredMethod("captureResponse", DialogResponseView::class.java,
            BuiltGroup::class.java).also { it.isAccessible = true }.invoke(renderer, view, built)

    private fun filteredFixture(): Triple<DialogRenderer, BuiltGroup, PlayerConfigSession> {
        val registry = ConfigUIRegistry()
        val visible = WandConfigItemImpl(ConfigKey("visible", "visible", defaultValue = 1.0, valueType = ConfigValueType.NUMBER_TEXT))
        val hidden = WandConfigItemImpl(ConfigKey("hidden", "hidden", defaultValue = 2.0, valueType = ConfigValueType.NUMBER_TEXT))
        val sub = ConfigGroup("sub", "sub", items = mutableListOf(visible, hidden))
        val root = ConfigGroup("root", "root", subGroups = mutableListOf(sub))
        registry.register(root)
        val player = UUID.randomUUID()
        val snapshot = registry.buildGroupForPlayer("root", player).snapshot
        val built = BuiltGroup(root, listOf(visible), listOf(sub), setOf("hidden"), snapshot)
        return Triple(renderer(registry), built, registry.getPlayerSession(player))
    }

    @Test
    fun `tiny finite values use a bounded round-trip representation`() {
        val values = listOf(1.0e-40, -1.0e-200, Double.MIN_VALUE)

        values.forEach { value ->
            val rendered = formatDialogNumberText(value)

            assertTrue(rendered.length <= DIALOG_NUMBER_TEXT_MAX_LENGTH, rendered)
            assertEquals(value, rendered.toDouble())
        }
    }

    @Test
    fun `ordinary values keep concise plain decimal formatting`() {
        assertEquals("80", formatDialogNumberText(80.0))
        assertEquals("0.125", formatDialogNumberText(0.125))
    }
}
