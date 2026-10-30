package work.art1st.javarailways.bukkit.api.model;

import org.bukkit.plugin.Plugin;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Host-owned ordinary presentation; contains no signalling, authorization or model-package policy. */
public interface ModelPresentationService {
    /**
     * Allocate an operation object without creating entities. The first valid submit creates the model.
     * Identity is the exact plugin instance, loaded world instance and nonblank object ID.
     * An active duplicate is rejected. World unload or owner disable closes its handles.
     * Throws IllegalStateException if the host/world/owner is unavailable.
     */
    ModelHandle create(Plugin owner, UUID worldId, String objectId, ModelDefinition definition);

    /** Create on the viewer entity owner; captures exact viewer/world instances. The caller closes
     * when editing ends. Quit, world change/unload and owner shutdown also withdraw the outline.
     * Session IDs are nonblank and unique for the exact owner/viewer/world identity.
     */
    EditorModelHighlightHandle createEditorHighlight(Plugin owner, org.bukkit.entity.Player viewer, String sessionId);

    /**
     * Intersect authored selection boxes at the latest accepted root poses of this exact owner's
     * active models. Value-only and safe on workers; never queries blocks, entities or viewers.
     * Missing boxes/submissions do not hit. Nearest distance wins, then lexical object ID.
     * Throws IllegalStateException for an unavailable host, owner or unregistered world.
     */
    Optional<ModelPick> pick(Plugin owner, UUID worldId, ModelRay ray);

    /**
     * Close this exact owner's models and observe completion of all outstanding cleanup, including
     * handles already closed individually. First stop creating/submitting models; await this stage
     * asynchronously while JavaRailways remains enabled, before disabling the host. Returning from
     * PluginDisableEvent/onDisable or handle.close does not prove entity deletion has completed.
     * This does not permanently prohibit a still-enabled owner from creating new models later.
     */
    CompletionStage<Void> closeOwner(Plugin owner);
}
