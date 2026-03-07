package work.art1st.mineRails.bukkit.extension

import work.art1st.mineRails.bukkit.render.model.CompositeModel

interface ModelLoader {
    fun load() : CompositeModel
}