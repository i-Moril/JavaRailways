# 修复路线端点坐标、停车容差误用与位置截断

## Goal

保留路线已知端点和列车真实位置，消除越过信号后仍被投影到信号处的问题，同时保留正常停车允许的偏差和连续发车能力。
2026-11-06 用户先授权创建任务和规划，随后明确回复“可以，允许执行”，批准本任务规划并授权实施。同日用户要求先完成本任务，再准备运行线模拟及停站语义的新任务；按提交确认与归档流程收尾，不部署。

## Requirements and evidence

- R1：已知起终点不应由累计距离反算重建。Basic 的 TopologyRoutePlanner.kt:681 保存原始坐标并换算图边距离，:726、:784 反算轨道坐标。既有重放中站台 AB=109.1008889681433，Mission 末段端点=109.10088896814318。:175 后的端点处理采用近似判等；SimpleATO 的 OperationalRoutePlanner.kt:72 还会过滤零长度 marker，不能仅追加 marker 就声称修好端点。
- R2：停车容差只用于停车语义，不用于信号前后位置、路线段归属或证明几何连续。保留真实停车允许偏差，不全库删除数值 epsilon。
- R3：移除篡改真实位置的截断。基线 MissionRouteProgress.kt 用容差接纳越界位置再截到段端点；既有重放中车头越过信号约 0.122m，却仍报告信号处进度。不得通过忽略红灯或放宽 ATP 修复。
- 关联检查：TimetableDispatcher.kt:923 停站距离、:6550 起实际车头与路线衔接；RetainedTimetableRoutes.kt:44 停站前缀；FrozenMissionRouteAssembler.kt 与 controller/SimpleATO.kt:804 进度消费。命中 coerceIn 不自动等于缺陷，区分合法裁剪和真实位置截断。

## Acceptance Criteria

- [x] AC1/R1：多轨合并边的已知起终点不因往返换算漂移；覆盖 AB/BA，验证 SimpleATO 适配后仍满足端点契约，不靠放大容差通过。
- [x] AC2/R2/R3：相邻段、信号前/处/后、重复经过同轨均报告正确进度；实际过信号后不能截回旧段端点。
- [x] AC3/R2：停车容差内停站、普通再次发车、折返衔接不回归；不要求列车恰好停在目标坐标。
- [x] AC4：相关模块测试通过，原时刻表与真实资产离线重放不再出现原 Tick 302/5403 故障，并报告完整重放结果。离线通过不等于服务器/浏览器验收。

AC4执行情况：模块测试通过，原故障点通过，完整报告重放在Tick10026以DelayExceeded结束。后续对照已确认折返两tick完成，延误主要来自运行线预览与实际执行环境差异，以及停站时间口径和终到计划冲突，交后续任务处理。AC4按原文的原故障回归及结果披露验收，不等于全周期模拟成功；用户已要求据此完成本任务。详见 verification.md 和 research/delay-10026-accounting-semantics.md。

## Out of Scope

不改信号自占用规则、ATP 安全政策、停车容差数值、WebUI、存档或迁移；不新增资源兜底、寻路体系、坐标框架；不全库机械删除容差/截断。

## Existing Draft and Risks

基线 HEAD=97de29ca。既有未提交 MissionRouteProgress.kt、MissionRouteProgressTest.kt、ato-policy-runtime.md 是待修正草稿，不能把“全部严格匹配”当成已确认方案。前轮投影单测 9 项通过，但 SimpleATO 767 项中 15 项失败，全局重放 Tick 302 失败；本轮未重跑。其他 dirty/untracked 文件保持用户所有权。
无待用户决定的产品问题；数值边界和具体修改点以实施阶段回归证据收敛，扩大范围须再确认。
