package work.art1st.javarailways.bukkit.signal;

import work.art1st.javarailways.core.api.physics.rail.ViewDirection;
import work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature;
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition;
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal;
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions;
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsSimulationFeature;
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection;
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition;
import work.art1st.javarailways.simplesignals.signalpack.SignalPackLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Test-only bridge from the Bukkit host test into the real SimpleSignals simulation feature.
 *
 * <p>SimpleSignals keeps its definition/runtime types Kotlin-{@code internal}, but their constructors
 * are JVM-public. This class calls those constructors directly (no reflection, no production
 * visibility widening, no hand-written signal simulator) and returns the production
 * {@link SimpleSignalsSimulationFeature}, which installs the official jr-four-light JS rules and the
 * real fixed-block resource/inspection/signal source chain into the isolated target.
 *
 * <p>The caller must bootstrap the cached Paper item registry (see {@code ModelRuntimeTestSupport})
 * before loading the distributed pack, because the loader materializes {@code Material.PAPER} models.
 */
public final class SimpleSignalsSimulationFeatureFactory {

    /** Matches the production {@code SimpleSignalsPlugin.name.lowercase()} owner identity. */
    public static final String OWNER_ID = "simplesignals";

    /** First-class signal type declared by the distributed jr-four-light pack. */
    public static final String SIGNAL_TYPE = "jr_four_light:block";

    /** Calls the production collision-owner transaction with its real geometry/dependency envelopes.
     * The caller owns the world's admitted between-frames scope; this does not fabricate events.
     */
    public static work.art1st.javarailways.core.api.physics.train.Train linkTouchingTrains(
            work.art1st.javarailways.core.physics.train.CoreTrain first,
            work.art1st.javarailways.core.physics.train.CoreTrain second) {
        work.art1st.javarailways.core.physics.CoreWorld world = first.getWorld();
        return work.art1st.javarailways.core.physics.train.TrainCoupling.INSTANCE.linkFromCollisionOnOwner(
                first, second, first.getPublishedFrame(), second.getPublishedFrame(), world.getTopologyRevision(),
                world.collisionEnvelope$Core(first.getId()), world.collisionEnvelope$Core(second.getId()));
    }

    private SimpleSignalsSimulationFeatureFactory() {
    }

    /**
     * Builds one fixed block per rail (each covering its whole AB length) with an entry signal bound
     * to the official four-light logic. The produced feature re-compiles these definitions against the
     * target topology, so every {@code railId} must exist in the source world's rail projection.
     *
     * @param signalPackDirectory                 path to {@code jr-four-light/signal-packs}
     * @param blockRailIds                        rail UUIDs, one block per rail, in travel order
     * @param blockRailLengthsMeters              matching AB arc length for each rail
     * @param protectionRegionMaxDistanceMeters   protection-region scan bound used by the runtime
     */
    public static PreparedSimulationFeature fourLightFeature(
            Path signalPackDirectory,
            List<UUID> blockRailIds,
            List<Double> blockRailLengthsMeters,
            double protectionRegionMaxDistanceMeters) {
        if (blockRailIds.size() != blockRailLengthsMeters.size()) {
            throw new IllegalArgumentException("block rail ids and lengths must align");
        }
        if (blockRailIds.isEmpty()) {
            throw new IllegalArgumentException("at least one fixed block rail is required");
        }
        List<FixedBlockDefinition> blocks = new ArrayList<>(blockRailIds.size());
        List<FixedBlockSignalDefinition> signals = new ArrayList<>(blockRailIds.size());
        for (int i = 0; i < blockRailIds.size(); i++) {
            String blockId = "test:block-" + i;
            String signalId = "test:signal-" + i;
            double length = blockRailLengthsMeters.get(i);
            blocks.add(new FixedBlockDefinition(blockId, 1L,
                    Collections.singletonList(new FixedBlockTraversal(
                            blockRailIds.get(i), ViewDirection.AB, 0.0, length))));
            signals.add(new FixedBlockSignalDefinition(
                    signalId, 1L, blockId, 0.0, SIGNAL_TYPE,
                    FixedBlockEntryDirection.FORWARD, null));
        }
        SimpleSignalsWorldDefinitions definitions = new SimpleSignalsWorldDefinitions(
                UUID.randomUUID(), 1L,
                Collections.emptyList(), Collections.emptyList(), blocks, signals,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        return new SimpleSignalsSimulationFeature(
                OWNER_ID,
                definitions,
                SignalPackLoader.INSTANCE.load(signalPackDirectory).getRules(),
                protectionRegionMaxDistanceMeters);
    }
}
