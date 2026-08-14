package work.art1st.javarailways.tunnelbridgebuilder.wand

import work.art1st.javarailways.tunnelbridgebuilder.algorithm.Carve
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.CrossSection
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.Ignore
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.Place
import work.art1st.javarailways.tunnelbridgebuilder.algorithm.SectionCoordinate

object WandCrossSectionFactory {
    fun <B> create(
        state: BlockWandState,
        resolveMaterial: (String) -> B
    ): CrossSection<B> {
        val anchor = requireNotNull(state.primaryRailCell) { "A primary rail cell is required" }
        val rows = (0 until state.height).map { row ->
            (0 until state.width).map { column ->
                when (val cell = state.cells[row * state.width + column]) {
                    WandCell.Ignore -> Ignore
                    WandCell.Air -> Carve
                    is WandCell.Material -> Place(resolveMaterial(cell.key))
                }
            }
        }
        return CrossSection(rows, SectionCoordinate(anchor.column, anchor.row))
    }
}
