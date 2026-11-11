# Research: 正式四灯 JS 包准备的源码边界

本文件保留`fb578ae3`之后首次准备时的只读发现，不代表后续功能仍缺失。用户随后批准G明确不限速及按参考速度/减速度计算扫描距离；当前实现状态归[实施计划](../implement.md#正式信号包前置明确不限速与扫描依据2026-10-15)，输出合同归[JS规范](../../../spec/simple-signals/backend/js-signal-rules.md)。下方“仍需确认”是当时的决策清单，不覆盖后续用户决定。

- Query: 复用现有四灯资产和正式 loader/runtime，需要改哪些文件；哪些规则仍待作者决定。
- Scope: internal；只读源码研究，不实现、不运行 Gradle、不部署。
- Date: 2026-10-15

## Findings

### 可复用资产与最小范围

- `SimpleSignals/examples/jr-four-light/README.md` 明确当前只是 model-only 示例，无运行规则；资源包仅声明 Minecraft 1.21.11/75.0，不能把它改名当作已完成正式包。
- `model-package/parts/head.yml:1-20` 已有 item_display 灯头，偏移 y=1.5m、旋转 y=90°，四个命名场景分别引用 `jr:four_light_signal_stop/caution/slow/proceed`。`model-package/models/head.yml:1-17` 已有 default=stop、完整 variants、左右2m预设及不随轨道倾斜设置。
- 客户端四个 `resourcepack/assets/jr/items/*.json`、四个 `models/block/*.json` 及共享 PNG 可复用，无需重新造贴图/模型或修改未跟踪模型工作区。`resourcepack/pack.mcmeta:1-7` 的版本范围不应凭推测更改。
- 建议最小正式目录为 `examples/jr-four-light/signal-packs/jr_four_light/{pack.yml,model/head.yml,logic/automatic.yml,logic/automatic.js,signal/automatic.yml}`，并更新示例 README 和直接消费实际示例的测试。模型可把已有部件内联进 `model/head.yml`，避免引入第三个布局或重复模型 import 节点；原 model-only 包用于附属设备时仍可保留。
- 生产插件读取 `<dataFolder>/signal-packs/`，见 `SimpleSignalsPlugin.kt:83`；不是 `models/`。`SignalPackLoader.kt:19-29,91-113` 从根的直接子目录读取 `pack.yml`，其字段严格仅 config-version/name/display-name。
- `SignalPackLoader.kt:116-138` 复用公共 ModelAssetYamlCompiler materialize 模型；`141-171` 要求同 stem YAML+JS，YAML 只有有限正数 `scan-distance-metres`；`175-193` 的 signal 包含 display-name、namespaced model/logic。ID从包名和文件相对路径派生。无需新增 Kotlin 规则、运行时配置表或迁移 reader。

### 真实 JS 输入输出：不要发明 entryAllowed

- 唯一生产投影在 `SignalPackEpochRuntime.kt:126-206`：根 `self` 只有 `occupied`、`lengthMeters`；根还包含 `forward` 和可选 `end`。**没有 entryAllowed、根 routeReady、train 或制动参数字段**。
- `occupied` 是该 section occupants 非空，不区分“当前申请列车”和其他列车；不要把它当作每车单独许可。
- 后继 section 节点为 `distanceMeters`、`section.{occupied,lengthMeters}`、`selection`、可选 `routeReady`、可选 `downstreamSignal.{speedLimitMetersPerSecond}`、`next`、可选 `end`（`185-195`）。终止型分支可以只有 distance/selection/routeReady/end，**不保证 section/next/downstreamSignal 存在**（`172-179`）。
- `selection` 是 `selected/not-selected/not-applicable`；`routeReady` 仅有保护进路 requirement 时提供（`147-160`）。全部候选分支都能观察，规则作者应明确是沿选中分支约束当前通路，还是有意汇总其他分支；不能无条件取 forward[0] 或把未选支路当实际路径。
- 下游限速取同节点逻辑信号的最严格值；缺状态按0，循环段或无信号则不提供 downstreamSignal（`162-167`）。递归读取 next 可跨越多个短区间；不会调用寻路。
- 保护根的固定进路或当前入口未 ready 时，在 `selectPlan`（`224-241`）直接拒绝求值，异常转 Unavailable（`95-112`）；无需 JS 另造 entryAllowed。Basic资源授权和SimpleATO约束仍独立，不因 JS 输出绿灯而获资源。
- `end` 包含 reason、distanceMeters 及可选 cycleStartDistanceMeters/cycleLengthMeters（`140-145`）；原因枚举见 `SignalObservationPlan.kt:126-132`。cycle 仅终止命中分支，循环段没有下游输出，不能把 cycle 视为 track-end 或用旧信号输出补齐。
- 根入口距离为0，后继入口累计包括当前区间长度及未分区连接轨距离，见 owner spec `js-signal-rules.md:43-59`。扫描距离仅限定可见范围，实际距离判定必须在 JS 编码，不自动按扫描长度改变灯色。
- `SignalLogicRuntime.kt:41-57` 用捕获的源码、fresh realm和真实 GraalJS同步 evaluate；两 setter `setSpeedLimit(m/s)` 与 `setModelVariant(opaque name)` 各调用一次。输出外观需匹配 materialized variants（`SignalPackEpochRuntime.kt:99-104`），不得 return 灯色或用灯色反查速度。
- dirty依赖和同 epoch 下游优先见 `SignalPackEpochRuntime.kt:83-99`；无需为正式包新写 tick 扫描或世界查找。

### 直接复用的验证路径

- `SimpleSignals/build.gradle:80-85` 已将整个 examples/jr-four-light 送进 test resources；无需复制第二份测试专用资产。
- `presentation/JrFourLightDemoTest.kt:28-74` 直接读取实际示例并验证真实模型 loader、四种物品引用、默认/预设与客户端 pack metadata。可扩充或独立新增紧凑规则测试，读取同一个正式 signal-packs 根调用 SignalPackLoader.load，再构造 SignalLogicRuntime(catalog)执行真实 JS。
- `signalpack/SignalLogicRuntimeTest.kt:205-243` 展示 loader → runtime 的完整调用方式；其临时脚本仅是沙箱测试输入，不应替换正式示例脚本。
- `runtime/SimpleSignalsWorldRuntimeManagerTest.kt:1885-1922` 已用真实 topology、fixed blocks、world definitions、manager.loadSignalPacks、SignalEpochRuntime 与 RailwayWorldFrameSnapshot 验证同 epoch 多级下游链。正式包组合测试可复用该模式，把 load 输入换成实际示例，不复制生产规则字符串。
- `writeAutomaticSignalPack`（同文件 `4956-4987`）默认恒定25m/s，`src/test/resources/signal-packs/automatic/logic/block.js`仅占用0/25；这两者都是有意简化的测试 fixture，不能声称是正式四灯或距离规则。
- 新增验收应覆盖自身占用、正常 G/YG/Y/R 传播、连续短区间进一步看远、选中/未选保护分支、无ready进路、cycle与扫描边界，不把人工JSON调用等同于真实世界epoch组合验证。

### 仍需用户确认的包作者决定

1. 四种速度上限，特别 G 是明确线路速度还是一个足够高的数值上限；当前输出只能有限非负数，无无穷大/“不限速”字段。
2. 距离自适应的正式判据：各目标限速的预告距离或明确的包内减速度/余量公式；不能仅数空闲区间，也不能把 Basic/ATO 实际动态制动参数凭空假设为 JS 输入。
3. 逻辑最远扫描米数，以及 distance-limit、track-end、cycle 和未确定路线各自怎样影响输出。距离上限不是自动通行证明。
4. 树形候选如何筛选实际路径，是否展示未选路径的指示；规则不应代替联锁授权。
5. 真正准备部署前确认服务器/客户端版本；当前 runFolia26.1.2 与资源包1.21.11目标不一致，未测试不能推断兼容。

## Related Specs

- `.trellis/spec/simple-signals/backend/index.md`
- `.trellis/spec/simple-signals/backend/js-signal-rules.md`
- `.trellis/spec/simple-signals/backend/model-packages.md`
- `.trellis/tasks/09-18-signal-addon-implementation/{prd.md,design.md,implement.md}`
- 研究角色隔离规则优先：未读取 implement/check jsonl。

## External References

未查询外网或日本现实标准；这里的证据仅是仓库当前公开合同和实现，不把示例名称等同于铁路标准认证。

## Caveats / Not Found

- 未发现已冻结的正式速度/距离规则；研究不替用户选择数值，不把已有smoke包升格正式。
- 未运行 Gradle、未部署、未修改产品/规范/存档/客户端资产；本文件只是研究持久化。
- 部分owner文档仍有历史TARGET措辞，实际Wand现状以当前index和代码为准，不借本批全面清文档。
