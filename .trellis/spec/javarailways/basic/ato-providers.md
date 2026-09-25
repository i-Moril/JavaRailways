# Basic ATO provider 契约

## 适用场景与用途

触发：注册`AtoProviderFactory`、创建per-train provider、增加`AtoProviderBoundary`或在`SimpleATO.plan`调用provider。

用途：provider只把immutable perception映射为当前Tick constraints；不得在physics worker修改world/train/turnout或执行platform/I/O。

## Owner、真实签名与lifecycle

每个`BasicWorldData.atoProviders`是world-local registry：

```kotlin
interface AtoProvider {
    val id: String
    fun constraints(context: AtoProviderContext): Collection<AtoConstraint>
}
fun interface AtoProviderFactory {
    fun create(train: Train): AtoProvider
}
```

来源：`Basic/src/main/java/work/art1st/javarailways/basic/api/ato/provider/AtoProvider.kt`。

`AtoProviderContext(train:TrainSnapshot,route:RouteSnapshot,perception:PerceptionSnapshot)`全部value。`register(id,factory)`要求nonblank且duplicate fail；`create(id,train)` unknown→error；factory每train创建instance，返回`provider.id`必须等于requested stable ID。

`AtoProviderBoundary(ref,location,providerId,action,direction?)`是world RailObject；action token`ACTIVATE/DEACTIVATE`。只有matching direction的`PASS` transition切换。register/unregister/lifecycle归同world`RailObjectStore`；world unload不共享provider instance。

## 调用与结果矩阵

| 条件 | 结果 | state/side effect |
| --- | --- | --- |
| duplicate registry ID | fail-fast | old factory保持 |
| unknown provider ID | ATO exception→owning world Stall | 其他world不受影响 |
| returned ID mismatch | invariant failure→Stall | provider不激活 |
| boundary ENTER/UPDATE/EXIT | 不切换 | current provider保持 |
| PASS + direction match | activate/deactivate at physics planning boundary | next constraints使用新provider |
| provider returns constraints | replace provider owner的current-Tick set | collection copied/validated |
| provider exception/mutation invariant | Stall+freeze，保留diagnostic provider ID | 不尝试platform rollback掩盖bug |

Provider必须non-blocking、无I/O、无Bukkit/Paper、无turnout/train/world mutation；只读context并返回constraints。stable ID不用class name reflection。

## 项目内正确示例

Registry对duplicate ID采用`putIfAbsent`：

```kotlin
fun register(id: String, factory: AtoProviderFactory) {
    require(id.isNotBlank())
    check(factories.putIfAbsent(id, factory) == null) {
        "ATO provider already registered: $id"
    }
}
```

来源：`AtoProvider.kt`。它避免插件reload静默覆盖正在运行列车的provider语义。

## 禁止实现

```text
错误：constraints()内selectNextRail、train.move、Bukkit API、HTTP/DB
正确：immutable context → Collection<AtoConstraint>；side effect转给main owner
```

不得让factory返回singleton跨world/per-train共享mutable state，也不得每Tick累积旧constraints。

## 必需测试与验证

- registry duplicate/unknown/returned ID mismatch。
- SimpleATO `SimpleATOTest.kt`：activate/deactivate PASS、direction mismatch、unknown只Stall owning world、turnout mutation被before-movement invariant捕获。
- provider test断言context snapshots immutable、exception报告含provider ID、freeze speed=0。

```bash
./gradlew :Basic:test --tests '*RailObjectStoreTest'
./gradlew :SimpleATO:test --tests '*SimpleATOTest'
```
