package work.art1st.javarailways.bukkit.api.model;

/** Authored root-local selection volume, in metres; independent of part animation and scale. */
public record ModelSelectionBox(double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ) {
    public ModelSelectionBox {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ) ||
            !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ) ||
            minX >= maxX || minY >= maxY || minZ >= maxZ) {
            throw new IllegalArgumentException("Model selection box must have finite, strictly ordered bounds");
        }
    }
}
