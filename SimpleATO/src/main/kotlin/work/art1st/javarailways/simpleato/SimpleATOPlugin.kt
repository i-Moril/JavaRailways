package work.art1st.javarailways.simpleato

import work.art1st.javarailways.core.api.util.plugin.Plugin

/** Core extension-slot identity for SimpleATO-owned world runtime state. */
class SimpleATOPlugin(override var identifier: Int) : Plugin {
    companion object {
        lateinit var INSTANCE: SimpleATOPlugin

        fun instanceOrNull(): SimpleATOPlugin? = if (::INSTANCE.isInitialized) INSTANCE else null
    }
}
