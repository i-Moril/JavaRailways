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

以下是整体目标顺序，不代表所有子系统检查已在创建world之前完成。rail连接图与curve现在在创建world之前完整预校验；train等业务恢复仍沿用各自边界。当前codec先parse为JsonObject、检查exact root formatVersion，再对同一树严格decode；版本缺失、非整数或非current在任何extension decoder调用前拒绝。extension三类map使用[显式稳定类型注册](./extension-data.md#已注册payload的持久化边界current)，不再按存档FQCN反射。当前train load在构造返回后、成功frame/mailbox发布前执行[摆位校验](./train-physics.md#存档摆位恢复)，验证同一路径/方向/顺序、车内连接点三维距离、相邻车钩与车端offset；失败沿既有discardFailedLoad撤销本次列车副作用。rail连接使用下方严格端点图预校验，但全world materialization的完整预校验/失败回滚仍不能由codec保证，不把下图当作全部现状验收结论。

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

### 显式目标端点的保存和恢复

`CURRENT`，Core拥有该连接格式及校验。`SavedRailV1`的`connectionsAtA/B`为有序`List<SavedRailEndpointRef>`，
`activeConnectionAtA/B`为同一值类型或null。每个值为`{"railId":"<uuid>","endpoint":"A"}`，端点只接受`A/B`。
source端由所属A/B字段决定；target的UUID与端点共同构成身份，active也必须按两者比较，不能只比较railId。
writer直接从目标away-view取身份：AB进入目标A、BA进入目标B。连接仍在rail-local列表双向保存，不增加世界级连接catalog。

正常加载先以UUID索引所有保存的rail并拒绝重复ID，再解码每份curve，通过`decodeSavedRailGraph(savedById, curvesById)`
生成完整端点状态图。该函数与prepared restore共享`decodeSavedRailEndpoint`：检查source存在、目标UUID有效且存在、
无同端自引用、精确端点不重复、至多两项、非null active属于本端列表，再按目标声明端点检查`Curve.TOLERANCE=5e-3 m`共点。
最后检查所有有向记录的精确反向声明。所有这些检查完成后才创建CoreWorld和disconnected rails；已解码curve与已验证state直接用于安装，
不为每条连接重新解码curve。单rail restore仍先解析并resolve双端，再修改任一live endpoint。

几何只验证声明身份：例如source B只与target A共点时，输入target B必须拒绝，不能因为A更近而改为A。
未知endpoint token由严格serializer拒绝；非法UUID、缺目标、重复/超度数、错误active、非共点或不对称连接由验证器失败，
错误携带相关rail/endpoint。坏连接图在world构造和parent binding前失败，不宣称这实现了其他world资源的通用rollback。
合法null、连接顺序和第二条active原样保留；active-only restore复用同一topology root，structural undo按完整身份保留仍有效active，
具体事务语义归[rail topology](./rail-topology-and-occupancy.md)。隔离模拟按railId再endpoint确定排序选择，不按几何猜测。

索引和连接验证为`O(N+E)`时间与空间，另计各curve解码及既有空间索引成本；仅在保存/加载/结构prepare路径，不增加tick查询。
curve只在本次prepare/load内复用，没有跨revision持久缓存。

该未发布开发格式直接修正，Core infrastructure仍为2，runtime仍为1，不因改字段增加版本或新增SavedRailV2。
UUID-only字段`nextRailAtA/B`、`activeTrackAtA/B`只在[独立离线工具](../../bukkit-plugin/backend/offline-development-migration.md)中转换，
current reader不同时接受两套形状。错误做法是启用未知字段容忍或根据距离补齐当前缺失身份；正确做法是完整外部恢复点后离线转换并严格验证。
该兼容转换只服务保留的开发数据，用户确认全部转换后可另批删除；不进入插件JAR。远端应用与新JAR部署需要单独授权。

固定测试需覆盖JSON明确端点及未知token拒绝、A-A/A-B/B-A/B-B、同railId不同端点的active比较、顺序/null/第二active、
完整图不对称/重复ID/共点错误在world创建前失败，以及prepared target snapshot与undo不再把目标降格成UUID。

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

## 单一弧长位置（2026-10-19）

RailLocationWithFacing 以插值点的弧长作为唯一位置，不再保存或叠加 minorDeltaS。任意合法弧长由现有分段线性插值求值；反向及保存只进行一次 AB/view 弧长转换。当前未发布格式保持既有版本，不新增旧字段 runtime reader。

已只读核对 JavaRailwaysTest 开发服六个世界的十二份 runtime 主文件/备份：展开 coreSnapshotJson 后 trains 均为空；当前 infrastructure/runtime/rail-render 文件及备份中未发现 minorDeltaS。针对这些存档无需迁移。此证据只覆盖核对时的落盘文件，不等同于实时内存列车清单，也不涵盖目录外历史归档。未修改开发服数据。

加载失败的discardFailedLoad同步尝试撤销全部Core注册，并返回子资源清理的CompletionStage。同步load立即重抛原失败，不阻塞owner等待平台任务；稍后清理失败附加原异常并令coordinator stall。各清理独立尝试，不能因head或car清理失败跳过world注销。
