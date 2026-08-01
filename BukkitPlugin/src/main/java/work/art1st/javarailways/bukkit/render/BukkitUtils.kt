package work.art1st.javarailways.bukkit.render

import org.bukkit.Location
import org.joml.Vector3d

fun Location.asVector3d(): Vector3d = Vector3d(x, y, z)