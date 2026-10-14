# Core persistence 契约

## 适用场景与用途

触发：修改Core infrastructure/runtime JSON、rail/train DTO、extension payload、revision/epoch pairing或restore。

用途：只给独立document boundary编号，strict decode后完整预校验再materialize；版本、revision、epoch与extension identity不得混用。通用政策见[共享持久化规范](../shared/persistence.md)。

## Owner、独立 boundary 与精确字段

`CoreWorld`拥有两个文档：

```kotlin
data class SavedInfrastructureV2(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val rails: List<CoreRail.SavedRailV1>
)
data class SavedRuntimeV1(
    val formatVersion: Int,
    val infrastructureRevision: Long,
    val physicsEpoch: Long,
    val trains: List<SavedTrainV1>
)
```

来源：`Core/src/main/java/work/art1st/javarailways/core/physics/CoreWorld.kt`。

- infrastructure current `formatVersion=2`；runtime current `formatVersion=1`。
- `infrastructureRevision>=0`仅配对同批documents；`physicsEpoch>=0`仅记录capture publication时序，均非schema version。
- `SavedRailV1/SavedTrainV1`是outer文档nested DTO，不自动独立升版。
- runtime可缺失；存在时version exact current且revision等于infrastructure。
- train恢复只经`SavedTrainV1 -> SavedCarV1 -> CarConfigV1.buildCar`构造current runtime；Core不维护另一套按字符串prototype
  组装列车的`TrainFactory/TrainConfig/BogieConfig`注册表。删除该未接线构建链不改变上述current DTO字段、serializer或format version。

`WorldSnapshotCodec`固定 `ignoreUnknownKeys=false`、`encodeDefaults=true`。writer只写current。

`SavedCarV1`另有`installedReversed: Boolean = false`，表示相对所属列车稳定cars顺序的安装朝向。列车保存/加载时按[train physics的混合朝向表示](./train-physics.md)映射每车运营端，不改变物理A/B或已保存位置。该新增字段有确定默认值，runtime仍为1，缺字段的同向编组输入由current decoder直接读取；不新增版本reader或按几何猜朝向的兼容路径。此处不承诺旧程序能读取含新字段的文件。

## Decode、prevalidation、materialization

以下是整体目标顺序，不代表所有检查已在创建world之前完成。当前codec先parse为JsonObject、检查exact root formatVersion，再对同一树严格decode；版本缺失、非整数或非current在任何extension decoder调用前拒绝。extension三类map使用[显式稳定类型注册](./extension-data.md#已注册payload的持久化边界current)，不再按存档FQCN反射。当前train load在构造返回后、成功frame/mailbox发布前执行[摆位校验](./train-physics.md#存档摆位恢复)，验证同一路径/方向/顺序、车内连接点三维距离、相邻车钩与车端offset；失败沿既有discardFailedLoad撤销本次列车副作用。rail连接使用下方严格端点解析，但全world materialization的完整预校验/失败回滚仍不能由codec保证，不把下图当作全部现状验收结论。

```text
bytes/text → read boundary version → optional pure upcast（仅任务明确支持）
→ strict current DTO → all identity/reference/business prevalidation
→ create fresh world → load all disconnected rails → restore connections
→ load trains with identity context → attach ephemeral runtime separately
```

| 条件 | 结果 | target state |
| --- | --- | --- |
| unsupported/future version | fail-fast含version | 不materialize |
| runtime/infrastructure revision mismatch | fail-fast | 不materialize |
| runtime absent | infrastructure-only world | 无trains |
| train location missing rail/invalid placement | `CorruptedDataException`并包装train name/id | failed train side effects清理 |
| duplicate train ID/name/namespace | fail-fast | fresh/unchanged |
| additive default field | current reader读旧fixture，不升boundary version | writer输出default |
| breaking shape/unit/meaning | 升最小boundary；decode边界单向upcast | business只见current |
| unknown extension type/version | stable registry fail-fast | 禁止反射/忽略 |

Rail connection restore与prepared snapshot restore共用Core内部`decodeSavedRailEndpoint`，只解析存档明确声明的rail ID，不扫描附近轨道补连接。每端引用必须是有效UUID、目标存在、真实端点在`Curve.TOLERANCE`内共点，且不重复/不超过两项；非null active必须在本端连接中，合法null与声明顺序原样保留。单rail的A/B均完整解析和resolve后才修改任一live endpoint；全图对称性仍由prepared validator或load的TopologyStateBuilder验证。坏引用拒绝，不静默删除/选首支路；合法运行时remove和preserve-active undo的fallback是不同边界，不受此规则改变。此保证不是全world load rollback；开发数据如需修复须离线审计迁移，不在JAR猜测兼容。

`physicsEpoch`不能被解释为restart后coordinator sequence自动续接。

## 项目内正确示例

codec先验证根版本，再解码同一棵树：

```kotlin
json.decodeFromJsonElement(
    CoreWorld.SavedRuntimeV1.serializer(),
    currentDocument(data, CoreWorld.RUNTIME_SNAPSHOT_FORMAT_VERSION, "runtime")
)
```

来源：`Core/src/main/java/work/art1st/javarailways/core/persistence/WorldSnapshotCodec.kt`。`currentDocument`只解析JSON和检查版本，不materialize world或调用payload decoder；future root携带坏nested内容时必须先报root版本错误。解析树成本随文档长度增长，没有第二次文本解析，但会保留一次树内存；不能宣称版本预检不读取整份文本。

## 禁止实现

- 用revision/epoch代替schemaVersion；
- 每个nested DTO机械V2；
- 为通过旧fixture全局`ignoreUnknownKeys=true`；
- business层分支处理V1/V2；
- reader按存档FQCN `Class.forName`；
- decode过程中启动DI/executor或留下partial train/world。

## 必需测试与验证

- fixed current/future/corrupt/unknown-field JSON，不只writer→reader round-trip。
- additive field：fixed old fixture、default、round-trip、version不变。
- breaking migration（仅明确支持）：每级pure upcaster、missing step/future fail、writer current-only。
- `WorldPersistenceRoundTripTest.kt`：version/invalid JSON/train identity。
- `CoreTrainReversedPersistenceTest.kt`：AB/BA exact placement与failed load cleanup。
- `SplitWorldSnapshotTest.kt`：revision pairing与完整resource close。
- `SavedRailConnectionsTest.kt`：load/prepared两入口拒绝坏引用/接头/active；AA/AB/BA/BB连接、顺序与null/第二支选择；B端坏输入在A/B mutation前拒绝。

```bash
./gradlew :Core:test --tests 'work.art1st.javarailways.core.persistence.*' \
  --tests '*CoreTrainReversedPersistenceTest' --tests '*RailLocationPersistTest'
```
