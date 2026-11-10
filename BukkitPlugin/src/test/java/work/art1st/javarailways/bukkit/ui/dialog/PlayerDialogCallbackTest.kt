package work.art1st.javarailways.bukkit.ui.dialog

import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.util.PaperPlatformDispatcher
import java.lang.reflect.Proxy

class PlayerDialogCallbackTest {
    private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        }
    )

    @Test
    fun `old callbacks stay fenced across enable and queued clicks are checked when executed`() {
        val tasks = ArrayDeque<Runnable>()
        val scheduler = proxy(EntityScheduler::class.java) { name, args ->
            check(name == "execute") { name }
            tasks.addLast(args[1] as Runnable)
            true
        }
        val player = proxy(Player::class.java) { name, _ ->
            check(name == "getScheduler") { name }
            scheduler
        }
        val plugin = proxy(Plugin::class.java) { name, _ -> error("Unexpected plugin access: $name") }
        val serverField = Bukkit::class.java.getDeclaredField("server").apply { isAccessible = true }
        val previousServer = serverField.get(null)
        serverField.set(null, proxy(Server::class.java) { name, _ ->
            check(name == "isOwnedByCurrentRegion") { name }
            false
        })
        try {
            var responseAvailable = true
            var responseText = "first"
            val response = proxy(DialogResponseView::class.java) { name, _ ->
                check(responseAvailable) { "Response read after callback returned" }
                check(name == "getText") { name }
                responseText
            }
            val applied = mutableListOf<String>()
            val oldDispatcher = PaperPlatformDispatcher(plugin)
            val oldCallback = playerDialogCallback(oldDispatcher, { it.getText("name")!! }) { input, _ ->
                applied.add(input)
            }
            oldCallback.accept(response, player)
            responseAvailable = false
            tasks.removeFirst().run()
            assertEquals(listOf("first"), applied)

            responseAvailable = true
            responseText = "queued old"
            oldCallback.accept(response, player)
            oldDispatcher.stopAndAwait()
            responseAvailable = false
            tasks.removeFirst().run()
            assertEquals(listOf("first"), applied, "A click queued before disable must be rejected at execution")

            val newDispatcher = PaperPlatformDispatcher(plugin)
            val newCallback = playerDialogCallback(newDispatcher, { it.getText("name")!! }) { input, _ ->
                applied.add(input)
            }
            responseAvailable = true
            responseText = "old after re-enable"
            oldCallback.accept(response, player)
            assertTrue(tasks.isEmpty(), "The old callback must keep its old dispatcher")
            responseText = "new"
            newCallback.accept(response, player)
            responseAvailable = false
            tasks.removeFirst().run()
            assertEquals(listOf("first", "new"), applied)
            newDispatcher.stopAndAwait()
        } finally {
            serverField.set(null, previousServer)
        }
    }
}
