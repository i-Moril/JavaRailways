# AI Task A：世界存档正确性与兼容性加固（收敛版）

> 状态：Ready
> 类型：confirmed defects / TDD
> 必修范围：Core snapshot、Bukkit split snapshot、extension type codec、Train runtime state
> 独立后续：RailRenderMeta 一致性与瘦身、外层 gzip

---

## 1. 交付目标与边界

本任务只修复当前代码已经能够证明的四类存档缺陷：

1. snapshot revision 在插件重启后仍按 world 单调递增，main/backup 不会导致恢复旧状态。
2. Core Rail/Train DTO 在创建并注册 `CoreWorld` 前完成严格校验，损坏引用不再被静默丢弃或替换。
3. extension data 使用稳定、显式注册的 type ID；旧 JVM FQCN 仅可通过 allowlist alias 读取，不再执行任意 `Class.forName()`。
4. Train 的脱轨状态和原因可以 round-trip；旧 V1 缺少字段时由默认值读取为 ACTIVE，但不恢复 live Controller。
5. 现有模型配置 JSON Schema 与严格 Kotlin loader 对齐，并由唯一 source of truth 生成文档站点发布副本；这类契约漂移修复不升级 schema version。

Task A 的完成不再绑定以下两项：

- RailRenderMeta 是否嵌入 infrastructure payload；
- snapshot 是否默认 gzip。

这两项问题和收益都真实存在，但解决方案会扩大文件协议和恢复矩阵，必须作为独立提交/后续任务处理，见第 8 节。

---

## 2. 已确认的代码证据

### P0：revision 跨重启回退

- `BukkitPlugin/.../logic/WorldManager.kt` 中 `serverTickIndex` 每次启动从 `0` 开始。
- `captureSnapshots()` 直接把 `serverTickIndex` 用作 `infrastructureRevision`。
- `BukkitPlugin/.../persistence/WorldPersistence.kt` 会从 main/backup 候选中选择数值最大的匹配 revision。
- 已观察到真实 Paper 文件 `main=0`、`backup=25`；当前 loader 会选择旧 backup。
- `loadSplitSnapshots()` 在枚举 backup 前先要求两个 main 文件都存在，因此完整的 backup-only pair 也不能恢复。

### P0：Rail 图会被静默改变

- `CoreRail.SavedRailV1.restoreConnections()` 使用 `mapNotNull` 丢弃非法 UUID 和缺失 Rail。
- 非法 active branch 会回退到 `firstOrNull()`。
- 合法的 `activeTrack=null` 在 adjacency 非空时也会被改成第一条支路。
- `CoreWorld.addRail()` 对重复 Rail UUID 使用覆盖写入；后一个对象还可能与空间索引中的旧对象不一致。

现有 Paper/Folia 样本未发现重复 Rail ID、缺失引用或非法 active branch。这说明应增加 fail-fast 保护，不代表现有世界已经损坏，也不需要自动修复器。

### P1：extension discriminator 绑定 JVM 类名

- `Core/.../persistence/WorldSnapshotCodec.kt` 根据存档中的字符串执行 `Class.forName(className).kotlin.serializer()`。
- 当前真实存档至少包含以下 legacy discriminator：
  - `work.art1st.javarailways.bukkit.extension.train.SavedTrainRenderState`
  - `work.art1st.javarailways.bukkit.extension.train.SavedCarRenderState`
- 仓库内还存在其他 `SerializableExtensionData` 子类；实现前必须完成一次盘点，不能假设只有上述两个类型。

### P1：脱轨状态未保存

- `SavedTrainV1` 不包含 `derailed` 或 `derailReason`。
- `CoreTrain.save()` 因此会把脱轨列车保存成下一次启动时的普通 ACTIVE 列车。
- Controller 本来就不在 snapshot 中；本任务无需新增 Controller/ATO 恢复系统。

### P1：模型 JSON Schema 与严格 loader 漂移

- `model-common.schema.json` 曾允许 `item.animations`，但 `PartRegistry.parseItemConfig()` 明确拒绝该字段。
- 同一 Schema 曾允许 `platform.spacingX/spacingZ`，但 loader 只接受 `spacing-x/spacing-z/spacing`。
- `actions-v1.schema.json` 曾允许 animation 字符串简写，但 `TrainActionConfig.parseAnimations()` 当前只接受 object。
- 根 `schemas/train-model/` 与 `docs-site/reference/schemas/train-model/` 曾保存两份人工副本，CI 校验和文档发布存在漂移风险。

