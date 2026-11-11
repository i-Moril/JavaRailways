package work.art1st.javarailways.bukkit.api.model.asset;

import work.art1st.javarailways.bukkit.api.model.ModelDefinition;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable ordinary model plus generic named scene selections and placement metadata. */
public record CompiledModelAsset(
    ModelDefinition definition,
    Map<String, String> defaultScenes,
    Map<String, Map<String, String>> variants,
    ModelAssetPlacement placement
) {
    public CompiledModelAsset {
        Objects.requireNonNull(definition, "definition");
        defaultScenes = immutableCopy(defaultScenes);
        var copiedVariants = new LinkedHashMap<String, Map<String, String>>();
        variants.forEach((name, selection) -> copiedVariants.put(name, immutableCopy(selection)));
        variants = Collections.unmodifiableMap(copiedVariants);
        Objects.requireNonNull(placement, "placement");
    }

    private static <K, V> Map<K, V> immutableCopy(Map<K, V> values) {
        Objects.requireNonNull(values, "values");
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, "map key");
            Objects.requireNonNull(value, "map value");
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
