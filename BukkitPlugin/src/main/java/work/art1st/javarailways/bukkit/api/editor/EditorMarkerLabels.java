package work.art1st.javarailways.bukkit.api.editor;

import java.util.Objects;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import work.art1st.javarailways.bukkit.api.model.ModelPose;

/** Pure marker preparation, shared by host and add-on tools. No input vectors are retained or modified. */
public final class EditorMarkerLabels {
    public static final Key FONT = Key.key("javarailways", "editor_markers");
    public static final double NORMAL_LIFT_METERS = 0.08;
    // The pack uses 8 font pixels per glyph side; one TextDisplay pixel occupies 0.025 metres.
    private static final double GLYPH_SIDE_METERS = 8 * 0.025;

    private EditorMarkerLabels() {}

    /**
     * Prepare a rail-plane glyph with local +Y along tangent, +Z along normal and +X across the rail.
     * Width is across-rail; length is along-rail. Reversing tangent reverses the arrow.
     * Tangent/normal must be finite, nonzero and not parallel; Gram-Schmidt removes normal drift.
     */
    public static EditorTextLabel at(String id, EditorMarkerGlyph glyph, Vector3dc position,
                                     Vector3dc tangent, Vector3dc normal, double widthMeters,
                                     double lengthMeters, TextColor color) {
        Objects.requireNonNull(glyph, "glyph");
        Objects.requireNonNull(color, "color");
        Vector3d along = finiteCopy(tangent, "tangent");
        Vector3d up = finiteCopy(normal, "normal");
        Vector3d point = finiteCopy(position, "position");
        if (!(along.lengthSquared() > 0) || !(up.lengthSquared() > 0))
            throw new IllegalArgumentException("Editor marker tangent and normal must be nonzero");
        along.normalize();
        up.sub(new Vector3d(along).mul(up.dot(along)));
        if (!(up.lengthSquared() > 1e-12))
            throw new IllegalArgumentException("Editor marker tangent and normal must not be parallel");
        up.normalize();
        Vector3d across = new Vector3d(along).cross(up).normalize();
        Quaterniond rotation = new Quaterniond().setFromNormalized(new Matrix3d(
                across.x, across.y, across.z, along.x, along.y, along.z, up.x, up.y, up.z));
        // 26.1.2 TextDisplay applies rotateY(pi), scale(-.025), then translate(1 - 9/2, -9).
        // Our 8x8 bitmap (ascent 7) has centre (4,4), so its local centre is (+.5,+5) pixels.
        Vector3d offset = new Vector3d(across).mul(-widthMeters / 16)
                .add(new Vector3d(along).mul(-5 * lengthMeters / 8));
        ModelPose local = new ModelPose(offset.x, offset.y, offset.z,
                rotation.x, rotation.y, rotation.z, rotation.w);
        EditorTextAppearance appearance = new EditorTextAppearance(local,
                widthMeters / GLYPH_SIDE_METERS, lengthMeters / GLYPH_SIDE_METERS,
                EditorTextAppearance.Mode.FIXED_GLYPH);
        point.add(new Vector3d(up).mul(NORMAL_LIFT_METERS));
        return new EditorTextLabel(id, point.x, point.y, point.z,
                Component.text(glyph.text(), color).font(FONT), appearance);
    }

    private static Vector3d finiteCopy(Vector3dc value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y()) || !Double.isFinite(value.z()))
            throw new IllegalArgumentException("Editor marker " + name + " must be finite");
        return new Vector3d(value);
    }
}
