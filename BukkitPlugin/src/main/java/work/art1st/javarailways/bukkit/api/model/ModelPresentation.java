package work.art1st.javarailways.bukkit.api.model;

import java.util.Map;
import java.util.Objects;

/** Complete immutable display state. Revision is local to one handle, not a physics or schema version. */
public record ModelPresentation(long revision, ModelPose transform, Map<String, String> animationScenes) {
    public ModelPresentation {
        if (revision < 0) throw new IllegalArgumentException("Model presentation revision must be nonnegative");
        Objects.requireNonNull(transform, "transform");
        animationScenes = Map.copyOf(animationScenes);
        for (var entry : animationScenes.entrySet()) {
            if (entry.getKey().isBlank() || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("Animation and scene names must not be blank");
            }
        }
    }
}
