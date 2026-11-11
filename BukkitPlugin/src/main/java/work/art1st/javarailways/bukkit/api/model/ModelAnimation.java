package work.art1st.javarailways.bukkit.api.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Named frames at absolute seconds. Scene selection holds the exact marked frame without playback. */
public record ModelAnimation(String name, List<Frame> frames) {
    public ModelAnimation {
        requireName(name, "animation name");
        frames = List.copyOf(frames);
        if (frames.isEmpty() || frames.getFirst().atSeconds() != 0.0) {
            throw new IllegalArgumentException("Animation '" + name + "' must have a first frame at zero seconds");
        }
        double previous = -1.0;
        var scenes = new HashSet<String>();
        for (Frame frame : frames) {
            if (frame.atSeconds() <= previous) {
                throw new IllegalArgumentException("Animation '" + name + "' frame times must strictly increase");
            }
            previous = frame.atSeconds();
            if (frame.scene() != null && !scenes.add(frame.scene())) {
                throw new IllegalArgumentException("Animation '" + name + "' repeats scene '" + frame.scene() + "'");
            }
        }
    }

    static void requireName(String name, String field) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(field + " must be nonblank");
        }
    }

    /**
     * Local overlay: add pose offset, postmultiply pose rotation and multiply item scale.
     * Null appearance uses the item's baseline, never the preceding frame's appearance.
     */
    public record Frame(double atSeconds, String scene, ModelPose pose, ModelScale scale, ItemAppearance appearance) {
        public Frame {
            if (!Double.isFinite(atSeconds) || atSeconds < 0) {
                throw new IllegalArgumentException("Frame time must be finite and non-negative seconds");
            }
            if (scene != null) requireName(scene, "scene name");
            Objects.requireNonNull(pose, "pose");
            Objects.requireNonNull(scale, "scale");
        }
    }
}
