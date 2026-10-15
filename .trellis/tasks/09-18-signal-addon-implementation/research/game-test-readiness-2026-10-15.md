# Stage 2 正式版游戏测试准备核对

最新前置更新（2026-10-15）：明确不限速输出及准备期按轨道静态速度/参考减速度计算扫描距离已实现，最新五模块2170项回归通过；新插件JAR/源码摘要归[实施记录](../implement.md#第二笔静态速度计算扫描距离验证)。SUBGRADE来源已确认，不再等待速度通知或另建线路配置。下方旧产物哈希仍是历史基线；正式JS包/资源、迁移与真实游戏验收仍未完成，不用smoke输入替代。

后续更新（2026-10-15）：CR-040及随后CR-041现已修复，最新五模块2157项回归通过，详见[执行记录](../../10-09-stage2-code-review/implement.md#cr-041配置ui收口2026-10-15用户单独批准)。下方源码发现与产物哈希保留核对时`bf703b4d`基线，不再把CR-040/041安排为下一未修项；主JAR已重新构建，最新哈希/源摘要以执行记录为准。正式包、迁移与游戏验收仍未完成；动态贡献依赖触发另记审查台账。

日期：2026-10-15；源码基线`bf703b4d`。本次只读检查源码、任务记录、本地测试目录与既有构建产物；没有新构建、运行服务器、SSH、迁移、部署或游戏验收。只更新本记录、父任务入口和SimpleSignals README陈旧说明，没有修改产品代码或测试。

## 当前结论

本轮建造交互已完成离线实现，不能再把历史“未实现测距/表示器/边界叠加”等文字当作缺口；但也不能据此宣称Stage 2整体验收完成。准备正式测试时须处理下述已知问题和输入，不新增smoke产品路径或通用防御框架。

### 1. 已知Basic缺陷：按原审查顺序恢复，不重开整仓审查

- CR-037/038资源释放/暂停换向已由`5080c691`修复，CR-039恢复预校验已由`5aa07434`修复，不重复安排。
- **CR-040未修**：坡道FIVE_SEGMENT/S_CURVE把水平station当作三维物理弧长；水平半径计算用三维弧长作分母；偏移/纵断面重建仍复用旧分段比例。当前`ConstraintSolver.kt:149-157,182-192`及`RailGeometryMeasurements.kt:55-64`仍可看到原计算。它影响距离尺度，不能因平直轨道测试绿色就称几何正确。
- **CR-041未修**：嵌套配置有效条目、隐藏子组和快照语义；平台renderer又拼入原子组items。当前`DialogRenderer.kt:197-198,291-292,353-354`仍保留此行为。
- 优先修CR-040，再集中修CR-041；不把API整理、全仓文档、空目录、弱测试等全部P3清零作为进服前置条件。完整问题说明及修复责任仍归[Basic审查台账](../../10-09-stage2-code-review/research/issues.md)，本文件不复制算法修复合同。

### 2. 正式信号包与客户端资源尚需准备

- `SimpleSignals/examples/jr-four-light`是正式加载器可读的**模型与资源示例**，其README明确没有运行规则，不能单靠复制到`models/`让Signal Wand出现主信号类型。生产启动还读取独立`signal-packs/`。
- 本地`BukkitPlugin/run-folia/plugins/SimpleSignals/signal-packs/jr-four-light/pack.yml`明确名为`JR four-light smoke signals`；`logic/automatic.js`仅按self.occupied输出0/25m/s与stop/proceed。测试资源`src/test/resources/signal-packs/automatic`同样是有意简化的测试输入。
- 上述输入不能作为完整四灯/距离敏感逻辑的正式游戏验收包。下一准备批次应复用已有灯头资产、统一pack/model/logic/signal布局与真实JS接口，明确规则及预期，不把简单占用规则改名冒充正式实现。具体速度、距离等包作者参数需在该批次说明，不在本次清单中猜测冻结。
- 包热重载不是前置要求：当前启动加载即可测试。客户端需安装匹配资源包，真实画面/朝向/缩放尚未验证。
- 还须确认实际测试版本：`BukkitPlugin/build.gradle:47`的`runFolia`目标为26.1.2，`:131`的`runServer`为1.21.11；四灯示例资源包仅声明1.21.11/75.0。不能推断资源包在26.1.2已兼容，也不能据此断言必然不可用；部署前明确服务端/客户端与匹配资源输入，不擅自改目标版本。

### 3. 现有本地运行目录不能直接换JAR启动

精确对象是`/workspace/minecraft/JavaRailways/BukkitPlugin/run-folia`，**不是远端开发服现状**：

- `plugins/JavaRailways/physics.yml`仍为schema1、`track.gravity-mps2`；当前`PhysicsConfig.kt:96,105-116`要求schema2与`track.gravity`，旧输入明确失败。已有`scripts/migrate_physics_config.py`默认dry-run，应用前需恢复点；本轮没有运行。
- `plugins/JavaRailways/worlds/`下以下三个stem的infrastructure/runtime primary均为outer format1，缺新的SimpleATO独立payload：`55b27506-e512-4f10-9584-fd9827c9980f`、`8c4902d8-f720-43a2-af65-3e458c2c42bd`、`9d928c61-b83f-4677-ac1f-c1ea398994d7`。`SplitWorldSnapshotCodec`当前要求outer2，不能直接读取它们。
- `scripts/migrate_extension_envelopes.py`明确只迁extension，不转换outer/Basic归属或曲线，不能称为完整世界迁移。现有pure转换器也不等于完整旧服可恢复迁移已验收。
- 历史远端只读副本及源目录见[开发服存档审查](../../10-09-stage2-code-review/research/dev-server-save-audit.md)。该10-12副本仍在`/tmp/javarailways-dev-save-6c0B2pq1`，不是持久备份，也不证明远端今天的格式/状态。真正使用现有开发服前须重新确认源、停止写入后备份、独立输出迁移、严格加载验证及恢复路径；远端应用另获授权。
- 用户已接受一次性可审计手动迁移；不能为此擅自实施09-24任务中的未来显式端点/打包自动迁移。该任务仍planning，本轮也不增加runtime旧开发reader或升版本。

## 现有产物与证据

候选目录`/tmp/javarailways-fixed-block-authority.nsoyCU`：

- `BukkitPlugin/build/libs/JavaRailways-1.0-SNAPSHOT-all.jar`，SHA256 `e4fd64bf6f5a386d30f32c6f326bf8d6e71c020740ce0d00c0714978c3c9d709`。
- `SimpleSignals/build/libs/SimpleSignals-1.0-SNAPSHOT.jar`，SHA256 `65b445bf52a906fb27f22f426e1fe0254de554c0e8545b5cc85dab7cbb26308c`。
- 上批实际执行Core409/Basic399/SimpleSignals562，共1370项通过，63秒，JAR/consumer/module门禁及Basic新增API文档通过；详情及源文件摘要归`implement.md`独立测距验证。不是本轮重跑，不代表其他模块本轮全部执行；WebUI前端任务被明确排除。
- `SimpleATOBukkit`是主JAR内嵌库，不是第三个要安装的插件。使用两个匹配产物，不能只更新SimpleSignals而保留缺少EditorText/测距API的旧宿主。

## 正式游戏验收顺序与通过条件

以下均**未执行**，只记录已有要求，不另建测试框架：

1. **启动与恢复**：两个插件启用、公开服务完整、正式包成功加载、Wand可选择真实signalType；世界恢复无损坏/缺引用错误，记录版本、JAR哈希及日志。失败时保留源文件，不自动清空世界。
2. **玩家建造**：道岔操作、闭塞/保护区域创建删除、自动进路/录制替换、设备摆放、删除进路后保留设备及显式重绑、表示器缺项映射、实例数值编辑和直线/沿轨测距，实际操作与契约一致。
3. **显示与隔离**：rail/ground的朝向、偏移与倾斜正确，主信号/附属/表示器外观随正式规则变化；两玩家编辑文字/粒子互相隔离，离手、权限变化、退出/换世界/卸载后清理。
4. **真实运行**：列车请求→道岔准备→授权→信号放行→车尾释放；红灯停车、保护区域出口净空、固定/无后继固定区间衔接、停车折返返库。记录实际停车误差、资源保留/释放及无误Stall，不以模型变绿推断授权正确。
5. **编辑与持久化**：应用时刻表的相关资源禁止误改、无关线路可编辑；获准结构编辑后的相关区间失效与修复；正常保存重启后定义/模型/运营状态恢复一致。真实卸载应观察模型/文字清理完成结果，不能以onDisable返回当作已删实体。
6. **运行成本**：用实际采用的JS包及有代表性的线路记录信号求值/服务器tick表现。单测耗时和默认服务器TPS不能单独证明信号扫描性能。

## 不应重新安排为“未实现”的项目

- 测距、命名预设/数值编辑、主信号/附属/表示器交互、边界叠加：已有生产入口与离线门禁，现场另验。
- 停车往返与共享block身份：已有`StoppedReturnToDepotIntegrationTest`正式物理组合回归；旧aggregate checkbox不是新增runtime缺口。
- 固定/移动边界衔接、出口净空和局部拓扑失效：已有production/test，不能因此增加第二套扫描或授权流程。
- SimpleSignals平台拆分、多闭塞单线方向协调、任意样式/拖拽、全轨道/车站统一显示、热重载、无具体规则需求的flank/approach机制仍按各自既定延期处理。
- 全仓语义ownership、运营编辑器和文档审查保持开放；本次准备核对不冒充完成Basic/SimpleATO/Bukkit等整模块审计，也不自动启动其余重构。

建议后续顺序：CR-040 → CR-041 → 正式信号包/资源与用例 → 现有开发服一次性离线迁移准备 → 经授权部署并进行上述正式游戏验收。每批开始前另说明目的、文件范围和验证，不在本次直接执行。
