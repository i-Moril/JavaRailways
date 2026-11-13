package work.art1st.javarailways.bukkit.passenger

import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import work.art1st.javarailways.bukkit.render.backend.PacketEntityMotion
import work.art1st.javarailways.bukkit.render.backend.SeatAuthorityMover
import work.art1st.javarailways.bukkit.render.backend.configureSeatCarrier
import java.util.concurrent.locks.ReentrantLock

/** Packet presentation is frame-owned; native movement stays on the carrier's entity owner. */
internal interface PassengerCarrierAccess {
    fun spawn(location: Location): ArmorStand
    fun register(carrier: ArmorStand, location: Location, viewers: Collection<Player>)
    fun canDismount(player: Player): Boolean
    fun render(carrier: ArmorStand, location: Location, viewers: Collection<Player>)
    fun holdPresentation(lock: ReentrantLock)
    fun syncAuthority(carrier: ArmorStand, location: Location)
    fun dismountAt(carrier: ArmorStand, player: Player, carrierTarget: Location): Boolean
    fun release(carrier: ArmorStand)
}

internal class PacketPassengerCarrierAccess(private val motion: PacketEntityMotion) : PassengerCarrierAccess {
    override fun spawn(location: Location): ArmorStand =
        location.world!!.spawn(location, ArmorStand::class.java, ::configureSeatCarrier)
    override fun register(carrier: ArmorStand, location: Location, viewers: Collection<Player>) =
        motion.registerSeatCarrier(carrier, location, viewers)
    override fun canDismount(player: Player) = !SeatAuthorityMover.hasPendingTeleport(player)
    override fun render(carrier: ArmorStand, location: Location, viewers: Collection<Player>) =
        motion.renderSeatCarrier(carrier, location, viewers)
    override fun holdPresentation(lock: ReentrantLock) = motion.holdSeatPresentation(lock)
    override fun syncAuthority(carrier: ArmorStand, location: Location) =
        motion.syncPassengerAuthority(carrier, location)
    override fun dismountAt(carrier: ArmorStand, player: Player, carrierTarget: Location) =
        SeatAuthorityMover.dismountAt(carrier, player, carrierTarget)
    override fun release(carrier: ArmorStand) = motion.release(carrier)
}

// Marker ArmorStand PASSENGER attachment is at height 0; Player VEHICLE attachment is (0, 0.6, 0).
internal const val PASSENGER_CARRIER_LIFT = 0.6