这些输入从未被当前 loader 接受；收紧 Schema 是修正错误契约，不是破坏磁盘格式，因此 part/actions 继续使用 V1。

---

## 3. 已锁定的设计决策

### 3.1 revision bootstrap

对当前无“单调 revision 标记”的 legacy 文件采用以下确定性规则：

1. 独立解码 main/backup 的 infrastructure 与 runtime 候选。
2. 如果两个 main 文件都有效且 revision 匹配，legacy bootstrap 优先选择完整 main pair；这避免真实 `main=0` 被旧 `backup=25` 覆盖。
3. 若完整 main pair 不可用，从所有可解码候选中选择 revision 最大的匹配 pair；因此 backup-only pair 可以恢复。
4. 同时记录所有已成功解码候选中的最大 revision `M`，不只记录最终选中的 revision。
5. 该 world 的第一次新 capture 必须使用 `revision > M`；后续 capture 继续单调递增。

成功写入新 revision 后，main 自然高于 backup，后续不再依赖旧的 tick 值。revision 只是比较和配对 token：

- 不使用 wall-clock；
- 不复用 `physicsEpoch`；
- 不要求连续，保存失败或 coalescing 可以留下空洞；
- 溢出时明确失败，不回绕。

### 3.2 严格加载，不修复数据

- validation 必须在 `CoreWorld(...)` 构造、`WorldParentContext.attachWorld()`、physics attach、Rail spatial-index 写入之前完成。
- 引用损坏直接失败，并保留原文件；不创建空世界、不覆盖文件、不自动断开连接。
- `activeTrack=null` 是可保存状态，恢复时必须保持 `null`。
- 相邻 curve point 的 arc length 只要求**非递减**；真实样本存在相邻重复值，禁止改成严格递增。
- 连接端点使用现有 `Curve.TOLERANCE`，不要引入更严格的私有阈值。

### 3.3 稳定 extension ID

新写出的 type discriminator 使用 namespaced stable ID，例如：

```text
javarailways:train_render_state
javarailways:car_render_state
```

不强制引入通用 `typeId/version/payload` envelope。现有 kotlinx JSON 形状可以保留，只要：

- 新 discriminator 不含 JVM FQCN；
- serializer 来自显式 registry；
- legacy FQCN 只走显式 alias map；
- 未知 ID/alias 明确失败；
- 任意输入都不能触发反射 class loading。

registry 必须在 `WorldPersistence.loadSplitSnapshots()` 前由 Core/Bukkit/已安装扩展完成注册。拒绝重复 ID 映射到不同 serializer，但不建设反射发现、全局 phase framework 或复杂 registry freeze 机制。

### 3.4 Train V1 兼容性字段扩展

依据 [`persistence_schema_evolution_rules.md`](./persistence_schema_evolution_rules.md)，`derailed` 和 `derailReason` 有无歧义默认值，属于兼容性字段扩展，不创建 `SavedTrainV2`，也不升级 Core runtime 或 outer split schema：

- 在 `SavedTrainV1` 增加 `derailed: Boolean = false`、`derailReason: String? = null`。
- 旧 V1 fixture 缺少字段时由 current serializer 直接解码为 `false/null`，不需要 upcaster。
- DERAILED 状态要求非空白 reason；ACTIVE 状态不携带 reason。
- 恢复 placement 成功后、发布最终 Train frame 前应用 persisted derail state。
- 加载不发布新的 `DerailEvent`，也不安装 Controller。

---

## 4. 非目标与 guardrails

