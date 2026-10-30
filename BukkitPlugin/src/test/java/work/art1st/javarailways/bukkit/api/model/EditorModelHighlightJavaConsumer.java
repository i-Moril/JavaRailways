package work.art1st.javarailways.bukkit.api.model;

import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Compile-only Java add-on consumer: no host internals or Kotlin signatures are required. */
final class EditorModelHighlightJavaConsumer {
    static void showProblems(ModelPresentationService service, Plugin owner, Player viewer) {
        try (EditorModelHighlightHandle handle = service.createEditorHighlight(owner, viewer, "editor")) {
            handle.submit(0L, Set.of("signal:broken"), 0xff0000);
            handle.submit(1L, Set.of(), 0xff0000);
        }
    }
}
