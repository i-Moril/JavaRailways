package work.art1st.mineRails.bukkit.config.model

/**
 * Package type: TRAIN or RAIL.
 */
enum class PackageType {
    TRAIN,
    RAIL
}

/**
 * Manifest for a self-contained train or rail package.
 *
 * Each package is a directory under `trains/` or `rails/` containing
 * this manifest.yml at its root, alongside all required config files.
 */
data class PackageManifest(
    /** Package name, e.g. "s100" or "standard". */
    val name: String,
    /** Package type: TRAIN or RAIL. */
    val type: PackageType,
    /** Manifest format version. */
    val version: Int = 1,
    /** Human-readable display name. */
    val displayName: String? = null,
    /**
     * TRAIN only: ordered list of car names (simple names, local to this package).
     * e.g. ["head", "passenger", "passenger", "head"]
     */
    val composition: List<String>? = null,
    /**
     * RAIL only: path to the rail model config YAML file,
     * relative to the package root. e.g. "rail_model.yml"
     */
    val railConfigFile: String? = null
)
