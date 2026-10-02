package work.art1st.javarailways.bukkit.api.model;

/** Metre position/offset and unit quaternion (x,y,z,w), with no scale. */
public record ModelPose(double x, double y, double z,
                        double rotationX, double rotationY, double rotationZ, double rotationW) {
    public static final ModelPose IDENTITY = new ModelPose(0, 0, 0, 0, 0, 0, 1);

    /** Accepted absolute error in quaternion squared length; inputs are never normalized silently. */
    public static final double UNIT_LENGTH_TOLERANCE = 1e-9;

    public ModelPose {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) ||
            !Double.isFinite(rotationX) || !Double.isFinite(rotationY) ||
            !Double.isFinite(rotationZ) || !Double.isFinite(rotationW)) {
            throw new IllegalArgumentException("Model pose components must be finite");
        }
        double lengthSquared = rotationX * rotationX + rotationY * rotationY +
            rotationZ * rotationZ + rotationW * rotationW;
        if (Math.abs(lengthSquared - 1.0) > UNIT_LENGTH_TOLERANCE) {
            throw new IllegalArgumentException("Model rotation must be a unit quaternion");
        }
    }
}
