package work.art1st.javarailways.bukkit.passenger

import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import work.art1st.javarailways.bukkit.render.backend.PacketEntityMotion
import work.art1st.javarailways.bukkit.render.backend.SeatAuthorityMover
import work.art1st.javarailways.bukkit.render.backend.configureSeatCarrier

/** Entity-owner operations; session policy and geometry stay independent of the packet transport. */
internal interface PassengerCarrierAccess {
    fun spawn(location: Location): ArmorStand
    fun register(carrier: ArmorStand, location: Location, viewers: Collection<Player>)
    fun move(carrier: ArmorStand, location: Location, viewers: Collection<Player>)
    fun dismountAt(carrier: ArmorStand, player: Player, feet: Location)
    fun release(carrier: ArmorStand)
}

internal class PacketPassengerCarrierAccess(private val motion: PacketEntityMotion) : PassengerCarrierAccess {
    override fun spawn(location: Location): ArmorStand =
        location.world!!.spawn(location, ArmorStand::class.java, ::configureSeatCarrier)
    override fun register(carrier: ArmorStand, location: Location, viewers: Collection<Player>) =
        motion.registerSeatCarrier(carrier, location, viewers)
    override fun move(carrier: ArmorStand, location: Location, viewers: Collection<Player>) {
        motion.renderSeatCarrier(carrier, location, viewers)
        motion.syncSeatAuthority(carrier, location)
    }
    override fun dismountAt(carrier: ArmorStand, player: Player, feet: Location) =
        SeatAuthorityMover.dismountAt(carrier, player, feet)
    override fun release(carrier: ArmorStand) = motion.release(carrier)
}

// Marker ArmorStand PASSENGER attachment is at height 0; Player VEHICLE attachment is (0, 0.6, 0).
internal const val PASSENGER_CARRIER_LIFT = 0.6
