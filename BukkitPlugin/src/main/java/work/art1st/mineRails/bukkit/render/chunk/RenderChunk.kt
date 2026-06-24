package work.art1st.mineRails.bukkit.render.chunk

import org.joml.Vector3dc
import kotlin.math.floor

data class RenderChunk(
    val x: Int,
    val z: Int
) {

    constructor(position: Vector3dc) : this(
        floor(position.x() / CHUNK_SIZE).toInt(),
        floor(position.z() / CHUNK_SIZE).toInt()
    )

    companion object {
        const val CHUNK_SIZE : Int = 16
        fun getChunksAround(position: Vector3dc, radius: Double): Iterable<RenderChunk> {
            return Iterable { RenderChunkAreaIterator(position.x(), position.z(), radius, CHUNK_SIZE.toDouble()) }
        }
    }
}
