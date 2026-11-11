package work.art1st.javarailways.bukkit.api.model.asset;

/** Selects the single import namespace used by one model-asset compilation. */
public enum ModelAssetImportMode {
    /** Imports resolve standalone shared-part YAML sources. Models are not import targets. */
    SHARED_PARTS,
    /** Imports resolve other model YAML sources and contribute only their parts tree. */
    MODELS
}
