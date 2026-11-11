package work.art1st.javarailways.bukkit.api.model;

/** Captured world-space eye ray. The caller limits distance for block occlusion before querying. */
public record ModelRay(double originX, double originY, double originZ,
                       double directionX, double directionY, double directionZ,
                       double maxDistance) {
    public ModelRay {
        if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ) ||
            !Double.isFinite(directionX) || !Double.isFinite(directionY) || !Double.isFinite(directionZ) ||
            !Double.isFinite(maxDistance) || maxDistance <= 0) {
            throw new IllegalArgumentException("Model ray components must be finite and distance positive");
        }
        double lengthSquared = directionX * directionX + directionY * directionY + directionZ * directionZ;
        if (Math.abs(lengthSquared - 1.0) > ModelPose.UNIT_LENGTH_TOLERANCE) {
            throw new IllegalArgumentException("Model ray direction must be a unit vector");
        }
    }
}
