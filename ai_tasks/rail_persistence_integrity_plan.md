# AI Task A：世界存档完整性与兼容性加固

> 状态：Ready
> 类型：confirmed defects
> 范围：Core persistence、Bukkit split snapshot、RailRenderMeta、Train runtime state

---

## 1. 目标

在不引入 generation store、不改变 canonical curve 几何语义的前提下，修复当前世界存档中已能由代码证明的完整性和兼容性问题：

1. revision 跨插件重启保持单调，不会因“选择最大 revision”恢复旧备份。
2. 损坏的 Rail 图在 materialize 前被拒绝，不再静默断轨或改变道岔。
3. render assignment 与 Core/Basic infrastructure 使用现有共同 revision 保存。
4. 删除 render meta 中未使用的派生 `segments/pointArcs`。
5. 快照默认 gzip，并继续支持当前未压缩文件。
6. 扩展数据使用稳定 type ID，不再根据存档字符串任意 `Class.forName()`。
7. 脱轨列车重启后仍保持脱轨；不恢复 live Controller。

---

## 2. 代码证据

### P0：revision 在重启后回退

- `WorldManager.serverTickIndex` 从 `0` 开始：`WorldManager.kt:50`。
- capture 直接把它作为 revision：`WorldManager.kt:426-437`。
- loader 从 main/backup 组合中选择数值最大的 revision：`WorldPersistence.kt:40-60`。

故障序列：

```text
第一次运行保存 main revision=100000
重启后保存 main revision=1200，同时 backup=100000
再次重启：loader 选择 backup revision=100000，回滚新数据
```

这是确定性缺陷，不是理论优化。

### P0：拓扑引用会被静默修复

- `CoreRail.kt:153-154` 使用 `mapNotNull` 丢弃非法/缺失 Rail。
- `CoreRail.kt:158-170` 在 active branch 无效时退到 `firstOrNull()`。
- `CoreWorld.addRail()` 对重复 Rail UUID 直接覆盖 map entry。

### P1：扩展数据绑定 JVM 类名

- `WorldSnapshotCodec.kt:19-25` 使用默认多态 serializer 和 `Class.forName(className)`。
- 当前内建 extension data 数量很少，可建立显式 registry；不需要通用反射发现系统。

### P1：render meta 事务和数据边界不正确

- standalone `RailRenderMetaStore` 不带 infrastructure revision。
- split/merge 会立即重写 render 文件，而 Core infrastructure 等周期快照；崩溃时两者可能不属于同一编辑结果。
- `RailRenderMetaStore.kt:28-38` 保存 segments/pointArcs。
- `RailVisualBootstrap.kt:45-48` 恢复时忽略它们并重新 `createSegments()`。

### P1：运行态缺少脱轨状态

- `SavedTrainV1` 只有 cars/reversed/extensions/head/tail：`TrainPersistence.kt:31-38`。
- `CoreTrain.save()` 未写 `isDerailed/derailReason`。

### P2：体积

现有 90 Rail 样本约 9.76MB，gzip 后约 1.39MB。先压缩已有 JSON 已能获得主要收益，不需要改曲线结构。

---

## 3. 非目标

- 不引入 generation 目录、`CURRENT` 指针、manifest、SHA-256 或管理员 generation rollback。
- 不删除或量化 `PrimeCurveImpl.pointList`；它目前是 canonical geometry，不是可丢弃 cache。
- 不引入 CBOR/ProtoBuf。
- 不恢复 live ATO、手动驾驶 session、pending command、route lock、Future 或 worker queue。
- 不消除 outer snapshot 中的 JSON string 嵌套；gzip 后再测量，未证明它值得本轮改动。
- 不在加载失败后创建空世界或覆盖原文件。

---

## 4. 实施任务

## A1. 修复 revision 单调性

### RED

新增跨重启测试：

1. 写 main revision `100000`。
2. 模拟新进程从该快照加载。
3. 修改世界并保存下一 revision。
4. 验证新 revision 必须 `> 100000`。
5. 再次加载时必须选择新 main，而不是旧 backup。

另测：

