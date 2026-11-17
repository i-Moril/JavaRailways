package work.art1st.javarailways.basic.api.route.planning

import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState

/**
 * Captures all authored one-way records together under Basic's sole direction-store lock.
 * The detached sparse overlay uses [topology]'s revision; unrestricted rails stay absent, so capture
 * is O(authored restrictions), not O(world rails). The caller owns world/read-phase consistency.
 * This capture does not validate rail existence: [OperatingDirectionOverlay.toRestrictions] retains
 * the existing rejection of orphan or ambiguous rail projections against the planning topology.
 * @throws IllegalStateException if Basic data is absent.
 */
fun World.operatingDirectionOverlay(topology: TopologyState): OperatingDirectionOverlay =
    basicWorldData.operatingDirections.overlay(topology)
