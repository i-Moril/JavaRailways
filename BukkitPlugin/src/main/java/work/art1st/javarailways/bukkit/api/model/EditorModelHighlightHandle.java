package work.art1st.javarailways.bukkit.api.model;

import java.util.Set;

/** Viewer-only editor outline on this owner's existing ordinary models in the captured world. */
public interface EditorModelHighlightHandle extends AutoCloseable {
    /** Complete copied selection; empty clears. RGB is 0x000000 through 0xffffff.
     * Revisions are nonnegative and increasing; stale submissions and closed handles are ignored.
     * Unknown IDs remain intent, so replacing a model handle preserves the editor outline.
     */
    void submit(long revision, Set<String> objectIds, int rgb);
    @Override void close();
}
