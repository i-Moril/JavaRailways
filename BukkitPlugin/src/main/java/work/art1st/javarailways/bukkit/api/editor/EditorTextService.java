package work.art1st.javarailways.bukkit.api.editor;

import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Host-owned temporary text, available from Bukkit's service registry. */
public interface EditorTextService {
    /**
     * Called on the viewer's entity owner; captures the actual current world without spawning.
     * Owner, viewer and world instances plus nonblank objectId identify one active handle.
     * Offline viewers, unloaded worlds, disabled plugins and active duplicates are rejected.
     * The caller closes when its tool or editing context ends. The host also closes on lifecycle events.
     */
    EditorTextHandle create(Plugin owner, Player viewer, String objectId);

    /**
     * Close all owner handles, including previously closed handles with outstanding cleanup.
     * Observe completion before disabling JavaRailways. Cleanup failures remain exceptional on later calls.
     */
    CompletionStage<Void> closeOwner(Plugin owner);
}
