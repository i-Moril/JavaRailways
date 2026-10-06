# Research: directional fixed-block signals

- Query: 同一个双向闭塞如何共享资源/占用，但按真实有向入口独立计算规则、下游依赖和模型；最小修改面、持久化边界及性能成本。
- Scope: internal
- Date: 2026-10-06
- Source baseline: `dev/signal-system`, HEAD `6911087b fix(signals): require full-train exit clearance before entry`，本次直接核实。既有 TODO、docs-site、另一任务及未跟踪资产/bin 保留。

## Findings

### 1. 最小字段与身份方案

建议插件私有 `FixedBlockEntryDirection { FORWARD, REVERSE }`，增加
`FixedBlockSignalDefinition.entryDirection: FixedBlockEntryDirection = FORWARD`。
它相对作者的完整 traversal 顺序，不是全路径绝对 AB/BA；路径可能是 `A AB -> B BA`。
`FORWARD` 用 `block.traversals`，`REVERSE` 用现有 `block.reverseTraversals`。
后者已有倒序/反向几何，见 `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/block/FixedBlockCatalog.kt:64`、`:288`。

唯一键从 `controlledBlockId` 改为 `(controlledBlockId, entryDirection)`，每个真实入口至多一条规则；
signal ID 在全世界/设备种类间仍唯一。反向规则要求其 block `bidirectional=true`；这一作者约束必须在编译 retained/unavailable binding 之前校验，不能被拓扑失效分支吞掉。
保持 block ID、`SectionRef(FIXED_BLOCK, blockId)`、occupancy area 和 `simplesignals:fixed-block` token 原样。
不增加 entry resource、area、train-specific 图、扫描或释放队列。

检查声明应由真实入口身份派生，与该端是否配置规则无关，继续保存该入口的 trigger/floor。
建议统一前缀 `simplesignals:fixed-block/forward/<namespace>/<path>` 与
`simplesignals:fixed-block/reverse/<namespace>/<path>`，或同等无歧义的 typed-entry 编码。
不可仅追加 `/reverse`：作者 ID 允许 `/`，`test:x` 的反向会与 `test:x/reverse` 的正向碰撞
（`SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/region/ProtectionRegionDefinition.kt:7`）。
声明 ID 是可重建的检查身份，不是持久化的资源身份；原 protection-signal 前缀继续独立。

`FixedBlockRouteWindow.reversed` 已表示真实经过方向，入口轨道/facing 已正确按该字段选择
（`SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/block/FixedBlockInspectionSource.kt:43`）。
在低频 binding 阶段转换为 entry key；每 tick 按预编译 key/声明身份 O(1) 查结果，不能依据模型朝向或随意选同一 section 的第一个 signal。

### 2. 必须同时改的最小 consumer 图

以下全部路径以 `SimpleSignals/src/main/kotlin/work/art1st/javarailways/simplesignals/` 为前缀。

- `signal/FixedBlockSignalDefinition.kt`：当前定义没有方向（`:73`）；新增稳定 enum/字段及 copy/构造传递，继续防御性复制规则集合。
- `signal/FixedBlockSignalCompiler.kt`：当前 `:61` 校验一 block 一个 signal，`:119` 以 block ID 保存 lead time；改 entry-key 索引，配置存在性必须保留，不用 `leadTime>0` 判断配置。
  `:124` 的 `compileDownstreamPath` 目前硬编码 source/target 正向 traversal；应接受所选有向 traversal 引用和既有 length，反向端距离从其入口经过自己的 block 到该端出口，再抵达下游的对应入口。
  推荐小型只读有向几何视图或显式参数；不能复制/注册第二份 CompiledFixedBlock occupancy。
- `signal/ProtectionSignalCompiler.kt`：`:109` 的 `allGeometry` 把 fixed signal 都映射为 block 正向几何，需按 definition 方向映射；`:125` 保护信号到 fixed signal 的路径同样必须抵达对应端。
  fixed→fixed、fixed→protection、protection→fixed 三种组合都要用所选几何。
  `:178` 的 unavailable declaration 映射、`:183` 的 section→unavailable signal map 也不能把两端互相覆盖。
