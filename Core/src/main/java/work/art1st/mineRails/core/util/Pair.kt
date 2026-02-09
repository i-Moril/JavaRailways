package work.art1st.mineRails.core.util

import java.util.*

data class Pair<A, B>(val a: A, val b: B) {
    override fun equals(other: Any?): Boolean {
        if (other is Pair<*, *>) {
            return a?.equals(other.a) == true && b?.equals(other.b) == true
        }
        return false
    }

    override fun hashCode(): Int {
        return Objects.hashCode(a) shl 16 + Objects.hashCode(b)
    }
}
