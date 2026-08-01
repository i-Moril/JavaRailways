package work.art1st.javarailways.core.api.physics.rail

import work.art1st.javarailways.core.api.util.annotation.MainThread

interface Reversible {
    @MainThread
    fun reverse()
}