- `runtime/SectionSignalRuleRuntime.kt`：`:195` 的 section→single signal 索引、`:373` 的单控者验证和 `:366` 的 unavailableSectionSignals 参数是关键旧假设。
  同一 section provider 连接两条 signal consumer 本身已符合现有有向无环图；图不需要新算法。
  查结果宜用已存在的 `binding.declarationId` 建通用 declaration→signal 索引，或让 caller 先取得确定 signal ID；删除“一 section 永远一个信号”的语义。
  有效/不可用的每端都保留独立 declaration；不能让反向 unavailable 覆盖正向可用结果。
- `runtime/SectionOccupancyRuntime.kt`：`:208` 的 `signalById` 已 O(1) 且有旧/新 publication 连续性交接；可复用。
  `:220` 的 `signalFor(section)` 现在只返回一条规则，不再可用于无方向真实入口。
  全 block 的 `blockControls`（方向失配、相关拓扑未知等）仍优先于所选端规则；没有配置端也不能绕过全区禁行。
  `:354` 的 changedControls 当前只失效一条 block declaration，需要失效两个真实入口，或按 immutable allowed-entry 索引产生声明。
  共享占用变化同时触发两条规则；只有某端下游变化时只沿该端依赖传播。
- `block/FixedBlockInspectionSource.kt`：`:23` 的回调类型、`:47` 的 declaration、`:52` 的 lead time、`:80` 的旧 block-only decode 均须方向化。
  最近 active 声明归约（`:71`）保持按稳定声明身份线性处理，反向经过不与正向的另一规则合并。
  无配置端仍有原 floor/dependency，但不发布额外限速；配置端缺 baseline 发布 0。未来 epoch/world 校验保留。
- `signal/SignalInspectionIdentity.kt`：当前 encoder/decoder 只恢复 block ID，需 typed entry key；protection 前缀隔离保持不变。
- `runtime/SimpleSignalsWorldRuntime.kt`：`:308` source 接线，`:419` 状态查找，`:424` 配置存在性，`:429` lead-time 一致 publication 校验都要更新为 entry 查询。
  保留首次 baseline fail-close、无关编辑读取最后已完成 epoch 和 source replacement 原子性。
- `persistence/SimpleSignalsWorldDefinitions.kt`：`:172` 的 signal DTO、`:373` encode、`:511` restore 增方向；`:268` 独立 schema 升 3。导入/导出草稿沿用同 codec。
  `signal/ProtectionSignalDefinition.kt:38` 当前复用 fixed 定义检查输出表，新增方向参数须有明确正向默认或改为现有独立校验 helper，不能误让 protection 类型依赖某个 bidirectional block。
- `runtime/SimpleSignalsDefinitionEditImpact.kt`：按 signal ID 比较定义（`:30`）会自动识别方向变化；旧/新依赖可达收集（`:140` 左右）必须包含方向化依赖的 `affectedRailIds`。
  所有同 block 的控者应保留为 consumer，不按当前输出/分支剪枝。旧/新 rail 模型位置、附属设备和静态/动态 blocker 一并保留。
- `runtime/SectionTopologyRevalidation.kt`：`:55` 按 signal ID 的依赖关系已适用；`:66` 的 block footprint 仍共享；`:298` 重编译要调用新的方向化 compiler，retained bindings 不能因只比较 block ID 而复用错误端。
- `presentation/SignalModelBinding.kt`：`:73` 已按独立 signal ID 准备模型/外观，`:95` 附属显示按具体主 signal ID 引用；主体结构无需第二套渲染。
  模型对象仍用 `fixed-block-signal/<signalId>`，两端各自稳定 handle，与资源 ID 无关。
- `authoring/SectionAuthoringActions.kt:52`：删除 block 已收集所有 `controlledBlockId in ids` 的 signal（`:64`），并连带删除引用这些主 signal 的 attached。该算法天然覆盖两端，但须加回归。
  `SectionWandBackend/Handler/Sessions` 当前只建造/删除闭塞与区域，不包含信号机放置；无需把方向规则扩展成新 Wand 系统。
- `persistence/SimpleSignalsDefinitionDrafts.kt:70`：以 signal ID diff，方向字段自动参与内容比较。恢复先前定义仍需推进自己的 revision，不能绕过同 revision 改内容拒绝。
  没有找到独立 undo command；失败原子回退、旧 JSON 草稿恢复和未来交互撤销不可混为一谈。

