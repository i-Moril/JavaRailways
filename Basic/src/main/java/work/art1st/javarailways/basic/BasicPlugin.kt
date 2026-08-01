package work.art1st.javarailways.basic

import work.art1st.javarailways.core.api.util.plugin.Plugin

class BasicPlugin(override var identifier: Int) : Plugin {
    companion object {
        lateinit var INSTANCE: BasicPlugin

        fun instanceOrNull(): BasicPlugin? = if (::INSTANCE.isInitialized) INSTANCE else null
    }
}
