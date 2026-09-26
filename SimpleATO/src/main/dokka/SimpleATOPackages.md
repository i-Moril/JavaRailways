# Package work.art1st.javarailways.simpleato.api.route

SimpleATO跨限制与资源检查共用的常量大小冻结路线绑定。它只携带路线身份、路线revision、拓扑revision和总长度；完整有序traversal仍由host持有。

# Package work.art1st.javarailways.simpleato.api.inspection

外部信号系统为一列车和冻结路线预编译的不可变资源检查声明。声明包含稳定身份、具体轨道/方向、路线内触发位置和提前检查距离；当前速度与动态制动距离由SimpleATO运行时负责。

# Package work.art1st.javarailways.simpleato.api.restriction

外部信号系统按列车发布的 namespaced、revisioned、冻结路线绑定限制。限制只能收紧 Basic 已有的速度、停车和移动授权约束；空的新 revision 用于显式撤销该来源的限制。

# Package work.art1st.javarailways.simpleato.api.signal

独立 Java/Kotlin 信号系统的同步 railway-epoch callback、静态监测区 source binding 与不可变输入/结果。空 epoch result 表示没有限制变化；撤销仍须发布更高 revision 的空限制 snapshot。
