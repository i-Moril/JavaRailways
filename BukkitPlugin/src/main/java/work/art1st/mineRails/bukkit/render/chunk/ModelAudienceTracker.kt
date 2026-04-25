package work.art1st.mineRails.bukkit.render.chunk

class ModelAudienceTracker() {
    private var ttl: Int = 0
    val isValid: Boolean
        get() = ttl > 0

    fun update() {
        ttl = DEFAULT_TTL
    }

    fun commit() {
        if (ttl > 0) ttl -= 1
    }

    companion object {
        // TODO: Make it configurable
        val DEFAULT_TTL = 5 * 20
    }
}