- infrastructure/runtime revision 始终相同；
- 多 world 各自单调；
- 周期 save、world unload save 连续递增；
- main 缺失但 `.bak` pair 完整时可以恢复。当前 `loadSplitSnapshots()` 在检查 backup 前要求两个 main 都存在，也应修复。

### GREEN

- 为每个 world 维护 `nextSnapshotRevision`，从成功加载的 revision 初始化。
- 每次 capture 原子取得一个新 revision；不再使用 `serverTickIndex` 作为持久化 revision。
- `physicsEpoch` 继续表达物理 epoch，不能代替 infrastructure revision。
- loader 仍可选择最高匹配 pair，但 revision 现在跨重启单调。

约束：

- revision 是比较/配对 token，不使用 wall-clock。
- 同一次 capture 的 Core/Basic/infrastructure/runtime/render 必须共享同一 revision。

## A2. 在 materialize 前严格验证 Core snapshot

### RED fixture

至少覆盖：

- 重复 Rail UUID；
- 非 UUID adjacency；
- adjacency 引用缺失 Rail；
- active branch 不属于对应 adjacency；
- active branch 引用缺失 Rail；
- 相连端点距离超过 `Curve.TOLERANCE`；
- curve 空点、NaN/Infinity、负弧长、非单调弧长；
- Train/Bogie/head/tail 引用缺失 Rail；
- RailLocation arc 超出 Rail 长度；
- 重复 train ID/name。

断言：

- 异常包含对象类型、ID 和失败不变量；
- 失败后 dependency 中没有部分 CoreWorld 被平台注册；
- 不再通过 `mapNotNull` 或“第一支路”改变数据。

### GREEN

- decode 后先验证 DTO 引用图，再调用 `loadDisconnected()`。
- `restoreConnections()` 使用严格 resolve；任何引用失败都抛异常。
- 校验 duplicate ID 必须发生在 CoreRail 构造写入 world map 之前。
- 保留正常的 `activeTrack=null`；不要把 null 自动替换成第一支路，除非格式语义明确要求且 fixture 锁定。

不要求建立大型 validation framework；一个职责清晰的 `WorldSnapshotValidator` 即可。

## A3. 把 render assignment 纳入现有 split snapshot

采用现有 infrastructure/runtime 两文件协议，不引入第三套 generation 协议。

目标格式：

```text
InfrastructureFileV2
  formatVersion
  infrastructureRevision
  coreSnapshotJson
  basicSnapshotJson
  railRenderSnapshotJson
```

`RailRenderSnapshotV3` 只保存：

```text
templates[]
rails[]: railId + templateId
```

### RED

- Core 与 render 修改之间注入崩溃，加载结果只能来自同一 infrastructure revision。
- render 记录引用缺失 Rail 或 template 时严格失败。
- V2 standalone render fixture 可迁移到 integrated V3。
- 新格式 round-trip 后不包含 `segments`、`arcStart/end`、`pointArcs`。

### GREEN

- `RailRenderMetaStore` 变成 world-local 内存 assignment repository；编辑命令更新内存和当前 visual。
- `WorldManager.captureSnapshots()` 在同一 simulation capture 中取得 render assignment。
- 新 infrastructure file 一起编码 render snapshot。
- 老世界无 integrated render 时，读取 standalone V2 作为一次性迁移来源。
- 只有新 integrated snapshot 成功写入后，standalone 文件才可归档；不得提前删除。

说明：仍可保留 `RailArcVisualSegment.pointArcs` 作为运行时派生数据；删除的是持久化字段。

## A4. 增加 gzip，同时保留当前 JSON schema

### RED

- `.json.gz` main/backup 候选可读。
- gzip 当前损坏时可从 gzip backup 恢复。
- 首次迁移前只有 `.json` 时仍可读。
- 压缩写失败不会破坏旧 `.json` 或上一个 `.json.gz`。

### GREEN

- 新保存目标使用 `*-infrastructure.json.gz`、`*-runtime.json.gz`。
- 使用 stream/channel 写 `.tmp`，完成 gzip 后再沿用 atomic move + `.bak`。
- loader 支持压缩和旧未压缩候选；依靠 A1 的单调 revision 选择最新匹配 pair。
- 首次成功 gzip save 后保留旧未压缩文件，至少到下一次成功启动验证；不自动删除最后一份可读快照。

