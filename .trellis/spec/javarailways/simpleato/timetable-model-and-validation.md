# SimpleATO timetable model 与 validation 契约

## 适用场景与用途

触发：修改Timetable/Service/Step/Action、editor rows、activation/condition、line speed、manual DSL或`TimetableValidator`。

用途：runtime execution authority只来自validated generated steps；presentation/authoring metadata不能绕过validation，invalid timetable不安装controller/run/resource且不Stall world。

## Owner、字段、token与时间

```kotlin
data class Timetable(
    val id: String,
    val periodDays: Long = 1L,
    val services: List<TimetableService> = emptyList(),
    val initialPositions: List<TimetableInitialPosition> = emptyList(),
    val lineSpeedLimits: List<TimetableLineSpeedLimit> = emptyList()
)
```

来源：`SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/Timetable.kt`。

`periodDays>0`；一天固定`24_000` Tick；`periodTicks=Math.multiplyExact(periodDays,24000)`。`TimetableService.steps`是runtime authority；`editorRows`、diagram line/arrival/compiler trace只presentation/authoring。`service.lineId`才是Basic infrastructure identity。

Step token：`Check(wayPointId)`、`At(tick,action)`。Action包括TakeFromDepot/Waypoint、StoreAtDepot、GoTo/GoVia、Stop、Extension/Conditional、Halt、Reverse、DestinationOptions、CallStation。`At.tick`为timetable-relative action/departure tick，nonnegative且period内、非降序。

## Validator gate与result

`TimetableValidator.validate(timetable): TimetableValidationResult(errors:List<String>,routes:List<ExpandedRoute>)`汇总全部errors并保留`[timetable/service] step#n`上下文；`isSuccess=errors.isEmpty()`。

| 条件 | error/result | mutation |
| --- | --- | --- |
| duplicate/blank IDs、period overflow | contextual errors | none |
| Line missing或service无line max speed | errors | none |
| AnyTrain未以唯一take action开始 | errors | none |
| explicit train movement前无Check | errors | none |
| 无movement、Stop/extension/conditional早于first movement | errors | none |
| At越period或decreasing | errors | none |
| station/destination options不匹配next movement | errors | none |
| store非home/非last，Halt非last | errors | none |
| route/direction/berth/provider/parameters unresolved | errors + failure context | none |
| valid | `isSuccess=true`并返回pre-expanded routes | Dispatcher才可begin/start |

Manual parser/factory、timetable model、validator、runtime store、extension registry 与 JSON 展示均由 `SimpleATO` 拥有。token `+id` 表示 pass；final 必须 stop。stop JSON strict 且只允许 `minimumDwellTicks/stoppingOffsetMetersBefore/doorSide`；unknown/type error拒绝。Destination、Station、Depot 与 Line 当前仍由 Basic 提供，因此 validator 暂时通过 Basic 的只读运营存储解析这些引用；这不是 Basic 对 timetable 的反向 ownership。

## 项目内正确示例

Validator捕获period overflow而不是让exception逃到Dispatcher：

```kotlin
val periodTicks = runCatching { timetable.periodTicks }.getOrElse {
    errors.add("时刻表周期 Tick 溢出: ${timetable.periodDays} days")
    Long.MAX_VALUE
}
```

来源：`SimpleATO/.../timetable/Timetable.kt`。它让同一validation run继续收集其他可定位错误。

## 禁止实现

- editorRows/diagram metadata直接执行；
- adapter重复或放宽SimpleATO validation；
- invalid timetable先安装controller/run再报错；
- validation failure Stall world；
- 用diagramLineId解析line cap；
- manual options允许unknown fields或final pass。

## 必需测试与验证

- `TimetableValidatorTest.kt`：所有gate、context、route expansion、line speed、invalid station approach且run/controller absent/world未Stall。
- `SimpleATO/ManualNavigationTest.kt`：`+pass`、final stop、strict JSON/type/token、station role生成CallStation及原 Dispatcher 集成行为。
- `TimetableSnapshotTest.kt`：presentation survives JSON但restore不进入runtime authority。

```bash
./gradlew :SimpleATO:test --tests '*TimetableValidatorTest' \
  --tests '*TimetableSnapshotTest' --tests '*ManualNavigationTest'
```
