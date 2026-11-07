# F1–F8 部署前最终复核

2026-11-07；基于 `5ce07279`，复核当前未提交 F1–F8 工作树。范围只包括物理帧统一、滚动准入车身几何、整车原生限速、授权交接与精确端点；不包括已提交的 F9 车门验证、其他延后审查项或原有无关 dirty 文件。

## 结论

- 未发现新的 F1–F8 阻断项，本轮没有修改生产或测试代码。
- Core 仍保持完整 frame 发布后再通知 controller；生产源码无 `TrainSnapshot`/`trainSnapshot` 残留。
- 净空车长只来自同一当前 frame 的 `bodyTraversal`，缺几何明确失败，没有恢复缺长度→整路线申请。
- 保留授权按当前 Core frame 定位；换向 rebase/编组 grant adopt 后立即安装新坐标轴。SimpleSignals 只在确切已知端点复用原累计值，内部点无新容差或截断。
- 规范差异与实现同步；`git diff --check` 通过。

## 当前强制验证

```bash
./gradlew :SimpleATO:test :SimpleSignals:test --rerun-tasks --offline --console=plain \
  --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx2g \
  -I /tmp/jr-simpleato-audit-az6Vw0/concurrent-tests.init.gradle \
  --tests '<F1–F8 focused classes>'
```

结果：SimpleATO 809 项、SimpleSignals 48 项，0 failure/error/skipped；所有任务强制重新编译/执行。

```bash
./gradlew :Core:test :Basic:test --rerun-tasks --offline --console=plain \
  --max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx2g \
  -I /tmp/jr-simpleato-audit-az6Vw0/concurrent-tests.init.gradle \
  --tests '*PhysicsCoordinatorTest' --tests '*CoreTrainPublicationBoundaryTest' \
  --tests '*ImmutableSnapshotTest' --tests '*PhaseAFoundationTest'
```

结果：Core 541 项、Basic 5 项，0 failure/error/skipped；所有任务强制重新编译/执行。

```bash
./gradlew :BukkitPlugin:compileKotlin :BukkitPlugin:compileJava \
  :BukkitPlugin:compileTestKotlin :BukkitPlugin:compileSignalApiConsumerJava \
  verifySignalModuleBoundaries --offline --console=plain --max-workers=1 \
  -Pkotlin.daemon.jvmargs=-Xmx2g \
  -I /tmp/jr-simpleato-audit-az6Vw0/concurrent-tests.init.gradle
```

结果：通过；同时生成宿主 `shadowJar` 并校验 SimpleSignals plugin JAR。仅见已有 JDK 自动探测、弃用 API 与非空断言编译警告。

## 复用的现有证据与边界

`authority-handoff-validation.md` 的联合 3320 通过/1 原有跳过以及原输入回放越过 Tick2002/Tick14219 仍有效；本轮又重跑了与代码变更直接相关的回归。没有再跑远程服务器或 Minecraft 客户端验收。当前真实回放只证明原 14219 故障链消失，后续时刻表是否完整跑通仍需部署后重新全局模拟。

## 提交边界

应精确提交当前 50 个 F1–F8 代码/测试/规范文件，再加本报告。明确排除 `TODO.md`、两个 `10-18-simulation-signal-features` deployment research、三个 `ai_tasks` 无关文件与 `tmp.1`。完整的 50 文件列表由当前 `git diff --name-only` 排除 `TODO.md`，再加三个新文件：`NativeBodySpeedLimits.kt`、`NativeBodySpeedLimitsTest.kt`、`DirectedRouteBoundaryPrecisionTest.kt`。
