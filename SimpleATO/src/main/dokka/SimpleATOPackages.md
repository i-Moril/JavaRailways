# Package work.art1st.javarailways.simpleato.api.ato

SimpleATO安全参数、controller skeleton、planner、world-local Stall coordinator 与结构化诊断。Coordinator 同时驱动 Basic 的通用物理 safety interlock。

# Package work.art1st.javarailways.simpleato.api.constraint

owner-scoped 的速度、停车、跟车和移动授权约束，以及取最严格限制的 manager。

# Package work.art1st.javarailways.simpleato.api.mission

从稳定 WayPoint 引用解析、按 revision 提交并在 physics Tick 边界激活的不可变 ATO Mission 契约。

# Package work.art1st.javarailways.simpleato.api.route

SimpleATO跨限制与资源检查共用的常量大小冻结路线绑定。它只携带路线身份、路线revision、拓扑revision和总长度；完整有序traversal仍由host持有。

# Package work.art1st.javarailways.simpleato.api.line

SimpleATO拥有的运营线路身份、上下行站序、revisioned world-local store与基于Basic通用最短路能力的线路编译结果。
Station与Destination由同world的SimpleATO canonical stores提供；Basic只接收显式WayPoint和方向执行通用寻路。

# Package work.art1st.javarailways.simpleato.api.destination

SimpleATO拥有的stable directed Destination、普通/StationBerth/DepotStablingPoint角色，以及原子维护Basic WayPoint和SimpleATO Depot/Station投影的world service。

# Package work.art1st.javarailways.simpleato.api.station

SimpleATO拥有的Station聚合、被动StationBerth RailObject、StationCall/DwellPlan、主线程解析和不可变停站通知。

# Package work.art1st.javarailways.simpleato.api.depot

SimpleATO拥有的Depot/StablingPoint、RollingStockRecord、occupancy/reservation、确定性配车和Stall-only原子Depot Reset ledger。

# Package work.art1st.javarailways.simpleato.api.inspection

外部信号系统注册不可变`InspectionSource`；host在选定production route后把exact train/`RouteSnapshot`交给binding provider，插件返回该路线的完整资源检查声明，不自行猜测路线。每份新binding先执行一次完整snapshot baseline，此后同步更新接口只在声明首次进入检查范围或显式dirty时调用。声明包含稳定身份、具体轨道/方向、路线内触发位置和提前检查距离；当前速度、动态制动距离、激活状态和共享perception由SimpleATO运行时负责。

# Package work.art1st.javarailways.simpleato.api.restriction

外部信号系统按列车发布的 namespaced、revisioned、冻结路线绑定限制。限制只能收紧 Basic 已有的速度、停车和移动授权约束；空的新 revision 用于显式撤销该来源的限制。

# Package work.art1st.javarailways.simpleato.api.signal

独立 Java/Kotlin 信号系统的同步 railway-epoch callback、静态监测区 source binding 与不可变输入/结果。空 epoch result 表示没有限制变化；撤销仍须发布更高 revision 的空限制 snapshot。
