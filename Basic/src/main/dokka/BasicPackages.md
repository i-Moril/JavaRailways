# Package work.art1st.javarailways.basic.api.ui.config

面向平台 UI 的类型安全配置项、约束、快照与玩家会话接口。当前属于实验性 API。

# Package work.art1st.javarailways.basic.api.ato.snapshot

ATO 规划输入使用的不可变 Route 与 Perception 快照。集合在构造时防御性复制。

# Package work.art1st.javarailways.basic.api.ato.command

主线程或插件向物理 worker 提交命令的 revisioned queue。payload 必须由调用方保证不可变。

# Package work.art1st.javarailways.basic.api.ato

ATO Planner、控制需求、车辆 Actuator、固定安全参数和按 World 隔离的 Stall 协调器。

# Package work.art1st.javarailways.basic.api.ato.constraint

SimpleATO 使用的 owner-scoped 速度、停车、跟车和移动授权约束。

# Package work.art1st.javarailways.basic.api.ato.mission

从稳定 WayPoint 引用解析、按 revision 提交并在 physics Tick 边界激活的不可变 ATO Mission 契约。

# Package work.art1st.javarailways.basic.api.ato.provider

可选单一信号 Provider、Provider Boundary RailObject 和 world-local registry。

# Package work.art1st.javarailways.basic.api.depot

Phase T3/T4 的 Depot/StablingPoint、RollingStockRecord、occupancy/reservation、确定性动态配车和 Stall-only 原子 Depot Reset。

# Package work.art1st.javarailways.basic.api.destination

Phase T2/T3 的 stable directed Destination、普通/StationBerth/DepotStablingPoint 角色和独立 WayPoint 服务。

# Package work.art1st.javarailways.basic.api.station

Phase C2/T2 的 Station 聚合、被动 StationBerth RailObject、StationCall/DwellPlan、主线程解析和不可变停站通知。

# Package work.art1st.javarailways.basic.api.operation

Phase C3 的平台无关语义列车操作、稳定 request、可确认 ticket 和主线程 Gateway 边界。

# Package work.art1st.javarailways.basic.api.infrastructure

Applied-plan dependency以persistent rail ID、stable topology-node key和runtime resource ID三类引用阻止相关编辑，
但不占用列车资源。Live claim使用独立的原子resource footprint；edit permit使用同三类影响集合并返回逐字段冲突诊断。

# Package work.art1st.javarailways.basic.api.observation

由同一个 Core world frame 投影得到的不可变铁路观察；物理 epoch、拓扑 revision、frame revision、列车位置、epoch末保守占用、整epoch扫过范围与有序边界穿越始终来自同一次发布。插件可用通用监测区定义获取带边界身份的`ENTER`/`LEFT`物理变化，但信号含义仍由插件拥有。

# Package work.art1st.javarailways.basic.api.route

受保护进路的有向边界、保护区域 provider/revision、冻结 route window、出口净空与组合原子请求。区域定义在注册或显式刷新时由主插件切分拓扑并检查内部唯一性/重叠；调用方只描述路径和区域，完整 infrastructure footprint 必须由主插件按权威拓扑解析。
