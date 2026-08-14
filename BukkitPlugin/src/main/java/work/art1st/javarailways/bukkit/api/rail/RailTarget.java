package work.art1st.javarailways.bukkit.api.rail;

import work.art1st.javarailways.core.api.physics.rail.Rail;

import java.util.Objects;

/** A rail selected by a ray, including the closest point on its immutable curve. */
public record RailTarget(Rail rail, double arcLength, double distanceToRay) {
    public RailTarget {
        Objects.requireNonNull(rail, "rail");
        if (!Double.isFinite(arcLength) || arcLength < 0.0) {
            throw new IllegalArgumentException("arcLength must be finite and non-negative");
        }
        if (!Double.isFinite(distanceToRay) || distanceToRay < 0.0) {
            throw new IllegalArgumentException("distanceToRay must be finite and non-negative");
        }
    }
}
