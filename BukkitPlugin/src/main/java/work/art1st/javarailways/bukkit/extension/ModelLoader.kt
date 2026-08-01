package work.art1st.javarailways.bukkit.extension

import work.art1st.javarailways.bukkit.render.model.CompositeModel

fun interface ModelLoader {
    fun load() : CompositeModel
}