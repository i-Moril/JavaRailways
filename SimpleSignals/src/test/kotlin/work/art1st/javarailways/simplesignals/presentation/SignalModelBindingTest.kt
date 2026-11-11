package work.art1st.javarailways.simplesignals.presentation

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.model.ModelDefinition
import work.art1st.javarailways.bukkit.api.model.ModelHandle
import work.art1st.javarailways.bukkit.api.model.ModelPresentation
import work.art1st.javarailways.bukkit.api.model.ModelPresentationService
import work.art1st.javarailways.bukkit.api.model.ModelPick
import work.art1st.javarailways.bukkit.api.model.ModelRay
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Shared fake for manager-level presentation lifecycle tests. */
internal class SignalModelBindingTest {
    internal class RecordingModels : ModelPresentationService {
        class Handle(val id: String) : ModelHandle {
            val submissions = mutableListOf<ModelPresentation>()
            var closed = false
            var failSubmit = false
            var closeCalls = 0
            var beforeSubmit: () -> Unit = {}
            override fun submit(presentation: ModelPresentation) {
                if (closed) return
                if (failSubmit) error("submit failed")
                beforeSubmit()
                submissions += presentation
            }
            override fun close() { closeCalls++; closed = true }
        }
        val handles = mutableListOf<Handle>()
        var failCreate = -1
        var beforeCreate: () -> Unit = {}
        var cleanup = CompletableFuture<Void>()
        var closeOwnerCalls = 0
        override fun create(owner: Plugin, worldId: UUID, objectId: String, definition: ModelDefinition): ModelHandle {
            check(handles.none { it.id == objectId && !it.closed }) { "duplicate active model $objectId" }
            if (handles.size + 1 == failCreate) error("create failed")
            beforeCreate()
            return Handle(objectId).also(handles::add)
        }
        fun byId(id: String) = handles.last { it.id == id }
        override fun createEditorHighlight(owner: Plugin, viewer: org.bukkit.entity.Player, sessionId: String):
            work.art1st.javarailways.bukkit.api.model.EditorModelHighlightHandle =
            error("Editor highlighting is not used by model-binding tests")
        override fun pick(owner: Plugin, worldId: UUID, ray: ModelRay): Optional<ModelPick> = Optional.empty()
        override fun closeOwner(owner: Plugin): CompletionStage<Void> {
            closeOwnerCalls++
            handles.filterNot { it.closed }.forEach(Handle::close)
            return cleanup
        }
    }
}
