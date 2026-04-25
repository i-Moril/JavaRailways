package work.art1st.mineRails.core.api.util.exception

import work.art1st.configLib.persistence.Loads

class CorruptedDataException(val data: Loads<*, *>) : Exception() {
}