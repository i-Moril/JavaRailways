package work.art1st.javarailways.bukkit.api.internal

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.operation.OperationResult
import work.art1st.javarailways.bukkit.api.operation.PlayerOperationService
import work.art1st.javarailways.bukkit.api.operation.UndoableOperation
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

internal class DefaultPlayerOperationService(
    private val logger: Logger,
    private val historyLimit: Int = 10
) : PlayerOperationService {
    private data class Entry(
        val sequence: Long,
        val owner: Plugin,
        val operation: UndoableOperation
    )

    private class PlayerHistory {
        val undo = ArrayDeque<Entry>()
        val redo = ArrayDeque<Entry>()
        val pending = ArrayDeque<Entry>()
        var inFlight: Entry? = null
    }

    private val sequence = AtomicLong()
    private val histories = ConcurrentHashMap<UUID, PlayerHistory>()

    init {
        require(historyLimit > 0) { "historyLimit must be positive" }
    }

    override fun record(playerId: UUID, owner: Plugin, operation: UndoableOperation): Long {
        check(owner.isEnabled) { "Cannot record an operation for disabled plugin ${owner.name}" }
        val entry = Entry(sequence.incrementAndGet(), owner, operation)
        val history = histories.computeIfAbsent(playerId) { PlayerHistory() }
        synchronized(history) {
            if (history.inFlight != null) {
                history.pending.addLast(entry)
            } else {
                history.undo.addLast(entry)
                trim(history.undo)
                history.redo.clear()
            }
        }
        return entry.sequence
    }

    override fun undo(player: Player): CompletionStage<OperationResult> = execute(player, undo = true)

    override fun redo(player: Player): CompletionStage<OperationResult> = execute(player, undo = false)

    override fun discard(playerId: UUID, owner: Plugin, sequence: Long): Boolean {
        val history = histories[playerId] ?: return false
        synchronized(history) {
            check(history.inFlight?.sequence != sequence) {
                "Cannot discard operation $sequence while it is in progress"
            }
            val removedUndo = history.undo.removeIf { it.sequence == sequence && it.owner === owner }
            val removedRedo = history.redo.removeIf { it.sequence == sequence && it.owner === owner }
            val removedPending = history.pending.removeIf { it.sequence == sequence && it.owner === owner }
            return removedUndo || removedRedo || removedPending
        }
    }

    override fun unregisterOwner(owner: Plugin) {
        histories.forEach { (_, history) ->
            synchronized(history) {
                history.undo.removeIf { it.owner === owner }
                history.redo.removeIf { it.owner === owner }
                history.pending.removeIf { it.owner === owner }
            }
        }
    }

    fun clear() {
        histories.clear()
    }

    private fun execute(player: Player, undo: Boolean): CompletionStage<OperationResult> {
        val history = histories[player.uniqueId]
            ?: return CompletableFuture.completedFuture(emptyResult(undo))
        val entry: Entry
        synchronized(history) {
            if (history.inFlight != null) {
                return CompletableFuture.completedFuture(
                    OperationResult.busy(Component.text("已有撤销或重做操作正在执行", NamedTextColor.YELLOW))
                )
            }
            entry = (if (undo) history.undo else history.redo).peekLast()
                ?: return CompletableFuture.completedFuture(emptyResult(undo))
            history.inFlight = entry
        }

        val resultFuture = CompletableFuture<OperationResult>()
        val operationStage = try {
            if (undo) entry.operation.undo(player) else entry.operation.redo(player)
        } catch (failure: Throwable) {
            finishExceptionally(player.uniqueId, history, entry, failure, resultFuture)
            return resultFuture
        }

        if (operationStage == null) {
            finishExceptionally(
                player.uniqueId,
                history,
                entry,
                NullPointerException("Operation returned a null CompletionStage"),
                resultFuture
            )
            return resultFuture
        }

        operationStage.whenComplete { result, failure ->
            if (failure != null) {
                finishExceptionally(player.uniqueId, history, entry, failure, resultFuture)
                return@whenComplete
            }
            val safeResult = result ?: OperationResult.failed(
                Component.text("操作没有返回执行结果", NamedTextColor.RED)
            )
            synchronized(history) {
                if (safeResult.successful()) {
                    val source = if (undo) history.undo else history.redo
                    if (source.peekLast() === entry) {
                        source.removeLast()
                        if (undo) {
                            if (history.pending.isEmpty()) history.redo.addLast(entry)
                        } else {
                            history.undo.addLast(entry)
                        }
                    }
                }
                if (history.pending.isNotEmpty()) {
                    history.redo.clear()
                    history.undo.addAll(history.pending)
                    history.pending.clear()
                    trim(history.undo)
                }
                history.inFlight = null
            }
            resultFuture.complete(safeResult)
        }
        return resultFuture
    }

    private fun finishExceptionally(
        playerId: UUID,
        history: PlayerHistory,
        entry: Entry,
        failure: Throwable,
        resultFuture: CompletableFuture<OperationResult>
    ) {
        val description = runCatching { entry.operation.description() }
            .getOrElse { Component.text("unknown operation") }
        logger.log(
            Level.SEVERE,
            "Player operation ${entry.sequence} ($description) failed",
            failure
        )
        synchronized(history) {
            if (history.pending.isNotEmpty()) {
                history.redo.clear()
                history.undo.addAll(history.pending)
                history.pending.clear()
                trim(history.undo)
            }
            history.inFlight = null
        }
        resultFuture.complete(
            OperationResult.failed(Component.text("撤销或重做执行失败", NamedTextColor.RED))
        )
    }

    private fun trim(entries: ArrayDeque<Entry>) {
        while (entries.size > historyLimit) entries.removeFirst()
    }

    private fun emptyResult(undo: Boolean): OperationResult = OperationResult.empty(
        Component.text(if (undo) "没有可撤销的操作" else "没有可重做的操作", NamedTextColor.GRAY)
    )
}
