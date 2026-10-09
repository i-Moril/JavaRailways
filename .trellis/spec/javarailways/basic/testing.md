# Basic testing 契约

## 适用场景与用途

触发：新增Basic unit/integration test、fixture、Core↔Basic thread test、persistence fixture或Bukkit/WebUI downstream validation。

用途：复用Core fixture并显式注册world-local Basic data；测试观察typed result、no-partial mutation、publication与cleanup，不把disabled/legacy coverage算作contract。

## Owner、fixture 与 lifecycle

`TestBasicWorldDataRegistry.getOrCreate(world)`实际`world.registerExtensionData(BasicPlugin,data)`；weak registry只辅助lookup/cleanup，不是production fallback。测试结束调用`unregister(world)`并关闭Core PhysicsCoordinator/TickManager。

```kotlin
@Synchronized
fun register(world: World, data: BasicWorldData) {
    val plugin = BasicPlugin.instanceOrNull()
        ?: BasicPlugin(0).also { BasicPlugin.INSTANCE = it }
    world.registerExtensionData(plugin, data)
    registry[world] = WeakReference(data)
}
```

来源：`Basic/src/testFixtures/kotlin/work/art1st/javarailways/basic/testing/TestBasicWorldDataRegistry.kt`。

Basic tests优先复用Core `createTestWorld/createStraightRailFromSegments/createTrainWithConfig/AsyncTickTestSupport`；production不得依赖fixture，新test不复制private/reflection builder。

## Assertion matrix

| 主题 | 必需assertion points |
| --- | --- |
| world/thread | world isolation、queue revision、publish前后、exact ticket、Stall first failure、cleanup |
| ATO policy | typed resolve/revision result、constraint math、no mission zero、diagnostic/freeze |
| Destination/domain | canonical IDs、all derived stores、revision/no partial、tail/full envelope |
| curve/rail | template token、SI单位、off-main fail、endpoint/frame/radius/roll、topology+metadata rollback |
| route/resource | actual weighted shortest、AB/BA/loop、compile failure、atomic turnout/no preempt、tail release |
| timetable | validator no mutation/no Stall、clock/state ticket gates、manual/recovery、simulation deterministic |
| persistence | fixed JSON、strict unknown/future、orphan/duplicate/collision、failure target unchanged、pairing |

每个test同时覆盖Good/Base/Bad：有效路径；boundary（zero/empty/tolerance/last tick）；非法/缺失/stale/failure及state不变。Global PluginRegistry/EventBus/ThreadGuard与world registrations显式teardown。

## 项目内正确示例

Revision queue并发test用latch/futures后断言完整序列：

```kotlin
val revisions = queue.drain().map { it.revision }
assertEquals((0L until 100L).toList(), revisions)
```

来源：`Basic/src/test/java/work/art1st/javarailways/basic/api/ato/PhaseAFoundationTest.kt`。它验证多producer的contract，而不是依赖thread scheduling顺序。

## 禁止实现

- 把disabled或已删除旧实现的测试算作当前拓扑planner覆盖；
- `Thread.sleep`猜physics/topology完成；
- same helper生成expected与actual persisted token；
- 只断言异常类型、不断言失败后stores/resources不变；
- 复制Core internal builder或production fallback到test registry；
- 忘记unregister/close。

## 验证矩阵

```bash
./gradlew :Core:test :Basic:test --rerun-tasks
./gradlew :Core:dokkaGeneratePublicationHtml :Basic:dokkaGeneratePublicationHtml --rerun-tasks

# Basic public/platform contract受影响
./gradlew :BukkitPlugin:test :WebUI:test
npm --prefix WebUI/frontend test

git diff --check
```

Dokka task成功仍人工审查warnings；focused tests只用于开发反馈，不能替代最终Core/Basic全量。
