package work.art1st.mineRails.asset

import org.bukkit.Chunk
import org.bukkit.entity.Player
import work.art1st.mineRails.core.asset.Asset

open class RenderManager<T : Asset<Player>>(
    protected val defaultViewDistance: Int
) {
    protected val assetsInChunks: MutableMap<Chunk, MutableSet<T>> = HashMap()
    protected val viewDistance: MutableMap<Player, Int> = HashMap()
    protected val playerView: MutableMap<Player, MutableSet<T>> = HashMap()

    fun getViewDistance(player: Player): Int {
        return viewDistance[player] ?: defaultViewDistance
    }

    fun getView(player: Player): MutableSet<T> {
        return playerView.getOrPut(player) {
            hashSetOf()
        }
    }

    fun chunksAround(player: Player) : Iterator<Chunk> {
        return object : Iterator<Chunk> {
            private val d = getViewDistance(player)
            private val x1: Int
            private val z1: Int
            private val x2: Int
            private val z2: Int
            private var x: Int
            private var z: Int
            private val world = player.world

            init {
                val p1 = player.location.subtract(d.toDouble(), 0.0, d.toDouble()).chunk
                val p2 = player.location.add(d.toDouble(), 0.0, d.toDouble()).chunk
                x1 = p1.x
                z1 = p1.z
                x2 = p2.x
                z2 = p2.z
                x = x1
                z = z1
            }

            override fun next(): Chunk {
                val ret = world.getChunkAt(x, z)
                if (z < z2) {
                    z++
                } else /* z == z2 */ {
                    z = z1
                    x++
                }
                return ret
            }

            override fun hasNext(): Boolean {
                return !(x1 == x2 && z1 == z2)
            }
        }
    }

    fun render(player: Player) {
        for (chunk in chunksAround(player)) {
            for (asset in assetsInChunks.getOrDefault(chunk, hashSetOf())) {
                if (asset.getLocation()) {
                    // TODO
                    asset.render(player)
                }
            }
        }
    }

    companion object {
        val chunkSize = 16
    }
}
