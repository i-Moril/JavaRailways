# Package work.art1st.javarailways.simpleato.api.restriction

外部信号系统按列车发布的 namespaced、revisioned、冻结路线绑定限制。限制只能收紧 Basic 已有的速度、停车和移动授权约束；空的新 revision 用于显式撤销该来源的限制。

# Package work.art1st.javarailways.simpleato.api.signal

独立 Java/Kotlin 信号系统的同步 railway-epoch callback、静态监测区 source binding 与不可变输入/结果。空 epoch result 表示没有限制变化；撤销仍须发布更高 revision 的空限制 snapshot。
