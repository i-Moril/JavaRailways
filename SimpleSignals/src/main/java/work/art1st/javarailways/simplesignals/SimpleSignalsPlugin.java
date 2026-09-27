package work.art1st.javarailways.simplesignals;

import org.bukkit.plugin.java.JavaPlugin;

/** Bukkit lifecycle owner for the independently installed SimpleSignals add-on. */
public final class SimpleSignalsPlugin extends JavaPlugin {
    private SimpleSignalsHostServices hostServices;

    @Override
    public void onEnable() {
        hostServices = SimpleSignalsHostServices.load(getServer().getServicesManager());
        getLogger().info("SimpleSignals enabled");
    }

    @Override
    public void onDisable() {
        hostServices = null;
    }
}
