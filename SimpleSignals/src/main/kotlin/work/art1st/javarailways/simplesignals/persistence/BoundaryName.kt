package work.art1st.javarailways.simplesignals.persistence

import work.art1st.javarailways.simplesignals.region.RailCutPosition

internal data class BoundaryName(val position: RailCutPosition, val name: String) {
    init { require(normalizeBoundaryName(name) == name && name.isNotEmpty()) { "Boundary name must be normalized and nonempty" } }
}

internal fun normalizeBoundaryName(input: String): String {
    require(input.codePoints().noneMatch { Character.isISOControl(it) || it == 0x2028 || it == 0x2029 }) { "Boundary names cannot contain control characters or line breaks" }
    val value = input.trim()
    require(value.codePointCount(0, value.length) <= 64) { "Boundary name is limited to 64 characters" }
    return value
}