资源消费者应不变：`block/FixedBlockRouteMatcher.kt`、`FixedBlockRouteResourcePolicy.kt`、`FixedBlockDirectionPolicy.kt`、
保护区域直接 successor/floor matcher、Basic claim/extension/tail release、时刻表静态 block resource dependency。
只改变入口规则/检查身份，不改它们的 block matching 或 resource identity。

### 3. 显示默认位置与逻辑入口

对应入口的默认轨道位置应由所选 traversal 的 first.travelStart 及 facing 派生，
可复用 `CompiledFixedBlock.entryTraversal(reversed)`；反向路径的第一段自然提供另一端朝向。
不把这个默认复制成第二个持久化逻辑坐标。

当前 `DeviceModelDefinition(modelId, placement)` 要求显式摆放，`model=null` 是无模型后端设备，
不是“自动创建默认模型”（`presentation/DeviceModelDefinition.kt:6`、`presentation/SignalModelBinding.kt:76`）。
主会话已明确本批不新增默认模型、默认 placement 变体或摆放 helper；未来 authoring 可从对应入口取默认选点，但不是本批实现内容。
显式 rail/ground 坐标只决定显示姿态，不改变所选 entryDirection、ATO restriction trigger、依赖距离或资源。
附属显示引用具体端的 signal ID，不自动共享另一端输出。

### 4. 持久化变化

当前独立 SimpleSignals world sidecar 使用 schema 2，严格 current-only decode，writer encodeDefaults=true、ignoreUnknownKeys=false
（`persistence/SimpleSignalsWorldDefinitions.kt:267-286`）。
旧的一个 fixed signal 同时控制两端，现在默认 FORWARD 只控制正向：虽然是带默认值新增字段，旧内容语义仍改变。
按 `ai_tasks/persistence_schema_evolution_rules.md` 第 5 节应只把此 sidecar 边界升至 3。
不升级 Core/Basic/SimpleATO 世界格式、model-package config-version、revision 或 epoch；不额外编号嵌套 DTO。
旧 schema 2 应明确拒绝，不能默默把旧共享规则当正向，也不能新增 BOTH、旧分支或 runtime upcaster。
若真实开发服数据需保留，另开明确授权的一次性 dry-run/恢复点迁移，不在本批实现。

需同步三个固定 JSON fixture：`SimpleSignals/src/test/resources/device-model-definitions.json`、
`fixed-block-without-bidirectional.json`、`world-definitions-without-attached-signals.json`。
其他内嵌 fixture/版本拒绝例见 `SimpleSignalsWorldDefinitionStoreTest`、`SimpleSignalsDefinitionDraftsTest`、`ProtectionSignalDevicesTest`。
更新 current=3、旧2拒绝、future4拒绝，不把“旧缺方向使用默认”的 fixture 误作旧 schema2兼容。
默认 FORWARD 可由当前 schema3 缺字段验证，writer 应明确写出方向。

### 5. 错误与原子性：一个现存合同冲突

重复同向、reverse-on-one-way、未知 controlledBlock、非法枚举/数值、依赖路径方向不可达、非法/闲置 turnout requirement 和 DAG cycle
应在候选准备拒绝，未取得许可、未写盘、未替换 source/publication/handle。
方向编辑与删除仍受既有旧、新影响许可及 retained revision 约束。

但 unknown downstream 当前存在两种行为：直接 `CompiledFixedBlockSignals.compile` 会拒绝；
production 混合 `CompiledProtectionSignals` 在 `signal/ProtectionSignalCompiler.kt:101` 把 unknown provider/上游闭包标成 unavailable。
这服务于“删除一个闭塞后，其他信号保留配置及失去目标的依赖，并持续0”的建造工具合同。
因此不能未经决定把 production 全部 unknown references 改成严格拒绝，否则现有合法 Wand 删除会失败。
主会话已决定保持既有 production unknown/暂失下游的 Unavailable 行为，不在本批扩大严格拒绝。
重复入口、不合法 reverse 和其他现有非法几何/路径仍原子拒绝；不要为区分删除历史增加持久化或兼容猜测。

### 6. 准确成本与验证范围

令 B 为物理闭塞数、S 为 configured entry signals（S≤2B）、P 为全部声明下游路径经过的 traversal/continuation，
D 为已编译路线入口声明数、A 为当前 active 声明数、N/E 为本 wave 实际触发节点/边。

