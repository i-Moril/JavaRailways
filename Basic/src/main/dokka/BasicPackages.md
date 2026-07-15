# Package work.art1st.mineRails.basic.api.ui.config

面向平台 UI 的类型安全配置项、约束、快照与玩家会话接口。当前属于实验性 API。

# Package work.art1st.mineRails.basic.api.ato.snapshot

ATO 规划输入使用的不可变 Route 与 Perception 快照。集合在构造时防御性复制。

# Package work.art1st.mineRails.basic.api.ato.command

主线程或插件向物理 worker 提交命令的 revisioned queue。payload 必须由调用方保证不可变。

# Package work.art1st.mineRails.basic.api.ato

ATO Planner、控制需求、车辆 Actuator、固定安全参数和按 World 隔离的 Stall 协调器。

# Package work.art1st.mineRails.basic.api.ato.constraint

SimpleATO 使用的 owner-scoped 速度、停车、跟车和移动授权约束。

# Package work.art1st.mineRails.basic.api.ato.mission

从稳定 WayPoint 引用解析、按 revision 提交并在 physics Tick 边界激活的不可变 ATO Mission 契约。

# Package work.art1st.mineRails.basic.api.ato.provider

可选单一信号 Provider、Provider Boundary RailObject 和 world-local registry。

# Package work.art1st.mineRails.basic.api.station

Phase C2 的 Station 聚合、被动 StationBerth RailObject、StationCall、DwellPlan 和 world-local store。
