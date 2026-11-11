package work.art1st.javarailways.bukkit.api.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Pre-expanded immutable nodes. Only groups have children; only item leaves have scale/appearance. */
public sealed interface ModelPart permits ModelPart.Group, ModelPart.Item {
    ModelPose pose();
    List<ModelAnimation> animations();

    record Group(ModelPose pose, List<ModelPart> children, List<ModelAnimation> animations) implements ModelPart {
        public Group {
            Objects.requireNonNull(pose, "pose");
            children = List.copyOf(children);
            animations = copyAnimations(animations);
            for (ModelAnimation animation : animations) {
                for (ModelAnimation.Frame frame : animation.frames()) {
                    if (!ModelScale.UNIT.equals(frame.scale()) || frame.appearance() != null) {
                        throw new IllegalArgumentException("Group animation '" + animation.name() +
                            "' may only change pose, not item scale or appearance");
                    }
                }
            }
        }
    }

    record Item(ModelPose pose, ModelScale scale, ItemAppearance appearance,
                List<ModelAnimation> animations) implements ModelPart {
        public Item {
            Objects.requireNonNull(pose, "pose");
            Objects.requireNonNull(scale, "scale");
            Objects.requireNonNull(appearance, "appearance");
            animations = copyAnimations(animations);
        }
    }

    private static List<ModelAnimation> copyAnimations(List<ModelAnimation> animations) {
        var copy = List.copyOf(animations);
        var names = new HashSet<String>();
        for (ModelAnimation animation : copy) {
            if (!names.add(animation.name())) {
                throw new IllegalArgumentException("Part repeats animation '" + animation.name() + "'");
            }
        }
        return copy;
    }
}
