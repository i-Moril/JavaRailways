package work.art1st.javarailways.bukkit.api.model.asset;

import java.util.Map;
import java.util.Objects;

/** Complete, already-captured candidate. A compile either returns every model or throws. */
public record ModelAssetYamlCompilation(
    String diagnosticNamespace,
    Map<String, ModelAssetYamlSource> models,
    Map<String, ModelAssetYamlSource> sharedParts,
    ModelAssetImportMode importMode
) {
    public ModelAssetYamlCompilation {
        Objects.requireNonNull(diagnosticNamespace, "diagnosticNamespace");
        if (diagnosticNamespace.isBlank()) throw new IllegalArgumentException("diagnosticNamespace must not be blank");
        models = Map.copyOf(models);
        sharedParts = Map.copyOf(sharedParts);
        importMode = Objects.requireNonNull(importMode, "importMode");
        if (importMode == ModelAssetImportMode.MODELS && !sharedParts.isEmpty()) {
            throw new IllegalArgumentException("model-import compilation cannot also define shared parts");
        }
    }
}
