package work.art1st.javarailways.simplesignals.authoring

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** Item-owned preference and exact continuation identity, not a player-global setting. */
internal data class SectionWandState(val itemId: UUID, val stateRevision: Long,
    val tool: SectionWandTool, val retainDevices: Boolean) {
    init { require(stateRevision >= 0) { "section wand revision must be non-negative" } }
    fun changed(tool: SectionWandTool = this.tool, retainDevices: Boolean = this.retainDevices) =
        copy(stateRevision = Math.addExact(stateRevision, 1), tool = tool, retainDevices = retainDevices)
    companion object {
        fun create(tool: SectionWandTool) = SectionWandState(UUID.randomUUID(), 0, tool, true)
    }
}

@Serializable
private data class SavedSectionWandState(val itemId: String, val stateRevision: Long,
    val tool: String, val retainDevices: Boolean)

internal object SectionWandStateCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun encode(state: SectionWandState): String = json.encodeToString(SavedSectionWandState.serializer(),
        SavedSectionWandState(state.itemId.toString(), state.stateRevision, state.tool.key, state.retainDevices))
    fun decode(text: String): SectionWandState {
        val saved = json.decodeFromString(SavedSectionWandState.serializer(), text)
        val id = UUID.fromString(saved.itemId)
        require(id.toString() == saved.itemId) { "section wand item ID must be canonical" }
        val tool = requireNotNull(SectionWandTool.entries.singleOrNull { it.key == saved.tool }) { "unknown section wand mode" }
        return SectionWandState(id, saved.stateRevision, tool, saved.retainDevices)
    }
}
