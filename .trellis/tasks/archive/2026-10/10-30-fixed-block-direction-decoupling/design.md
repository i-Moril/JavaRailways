# 技术设计：闭塞保护与轨道方向解耦

## 1. 状态与所有权

本文为已批准的实现设计，需求见 [prd.md](prd.md)，源码依据见 [research/current-behavior.md](research/current-behavior.md)，验证状态以实施清单和结果记录为准。

- SimpleSignals：闭塞几何、逻辑入口、信号扫描及附加闭塞资源。
- SimpleATO：运营路径、停车余量、自动运行方向验证与控制约束。
- Basic：唯一公共方向配置、冻结路线物理校验、原子准入、grant、换向交接和车尾释放；Core：物理拓扑和唯一资源 registry。本批不修改两者运行代码或公开 API。

不新增资源管理器、方向事件总线、可写方向副本或逐 tick 路线搜索。

## 2. 删除闭塞权限，保留有向几何

删除 `FixedBlockDefinition.bidirectional` 及其保存字段、专用 Boolean serializer、创建提示和消费者条件分支。

`BlockPathBuilder.definition` 不再根据轨道方向决定闭塞能否创建，也不再依方向倒转路径。新闭塞保存几何选择所得顺序；已有定义保留原顺序，不在加载或方向编辑时重排。`FORWARD/REVERSE` 相对于保存路径，已有信号不会交换入口。

`CompiledFixedBlock` 始终派生反向路径，两端 `entryTraversals` 均参与编辑影响计算。`FixedBlockRouteMatcher` 对所有闭塞识别两方向，移除只为权限过滤服务的 `detectReverse` 分支；内部起终点、真实重复经过、中途非法离开和歧义校验继续成立。

反向几何不授权自动逆行。道岔／平交冲突边界、路径唯一性、连续性、长度和重叠约束保留。

## 3. SimpleATO 方向验证与控制边界

### 3.1 公共配置与内部计算

沿用 Basic 的 `OperatingDirectionStore`、完整子集读取及 `OperatingDirectionOverlay.toRestrictions`。SimpleATO 增加／提取小型内部纯计算，使用同一许可规则：完整可信存储中无单向记录允许两向，有记录只允许相应方向；缺轨道、缺条目或过期读取直接失败。

按一次有效方向读取验证输入冻结路径，不引入第二份可写方向配置。方向结果绑定精确 frozen route、世界和拓扑；缺少结果不得表示无限许可。

### 3.2 四个不同位置

以下都使用现有路线米数，不是新的 Basic 授权类型：

- 运营停车点：现有运营终点扣除有效停车偏移。
- 必需合法末端：真实停车点加全局安全距离；必要的运营前缀和车身路径也必须合法。
- 物理授权末端：SimpleSignals 补全后的闭塞出口，仍交给 Basic 申请完整资源。
- 方向许可末端：完整有向冻结路径上第一个禁止方向的起点；全程合法时为路径末端。

验证运营前缀、换向后的车身相关路径和停车余量均合法。车头已在双向段但反向后的车身路径仍涉及单向段时，不能跳过车身部分。失败不发布新可运行候选，原保护保留。

### 3.3 终端补全不能掩盖方向失败

当前 `completeTerminalRoute` 在插件接管终点时不一定使用宿主余量候选的 failure；检查最终总长度不足以证明方向合法。最终路径确定后，必须对实际行驶前缀及安全余量做明确验证，覆盖原路径、已有后缀复用和新后缀，不能只检查 newly appended segments。

纯保护后缀可以延伸到方向许可末端之后，以获得完整闭塞及必要物理资源；它不能扩大 ATO 的合法行驶边界。拓扑连续性、保护区域进路和未选道岔限制继续按现有规则校验。

### 3.4 保留真实 grant，使用现有限制管线

`RouteAuthorityAdmissionClient` 如实保留 Basic 返回的 `committedAuthority`，不把它改写成运营终点，不向 `RouteResourceIntent` 或 grant 添加字段。

将方向许可末端作为内部、与当前 frozen binding 一致的附加结果传入客户端。在既有 authority source 的同一 `TrainRestrictionSnapshot` 中增加独立 ID 的 `MovementAuthorityRestriction`，保留原物理授权 restriction、速度上限及接触授权规则。

既有 `TrainRestrictionConstraintAdapter` 和 `MinimumConstraintPlanner` 取最严格结果；`MovementAuthorityConstraint` 已使用统一 safetyDistance 制动。因此长物理 grant 不会覆盖更短的方向边界，也不需要第二套限制 store、制动器或 Basic 运行模式。

方向结果必须完整传递到候选、接续、后缀扩长、grant adoption、pending 到 ready、换向 rebase 等入口。pending／拒绝不能提前换入候选的宽松限制。旧物理前缀和方向限制的坐标重绑定一起验证；形成／连挂特殊路径保留原有接触证明，不得借新限制绕过。

### 3.5 更新与无信号插件场景

方向计算只在选路、冻结、补全、接续、换向重绑定和相关变更时执行。复用现有 `changedOperatingDirectionRailIds` 及路线 rail 索引定向失效／重编；未完成时拒绝放宽并保留保护。完整物理后缀仍纳入冻结路线和静态时刻表编辑保护。

