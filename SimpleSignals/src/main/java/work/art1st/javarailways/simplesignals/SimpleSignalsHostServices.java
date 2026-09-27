package work.art1st.javarailways.simplesignals;

import org.bukkit.plugin.ServicesManager;
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService;
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationService;
import work.art1st.javarailways.bukkit.api.signal.InspectionSourceService;
import work.art1st.javarailways.bukkit.api.signal.SignalEpochSourceService;

import java.util.Objects;
import java.util.function.Function;

/** The host capabilities required before SimpleSignals can install any world runtime. */
record SimpleSignalsHostServices(
    RailwayObservationService observations,
    InfrastructureResourceService infrastructure,
    SignalEpochSourceService signalEpochSources,
    InspectionSourceService inspectionSources
) {
    SimpleSignalsHostServices {
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(infrastructure, "infrastructure");
        Objects.requireNonNull(signalEpochSources, "signalEpochSources");
        Objects.requireNonNull(inspectionSources, "inspectionSources");
    }

    static SimpleSignalsHostServices load(ServicesManager servicesManager) {
        Objects.requireNonNull(servicesManager, "servicesManager");
        return load(type -> servicesManager.load(type));
    }

    static SimpleSignalsHostServices load(Function<Class<?>, Object> lookup) {
        Objects.requireNonNull(lookup, "lookup");
        return new SimpleSignalsHostServices(
            require(lookup, RailwayObservationService.class),
            require(lookup, InfrastructureResourceService.class),
            require(lookup, SignalEpochSourceService.class),
            require(lookup, InspectionSourceService.class)
        );
    }

    private static <T> T require(Function<Class<?>, Object> lookup, Class<T> type) {
        Object service = lookup.apply(type);
        if (service == null) {
            throw new IllegalStateException("JavaRailways did not register " + type.getSimpleName());
        }
        return type.cast(service);
    }
}
