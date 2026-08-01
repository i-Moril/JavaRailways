package work.art1st.javarailways.bukkit.render.config

import work.art1st.javarailways.core.api.render.Renderable
import work.art1st.configLib.persistence.Loads
import work.art1st.javarailways.bukkit.render.Animation

interface AnimationConfig : Loads<Animation, Renderable> {
}