package work.art1st.javarailways.bukkit.api.internal

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.operation.OperationResult
import work.art1st.javarailways.bukkit.api.operation.UndoableOperation
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.logging.Logger

class DefaultPlayerOperationServiceTest {
    @Test
    fun `uses one chronological history across owners`() {
        val service = DefaultPlayerOperationService(Logger.getAnonymousLogger())
        val player = player(UUID.randomUUID())
        val firstOwner = plugin("first")
        val secondOwner = plugin("second")
        val calls = mutableListOf<String>()

        service.record(player.uniqueId, firstOwner, operation("first", calls))
        service.record(player.uniqueId, secondOwner, operation("second", calls))

        assertEquals(OperationResult.Status.SUCCESS, service.undo(player).toCompletableFuture().join().status())
        assertEquals(listOf("undo:second"), calls)
        assertEquals(OperationResult.Status.SUCCESS, service.redo(player).toCompletableFuture().join().status())
        assertEquals(listOf("undo:second", "redo:second"), calls)
    }

    @Test
    fun `failed undo remains at the top of history`() {
        val service = DefaultPlayerOperationService(Logger.getAnonymousLogger())
        val player = player(UUID.randomUUID())
        val owner = plugin("owner")
        var failures = 0
        service.record(player.uniqueId, owner, operation("older", mutableListOf()))
        service.record(player.uniqueId, owner, object : UndoableOperation {
            override fun description(): Component = Component.text("failure")

            override fun undo(player: Player): CompletionStage<OperationResult> {
                failures++
                return CompletableFuture.completedFuture(
                    OperationResult.failed(Component.text("failed"))
                )
            }
        })

        assertEquals(OperationResult.Status.FAILED, service.undo(player).toCompletableFuture().join().status())
        assertEquals(OperationResult.Status.FAILED, service.undo(player).toCompletableFuture().join().status())
        assertEquals(2, failures)
    }

    @Test
    fun `records completed forward actions behind an in-flight undo`() {
        val service = DefaultPlayerOperationService(Logger.getAnonymousLogger())
        val player = player(UUID.randomUUID())
        val owner = plugin("owner")
        val undoCompletion = CompletableFuture<OperationResult>()
        val calls = mutableListOf<String>()
        service.record(player.uniqueId, owner, object : UndoableOperation {
            override fun description(): Component = Component.text("async")
            override fun undo(player: Player): CompletionStage<OperationResult> = undoCompletion
        })

        val undoRequest = service.undo(player)
        service.record(player.uniqueId, owner, operation("newer", calls))
        undoCompletion.complete(OperationResult.success(Component.text("done")))

        assertEquals(OperationResult.Status.SUCCESS, undoRequest.toCompletableFuture().join().status())
        assertEquals(OperationResult.Status.EMPTY, service.redo(player).toCompletableFuture().join().status())
        assertEquals(OperationResult.Status.SUCCESS, service.undo(player).toCompletableFuture().join().status())
        assertEquals(listOf("undo:newer"), calls)
    }

    @Test
    fun `discard and owner removal delete retained operations`() {
        val service = DefaultPlayerOperationService(Logger.getAnonymousLogger())
        val player = player(UUID.randomUUID())
        val firstOwner = plugin("first")
        val secondOwner = plugin("second")
        val firstSequence = service.record(player.uniqueId, firstOwner, operation("first", mutableListOf()))
        service.record(player.uniqueId, secondOwner, operation("second", mutableListOf()))

        assertTrue(service.discard(player.uniqueId, firstOwner, firstSequence))
        assertFalse(service.discard(player.uniqueId, firstOwner, firstSequence))
        service.unregisterOwner(secondOwner)

        assertEquals(OperationResult.Status.EMPTY, service.undo(player).toCompletableFuture().join().status())
    }

    private fun operation(name: String, calls: MutableList<String>): UndoableOperation =
        object : UndoableOperation {
            override fun description(): Component = Component.text(name)

            override fun undo(player: Player): CompletionStage<OperationResult> {
                calls += "undo:$name"
                return CompletableFuture.completedFuture(OperationResult.success(Component.text("undone")))
            }

            override fun redo(player: Player): CompletionStage<OperationResult> {
                calls += "redo:$name"
                return CompletableFuture.completedFuture(OperationResult.success(Component.text("redone")))
            }
        }

    private fun player(id: UUID): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "getUniqueId" -> id
            "toString" -> "Player($id)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> defaultValue(method.returnType)
        }
    } as Player

    private fun plugin(name: String): Plugin = Proxy.newProxyInstance(
        Plugin::class.java.classLoader,
        arrayOf(Plugin::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "getName", "toString" -> name
            "isEnabled" -> true
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> defaultValue(method.returnType)
        }
    } as Plugin

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