- 不引入 generation 目录、`CURRENT` 指针、manifest、checksum 或管理员 generation rollback。
- 不删除、量化、重采样或重新生成 `PrimeCurveImpl.pointList`；它是 canonical geometry。
- 不引入 CBOR/ProtoBuf，也不重写 outer JSON-string schema。
- 不自动恢复 Controller、ATO、manual session、pending command、route lock、Future 或 worker queue。
- 不建立通用 migration framework、自动拓扑 repair mode 或运行时全图 validator。
- 不要求 adjacency 双向互反、固定图度数、严格单位 tangent/normal、曲率连续或严格递增 arc length；这些语义尚未由代码契约证明。
- 不静默忽略未知 extension、缺失 Rail、非法 active branch 或不匹配 snapshot pair。
- 每一阶段独立提交和验证，不做一次性 persistence 大重写。
- Schema 调整遵循 [`persistence_schema_evolution_rules.md`](./persistence_schema_evolution_rules.md)：修正 schema/loader 漂移不虚增版本，破坏性磁盘契约才升级最小独立边界。

---

## 5. 实施前置：固定 fixture 与类型盘点

在业务实现前先提交固定 JSON fixture，避免测试只验证“新 writer 能被新 reader 读回”。fixture 至少包含：

```text
legacy-valid-v1-infrastructure.json
legacy-valid-v1-runtime.json
legacy-fqcn-extension-runtime.json
legacy-train-without-derail-state.json
```

另建立测试内的 main/backup 文件组合，不依赖仓库中的真实运行目录。

extension 类型盘点必须记录：

1. 仓库内所有 concrete `SerializableExtensionData` subtype；
2. 当前 Paper/Folia fixture 中出现的 discriminator；
3. Bukkit 启动阶段由第三方扩展注册 stable ID 和 legacy alias 的入口；
4. 无法支持的历史类型，必须在迁移前明确报告，不能静默跳过。

fixture 需要最小化并脱敏；不要把完整 9MB 世界文件复制进测试资源。

---

## 6. TDD 实施任务

## A1. revision 单调性与 pair 恢复

### RED

新增以下失败场景：

1. **真实回退形状**：main pair revision `0`、backup pair revision `25`；legacy load 选择 main `0`，但返回的最大已见 revision 为 `25`，下一 capture 使用 `>25`。
2. **跨重启**：加载 revision `100000` 后模拟新 `WorldManager`，下一保存 revision 必须 `>100000`，再次加载必须得到新 main。
3. **backup-only**：main 不存在、两个 `.bak` 文件有效且 revision 相同，恢复成功。
4. **main 损坏**：一个 main 无法解码时，可从剩余候选中选择最高匹配 pair。
5. **无匹配 pair**：明确返回 `Corrupted`，不创建空世界。
6. **中断写入**：在 infrastructure 替换后、runtime 替换前注入失败；若之前存在可读 pair，下一次加载仍能恢复该 pair。
7. 多 world 的 revision allocator 互不影响。
8. periodic capture、unload capture 连续取得不同且递增的 revision；允许中间 revision 未写盘。
9. `Long.MAX_VALUE` 不回绕。

### GREEN

- 让 load result 暴露：选中的 pair、候选来源，以及初始化 allocator 所需的最大已见 revision。
- 在 `WorldManager` 维护 world-local 的简单 `Long` revision state；capture 已由 simulation owner 串行化，不额外引入 Atomic/CAS 服务。
- 新 world 使用明确的初始 revision；已加载 world 的 next revision 必须大于最大已见 revision。
- 同一次 capture 的 Core/Basic infrastructure/runtime 使用同一 revision。
- world unregister 后清理 allocator state。
- 保留现有 `.tmp`、`.bak` 和单文件 atomic move；先通过 candidate recovery 测试，不引入 manifest/generation store。

### REFACTOR / 验收

- pair 选择策略集中在一个可单测的纯逻辑边界。
- 日志明确说明 main/backup 来源、选中 revision 和忽略的损坏候选。
- 不再用 `serverTickIndex` 表达持久化 revision；它仍可保留为 server tick metrics。

---

## A2. Core DTO 预校验与严格 resolve

### RED

为每项不变量增加独立 fixture/变体：

#### Rail ID 与连接

- Rail ID 非 UUID；
- 重复 Rail UUID；
- adjacency 非 UUID；
- adjacency 引用缺失 Rail；
- active branch 不属于对应 adjacency；
- active branch 引用缺失 Rail；
- 连接端点与目标 Rail 两端的最近距离仍大于 `Curve.TOLERANCE`；
- `activeTrack=null` + 非空 adjacency round-trip 后仍为 `null`。

#### Curve

