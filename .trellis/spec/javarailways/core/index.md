# Core 可执行契约索引

## 范围与加载条件

本层由 `Core/` 拥有。修改候选 `core.api.*`、事件、physics epoch、curve/rail/train、render frame、extension/DI、Core snapshot 或 Core fixtures 时，加载下表对应单主题文档。Basic 的运营方向、速度、route/berth/depot 规则不在本层定义；Bukkit/Paper 的 scheduler、entity、viewer 与 model side effect 也不在本层定义。

跨模块规则见[共享规范](../shared/index.md)。

## 主题导航

| 文档 | 何时加载 | 唯一归属 |
| --- | --- | --- |
| [API 边界](./api-boundaries.md) | 改 `core.api.*`、Dokka 或 consumer imports | 候选 API、value/live 边界、dependency direction |
| [事件总线](./event-bus.md) | 发布/监听 Main 或 Physics event | 同步派发、handler、registration token、World来源与物理epoch通知 |
| [runtime publication](./runtime-publication.md) | 改 coordinator、World owner、ticket、epoch、帧间操作、TickManager、mailbox | 每World串行归属、physics/generic Tick顺序、帧间准入与成功边界 |
| [curve geometry](./curve-geometry.md) | 改 `Curve`/`PrimeCurve` 或 AB/BA 数值转换 | curve 输入输出、米制弧长与 surgery |
| [rail topology 与 occupancy](./rail-topology-and-occupancy.md) | 改 connection、split/merge/remove、占用 | topology transaction、swept interval、release |
| [版本化铁路拓扑](./versioned-operational-topology.md) | 改寻路图、结构节点、拓扑 snapshot 或外部 rail-slice 引用 | 唯一 immutable state、edge/node 归一化与原子 publication |
| [train physics](./train-physics.md) | 改 consist movement、collision、derail、reverse、结构生命周期 | 列车物理与两阶段碰撞 |
| [固定单元组成](./train-composition.md) | 改默认单元、重联组成、解编边界、单元快照与恢复 | Core 单元身份与车辆成员 |
| [train placement](./train-placement.md) | 改新建列车唯一放置、prepare/install与模拟播种 | 完整车体候选与一次安装 |
| [train controller](./train-controller.md) | 实现/替换 controller 或 command | `TrainController` worker contract |
| [render publication](./render-publication.md) | 增加 frame 字段或平台消费 | value frame、revision、latest mailbox |
| [extension data](./extension-data.md) | 注册 Plugin slot 或 host data | runtime slot、save identity、initialize/save |
| [DI 与 runtime lifecycle](./di-and-runtime-lifecycle.md) | 创建/卸载 world、注册 processor/executor | composition ordering 与成对释放 |
| [持久化](./persistence.md) | 改 Core infrastructure/runtime JSON | document version、strict codec、restore 顺序 |
| [测试](./testing.md) | 新增 Core/consumer test 或 fixture | fixture、barrier、cleanup 与验证矩阵 |

一个 invariant 只由表中一页定义；交界页通过链接引用，不复制规则。例如 frame shape 由 render 页拥有，ticket 成功时点由 runtime 页拥有。

## 模块验证

```bash
./gradlew :Core:test :Basic:test
./gradlew :Core:dokkaGeneratePublicationHtml
python3 ./.trellis/scripts/get_context.py --mode packages
git diff --check
```

API/consumer contract 变化再运行 `./gradlew :BukkitPlugin:test :TunnelBridgeBuilder:test`。Dokka task 成功仍须审查 warning，因为 `Core/build.gradle.kts` 设置 `failOnWarning=false`。
