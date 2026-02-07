package work.art1st.mineRails.packet

import org.bukkit.entity.Player

class PacketEntity {
    val entityId: Int
    val x: Double
    val y: Double
    val z: Double
    val yaw: Float
    val pitch: Float
    
    fun send(player: Player) {

    }
}