package work.art1st.javarailways.bukkit.api.model.asset;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Optional placement defaults; a consumer decides what the reference path represents. */
public record ModelAssetPlacement(
    boolean followReferenceTilt,
    Map<String, ModelAssetPlacementPreset> presets
) {
    public ModelAssetPlacement {
        Objects.requireNonNull(presets, "presets");
        presets.forEach((name, preset) -> {
            Objects.requireNonNull(name, "preset name");
            Objects.requireNonNull(preset, "preset");
        });
        presets = Collections.unmodifiableMap(new LinkedHashMap<>(presets));
    }
}
