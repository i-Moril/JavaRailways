# Basic line 契约

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
    val color: String? = null
)
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/api/line/Line.kt`。

- `LineId`world-local stable、trimmed、no control char。
- UP/DOWN独立有序，各2..128 stations、无duplicate、defensive copy。
- color nullable或严格`#[0-9a-fA-F]{6}`。
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
| Timetable service line speed | 用`service.lineId`解析 | 不用`diagramLineId` |

Through-running跨line boundary必须显式更新current infrastructure line与cap；不得把未完成实现写成已支持，其implementation issue单独跟踪。

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
./gradlew :Basic:test --tests '*LineStoreTest' --tests '*LinePlannerTest' --tests '*LineMaximumSpeedResolverTest'
```
