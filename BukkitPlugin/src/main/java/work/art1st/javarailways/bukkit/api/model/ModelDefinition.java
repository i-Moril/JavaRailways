package work.art1st.javarailways.bukkit.api.model;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reusable immutable ordinary model tree; no package loader, registry, Car or mutable rendering values. */
public record ModelDefinition(List<ModelPart> parts) {
    public ModelDefinition {
        parts = List.copyOf(parts);
        var sceneSets = new HashMap<String, Set<String>>();
        var pending = new ArrayDeque<ModelPart>(parts);
        while (!pending.isEmpty()) {
            var part = pending.removeFirst();
            for (ModelAnimation animation : part.animations()) {
                var scenes = new HashSet<String>();
                for (ModelAnimation.Frame frame : animation.frames()) {
                    if (frame.scene() != null) scenes.add(frame.scene());
                }
                var previous = sceneSets.putIfAbsent(animation.name(), scenes);
                if (previous != null && !previous.equals(scenes)) {
                    throw new IllegalArgumentException("All parts declaring animation '" + animation.name() +
                        "' must declare the same exact scene names");
                }
            }
            if (part instanceof ModelPart.Group group) pending.addAll(group.children());
        }
    }
}
