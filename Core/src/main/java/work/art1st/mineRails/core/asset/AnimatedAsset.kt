package work.art1st.mineRails.core.asset

interface AnimatedAsset : Asset {
    /**
     * 更新Asset
     * @param deltaT
     */
    fun playAnimation(animation: String)
}