- 入口 map 和 declaration→signal 索引低频建立 O(S) 空间/时间（确定顺序排序另 O(S log S)）；既有 reverseTraversals 不再复制。
- 路径验证 O(P)，仅 authoring/load/revalidation worker；共享 occupancy 不变，资源 matching、floor 和 release 成本不变。
- signal wave 仍 O((N+E) log N)，每到达规则的条件/下游/档位成本维持 O(c+d+l)。共享占用变化最多触发两端，单端下游变化不唤醒对端，输出相同即停传播。
- ATO 激活查询仍 O(log D + K)，handler 最近声明归约 O(A)，每个 distinct-entry 状态/配置查找 O(1)。没有新增路线重扫、图遍历或每列车每信号图；同路线重复同向归约不变。
- block control 改变至多产生两个 declaration ID；显示更新仅访问发生变化主 signal 的设备列表，额外设备数由作者实际配置决定。
- 数值都是静态复杂度，不是实测延迟或 live Folia 证明。本研究未构建/运行测试。

建议重点回归已有测试 owner：

- `signal/FixedBlockSignalCompilerTest`：两端不同 output/lead/dependency；混合 AB/BA 路径相对方向；同端重复与单向反向拒绝；方向匹配的真实距离和错误向目标不可达；跨 protection 依赖与 cycle。
- `runtime/SectionOccupancyRuntimeTest` / `OneWayInvalidationGraphTest`：一 occupancy provider 两规则；首 baseline 完整；仅一端下游改动只触发该端；共享占用变化两端；输出不变无上游重算；方向失配两端0且无配置端也0。
- `block/FixedBlockInspectionSourceTest`：现有 `both genuine entries use one configured rule...` 必须改为各端独立；无配置端不施加速度但 floor/resource 不丢；已配置端无 baseline=0；同向重复最近入口、正反交替不串规则；含 '/' 的作者 ID 无碰撞。
- `signal/ProtectionSignalDevicesTest` / `SignalReplacementContinuityTest` / `runtime/SectionTopologyRevalidationTest`：混合图正确端距离、失效/恢复独立、当前旧新 publication 不拼接、冷启动失败关闭、epoch一致；净空检查只忽略自己的精确 protection entry red，不吞新 fixed red。
- `presentation/SignalModelBindingTest`：同 block 两主 signal 的模型/附属各读自己的 variant，不增规则/area/token；显式 model 放在别处不改逻辑；无变化不submit；换向/删除保留无关 handle。
- `runtime/SimpleSignalsWorldRuntimeManagerTest`：方向/依赖/模型修改提高 revision，旧新保护范围及 consumer 路径，静态时刻表/动态claim blocker，错误reverse候选及保存/source失败原子性，合法删除后外部依赖持续不可用。
- `authoring/SectionAuthoringActionsTest` / `SectionWandHandlerTest`：删除 block 两端与其全部附属同批清除，外部依赖保留不可用；资源含义不变，不增加signalWand功能。
- persistence 五组既有测试及固定 fixture：schema3，旧2与未来4拒绝，非法方向枚举，current缺方向defaultforward，显式reverse round-trip，完整三类设备/附属值保留。
- `block/FixedBlockRouteResourcePolicyTest` / `FixedBlockStopContinuationTest` / `FixedBlockTimetableDependencyTest`：真实 Basic 相向同一token互斥、失败无部分claim、内部折返/token保留/车尾释放、静态dependency仍只有block身份。不必为本批改变 Basic/ATO arbiter。

### 7. 构建门禁的已有证据与可复用命令

本研究没有运行构建；直接读取 `/tmp/javarailways-exit-clearance-final-gate.log`，其结尾为
`BUILD SUCCESSFUL in 36s`，52 tasks 中 2 executed、50 up-to-date。
日志确实包含 Core/Basic/SimpleATO/BukkitPlugin/SimpleSignals/TunnelBridgeBuilder test、
BukkitPlugin compileSignalApiConsumerJava、SimpleSignals verifyPluginJar。
这只证明上一批门禁存在，不能替代本批修改后的重新验证。

