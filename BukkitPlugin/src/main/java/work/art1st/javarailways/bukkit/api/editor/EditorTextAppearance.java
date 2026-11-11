package work.art1st.javarailways.bukkit.api.editor;

import java.util.Objects;
import work.art1st.javarailways.bukkit.api.model.ModelPose;

/** Immutable local transform; scales act in the text plane, not on the label's world position. */
public record EditorTextAppearance(ModelPose transform, double scaleX, double scaleY, Mode mode) {
    public enum Mode { READABLE_TEXT, FIXED_GLYPH }

    public static final EditorTextAppearance READABLE =
            new EditorTextAppearance(ModelPose.IDENTITY, 1, 1, Mode.READABLE_TEXT);

    public EditorTextAppearance {
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(mode, "mode");
        if (!Double.isFinite(scaleX) || !Double.isFinite(scaleY) || scaleX <= 0 || scaleY <= 0)
            throw new IllegalArgumentException("Editor text scale must be finite and positive");
    }
}
