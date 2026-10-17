# Persist exact target rail endpoints

## Goal

保存连接及当前选中分支的完整目标轨道 UUID + A/B 端点，使普通加载、结构编辑快照和撤销恢复不再依靠几何猜测端点。

2026-10-17用户确认聚焦此问题，并同意沿用任务进入规划；提醒开发服位于远端192.168.1.230。
用户随后批准最终收窄方案，任务已进入in_progress；远端应用和提交仍按各自门槛处理。

## Confirmed background

- 源码基线：dev/signal-system，HEAD 3fc32374，工作区有其他任务未提交改动，必须保留。
- CoreRail.kt:94-114的writer与SavedRailV1只保存目标UUID，连接及active字段均丢失目标端点。
- SavedRailEndpointDecoder.kt:21-41已统一严格解析UUID/存在性/距离，但仍按目标两端距离选A/B；不是旧文档所述静默mapNotNull现状。
- PreparedRailWorldSnapshotPlan.kt:252-274复用单端decoder并额外校验全图对称；CoreWorld.kt:144-188普通加载先创建world/rails再恢复连接。
- CoreWorld.kt:695-735的prepared target snapshot再次把精确RailEndpointRef降为railId；PreparedRailTopologyUpdate.kt:118及Bukkit PreparedRailWorldUndo、IsolatedTimetableSimulationFactory.kt:110-115也消费该DTO。
- 10月16日旧归属迁移和受限曲线修复已完成，本次远端只读复核确认outer2/Basic2/SimpleATO1，不再把这些事项视为待办。
- 远端6个世界的current与既有.bak共12对均仍为UUID-only Core infrastructure2。主世界每份106条rail、234条有向连接；按现有5mm容差目标端点全部唯一，四种组合均存在，无不对称或非递增arc。本次是文件结构/几何核对，非原子停服快照或current Kotlin业务restore。
- 详细路径、哈希与局限见[核对记录](research/endpoint-save-audit-2026-10-17.md)。旧规划保留在[历史计划](research/planning-before-2026-10-17.md)，不继续授权其中打包自动迁移。

## Requirements

1. 当前保存模型的每条连接及非null active都持有railId和明确A/B；source侧由所属A/B字段表达，连接顺序、合法null及分支选择必须保留。
2. 当前读取与普通/撤销恢复按声明端点恢复；几何仅验证声明两端在既有Curve.TOLERANCE内共点，不得替换端点或选首项修坏数据。
3. 共享Core端点验证逻辑拒绝非法/重复身份、未知端点、缺失rail、重复目标、同端自引用、超容量、非对称连接、非法active与几何不匹配。普通world加载在创建live rails前验证完整连接图；不扩展为整个world所有子系统的通用rollback工程。
4. 所有当前producer/consumer一次性切换，包括prepared target snapshots、结构撤销、隔离时刻表模拟及固定fixtures。active身份比较必须含目标端点。
5. 按versioning政策直接修正未发布当前格式，保留Core infrastructure=2及其他边界现有版本；部署开发服不视为发布/冻结证据，不新增运行时旧格式reader。
6. 为现有UUID-only、已完成归属迁移的开发存档提供独立一次性离线转换：默认dry-run，只读源，输出到新目录；保留current/.bak文件配对、revision/epoch、连接顺序、active及其他业务数据。完整外部恢复点是正式应用前置条件。
7. 旧数据只能在离线迁移边界依据几何唯一确定端点；零或两个候选、非法引用或业务校验失败均拒绝整次候选，不静默丢弃/修复。转换结果必须通过新current严格codec及真实恢复验证。

## Acceptance

- [x] writer与固定JSON明确保存目标端点；A-A/A-B/B-A/B-B、null及第二分支active准确恢复。
- [x] 普通load/prepared restore/undo共用验证并消费相同精确身份，无当前路径最近端点推断；损坏声明即使另一端靠近也拒绝。
- [x] prepared snapshot生成不再把端点降格为UUID；active-only恢复不改变拓扑root，preserve-active undo行为保持。
- [x] 连接图错误在live rails创建前失败；测试锁定失败无可观察的部分轨道图，不夸大到所有world资源。
- [x] 固定旧输入转换覆盖唯一/零/双候选、active、顺序、对称性及源字节不变；current显式输入可识别且不二次转换。
- [x] 远端新取得的只读副本全部通过离线dry-run、新codec及真实restore，逐对报告连接身份/数量及revision/epoch保持；不把在线读取当停服备份。
- [x] 受影响模块测试、独立检查及打包门禁通过；JAR不包含旧输入转换器。

## Out of scope

不重做outer/Basic/SimpleATO归属迁移、旧曲线修复或物理图算法；不新增全局连接catalog，不改变连接容量或运行时自动连接策略，不以优化tick性能为目标。
不实现自动启动迁移或插件内旧开发reader，不停服、不写远端、不部署。实际应用另获授权，并先正常停止写入、整目录外部备份和复核新源。
