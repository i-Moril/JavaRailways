package work.art1st.javarailways.bukkit.api.editor;

import java.util.Objects;
import net.kyori.adventure.text.Component;

/** Immutable text at an absolute position in the handle's captured world. IDs have no business meaning. */
public record EditorTextLabel(String id, double x, double y, double z, Component text,
                              EditorTextAppearance appearance) {
    /** Ordinary viewer-facing text retains its original style. */
    public EditorTextLabel(String id, double x, double y, double z, Component text) {
        this(id, x, y, z, text, EditorTextAppearance.READABLE);
    }

    public EditorTextLabel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(appearance, "appearance");
        if (id.isBlank()) throw new IllegalArgumentException("Editor text label ID must not be blank");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("Editor text coordinates must be finite");
    }
}
