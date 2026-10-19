# 第一批验证记录（2026-10-18）

## 候选与范围

基线`a76b076cfe632d8058528bffb3575adc2c4a93b5`已包含坐标统一和UI改进。当前候选位于`/workspace/minecraft/JavaRailways/.worktree/world-epoch-events`，分支`codex/world-epoch-events`，用户于2026-10-19批准本地提交，父任务整合尚待进行。

实现：World不可变LIVE/SIMULATION模式、统一WorldEvent来源、8类运行事件与Pre/Post来源、逐实际物理epoch通知、模拟正常碰撞事件、平台consumer模式过滤。完整机制由[事件规范](../../../spec/javarailways/core/event-bus.md)和[发布规范](../../../spec/javarailways/core/runtime-publication.md)拥有。

实现代理完成后冻结源，独立检查代理检查全部产品diff、测试、实际消费者及相关模块规范，没有发现行为缺陷。检查代理最后仅修改KDoc：说明coordinator/workers，并补本批属性文档；这些注释修改后执行diff检查，未重复整套编译、测试或Dokka。

最终26个修改/新增Kotlin文件集合SHA256：`a25c8b147768edae92befc773726bd5060254ddd70e56581670ed6d58d7cf1a9`。算法：按相对路径排序，逐项拼接`路径 + NUL + 文件内容 + NUL`。完整门禁与强制重跑覆盖最后的测试import整理；其后产品变更仅为上述KDoc。

## 执行结果

- Core：442项；Basic：413项；SimpleATO：378项；BukkitPlugin：447项；TunnelBridgeBuilder：31项。共1711项，失败/错误/跳过均为0。
- Core/Basic强制`--rerun-tasks`：855项，失败/错误/跳过均为0。
- Signal API Java consumer编译、Core Dokka和Bukkit资源任务触发的WebUI生产构建通过。
- 任务manifest与`git diff --check`通过。train-physics注入长度警告已通过读取原文相关章节处理。
- Dokka成功但存在既有未文档化API警告；本批新增文档警告随后通过KDoc修正，未再次运行Dokka，不宣称零警告。
- 主工作区原始78份WIP文件与预合并恢复清单SHA256逐一一致；未复制、覆盖或提交其他会话WIP。

```bash
GRADLE_USER_HOME=/workspace/.gradle ./gradlew \
  :Core:test :Basic:test :SimpleATO:test :BukkitPlugin:test \
  :BukkitPlugin:compileSignalApiConsumerJava :TunnelBridgeBuilder:test \
  :Core:dokkaGeneratePublicationHtml \
  --offline --no-daemon --no-build-cache --no-parallel --max-workers=2 \
  -Pkotlin.incremental=false \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs='-Xmx1400m -XX:MaxMetaspaceSize=640m'

GRADLE_USER_HOME=/workspace/.gradle ./gradlew \
  :Core:test :Basic:test --rerun-tasks \
  --offline --no-daemon --no-build-cache --no-parallel --max-workers=2 \
  -Pkotlin.incremental=false \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Dorg.gradle.jvmargs='-Xmx1400m -XX:MaxMetaspaceSize=640m'
```

进程日志：`/tmp/world-epoch-gate.log`（BUILD SUCCESSFUL，2m47s）、`/tmp/world-epoch-core-basic-rerun.log`（BUILD SUCCESSFUL，1m03s）。测试XML在各模块`build/test-results/test`。构建资源hook执行了既有npm ci；package.json与lock没有改动，没有新增依赖或升级。

## 已断言的边界

WorldEventTest验证8类事件、两种模式、相同列车名称和不同World对象。PhysicsEpochEventsTest验证正常/fallback/模拟、空World、合并唤醒、clamp、失败/关闭、listener异常、Pre先于计算、Post的同一已提交frame及提交锁外callback。真实碰撞fixture在LIVE和SIMULATION分别验证默认DERAIL及请求LINK；平台consumer回归验证模拟不进入renderer/CSV。

## 尚未验证或实现

本批未实现World执行隔离、完整SimpleSignals安装、headless onReverse及逐车领域行为；不得将本批绿测试当作全隔离/全Feature验收。未进行真实Paper/Folia服务器或浏览器交互验证。父任务最终联合并发及业务对照仍待子任务2–4完成。
