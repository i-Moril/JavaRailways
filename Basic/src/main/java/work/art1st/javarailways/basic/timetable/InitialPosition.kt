package work.art1st.javarailways.basic.timetable

sealed interface TimetableObjectSelector {
    fun matches(id: String): Boolean

    data class Regex(val pattern: String) : TimetableObjectSelector {
        private val compiled = kotlin.text.Regex(pattern)
        override fun matches(id: String): Boolean = compiled.matches(id)
    }

    data class And(val selectors: List<TimetableObjectSelector>) : TimetableObjectSelector {
        init { require(selectors.isNotEmpty()) }
        override fun matches(id: String): Boolean = selectors.all { it.matches(id) }
    }

    data class Or(val selectors: List<TimetableObjectSelector>) : TimetableObjectSelector {
        init { require(selectors.isNotEmpty()) }
        override fun matches(id: String): Boolean = selectors.any { it.matches(id) }
    }

    data class Not(val selector: TimetableObjectSelector) : TimetableObjectSelector {
        override fun matches(id: String): Boolean = !selector.matches(id)
    }

    /** Reserved persistence node for a future tag index; not exposed by the current JS DSL. */
    data class Tags(val required: Set<String>) : TimetableObjectSelector {
        init { require(required.isNotEmpty() && required.all(String::isNotBlank)) }
        override fun matches(id: String): Boolean = false
    }
}

data class TimetableInitialPosition(
    val trainSelector: TimetableObjectSelector,
    val wayPointSelector: TimetableObjectSelector
)