验收报告记录：文件大小、encode/compress/load 时间。没有硬编码“必须缩小到某百分比”的产品门槛。

## A5. 用稳定 type ID 替代反射类名

目标 envelope：

```json
{
  "typeId": "javarailways:car_render_state",
  "version": 1,
  "payload": {}
}
```

### RED

- 重命名实现类不改变新格式 JSON。
- 未知 type ID/version 明确失败并指出 extension owner/key。
- 恶意 class name 不触发 `Class.forName()`。
- 当前 V1 类名 fixture 仍可通过白名单 alias 读取。

### GREEN

- Core 提供显式 `PersistenceTypeRegistry`。
- Bukkit 启动时注册当前内建 extension serializers 和 legacy class-name aliases。
- V2 decoder 只按 namespaced type ID 查 registry。
- V1 decoder 只查显式 alias map；禁止对任意字符串反射加载 class。

约束：

- 同一个 type ID 不允许重复注册不同 serializer。
- registry 在第一次 world decode 前冻结，避免运行期变化。
- 未知 extension 不得静默忽略，因为它可能影响模型或列车结构语义。

## A6. 保存脱轨状态，但不热恢复 Controller

### RED

- ACTIVE train round-trip 后仍 ACTIVE。
- DERAILED train round-trip 后仍 DERAILED，reason 保留。
- 脱轨列车加载后无 Controller，不能自行运动。
- 当前 V1 train fixture 缺字段时按明确迁移规则恢复为 ACTIVE。

### GREEN

- 新增显式 `SavedTrainV2` 或 runtime V2；不要悄悄改变 V1 语义。
- 保存 `derailed` 和 `derailReason`。
- restore placement 成功后再应用 derail state，失败时仍完整回滚构造副作用。
- Controller、速度和 dynamics continuity 不作为本任务恢复内容。

当前运营恢复继续遵守文档：活动 run 保留为 `RECOVERY_REQUIRED`，不伪造旧 route lock/ATO 仍然有效。

---

## 5. 推荐提交顺序

```text
1. test: reproduce revision rollback across restart
2. fix: make snapshot revisions monotonic per world
3. test: add invalid topology fixtures
4. fix: validate before CoreWorld materialization
5. refactor: integrate minimal render assignment into infrastructure snapshot
6. feat: gzip split snapshot files with legacy readers
7. feat: stable extension persistence type registry
8. feat: persist derail state in explicit runtime/train V2
9. docs: update persistence format and recovery boundaries
```

格式变化和行为修复不要压成一个 commit。

---

## 6. 验证

```bash
./gradlew :Core:test
./gradlew :Basic:test
./gradlew :BukkitPlugin:test
./gradlew :Core:test :Basic:test :BukkitPlugin:test
./gradlew build
```

必须增加的测试类或等价覆盖：

- `WorldSnapshotRevisionRestartTest`
- `WorldSnapshotValidatorTest`
- `WorldPersistenceCompressedRecoveryTest`
- `RailRenderSnapshotMigrationTest`
- `PersistenceTypeRegistryTest`
- `TrainDerailPersistenceTest`

---

## 7. 完成定义

- [ ] 连续两次以上重启和保存不会选择旧高 revision backup。
- [ ] backup-only 的完整 pair 可以恢复。
- [ ] 缺失/重复 Rail、非法 active branch、非法 curve/location 在 materialize 前失败。
- [ ] 新 extension JSON 不含 JVM class name；V1 白名单 fixture 可读。
- [ ] render assignment 与 Core/Basic infrastructure 共用 revision。
- [ ] 新 render snapshot 不持久化 segments/pointArcs。
- [ ] 新快照默认 gzip，旧未压缩快照可迁移读取。
- [ ] canonical curve points 未被删除、量化或重新生成。
- [ ] 脱轨状态 round-trip；live Controller 不被自动恢复。
- [ ] 所有相关测试和 `./gradlew build` 通过。
