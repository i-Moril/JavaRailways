package work.art1st.mineRails.basic

import work.art1st.mineRails.core.api.util.plugin.Plugin

class BasicPlugin(override var identifier: Int) : Plugin {
    companion object {
        lateinit var INSTANCE: BasicPlugin
    }
}
