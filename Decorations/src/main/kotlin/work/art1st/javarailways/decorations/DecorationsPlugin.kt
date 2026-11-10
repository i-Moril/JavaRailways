package work.art1st.javarailways.decorations

import org.bukkit.plugin.java.JavaPlugin
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionRegistration
import work.art1st.javarailways.bukkit.api.model.extension.PartExtensionService
import work.art1st.javarailways.decorations.display.DisplayPartProvider
import work.art1st.javarailways.decorations.integration.simpledisplays.OptionalSimpleDisplays
import java.util.logging.Level

class DecorationsPlugin : JavaPlugin() {
    private var registration: PartExtensionRegistration? = null
    private var simpleDisplays: OptionalSimpleDisplays? = null

    override fun onEnable() {
        val service = checkNotNull(server.servicesManager.load(PartExtensionService::class.java)) {
            "JavaRailways did not register PartExtensionService"
        }
        val optional = OptionalSimpleDisplays(this)
        simpleDisplays = optional
        try {
            // Existing published packages are validated during register, so discover the compiler first.
            optional.start()
            registration = service.register(this, DisplayPartProvider(prepareTemplate = optional::prepareTemplate, validateTemplates = optional::validateTemplates))
            registration!!.existingPackageFailures.forEach { (name, reason) ->
                logger.warning("Existing package '$name' has invalid ${DisplayPartProvider.TYPE_ID} definitions: $reason")
            }
        } catch (failure: Throwable) {
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
}
