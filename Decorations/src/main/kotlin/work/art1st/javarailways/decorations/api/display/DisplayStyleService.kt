package work.art1st.javarailways.decorations.api.display

import java.util.Collections
import java.util.UUID

/** Runtime-only, fixed-unit/category style controls. All methods are thread safe. */
interface DisplayStyleService {
    fun unitIds(worldId: UUID): Set<String>
    fun categories(worldId: UUID, unitId: String): Set<String>
    fun inspect(worldId: UUID, unitId: String, category: String): DisplayStyleSelection
    fun set(worldId: UUID, unitId: String, category: String, templateId: String): DisplayStyleSelection
    fun reset(worldId: UUID, unitId: String, category: String): DisplayStyleSelection
}

/** templateId is the override, or null when each screen resolves its own defaults. */
class DisplayStyleSelection(
    val worldId: UUID,
    val unitId: String,
    val category: String,
    val templateId: String?,
    availableTemplates: Set<String>,
    val screenCount: Int,
) {
    val availableTemplates: Set<String> = Collections.unmodifiableSet(availableTemplates.toSortedSet())
}