缓存 Gradle 9.5 可执行文件已直接核实：
`/tmp/javarailways-gradle-home/wrapper/dists/gradle-9.5.0-bin/estug5qhtw5xqrldjyqnn9yr5/gradle-9.5.0/bin/gradle`。
仓库 wrapper 仍配置 Gradle 9.0；不修改 wrapper，复用上述独立缓存路径。
缓存 toolchains 也存在：
`/tmp/javarailways-gradle-home/jdks/eclipse_adoptium-17-amd64-linux.2`、
`/tmp/javarailways-gradle-home/jdks/eclipse_adoptium-25-amd64-linux.2`。
SimpleSignals/BukkitPlugin 编译需要 Java25（SimpleSignals/build.gradle:56），平台无关模块用 Java17。

以下是依据已有成功门禁任务和当前缓存重建的建议命令，不声称本研究执行过完全相同的命令行：

```sh
GRADLE_USER_HOME=/tmp/javarailways-gradle-home \
JAVA_HOME=/tmp/javarailways-gradle-home/jdks/eclipse_adoptium-25-amd64-linux.2 \
/tmp/javarailways-gradle-home/wrapper/dists/gradle-9.5.0-bin/estug5qhtw5xqrldjyqnn9yr5/gradle-9.5.0/bin/gradle \
  --offline --no-daemon \
  -x :WebUI:installFrontend -x :WebUI:buildFrontend \
  -Dorg.gradle.java.installations.auto-detect=false \
  -Dorg.gradle.java.installations.paths=/tmp/javarailways-gradle-home/jdks/eclipse_adoptium-17-amd64-linux.2,/tmp/javarailways-gradle-home/jdks/eclipse_adoptium-25-amd64-linux.2 \
  :Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test \
  :BukkitPlugin:compileSignalApiConsumerJava :SimpleSignals:test \
  :SimpleSignals:verifyPluginJar :TunnelBridgeBuilder:test
```

focused 修改可先仅运行 `:SimpleSignals:test --tests '*FixedBlockSignalCompilerTest' --tests '*FixedBlockInspectionSourceTest' --tests '*SectionOccupancyRuntimeTest'`，
再完整门禁；不能只停在编译成功。Gradle 单用 daemon 需要本地 socket，若 sandbox 拒绝，按既有审批边界仅升级此 offline 构建，不联网补依赖。
本批无宿主 API 改动，Java consumer 应继续作为包边界回归；Javadoc 不在上述运行命令内，上一批离线依赖缺失不能误报本批已验证。

### Related specs

- `.trellis/spec/simple-signals/backend/index.md`：现有一 block 一 signal、共享两端结果假设与 deployment/epoch/编辑边界。
- `.trellis/spec/simple-signals/backend/bidirectional-fixed-block.md`：已有反向几何、真实入口、共享资源和方向失配。
- `.trellis/spec/simple-signals/backend/definition-edit-impact.md`：旧、新结构依赖/显示位置和许可失败原子性。
- `.trellis/spec/simple-signals/backend/model-presentation.md`：逻辑入口与显示位置独立、具体主 signal 附属、world handle生命周期。
- `.trellis/spec/simple-signals/backend/section-authoring-wands.md`：删除闭塞连带设备、保留外部依赖 unavailable、signalWand未实现。
- `ai_tasks/persistence_schema_evolution_rules.md`：语义改变属于破坏性变化，仅最小独立边界升级。
- `.trellis/tasks/09-18-signal-addon-implementation/prd.md`：2026-10-05修正决定；本文件是实现前调查，不替代主会话 owner contract。

### External references

无；问题由当前仓库源码和已批准合同确定，不需要在线产品/铁路资料。

## Caveats / Not Found

- 未执行构建、测试、服务启动、Git写操作或实际 Folia/客户端验证；源码结论固定于上述 HEAD，主会话随后写入 owner contract 可能改变最终接口名。
- 未发现独立信号机 Wand、独立撤销命令、方向入口 model default 的既有持久化变体；不要把待实现交互当现状。
- production unknown downstream 与直接 compiler 的行为不同；主会话已确认保持合法删除/暂失下游 Unavailable，不扩大严格拒绝。研究未擅自改运行代码。
- 单线多闭塞协调、JS loader、固定点、第二套资源仲裁/扫描、旧开发数据 runtime兼容均为非目标。
- Memory quick-pass仅用于既有小批提交/保留脏文件的工作偏好（MEMORY.md:719-724）；当前实现事实均重新直接查源码，不依赖旧 rollout 的状态。
