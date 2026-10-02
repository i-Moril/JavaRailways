package work.art1st.javarailways.bukkit.render.backend

/** Explicit cleanup failure, distinct from an ordinary presentation failure that was fully cleaned. */
internal class DisplayCleanupException(cause: Throwable) : RuntimeException("Display cleanup failed", cause)
