package work.art1st.mineRails.core.api.event

/**
 * Marker for events dispatched by MineRails [work.art1st.mineRails.core.api.event.processor.EventManager].
 *
 * MineRails events are not Bukkit events. Dispatch is synchronous on the calling thread; consult the
 * concrete event and dispatch API before accessing platform objects.
 */
interface Event
