package work.art1st.javarailways.bukkit.api.model.asset;

import work.art1st.javarailways.bukkit.api.model.ModelPose;
import java.util.Objects;

/** Host-neutral placement metadata captured from one named YAML preset. */
public record ModelAssetPlacementPreset(ModelPose localPose, boolean followReferenceTilt) {
    public ModelAssetPlacementPreset {
        Objects.requireNonNull(localPose, "localPose");
    }
}
