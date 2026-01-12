package work.art1st.mineRails.core.asset

import work.art1st.mineRails.core.util.Quaternion
import work.art1st.mineRails.core.util.Vector3D

interface Asset<Audience> {
    val offset: Vector3D
    val offsetTransform: Quaternion

    fun submit()
}
