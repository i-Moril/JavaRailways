package work.art1st.javarailways.bukkit.api.operation;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Global chronological undo/redo history shared by JavaRailways and its add-ons.
 *
 * <p>Operations must be recorded only after their forward change has completed successfully. Operations
 * recorded while an undo or redo is in progress are queued behind that request and invalidate redo history.</p>
 */
public interface PlayerOperationService {
    long record(UUID playerId, Plugin owner, UndoableOperation operation);

    CompletionStage<OperationResult> undo(Player player);

    CompletionStage<OperationResult> redo(Player player);

    /** Discards one retained operation when a later action makes it invalid. */
    boolean discard(UUID playerId, Plugin owner, long sequence);

    /** Removes all retained operations implemented by {@code owner}. */
    void unregisterOwner(Plugin owner);
}
