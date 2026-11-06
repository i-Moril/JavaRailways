# 修复边界（已获用户批准实施）

## Basic：端点保真（R1）

主要修改 Basic/src/main/java/work/art1st/javarailways/basic/route/TopologyRoutePlanner.kt。
图搜索继续使用合并边距离，但输出已知端点时保留输入坐标，不用反算结果替代；验证 AB/BA、起终点、普通边、路径替换及方向 marker。
不给 Basic 引入站台业务，不改寻路策略或另建坐标框架。

## SimpleATO：路线适配、停站与 Mission 衔接（R1/R2）

以下路径均相对 SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/：

- routing/OperationalRoutePlanner.kt：检查正长度段适配不破坏 Basic 端点契约，必要时修改；不能靠随后被过滤的零长度 marker 修复。
- timetable/RetainedTimetableRoutes.kt：检查站台前缀截取，避免以近似接纳加截断改变实际站台坐标。
- timetable/TimetableDispatcher.kt：分离停站容差判定和坐标换算，检查停站距离解析及真实停车位置到下一 Mission 的衔接。

站台可以在 Mission 中途，不能一律把站台距离当成 Mission 总长。优先复用既有前缀/路线信息，不新增通用位置证明系统。停车允许偏差不应污染任意信号边界投影。

## SimpleATO：真实进度（R2/R3）

主要修改 routing/MissionRouteProgress.kt，取消用停车容差接纳旧段并截回端点的行为，保留重复轨道的正确出现次数选择。
关联检查 controller/SimpleATO.kt 和 routing/FrozenMissionRouteAssembler.kt 的物理路线、Mission、限制路线之间的换算，仅修有证据的误用；不自动删除所有 coerceIn。
数值误差处理必须有具体算术来源和回归，不用 0.3m 停车容差充当数值误差预算。

## 不修改的模块与风险

SimpleSignals、BukkitPlugin 当前仅作为集成验证对象；Core、WebUI、存档格式不变，无迁移。保留已有滚动申请修复。
直接把所有投影改成严格匹配会破坏停车后衔接，既有草稿和规范需按本任务重新收敛。
实施后同步 Basic route-planning、SimpleATO ato-policy-runtime 及实际受影响的 dispatcher/operational-routing 契约。