- 空 `pointList`；
- position/tangent/normal 或 arc length 出现 NaN/Infinity；
- tangent/normal 为零向量；
- 负 arc length；
- arc length 下降；
- 首点 arc length 不是零（允许固定的小数值序列化 epsilon）；
- 相邻重复 arc length 是合法 fixture，必须通过且不得重建 point list。

#### Train 与 location

- head/tail/Bogie location 引用缺失 Rail；
- location arc 非 finite 或超出 `[0, rail.totalArcLength]`；
- `minorDeltaS` 非 finite；
- 重复 train technical ID；
- 重复 train name；
- train name 与另一列车 technical ID namespace 冲突。

所有损坏场景都断言：

- 错误包含对象类型、稳定 ID/索引及失败不变量；
- `WorldParentContext.attachWorld()` 未被调用；
- physics coordinator 未 attach/start；
- 没有 Rail、Train 或 spatial-index 部分状态泄漏；
- 输入 DTO 未被改写。

### GREEN

- 在 `CoreWorld.load()` 创建 world 前执行纯 DTO validation。
- curve 自身不变量在 saved-curve 边界验证；验证只能读取 canonical points，不生成替代曲线。
- 先建立 Rail ID → DTO/curve summary，再验证 adjacency、active 和 endpoint。
- 再验证所有 Train/location 引用和唯一性。
- materialize 阶段把 `mapNotNull`/`runCatching(...).getOrNull()` 改为严格 resolve。
- `activeTrack=null` 保持 null；非 null 必须 resolve 到对应 adjacency。

### REFACTOR / 验收

- 可以使用一个小型 `WorldSnapshotValidator`，也可以使用几个同职责纯函数；不得发展成通用 validation framework。
- materialize 保留必要的 defensive checks，但不重复昂贵全图扫描。
- 正常 fixture 和当前 Paper/Folia 样本可 dry-run 通过；dry-run 不修改、迁移或覆盖真实文件。

---

## A3. stable extension discriminator 与 legacy alias

### RED

1. 新 writer 输出 stable ID，不出现 `work.art1st...Saved*` JVM 类名。
2. legacy 两个已知 FQCN fixture 可通过显式 alias 解码。
3. 实现类重命名不改变 stable ID fixture。
4. 未知 stable ID 明确失败，错误至少包含 discriminator 和 snapshot/train 上下文。
5. 未注册 legacy FQCN 明确失败。
6. 恶意 class name 不触发 `Class.forName()`、class initialization 或任意 serializer discovery。
7. duplicate stable ID / duplicate legacy alias 注册不同 serializer 时 fail-fast。
8. registry 未在 world load 前装配完成时 fail-fast，而不是退回反射。

### GREEN

- Core 提供最小显式 persistence type registry/catalog API。
- Bukkit 在调用 `WorldPersistence.loadSplitSnapshots()` 前注册内建 serializer、stable ID 和 legacy aliases。
- 第三方 provider 使用同一显式注册入口；不扫描 classpath。
- 新 encode 只使用 stable ID。
- legacy decode 只查 alias map。
- 删除 `WorldSnapshotCodec` 中基于任意字符串的 `Class.forName()` 路径。
- 未知 extension 不得忽略，因为它可能影响 Train/Car 结构和渲染语义。

### REFACTOR / 验收

- registry 不依赖 Bukkit 类；Bukkit serializer 由 Bukkit 层注册。
- codec 使用装配完成的显式 catalog；不增加复杂全局 freeze 生命周期。
- 保存前确认所有实际 extension value 都有 stable registration，避免写到一半才失败。

---

## A4. Train V1 脱轨状态兼容性扩展

### RED

1. 旧 V1 train fixture 缺少 derail 字段，current reader 解码后是 ACTIVE、reason 为 null。
2. 当前 V1 ACTIVE train round-trip 后仍 ACTIVE。
3. 当前 V1 DERAILED train round-trip 后仍 DERAILED，reason 原样保留。
4. `derailed=false + non-null reason` 被拒绝。
5. `derailed=true + blank reason` 被拒绝。
6. DERAILED train 加载后没有 Controller，且普通 physics tick 不能使其自行恢复运动。
7. restore 不发布新的 `DerailEvent`；首次 published frame 已标记 derailed。
8. placement 或 location 加载失败时，Train 构造副作用完整回滚。

