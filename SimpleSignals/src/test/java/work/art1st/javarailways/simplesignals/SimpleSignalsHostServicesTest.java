package work.art1st.javarailways.simplesignals;

import org.junit.jupiter.api.Test;
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService;
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationService;
import work.art1st.javarailways.bukkit.api.signal.InspectionSourceService;
import work.art1st.javarailways.bukkit.api.signal.SignalEpochSourceService;
import work.art1st.javarailways.bukkit.api.signal.RoutePlanningRestrictionSourceService;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleSignalsHostServicesTest {
    @Test
    void loadsOnlyTheRequiredSignalHostCapabilities() {
        var registered = completeCapabilities();

        var services = SimpleSignalsHostServices.load(registered::get);

        assertSame(registered.get(RailwayObservationService.class), services.observations());
        assertSame(registered.get(InfrastructureResourceService.class), services.infrastructure());
        assertSame(registered.get(SignalEpochSourceService.class), services.signalEpochSources());
        assertSame(registered.get(InspectionSourceService.class), services.inspectionSources());
        assertSame(
            registered.get(RoutePlanningRestrictionSourceService.class),
            services.routePlanningSources()
        );
    }

    @Test
    void rejectsEnableWhenAnyRequiredCapabilityIsMissing() {
        for (var missing : completeCapabilities().keySet()) {
            var registered = completeCapabilities();
            registered.remove(missing);

            var error = assertThrows(
                IllegalStateException.class,
                () -> SimpleSignalsHostServices.load(registered::get)
            );

            assertTrue(error.getMessage().contains(missing.getSimpleName()));
        }
    }

    private static Map<Class<?>, Object> completeCapabilities() {
        var services = new HashMap<Class<?>, Object>();
        services.put(RailwayObservationService.class, proxy(RailwayObservationService.class));
        services.put(InfrastructureResourceService.class, proxy(InfrastructureResourceService.class));
        services.put(SignalEpochSourceService.class, proxy(SignalEpochSourceService.class));
        services.put(InspectionSourceService.class, proxy(InspectionSourceService.class));
        services.put(
            RoutePlanningRestrictionSourceService.class,
            proxy(RoutePlanningRestrictionSourceService.class)
        );
        return services;
    }

    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (proxy, method, arguments) -> {
                throw new UnsupportedOperationException(method.getName());
            }
        ));
    }
}
