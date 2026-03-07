package work.art1st.mineRails.core.api.util.exception

import work.art1st.mineRails.core.util.Loads

class CorruptedDataException(val data: Loads<*>) : Exception() {
}