### GREEN

- 在 `SavedTrainV1` 增加带默认值的 `derailed` 和 `derailReason`；不新增 Train/runtime/split V2。
- current reader 直接用默认值读取旧 V1 JSON，不建立只为这两个字段服务的 migration/upcaster。
- 保存 committed/published 的 derail state 和 reason。
- placement restore 成功后应用 persisted derail state，再发布最终 frame。
- persisted restore 使用内部无事件路径，不调用面向运行时命令的 `Train.derail()`。
- 不恢复 Controller、ATO、route allocation 或 physics worker queue。

### REFACTOR / 验收

- 旧 V1 fixture 与包含新字段的 current V1 fixture 都有覆盖，不只做对象 round-trip。
- 当前 dispatcher 的活动 run 继续进入既有 `RECOVERY_REQUIRED` 流程；不得因新增字段伪造旧运营资源仍有效。

---

## A5. 模型配置 Schema 对齐与单一来源

### RED

增加可执行 rejection fixtures，证明以下输入必须同时被 JSON Schema 和 Kotlin loader 拒绝：

1. `item.animations`；
2. `platform.spacingX`；
3. `platform.spacingZ`；
4. `actions[].animations` 的字符串简写。

另验证：

- 仓库现有 `models/` 全部通过 Schema；
- part/actions 文件版本仍分别为 V1；
- 文档发布的 `/schemas/` 来自根 `schemas/`，不存在第二份人工维护副本。

### GREEN

- 从 `model-common.schema.json` 删除 loader 不接受的 `item.animations`、`spacingX` 和 `spacingZ`。
- 从 `actions-v1.schema.json` 删除 animation string branch，只保留 object 结构。
- 根 `schemas/train-model/` 作为唯一 source of truth。
- `docs-site` 构建直接复制根 Schema；删除 `docs-site/reference/schemas/train-model/`。
- 在 docs build/check 中运行 model validation 和 rejection fixture tests。

### REFACTOR / 验收

- 不升级 part/actions schema version；这是对当前严格 loader 的纠错。
- 未来 schema 或 loader 任一变化都必须同时更新等价测试，避免“编辑器接受、服务器拒绝”。
- `npm run validate-models`、`npm run test-schemas` 和 docs build/check 通过。

---

## 7. 兼容性矩阵

| 输入 | 预期 |
|---|---|
| 完整 legacy raw JSON main pair | 可读；main 优先；下一 revision 大于所有已见候选 |
| backup-only 完整 pair | 可读并记录恢复来源 |
| main/runtime revision 不匹配且无其他 pair | `Corrupted`，不注册 world |
| legacy FQCN allowlist | 可读并在下一次保存写 stable ID |
| 未知 FQCN / stable ID | 明确失败，不反射、不忽略 |
| 旧 V1 Train 无 derail 字段 | 由默认值读取为 ACTIVE |
| 当前 V1 Train DERAILED | 保持 DERAILED，无 live Controller |
| 相邻重复 curve arc | 可读，canonical point list 不变 |
| 缺失 Rail / 非法 active | materialize 前失败 |

所有迁移均为 reader compatibility；不得在仅加载成功后立即覆盖原文件。只有正常 snapshot save 才写新格式，并继续沿用 `.tmp`/`.bak` 恢复点。

---

## 8. 从 Task A 主交付拆出的独立后续

## R1. RailRenderMeta 正确性与瘦身

问题成立，但不预先锁定“必须嵌入 `InfrastructureFileV2`”。独立任务先定义最小不变量：

- render assignment 与 Rail topology 必须能按同一 revision 选择；可以评估 revisioned 第三文件或 integrated payload，不能直接假定后者更简单；
- 新格式只保存 `railId → templateId/templateSnapshot`；
- 删除持久化 `segments/arcStart/arcEnd/pointArcs`，但保留运行时 `RailArcVisualSegment`；
- 缺失 Rail/template 必须报错，禁止当前 `filter + saveAll` 式静默清理；
- standalone V2 必须有固定迁移 fixture，且新 revision 成功写入前不得归档旧文件。

该任务应有独立 RED、提交和完成定义，不阻塞本 Task A 的四项正确性修复。

