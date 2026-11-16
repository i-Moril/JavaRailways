package work.art1st.javarailways.basic.control

import work.art1st.javarailways.basic.api.ato.CarDynamicsProfile
import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate

/** Exact profile/state preparation shared by the executor and actuator composition. */
internal class PreparedConsistDynamics(
    val carProfiles: List<CarDynamicsProfile>,
    val update: PreparedControllerStateUpdate
)
