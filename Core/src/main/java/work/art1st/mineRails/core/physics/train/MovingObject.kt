package work.art1st.mineRails.core.physics.train

interface MovingObject {
    /**
     * 同步地更新位置信息
     */
    fun commit()
}