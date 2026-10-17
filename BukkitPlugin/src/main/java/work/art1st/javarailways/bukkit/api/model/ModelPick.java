package work.art1st.javarailways.bukkit.api.model;

/** Selection of latest accepted geometry, not a renderer acknowledgment or authoring revision. */
public record ModelPick(String objectId, double distance, long presentationRevision) {}
