package work.art1st.mineRails.bukkit.config.model

/**
 * A train definition loaded from a train YAML file.
 *
 * A train is a composition of cars in order.
 * Each entry in [composition] is a qualified car name e.g. "s100/head".
 *
 * @param key the lookup key (filename without extension, e.g. "s100_4car")
 * @param name human-readable display name
 * @param composition ordered list of car model names
 */
data class TrainDefinition(
    val key: String,
    val name: String = key,
    val composition: List<String> = emptyList()
)
