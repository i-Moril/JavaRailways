package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable

/**
 * Optional train behavior script loaded from behavior.js in a train package.
 */
@Serializable
data class TrainBehaviorScript(
    val fileName: String,
    val source: String
)
