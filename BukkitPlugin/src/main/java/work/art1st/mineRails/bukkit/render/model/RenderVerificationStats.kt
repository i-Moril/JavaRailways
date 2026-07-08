package work.art1st.mineRails.bukkit.render.model

import java.util.concurrent.atomic.AtomicLong

object RenderVerificationStats {
    @Volatile
    var enabled: Boolean = false

    private val rootTeleports = AtomicLong()
    private val displayMounts = AtomicLong()
    private val displayMountedUpdates = AtomicLong()
    private val displayWorldTeleports = AtomicLong()
    private val lodMounts = AtomicLong()
    private val lodMountedUpdates = AtomicLong()
    private val lodWorldTeleports = AtomicLong()

    fun reset() {
        rootTeleports.set(0)
        displayMounts.set(0)
        displayMountedUpdates.set(0)
        displayWorldTeleports.set(0)
        lodMounts.set(0)
        lodMountedUpdates.set(0)
        lodWorldTeleports.set(0)
    }

    fun onRootTeleport() {
        if (enabled) rootTeleports.incrementAndGet()
    }

    fun onDisplayMount() {
        if (enabled) displayMounts.incrementAndGet()
    }

    fun onDisplayMountedUpdate() {
        if (enabled) displayMountedUpdates.incrementAndGet()
    }

    fun onDisplayWorldTeleport() {
        if (enabled) displayWorldTeleports.incrementAndGet()
    }

    fun onLodMount() {
        if (enabled) lodMounts.incrementAndGet()
    }

    fun onLodMountedUpdate() {
        if (enabled) lodMountedUpdates.incrementAndGet()
    }

    fun onLodWorldTeleport(count: Long = 1) {
        if (enabled) lodWorldTeleports.addAndGet(count)
    }

    fun snapshotLines(): List<String> = listOf(
        "enabled=$enabled",
        "rootTeleports=${rootTeleports.get()}",
        "displayMounts=${displayMounts.get()}",
        "displayMountedUpdates=${displayMountedUpdates.get()}",
        "displayWorldTeleports=${displayWorldTeleports.get()}",
        "lodMounts=${lodMounts.get()}",
        "lodMountedUpdates=${lodMountedUpdates.get()}",
        "lodWorldTeleports=${lodWorldTeleports.get()}",
        "expectation: rootTeleports grows, while displayWorldTeleports/lodWorldTeleports stay near zero in mounted mode"
    )
}
