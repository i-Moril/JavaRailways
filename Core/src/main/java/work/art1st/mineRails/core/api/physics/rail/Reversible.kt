package work.art1st.mineRails.core.api.physics.rail

import work.art1st.mineRails.core.api.util.annotation.MainThread

interface Reversible {
    @MainThread
    fun reverse()
}