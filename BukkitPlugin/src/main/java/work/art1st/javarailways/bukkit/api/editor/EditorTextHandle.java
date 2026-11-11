package work.art1st.javarailways.bukkit.api.editor;

import java.util.List;

/** One temporary, viewer-only collection of text. Submit and close never await platform work. */
public interface EditorTextHandle extends AutoCloseable {
    /**
     * Submit a complete copied list with unique label IDs and a nonnegative increasing revision.
     * Empty lists clear all labels. Older/equal revisions and submissions after close are ignored.
     * Invalid lists throw before replacing valid state; platform failures are logged by the host.
     */
    void submit(long revision, List<EditorTextLabel> labels);

    /** Idempotently withdraw intent and schedule cleanup. Observe completion through closeOwner. */
    @Override
    void close();
}
