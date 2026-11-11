package work.art1st.javarailways.simpleato.testing

import work.art1st.javarailways.core.api.physics.train.TrainComposition
import work.art1st.javarailways.core.api.physics.train.TrainUnitComposition

/** Constructs Core-owned test membership before the train is created, never an ATO registry. */
fun testTrainComposition(vararg units: Pair<String, List<String>>): TrainComposition =
    TrainComposition.create(units.map { TrainUnitComposition(it.first, it.second) }, units.flatMap { it.second })

fun testPerCarComposition(count: Int): TrainComposition = testTrainComposition(
    *(0 until count).map { "unit-$it" to listOf("car-$it") }.toTypedArray())

fun testGroupedComposition(count: Int, groupSize: Int): TrainComposition = testTrainComposition(
    *(0 until count).map { "car-$it" }.chunked(groupSize).mapIndexed { index, cars -> "unit-$index" to cars }.toTypedArray())
