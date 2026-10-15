# Separate SimpleSignals logic from its Bukkit adapter

## 目标与状态

2026-10-15用户要求记录的后续任务：将SimpleSignals信号逻辑与Bukkit解耦，使领域逻辑可在没有Paper/Bukkit的环境中构造、运行和测试。建议保留SimpleSignals为平台无关逻辑模块，新增SimpleSignalsBukkit承接平台适配。
本任务仅完成简要源码评估与需求记录，保持planning，不启动实现，不取代当前Stage 2收尾任务，也不把本次拆分加入当前游戏测试前置条件。完整设计、模块级搬迁清单和实施审批留到实际排期时完成。

## 当前证据与初步评估

评估基线为dev/signal-system的af156aa7；这是静态抽查，不是完整依赖审计或工期估算。

- `SimpleSignals/build.gradle`同时声明Paper/BukkitPlugin依赖、GraalJS规则引擎和plugin.yml；当前SimpleSignals本身是独立Bukkit插件，没有SimpleSignalsBukkit模块。
- `runtime/SectionOccupancyRuntime.kt`已使用Basic/SimpleATO公开的占用、epoch与信号接口，但仍直接关联`presentation.SignalModelBinding`。因此不能只搬走JavaPlugin入口就称为解耦。
- `runtime/SimpleSignalsWorldRuntime.kt`中的manager直接接收`Plugin`、Bukkit基础设施/信号服务、RailTargetService和ModelPresentationService，混合世界安装、定义事务和平台显示接线。需要保持同一原子发布点，不能拆出两个可能错配的运行时。
- `block/FixedBlockRouteResourcePolicy.kt`使用`Plugin`及Bukkit资源适配接口。领域资源贡献规则与插件身份/服务注册应分离，而不是把整套资源策略搬到适配层。
- `presentation/ModelPlacement.kt`和`persistence/SimpleSignalsWorldDefinitions.kt`引用BukkitPlugin的`ModelPose`；`signalpack/SignalPackCatalog.kt`持有`CompiledModelAsset`，`SignalPackLoader`复用Bukkit模型编译器。即使类型自身看似不可变，逻辑模块依赖BukkitPlugin仍不满足平台隔离。
- `authoring`同时有路径/边界算法和Paper Wand/Dialog/粒子显示，不能按整个目录机械归类。纯算法保留逻辑层，玩家交互、PDC、owner调度和实际显示进入适配层。

初步判断可行，但属于跨模块的中等规模重构，不是简单移动文件。已有大量纯算法与公开中立接口可复用；主要风险在manager原子事务、模型类型传递和生命周期，而非信号算法本身。

## 范围与约束

- SimpleSignals负责区间/保护区域定义、拓扑编译、进路选择、占用与信号求值、JS观察/规则及中立的限制/资源贡献逻辑。
- SimpleSignalsBukkit负责JavaPlugin、ServicesManager发现、世界/插件事件、Paper/Folia调度、命令/Wand/Dialog/PDC、模型与文字实际显示，以及平台资源适配。
- 用户进一步确认BukkitPlugin可以公开Wand、Rail可视化等通用平台API。SimpleSignalsBukkit优先消费这些能力，而不是复制宿主交互/显示基础设施；SimpleSignals领域模块仍不依赖Bukkit。方向和逐步提取边界归[编辑标记规范](../../spec/bukkit-plugin/backend/editor-markers.md#已确认的公开能力方向target2026-10-15)，具体签名在实际实现前确认，不要求本任务先完成一整套通用框架。
- 由中立的不可变输入/输出或最小端口连接两层；优先复用Core/Basic/SimpleATO已有API。不新增另一套epoch、资源仲裁、定义管理器或逐tick消息总线，不为解耦复制观察树或增加额外全量扫描。
- 模型资产编译与呈现类型应在正确owner处转换或拆开逻辑元数据和平台资产；不能简单把Bukkit类型包一层接口后继续从逻辑模块依赖BukkitPlugin。是否值得提取独立纯模型公共库，留到完整依赖审计后决定，不预建通用平台框架。
- 保持现有JS、包内ID、区间/进路语义、同epoch一致性、唯一定义事务、保存失败原子性与显示清理边界。保持现有对外插件身份、数据路径及存档/包格式，除非用户之后明确批准变更；不得因为移动模块升schema或增加旧开发数据reader。
- 梳理Kotlin internal可见性，只开放跨模块实际需要的最小接口，不能为编译通过把全部实现改成public。
- SimpleSignals仍是独立信号系统，不将其领域代码嵌回JavaRailways主插件。建议由Bukkit适配产物承载唯一插件入口和一份逻辑模块，精确打包方式在design阶段确认；不能照搬SimpleATOBukkit嵌入主插件的部署方式。

## 验收标准

- [ ] SimpleSignals的生产与核心领域测试不依赖Paper、BukkitPlugin或SimpleSignalsBukkit；除直接import外，检查传递依赖和公开签名。
- [ ] 在没有Bukkit/Paper的测试运行时，实际执行区间编译、占用/epoch、进路选择和JS规则的代表性回归。
- [ ] SimpleSignalsBukkit通过已有公开服务接线，平台代码不反向泄漏到领域模块，领域算法不在适配层重写。
- [ ] 代表性定义编辑仍只有一个原子提交点；旧publication/source/显示回调不能与新状态错配，失败保留旧磁盘和运行状态。
- [ ] 保存/读取现有current fixture，包和JS行为不变；模型、Wand和世界卸载/插件停用回归通过，并区分平台替身与现场验证。
- [ ] 构建边界和JAR检查证明宿主类未被复制，逻辑代码只打包一份、插件入口唯一；同步Java消费示例及相关模块契约。

## 非目标与后续准备

不改变信号规则/ATO算法、不重做Basic/Core、不更改玩家建造交互、不添加新平台实现、不部署或迁移开发服数据。通用Bukkit API的方向已获用户同意，但具体接口和实施批次需独立确认；不因此立即启动本拆分任务。
开始前须完成详细依赖清单及design.md、implement.md，整理实现/检查context，并向用户确认最终拆分与打包方案；当前记录不代表设计已收敛或可直接开始。
