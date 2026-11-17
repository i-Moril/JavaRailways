package example.signaladdon;

import java.util.List;
import work.art1st.javarailways.core.api.physics.World;
import work.art1st.javarailways.core.api.physics.rail.RailInterval;
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot;
import work.art1st.javarailways.core.api.physics.train.Train;
import work.art1st.javarailways.core.api.physics.train.PreparedTrainTeleport;
import work.art1st.javarailways.core.api.physics.train.TrainTeleportInspection;

/** Compilation fixture: a Java add-on can inspect and teleport without Core implementation types. */
final class PhysicsMaintenanceApiConsumer {
    static List<RailInterval> teleport(World world, Train train, RailPositionSnapshot target) {
        return world.withPhysicsStalled(maintenance -> {
            TrainTeleportInspection inspection = maintenance.inspectTeleport(train, target);
            if (inspection instanceof TrainTeleportInspection.Rejected rejected) {
                throw new IllegalArgumentException(rejected.getReason());
            }
            PreparedTrainTeleport placement = ((TrainTeleportInspection.Ready) inspection).getPlacement();
            List<RailInterval> occupied = List.copyOf(placement.getOccupiedRailIntervals());
            maintenance.teleport(placement);
            return occupied;
        });
    }
}
