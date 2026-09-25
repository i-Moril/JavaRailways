# Core testing 契约

## 适用场景与用途

触发：新增Core test、跨模块fixture、thread/topology/physics/persistence regression，或修改`java-test-fixtures`。

用途：测试必须观察真实publication/barrier与cleanup，不用sleep、reflection或same-helper自洽掩盖错误。

## Fixture owner 与签名

Core `java-test-fixtures`导出：

- `Core/src/testFixtures/kotlin/work/art1st/javarailways/core/physics/train/TrainTestUtils.kt`：world/rail/train、AB/BA/loop builders；
- `Core/src/testFixtures/kotlin/work/art1st/javarailways/core/util/AsyncTickTestSupport.kt`：安装与production worker相同marker。

真实worker helper：

```kotlin
object AsyncTickTestSupport {
    fun <T> run(block: () -> T): T =
        AsyncTickThreadMarker.runInAsyncTickContext(block)
}
```

consumer test通过`testFixtures(project(":Core"))`复用；production不得依赖fixture。`createTestWorld()`创建TickManager与PhysicsCoordinator，caller在`finally/use`关闭并清world/global registrations。

## 测试矩阵与 assertion points

| 主题 | 必需断言 |
| --- | --- |
| physics | exact ticket epoch、before/after publication、delta单位/clamp、failure→Stall、finally close |
| thread | wrong thread fail-fast；annotation本身不调度；marker lexical cleanup |
| curve/topology | AB/BA、endpoint/frame、turnout、body occupancy、transaction reader、rollback |
| train | head/tail/bogie/car spacing、reverse own epoch、collision next epoch、destroy全cleanup |
| render | value-only、defensive copy、revision stale、coalesce/capacity、无live补读 |
| event/global registry | handler顺序/failure、token close、test后无registration、slot不依赖order |
| persistence | hand-written fixed JSON、strict unknown/future、orphan/duplicate、failure无partial state |

## 结果/资源矩阵

| 测试动作 | 正确结束状态 | failure时仍必须 |
| --- | --- | --- |
| coordinator test | awaited ticket或明确exception | `close()` |
| process-wide bus registration | token closed | `finally close` |
| ThreadGuard bridge install | previous bridge restored/cleared | 不污染后续test |
| world materialization失败 | 无partial registered train | close created resources |
| concurrent barrier | latch/future确定时序 | 不用固定sleep猜测 |

## 项目内正确示例

`Phase`类并发测试应采用exact future/ticket；Core fixture的marker只包lexical block：

```kotlin
AsyncTickTestSupport.run {
    controller.tick(50.0)
}
```

来源：`Core/src/testFixtures/kotlin/work/art1st/javarailways/core/util/AsyncTickTestSupport.kt`及SimpleATO `SimpleATOTest.kt`。它验证runtime guard而不把测试线程永久标记为worker。

## 禁止实现

- `Thread.sleep`证明锁/epoch完成；
- 只累加local distance、不断言train published location；
- disabled test算绿色coverage；
- reflection写private registry作为新fixture contract；
- 同一serializer/helper同时生成expected token与actual；
- 忘记关闭world/coordinator/executor/event token。

## 必需验证

```bash
./gradlew :Core:test :Core:testFixturesClasses
./gradlew :Core:dokkaGeneratePublicationHtml
# Core contract consumers
./gradlew :Core:test :Basic:test :BukkitPlugin:test :TunnelBridgeBuilder:test
git diff --check
```

修改单主题时可先跑focused tests，但交付前必须运行`./gradlew :Core:test :Basic:test --rerun-tasks`。
