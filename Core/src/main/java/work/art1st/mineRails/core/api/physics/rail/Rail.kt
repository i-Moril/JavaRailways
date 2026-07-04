package work.art1st.mineRails.core.api.physics.rail

import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.render.RenderableExtension
import work.art1st.mineRails.core.api.util.plugin.Extendable
import work.art1st.mineRails.core.api.util.Destroyable
import work.art1st.mineRails.core.api.physics.curve.PrimeCurve
import java.util.UUID

interface Rail : Destroyable, Extendable<RenderableExtension<Rail>> {
    val id: UUID
    val world: World
    /**
     * 获取铁轨的曲线（AB 方向）。
     * 这是铁轨的几何形状定义，用于渲染和物理计算。
     */
    val curve: PrimeCurve
    fun getView(direction: ViewDirection): RailView
}
