# 未完成道岔：接点成员与合法通路分离

## 授权与边界

用户已在确认完整方案后回复“可以”，授权实现；随后再次明确“继续不创建任务”。本文件记录实现与验证，不创建或激活 Trellis task。

目标：允许先创建两条直线及渡线、之后补公共腿，建造顺序不改变最终合法通路。未完成接点可保存、重载、撤销和重做。同侧岔后腿不互通；公共腿补入后可通向直股或侧股。

不在本轮部署、改写服务器存档、自动提交、修改既有曲线几何、改变 5 mm 坐标容差、或重构不相关的列车骤停/显示模块。保留仓库已有 dirty 工作。

补充授权：2026-11-09 用户回复“提交”，授权将本次实现、测试、规格与本记录提交到本地 Git；不包含其他并行改动。

## 当前问题与实证

`RailWand.connectSelectedAnchors` 把所选端点对直接声明为下一段，Core 再从这些合法接续反推出共同接点，无法表示已有两条同侧腿而公共腿缺失的状态。测试服副本中 `62a466ff:B` 与 `7f41ca18:A` 被载入为普通双段连接；离线实际 `forward(0.02)` 可从前者进入后者。

副本：`/tmp/jr-rail-save-audit-20261109/`。主世界 113 rails、5 trains，88 个已有显式端点组件（54 双端、34 三端）。只对已有显式关联求组件，不根据空间相近连接无关轨道。

## 设计

Core 保存独立的显式端点组成员，`RailView.nextRails` 仍只表示可通行接续。为现有 SavedRailV1 增加必填的 `joinedEndpointsAtA/B`；`connectionsAtA/B` 保持精确有序合法通路含义。current reader 严格校验成员完整对称、合法通路与成员/几何一致以及 active 属于合法出口，不在 runtime 猜测旧开发数据。

`TopologyInput` / `TopologyState` 带独立端点组。两端点同侧且没有合法通路的组保留为现有 Turnout 节点，不添加新节点子类型。`endpointGroupAt(port)` 提供不可变精确成员；group-only 编辑也更新 revision、影响/占用保护、stale 检查与事务回滚。

统一方向规则：从接点向轨道内部的单位切向量在 A 为 AB tangent，在 B 为其负值；仅点积严格小于零的两腿可接续。该规则区分同侧/异侧，不新增全局 1° 限制；既有曲线平滑性校验仍归生成器。Core 提供同一纯函数供生产与离线迁移复用。

`captureExplicit` 的 selected endpoint pairs 表示明确的组联结，自动扩展完整已知组；批内分段、替换映射、删除、restore 都在 Core 所有者事务内处理。仍然合法的 active 保留；新增连接沿用既有合法默认选择规则。精确 restore 保留明确 null。

RailWand 只读成员组并提交用户明确选择。端点编辑、标记、未完成提示、自动坡度平滑与 Basic TurnoutTopology 都要识别未完成组。首次同侧支腿创建也不能让自动坡度平滑重建折返链。undo 保存完整 current snapshot。

离线迁移从旧显式连接图求完整端点组件，再调用 Core 生成合法通路；不改 rail UUID、curve JSON、runtime bytes、Basic/SimpleATO 数据或 revision/epoch。保留合法 active 与原 null；旧 active 无效且有唯一出口可确定修复，无出口置 null，多出口歧义拒绝。先 dry-run、真实 Core/Basic/SimpleATO restore 校验；写入只允许新目录。此轮仅对副本执行 dry-run。

开发格式保持当前约定版本，不因开发提交递增；不添加旧输入 runtime fallback。Core 改动与迁移工具的字段变化同步。

## 分工

- Core runtime/API、snapshot、prepared transactions 与 Core 测试：junction_core。
- RailWand、Basic TurnoutTopology、i18n、undo 与消费端测试：junction_editor。
- scripts/offline-world-migration、独立测试、Gradle migration 开关、工具文档：junction_migration。
- 协调、跨模块检查、补充回归、相关 specs、最终验证：主会话。

## 验证

- 双同侧腿 → 零合法通路但保留成员；从任一腿补公共腿 → 同一结果。
- AB/BA、坡度、三腿正反向、显式 null、无关同坐标轨道不自动合组。
- 删除公共腿恢复未完成状态；替换/切分/重建保留成员；group-only stale 与 rollback。
- 未完成时 save/load、undo/redo；寻路不能使用同侧通路；真实物理点推进不进入同侧腿。
- Core、Basic、Bukkit 受影响测试及下游 compile；必要 Dokka/API 检查。
- 主世界副本 migration dry-run 与真实完整模块 restore，输出变化报告，服务器原档不变。

## 进度

- [x] 用户确认方案；代码与存档证据复核。
- [x] 模块 ownership 和 Core API 方案确认。
- [x] Core 实现与回归。
- [x] 编辑器、Basic 与 undo 集成。
- [x] 离线迁移与副本 dry-run。
- [x] 全范围质量检查、spec 同步和交付；Bukkit 既有失败单独记录如下。

