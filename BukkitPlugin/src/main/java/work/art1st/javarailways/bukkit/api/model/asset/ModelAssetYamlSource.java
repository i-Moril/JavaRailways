package work.art1st.javarailways.bukkit.api.model.asset;

import java.util.Objects;

/** Captured YAML text with a stable diagnostic name; the compiler performs no file I/O. */
public record ModelAssetYamlSource(String sourceName, String yaml) {
    public ModelAssetYamlSource {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(yaml, "yaml");
        if (sourceName.isBlank()) throw new IllegalArgumentException("sourceName must not be blank");
    }
}
