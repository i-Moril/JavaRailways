package work.art1st.javarailways.core.api.util.tick

interface Synchronizable {
    /**
     * 同步地更新信息
     */
    fun commit()
}