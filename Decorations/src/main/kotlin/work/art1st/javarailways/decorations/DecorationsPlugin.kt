package work.art1st.javarailways.decorations

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.ServicePriority
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionAudiencePolicy
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRegistration
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionService
import work.art1st.javarailways.decorations.api.display.DisplayStyleService
import work.art1st.javarailways.decorations.display.RuntimeDisplayStyles
import work.art1st.javarailways.decorations.display.DisplayStyleCommand
import work.art1st.javarailways.decorations.display.DecorationsCommand
import work.art1st.javarailways.decorations.display.DisplayPartProvider
import work.art1st.javarailways.decorations.display.DisplayDistanceCommand
import work.art1st.javarailways.decorations.display.DisplayDistanceConfig
import work.art1st.javarailways.decorations.display.DisplayDistanceSettings
import work.art1st.javarailways.decorations.display.DisplayStyleBundles
import work.art1st.javarailways.decorations.integration.simpledisplays.OptionalSimpleDisplays
import java.util.logging.Level

class DecorationsPlugin : JavaPlugin() {
    @Volatile private var registration: PartExtensionRegistration? = null
    private var simpleDisplays: OptionalSimpleDisplays? = null
    private var distanceSettings: DisplayDistanceSettings? = null
    private var displayStyles: RuntimeDisplayStyles? = null

    override fun onEnable() {
        saveDefaultConfig()
        val distances = DisplayDistanceConfig.parse(dataFolder.resolve("config.yml").readText(Charsets.UTF_8))
        distanceSettings = distances
        val service = checkNotNull(server.servicesManager.load(PartExtensionService::class.java)) {
            "JavaRailways did not register PartExtensionService"
        }
        val optional = OptionalSimpleDisplays(this)
        simpleDisplays = optional
        try {
            val externalStyles = DisplayStyleBundles.load(dataFolder.resolve("styles").toPath())
            val styles = RuntimeDisplayStyles { registration?.refreshRendering() }
            displayStyles = styles
            server.servicesManager.register(DisplayStyleService::class.java, styles, this, ServicePriority.Normal)
            // Existing published packages are validated during register, so discover the compiler first.
            optional.start()
            optional.validateTemplates("external display styles", externalStyles)
            registration = service.register(this, DisplayPartProvider(
                openTemplate = optional::openTemplate,
                validateTemplates = optional::validateTemplates,
                interiorAudiencePolicy = PartExtensionAudiencePolicy { id -> distances.distanceFor(id) },
                runtimeStyles = styles,
                externalStyles = externalStyles,
            ))
            registration!!.existingPackageFailures.forEach { (name, reason) ->
                logger.warning("Existing package '$name' has invalid ${DisplayPartProvider.TYPE_ID} definitions: $reason")
            }
            val activeRegistration = checkNotNull(registration)
            val commands = DecorationsCommand(
                DisplayDistanceCommand(this, distances, activeRegistration::refreshAudience),
                DisplayStyleCommand(this, styles),
            )
            checkNotNull(getCommand("decorations")) { "Decorations command is missing from plugin.yml" }.apply {
                setExecutor(commands)
                tabCompleter = commands
            }
            server.pluginManager.registerEvents(object : Listener {
                @EventHandler
                fun onQuit(event: PlayerQuitEvent) { distances.reset(event.player.uniqueId) }

                @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
                fun onWorldUnload(event: WorldUnloadEvent) { styles.clearWorld(event.world.uid) }
            }, this)
        } catch (failure: Throwable) {
            clearDistanceInputs()
            try { clearStyleInputs() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            try {
                registration?.closeAsync()?.whenComplete { _, cleanup ->
                    if (cleanup != null) logger.log(Level.WARNING, "Failed to clean up Decorations registration after enable failure", cleanup)
                }
            } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            registration = null
            try { optional.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            simpleDisplays = null
            throw failure
        }
    }

    override fun onDisable() {
        clearDistanceInputs()
        try { clearStyleInputs() } catch (failure: Exception) {
            logger.log(Level.WARNING, "Failed to close Decorations display styles", failure)
        }
        try {
            registration?.closeAsync()?.whenComplete { _, failure ->
                if (failure != null) logger.log(Level.WARNING, "Failed to remove Decorations visuals during unregister", failure)
            }
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "Failed to unregister Decorations part provider", failure)
        } finally {
            registration = null
            simpleDisplays?.close()
            simpleDisplays = null
        }
    }

    private fun clearStyleInputs() {
        val styles = displayStyles ?: return
        displayStyles = null
        try {
            server.servicesManager.unregister(DisplayStyleService::class.java, styles)
        } finally {
            styles.close()
        }
    }

    private fun clearDistanceInputs() {
        HandlerList.unregisterAll(this)
        getCommand("decorations")?.apply {
            setExecutor(null)
            tabCompleter = null
        }
        distanceSettings?.clear()
        distanceSettings = null
    }
}
