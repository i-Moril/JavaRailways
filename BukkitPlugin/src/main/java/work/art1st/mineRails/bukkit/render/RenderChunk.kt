package work.art1st.mineRails.bukkit.render

import org.joml.Vector3dc
import work.art1st.mineRails.core.api.render.Renderable

data class RenderChunk(
    val x: Long,
    val z: Long
) {

    constructor(position: Vector3dc) : this(position.x().toLong() / CHUNK_SIZE, position.z().toLong() / CHUNK_SIZE)

    companion object {
        const val CHUNK_SIZE = 16
        fun getChunksAround(position: Vector3dc, radius: Double): Collection<RenderChunk> {
            val chunks = ArrayList<RenderChunk>()
            val x1 = (position.x() - radius).toLong() / CHUNK_SIZE
            val z1 = (position.z() - radius).toLong() / CHUNK_SIZE
            val x2 = (position.x() + radius).toLong() / CHUNK_SIZE
            val z2 = (position.z() + radius).toLong() / CHUNK_SIZE
            for (x in x1..x2) {
                for (z in z1..z2) {
                    chunks.add(RenderChunk(x, z))
                }
            }
            return chunks
        }
    }
}

val Renderable.chunk
    get() = RenderChunk(position)