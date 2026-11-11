package work.art1st.javarailways.bukkit.api.model;

/** Item-leaf scale or frame scale factor. Zero hides an axis; negative values are rejected. */
public record ModelScale(double x, double y, double z) {
    public static final ModelScale UNIT = new ModelScale(1, 1, 1);

    public ModelScale {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || x < 0 || y < 0 || z < 0) {
            throw new IllegalArgumentException("Model scale must be finite and non-negative");
        }
    }
}
