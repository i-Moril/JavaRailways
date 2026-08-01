package work.art1st.javarailways.bukkit.util

data class PlatformCapabilities(
    val folia: Boolean
) {
    companion object {
        fun detect(classLoader: ClassLoader = PlatformCapabilities::class.java.classLoader): PlatformCapabilities =
            PlatformCapabilities(
                folia = runCatching {
                    Class.forName(
                        "io.papermc.paper.threadedregions.RegionizedServer",
                        false,
                        classLoader
                    )
                }.isSuccess
            )
    }
}