## R2. 外层 gzip

gzip 是已测得有效的体积优化，不是完整性修复：真实 9.76MB 样本可压缩到约 1.35–1.39MB，但当前环境单次默认 gzip 约数百毫秒。

独立任务要求：

- 先在 JVM 异步 writer 路径测量 encode/compress/load 时间和 CPU；
- reader 同时支持 legacy `.json` 和新 `.json.gz`；
- writer 若启用压缩，只写 gzip 新格式并继续使用 `.tmp`/`.bak`；
- 压缩失败不得删除最后一份可读 raw/gzip pair；
- 不引入 CBOR、内层 JSON 压缩或自动删除旧恢复点。

---

## 9. 推荐提交顺序

```text
1. test: add legacy persistence fixtures
2. test: reproduce revision rollback and backup-only recovery
3. fix: make snapshot revisions monotonic per world
4. test: reject invalid Core snapshot graphs before materialization
5. fix: validate and strictly restore Core snapshot references
6. test: lock stable and legacy extension discriminators
7. fix: replace reflective extension decoding with explicit registry
8. test: cover additive Train V1 derail persistence
9. feat: persist Train derail state without schema bump or Controller recovery
10. fix: align model schemas with strict loaders and one source of truth
11. docs: document snapshot compatibility and recovery boundaries
```

R1 RailRenderMeta 和 R2 gzip 使用单独任务/提交，不夹入上述序列。

---

## 10. 验证命令

每一阶段先运行最小模块测试，最终运行：

```bash
./gradlew :Core:test
./gradlew :Basic:test
./gradlew :BukkitPlugin:test
./gradlew :Core:test :Basic:test :BukkitPlugin:test
./gradlew :BukkitPlugin:shadowJar
./gradlew build

cd docs-site && npm run validate-models
cd docs-site && npm run test-schemas
cd docs-site && npm run check

git diff --check
git status --short
```

还必须执行只读 migration dry-run：

```text
- Paper main/backup samples
- Folia main/backup samples
- 所有固定 legacy/current fixtures
```

报告每个候选的来源、revision、选择结果、最大已见 revision、验证结果和 extension aliases；dry-run 不得写文件。

已知根 `./gradlew build` 可能被既有 Java 25 / TinyRemapper 0.12 class-major-69 工具链问题阻塞。若 scoped tests 与 `shadowJar` 通过而根 build 仅因此失败，必须保留原始错误并单独报告，不能把它伪装成 Task A 回归，也不能静默跳过命令。

---

## 11. 完成定义

- [ ] legacy `main=0, backup=25` 选择 main，但首次新 revision `>25`。
- [ ] 连续多次重启/保存后不会恢复旧高 revision backup。
- [ ] backup-only 完整 pair 可以恢复；无匹配 pair 不创建空 world。
- [ ] split write 中断不会覆盖最后一份可读 pair。
- [ ] 重复/缺失 Rail、非法 active、非法 curve/location 和重复 Train identity 在 `CoreWorld` 注册前失败。
- [ ] 合法 `activeTrack=null` 与相邻重复 arc length 保持原语义。
- [ ] canonical `PrimeCurveImpl.pointList` 未删除、量化、重采样或重新生成。
- [ ] 新 extension JSON 不含 JVM FQCN；legacy allowlist fixture 可读；未知类型不反射、不忽略。
- [ ] 旧 V1 Train 缺失 derail 字段由默认值读取为 ACTIVE；当前 V1 DERAILED round-trip 保持状态和 reason。
- [ ] 加载的脱轨列车无 live Controller，且 restore 不产生新的运行时 `DerailEvent`。
- [ ] Schema rejection fixtures 与 Kotlin loader 一致；现有模型全部通过。
- [ ] 根 `schemas/train-model/` 是唯一人工维护来源，文档站点不保留复制品。
- [ ] part/actions 仍为 V1，没有因纠正 schema 漂移而虚增版本。
- [ ] Paper/Folia dry-run 全部通过且未写文件。
- [ ] Core、Basic、BukkitPlugin scoped tests 和 `shadowJar` 通过；根 build 结果按已知工具链边界如实记录。
- [ ] R1 RailRenderMeta 与 R2 gzip 未被偷偷并入本任务实现。
