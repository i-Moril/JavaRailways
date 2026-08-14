package work.art1st.javarailways.bukkit.api.operation;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** One completed player operation that can participate in JavaRailways' global history. */
public interface UndoableOperation {
    Component description();

    CompletionStage<OperationResult> undo(Player player);

    default CompletionStage<OperationResult> redo(Player player) {
        return CompletableFuture.completedFuture(
            OperationResult.unsupported(Component.text("该操作不支持重做"))
        );
    }
}