## 审查补充

独立复核后修正三项生产边界：merge 判定须包含同组但无合法通路的支腿；live capture 拒绝稀疏、非完整的成员声明，避免写出不能重载的快照；direct/prepared 的默认选路不依赖未持久化的隐含标记。精确恢复仍保留 null，新增首条合法出口时沿用同一默认选择语义。

RailWand 的 mapped snapshot restore 同时比较成员与合法出口，不能把两个出口均为空、成员却不同的接点视为同一状态。Core、Basic、RailWand、离线迁移与相关规格已经过全链审查。

## 验证记录

- Core 全量：569 项通过。
- Basic 全量：494 项通过。
- SimpleATO 全量：826 项通过，包含新增未完成接点的手动防护回归。
- SimpleSignals 全量：682 项通过。
- Bukkit 全量：762 项，1 项失败、1 项跳过。本次工具、undo 和迁移回归均通过；唯一失败是 `ProgramExpressionsTest.one string parameter composes into destination station depot and display fields`，expected `[central@1]`、actual `[]`。既有记录见 `.trellis/tasks/11-08-simpledisplays-train-integration/research/stage1-validation.md`，该测试与 scriptworker 生产目录自本轮 HEAD 无改动。本次未扩大修改范围，不宣称 Bukkit 全量绿色。
- TunnelBridgeBuilder：31 项通过；WebUI backend：67 项通过。
- Core/Basic Dokka 生成通过；检查输出未发现本次新增 `endpointGroupAt` 的缺失文档或无法解析链接。已有大量其他 API 缺失文档 warning，未作为本轮扩展修复范围。
- `SimpleATOBukkit`、`BukkitPluginDev` 与 `BukkitPlugin:compileSignalApiConsumerJava` 编译通过。
- 离线工具已补齐首次解码前的生产 render extension 注册，并使用 fresh restored CoreWorld 的真实资源注册表验证、保存隔离时刻表依赖；不访问 Bukkit 服务或 live world，不绕过 checkpoint 拒绝。
- 三组迁移测试最终 20 项通过（Junction 9、Endpoint 6、Development 5）。新增回归覆盖真实非空 clock/run、路线依赖恢复、runtime 原字节保持，以及修复后原路线不可达时拒绝候选、不创建输出、不清空 checkpoint。成功或失败均关闭隔离资源注册表。

日志：`/tmp/jr-junction-final-core-basic-ato.log`、`/tmp/jr-junction-final-consumers-migration.log`、`/tmp/jr-junction-final-runtime-tests.log`。完整 Bukkit XML/HTML 报告在后续 focused tests 覆盖默认目录前保留至 `/tmp/jr-junction-bukkit-full-results-22dkqi_w`。

dry-run 配对源副本：`/tmp/jr-junction-migration-20261109-9g1sl2y9/worlds`。其 manifest 中的源/副本 size 与 SHA-256 已逐一复核，保持原值。其他五个审计世界缺少配对 runtime，不伪造数据或据此宣称完整恢复已验收。

## 真实主世界 dry-run

`/tmp/jr-junction-final-real-save-dry-run.log` 与最终 `/tmp/jr-junction-final-migration-gate.log` 均为 `BUILD SUCCESSFUL`、`Dry-run validated; no files written.`；最终门禁也完成上述 20 项迁移测试，覆盖隔离注册表清理。

真实 Core/Basic/SimpleATO restore 已验证 113 rails、5 trains 和非空时刻表 checkpoint。revision=454882、epoch=1904629；88 个接点组，其中 1 个未完成组；合法有向通路 244 → 242，删除 4 条错误有向通路、补回 2 条遗漏有向通路、修正 4 处 active。

- 删除 `62a466ff:B ↔ 7f41ca18:A`，保留两端的接点成员关系；该处恢复未完成状态。
- 删除 `7f41ca18:B ↔ e11eeffc:A`，补回 `0c47adc1:A ↔ 7f41ca18:B`，同侧两腿不再互通。
- active：`62a466ff:B` 与 `7f41ca18:A` 置 null，`7f41ca18:B` 与 `e11eeffc:A` 指向公共腿 `0c47adc1:A`。
- infrastructure SHA-256：`269795bcac7cfc48d5f08ac1c1dd7c20714feed94829b7c71315be907a9dcd7e` → 候选 `3e376bf085ceef4c5e72e16c6c3a4ae75e80c9075549fce06ed4b68aa9ff62bb`。
- runtime 源和候选 SHA-256 同为 `45d86adbe5f14b9872318c044e6d904062ac1bd1990ec9937b25ceae8737aaa5`，原始 bytes 未变。
- dry-run 后再次逐一复核原审计文件和副本的 manifest hashes，全部不变；源目录仍只有原始配对 2 文件。

实现与 dry-run 阶段没有写入迁移输出、改服务器、部署 JAR 或自动提交 Git；随后用户单独授权本地提交。不宣称 Folia/游戏交互和客户端画面已验收。