方向限制归 SimpleATO，不依赖 SimpleSignals source 存在。现有存储通知在释放 Basic 锁后立即通过 InspectionRuntime 的 rail 索引失效旧绑定；信号 epoch 仅分发合并后的来源通知，不得再次失效编辑后新建的合法绑定。不能用假闭塞 source 或全世界扫描补洞。正常 physics tick 仅消费已有不可变约束。

## 4. SimpleSignals 移除整块方向政策

删除 `FixedBlockDirectionPolicy.disabledBlocks/normalWindows` 的单双向比较及组合注入，清理 plugin、world runtime、隔离仿真 feature、Java consumer 和测试接线。

- contributor 按几何匹配贡献同一个 block ID，按本次已申请窗口的最后出口计算释放边界。
- inspection 按 `window.reversed` 选择真实入口；内部起步只保留既有 authority floor，不虚构车后信号。
- `SectionOccupancyRuntime` 删除旧策略产生的整块 blockControls；拓扑不可用、等待基线、source 异常、占用和规则失败的收紧行为保留。
- 直接调用 Basic／contributor 取得资源，不是运营方向合法的证明。自动运行资格由 SimpleATO 的合法候选与方向约束保证，不把资源回调变成第二个运营规划器。

## 5. 两端入口与信号扫描

入口继续以 `(blockId, FORWARD/REVERSE)` 标识，每端最多一台主信号。选择要求点击真实入口并朝内，保留 0.75m 容差和歧义拒绝；模型位置和旋转不改变逻辑入口。

`FixedBlockSignalEntranceResolver`、`FixedBlockSignalCompiler` 与 Signal Wand rebind 不再按单双向过滤。`SignalObservationGraphBuilder` 始终建立两个几何方向，各端按自己的规则扫描下游；共享占用与资源，不共享主信号输出。

无主信号不免除资源和方向校验；有配置但缺状态／异常时，现有零速或不可用行为保持。信号允许通过不能覆盖 ATO 的方向边界。

## 6. 存档与开发数据

仓库当前规范将 SimpleSignals sidecar 定义为未发布 schema 1。遵守版本前置规范：修改当前模型、writer、fixture 和文档并保持 1，不因删字段升版；Basic 方向文件和其他边界不改版。

删除闭塞 `bidirectional` 和专用 serializer，reader 继续严格拒绝未知字段。保留信号 `entryDirection`、ID、revision、遍历顺序和 placement，不用 unknown-key 放行、legacy alias 或旧 flag 默认值兼容开发文件。

部署前如发现必须保留的旧开发 sidecar，提供一次性转换：只删除闭塞旧字段，保留其余内容；校验目标数据，dry-run 输出差异与文件 hash，apply 前建立不可覆盖恢复点；primary／backup 分别验证。未知布局或损坏数据拒绝转换。用户随后明确授权提交并部署；已按此门禁完成本次一次性转换，见 [results/deployment.md](results/deployment.md)，不新增运行时 reader。

如发现真实发布／冻结基线与当前规范不同，先修订版本和迁移设计，不猜测升版。

## 7. 核心示例与反例

两条普通连接的轨道组成同一闭塞：左侧 A 仅向右，右侧 B 双向；车站与折返点均在 B，换向时整列车在 B。

- B 内去程停车、换向并返回 B 内车站：允许。回程保护后缀可延伸至 A，但方向边界位于 B/A 连接处。
- 返回目标合法而停车余量进入 A：拒绝；保护后缀覆盖 A 不等于余量合法。
- 回程目标位于 A，或反向时车身仍涉及 A：拒绝相应自动运行候选。
- B 内停车换向后闭塞仍占用；再次沿合法方向驶出，真实车尾清除后才释放。

## 8. 取舍、风险与回退

不以全路径方向交集决定闭塞类型，它仍会误伤双向部分内部折返。不新增 Basic 边界字段，使用现有 ATO restriction 管线独立收紧。

计算成本限于冻结路径及相关轨道，按现有索引路由变更，不增加每 tick 世界查询。主要风险：把保护后缀误作运营路径、余量 failure 被总长掩盖、接续／换向复用旧坐标、清理旧策略时误删真实失效门禁。

单线多闭塞协调仍为独立 TODO，不能声称两个几何方向已经解决整段对向放行。

模型、消费者、writer、fixture 和文档作为一致变更交付，不发布只删除创建检查的中间状态。代码整体回退；已转换开发数据依赖转换前恢复点，不添加降级 writer。用户既有脏文件不纳入任务。用户后续授权本地提交和测试服部署，不推送；相对于实际部署基线4c19f7f4，不修改Basic/Core，整合仅保留其已有功能。

## 9. 实施复核补充

独立检查进一步固定了既有流程中的三处边界，不改变 Basic API 或制动算法：

- 编组初始交接只提交真实车身路径／body-only grant，移动任务通过内部 terminal preparation 状态进入原 append／补全流程，不能因同 missionRevision 快速复用而跳过停车余量。
- 静态应用对结构输出 onward 路径的依赖快照做相同终端补全，认证的 corridor proof 不变；新增余量跨轨道时也先取得编辑保护。
- 滚动申请阈值取原 timing 距离和最大速度停车前视包络的较大者，避免默认5m安全距离把车停在旧4m申请阈值之外。已有制动曲线、全局余量配置和物理授权语义不变。

这些机制的最终 owner 合同见 `.trellis/spec/javarailways/simpleato/operating-direction-enforcement.md`，执行证据记录在本任务 results 中。
