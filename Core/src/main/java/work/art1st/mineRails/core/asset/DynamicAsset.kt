package work.art1st.mineRails.core.asset

interface DynamicAsset<Audience> : Asset<Audience> {
    /**
     * 更新Asset
     * @param deltaT
     */
    fun tick(deltaT: Double)
}
