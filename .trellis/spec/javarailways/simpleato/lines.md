# SimpleATO line 契约

## 适用场景与用途

触发：修改`Line/LineStore/LinePlanner`、UP/DOWN patterns、revision、line speed或Timetable line identity。

用途：Line是可复用infrastructure identity，不是diagram presentation；每次编辑用expected revision防lost update，planning按exact/available berths选择最短directed route。

## Owner、字段与revision

```kotlin
data class Line private constructor(
    val id: LineId,
    val displayName: String,
    val upStationIds: List<StationId>,
    val downStationIds: List<StationId>,
    val color: String? = null,
    val maximumSpeedMetersPerSecond: Double? = null
)
```

来源：`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/line/Line.kt`。

`LineStore` 由每世界唯一的 `SimpleATOWorldData.lines` 持有。Station 与 Destination 同样由
`SimpleATOWorldData`唯一持有；Line planner从这些canonical stores读取停靠点与方向，再把显式方向传给
SimpleATO共用`OperationalRoutePlanner`。该seam在同一个immutable `TopologyState`上组合运行方向和所有已注册source。

- `LineId`world-local stable、trimmed、no control char。
- UP/DOWN独立有序，各2..128 stations、无duplicate、defensive copy。
- color nullable或严格`#[0-9a-fA-F]{6}`。
- `maximumSpeedMetersPerSecond` 是线路设置，单位 m/s；允许 null 表示尚未配置，非空必须有限且严格正数。线路保存、HTTP 和基础设施快照必须保留它。WebUI 以 km/h 编辑，只在边界换算。
- `LineStoreSnapshot(revision:Long,lines:List<Line>)`稳定按ID排序。
- `put(line,expectedRevision): Long`；`remove(id,expectedRevision): Pair<Line?,Long>`；mismatch抛`LineRevisionConflict(expected,actual)`且不mutation。
- persistence `restore(values,savedRevision)`只允许fresh store，duplicate fail并恢复该edit revision。它不是schemaVersion。

`LinePlanner.compile(line,direction): Success(line,legs)|Failure(line,legIndex,reason)`；从每站berth destination组合找最短directed route。reset candidate跨站携带actual end direction。

## 结果矩阵

| 条件 | result | state |
| --- | --- | --- |
| invalid pattern/color/id | constructor failure | Line不创建 |
| expected revision stale | `LineRevisionConflict` | store/revision不变 |
| remove missing ID且revision current | `(null,same revision)` | 不增revision |
| station无berth/route不可达 | `LineCompileResult.Failure`含leg index | 不返回partial success |
| exact berth不属于station | Failure/argument failure | 不猜其他berth |
| multiple berth routes | 选`ExpandedRoute.totalLength`最小 | 保持direction |
| Timetable line speed | 分段时用当前 `runSegments.lineId`，未分段用 `service.lineId` | 不用 `diagramLineId` |

停车跨线已实现：当前运行段与 cap 按实际执行地址切换，段间程序保留上一限制。具体范围、初始化和错误契约见[停车跨线与单元显示](./through-running-and-unit-displays.md)。不停站跨线尚未实现。

## 项目内正确示例

Revision check在任何mutation前：

```kotlin
private fun checkRevision(expected: Long) {
    val actual = revision.get()
    if (expected != actual) throw LineRevisionConflict(expected, actual)
}
```

来源：`Line.kt`。它让HTTP `If-Match`类consumer可靠检测并发编辑。

## 禁止实现

- 合并UP/DOWN或允许pattern duplicate；
- `diagramLineId`作为infrastructure LineId；
- stale revision last-write-wins；
- route按rail hop数/首个berth而非最短directed distance；
- line revision作为schema version；
- service跨line后沿用旧cap。

## 必需测试与验证

- `LineStoreTest.kt`：pattern、color、copy、revision conflict/remove/restore stable order。
- `LinePlannerTest.kt`：shortest berth combination、exact berth、direction carrying、failure leg index。
- `LineMaximumSpeedResolverTest.kt`：`service.lineId`与missing cap。
- Dispatcher through-running test：boundary前后cap owner、published ATO constraint与currentLineId。

```bash
./gradlew :SimpleATO:test --tests '*LineStoreTest' --tests '*LinePlannerTest' --tests '*LineMaximumSpeedResolverTest'
```

## 线路限速的编辑归属

CURRENT：最高速度在 Lines 页面编辑，run 只引用线路，不再持有限速编辑入口。结构化编辑文档不独立维护线路速度；生成 JS、前端校验和单 run Reset 从当前线路集合读取上限。缺少线路或未配置速度时明确阻止依赖该速度的操作，不沿用旧文档中的速度，不猜默认值。线路变化必须使先前编译与模拟结果失效。

已编译 Timetable 的 `lineSpeedLimits` 是运行快照，仍由现有 resolver 按实际运行段使用。修改线路并不会就地重写已发布时刻表；重新生成、编译和发布才更新其快照。例如 Lines 中将 72 km/h 改为 90 km/h 后，下次生成使用 25 m/s，已发布的 20 m/s 快照保持原值。

持久化新增 nullable 字段使用缺省 null，不提升开发格式版本。已有开发线路需要在 Lines 中明确设置速度；本次不自动把任意时刻表文档的速度合并进线路，因为不同文档可能冲突。部署前应补齐所需线路配置，不新增永久旧格式兼容路径。

回归验证覆盖线路速度的非法值和快照往返、HTTP 输入输出、km/h 转换、run 无编辑入口、生成/Reset 使用当前线路及缺省错误。
