package work.art1st.javarailways.basic.api.route

import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import java.util.UUID

/**
 * Reads the current permitted direction under the sole Basic direction-store lock.
 * `null` means no authored restriction (both directions). The caller supplies a valid rail identity
 * in the world's allowed read context; this lookup does not validate rail existence or pin topology.
 * O(1), with no mutation or snapshot allocation.
 * @throws IllegalStateException if Basic data is absent; absence is never treated as both directions.
 */
fun World.operatingDirection(railId: UUID): ViewDirection? = basicWorldData.operatingDirections[railId]

/**
 * Captures exactly [railIds] together under the sole direction-store lock, including `null` entries
 * for unrestricted rails. The detached result retains the supplied [topology] root and is unaffected
 * by later writes. The caller supplies its captured topology and owns world/read-phase consistency;
 * this query does not capture or replace topology. Work is O(requested rails), with no world scan.
 * @throws IllegalArgumentException if a requested rail is absent from [topology].
 * @throws IllegalStateException if Basic data is absent.
 */
fun World.operatingDirections(topology: TopologyState, railIds: Collection<UUID>): RailOperatingDirections =
    basicWorldData.operatingDirections.snapshot(topology, railIds)
