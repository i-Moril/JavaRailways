package work.art1st.javarailways.bukkit.api.operation;

import net.kyori.adventure.text.Component;

import java.util.Objects;

/** Result of an undo or redo request. */
public record OperationResult(Status status, Component message) {
    public OperationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
    }

    public boolean successful() {
        return status == Status.SUCCESS;
    }

    public static OperationResult success(Component message) {
        return new OperationResult(Status.SUCCESS, message);
    }

    public static OperationResult empty(Component message) {
        return new OperationResult(Status.EMPTY, message);
    }

    public static OperationResult busy(Component message) {
        return new OperationResult(Status.BUSY, message);
    }

    public static OperationResult unsupported(Component message) {
        return new OperationResult(Status.UNSUPPORTED, message);
    }

    public static OperationResult failed(Component message) {
        return new OperationResult(Status.FAILED, message);
    }

    public enum Status {
        SUCCESS,
        EMPTY,
        BUSY,
        UNSUPPORTED,
        FAILED
    }
}
