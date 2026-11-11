package work.art1st.javarailways.core.api.physics

/**
 * Synchronous value-only work that must finish before one railway physics epoch becomes visible.
 *
 * The processor runs on the world's serialized physics-coordinator thread after train frames and
 * swept occupancy have been captured into a [RailwayWorldFrameSnapshot], but before that frame is
 * published and before the exact physics ticket completes. Implementations must not retain mutable
 * Core objects, call Bukkit, perform blocking I/O, or dispatch safety work for later replay.
 * Processors are installed during world composition before the coordinator starts and remain
 * static while it runs. Throwing fails the epoch closed: the new world frame is not published and
 * the coordinator stalls.
 */
fun interface RailwayEpochProcessor {
    /** Completes all synchronous work derived from this immutable [frame], or throws. */
    fun process(frame: RailwayWorldFrameSnapshot)
}
