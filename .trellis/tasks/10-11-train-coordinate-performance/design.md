# Technical design

## Geometry
以现有 connectorPosition 为共同坐标语义，车内残差为三维连接点间距减配置轴距；车间残差维持真实车钩长度与正确纵向侧。领车本 tick 目标固定，复用上一位置及沿轨位移作为候选，局部修正后验证残差。优先使用有区间保护的割线/局部修正，无法可靠收敛时使用有界 bracket/bisection；不为追求固定运算次数容许跨 tick 残差。先测正确性再确定具体求根细节，不承诺严格 O(1)。

保留车间外层搜索、车内内层求解的结构，优先复用上一位置/候选；仍可能有嵌套计算，不声称完全消除。避免简单恢复全局嵌套二分：车内距离求解与车间跟随必须共享已确定 train-local 路径证据，复用候选几何；任何缓存只属于本次解算且不得改变线程 ownership。不得利用反向 RailView 的独立分岔选择猜测运行路径。显式摆位允许现有独立搜索入口，但收敛后遵循同一距离约束。

车体中心保留配置 offset 的直线含义，从已经满足轴距约束的端点重建；不采用上一 chat 中已撤回的比例投影近似。误差预算保证最终 published 轴距及车钩误差均在5mm内，而非每层单独用5mm后累加超限。

## Persistence and publication
修正 CoreTrain 恢复校验的车内距离条件，保留路径顺序、方向和端部 offset 约定。无 JSON 字段/版本变更，不添加旧弧长模型兼容分支。已阅读 shared/versioning.md 和 ai_tasks/persistence_schema_evolution_rules.md；本任务未获授权保留旧开发存档，若发现格式层面改动需求须返回规划。
维持 existing epoch barrier、motion state、失败清理和 conservative occupancy 合同。求解失败沿现有 PhysicsException/derail 边界处理，不把残差不合格的候选当作成功结果；不新增整个编组运动的事务回滚保证。

## Measurement
用当前 Core headless harness；如需增加几何正确性断言或额外紧曲线场景，before/after 使用同一测量代码，新增正确性门禁与旧实现性能测量分开。基线先在当前代码实测并保存，随后运行 fixed，再以保存的 baseline 源码/隔离 checkout 补必要交错重复；不覆盖工作区 dirty 内容。所有构建、测试与基准串行，避免共享 build 目录和性能干扰。
记录 HEAD、源码 diff/hash、JVM/CPU/内存/可用核、各次参数及原始 JSON/CSV/Markdown。默认矩阵见 PRD；50ms仅为本机 headless p95预算，不代表 Paper/Folia、网络、真实ATO/Dispatcher 或生产零超时保证。

## Rollback
生产修复、回归和压测报告独立可审查；通过 baseline 源码和 Git diff 回溯，不回滚无关改动，不修改开发服数据。

## User scope clarification
2026-10-11 用户明确认为极紧曲线可忽略。接近半圆轴距的非单调多根完整性不作为验收条件；保留预测+局部区间+割线/二分，不加入针对该极端的有限差分/回溯搜索。正常R30、S弯和坡道仍须满足5mm。
