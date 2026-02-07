package work.art1st.mineRails.core.physics.train

import work.art1st.mineRails.core.util.Vector3D

interface MovingObject {
    val position: Vector3D
    val velocity: Vector3D
    /**
     * 同步地更新位置信息
     */
    fun commit()
}