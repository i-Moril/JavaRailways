package work.art1st.mineRails.bukkit.render.config

import work.art1st.mineRails.core.api.render.Renderable
import work.art1st.configLib.persistence.Loads
import work.art1st.mineRails.bukkit.render.Animation

interface AnimationConfig : Loads<Animation, Renderable> {
}