# Decorations附属插件与列车部件扩展边界调查 — 2026-11-10

状态：规划修订/源码调查，未修改生产代码。用户明确将SimpleDisplays适配迁移到新独立插件Decorations；它未来负责列车和轨道相关装饰，本轮仅讨论display。旧主任务下一步“直接在BukkitPlugin接模板编译缓存”已被本次边界调整取代；缺失扩展处理等产品决策尚未冻结，不启动实施。

## 已核对的当前封闭点

- config/model/PartType.kt:9固定enum和fromString，含SCREEN；PartRegistry.kt:115/122固定字段表并在134/140硬编码类型和screen解析；PartDefinition.kt:26/29将screen作为主插件特有字段和构造不变量。只增加一个运行时renderer注册表不足以开放配置/快照。
- ModelTreeBuilder.kt:271为固定when，307将SCREEN作为DummyModel；所有变换、公共动画、递归子树和construction回滚目前由宿主管理。
- config/model/PackageLoader.kt:145、LoadedPackage.kt:23、SavedTrainPackageSnapshot.kt:23/35/46均为专用displays资源；ModelManager.kt:111/117分别固定主包和每车源包，Store.load也重算专用内容。
- 公共api/model/ModelPart.java:8只允许Group/Item，ModelPresentationService.java:16提供独立world模型，ModelHandle只能提交整体pose/scenes，没有列车部件挂载上下文/逐玩家文字帧。普通模型API不等于列车parts注册API。
- render/backend/DisplayVisual.kt:21只有Item/Block，没有Text；文本还未在任何一侧实现。双backend、entity/region调度、packet bundle及parent动画归属仍由宿主通用显示机制承担，扩展不应直接导入AbstractModel、CarExtension、ModelTreeBuilder或私有backend实现。
- BukkitPlugin.kt:224直接创建OptionalSimpleDisplays，build.gradle直接compileOnly API并softdepend。它们应迁往Decorations，主插件不再链接SimpleDisplays类型/发现逻辑。
- BukkitPlugin.kt:346/347在宿主enable中准备并发布包，371登记世界。新增依赖宿主的附属插件要有明确的注册窗口与已加载实例补挂/重建机制，不能只假定addon普通onEnable注册早于宿主包加载。
- SimpleSignals已有独立模块/JAR、公开服务/owner注册及owner清理模式；RailwayObservationService可复用只读发布思想，ModelPresentationService/轨道定位API可供将来静态轨道装饰，不必本轮扩展其业务范围。

## 建议的职责

JavaRailways主插件负责公共parts树、配置公共字段/引用/变换/动画、扩展类型注册和owner、通用资源捕获及快照、列车实际实例挂载/更新/接收者/销毁、两种backend与Folia调度。Decorations负责display专属字段校验、模板文件声明读取、SimpleDisplays发现/模板编译与缓存、数据映射/布局/逐观看者内容及内部屏幕距离策略，通过宿主API提交显示状态。具体视距配置/命令放置应随display方案细化，主插件提供已有通用距离/受众能力，不再持有SimpleDisplays专属配置或模板类型。

新插件建议作为当前仓库独立Decorations模块/JAR，复用附属插件构建方式；依赖JavaRailways公开API，与独立SimpleDisplays通过其API接入。SimpleDisplays library仍独立、无需认识列车或Decorations。SimpleDisplays是Decorations的hard还是soft依赖需在插件降级策略中确定，不能从“迁移适配”推断为合并library。

## 开放API需要覆盖的完整链路

1. **部件类型和配置**：引入受plugin实例拥有的可撤销注册。稳定namespaced类型ID（例如decorations:display，仅示意）关联专属payload验证/解码及实例处理器；重复类型不能覆盖，禁止从配置字符串反射加载类。宿主复用inline/import/公共变换/子树，扩展只定义专属payload，未知字段仍严格检查。配置与离线schema也需配套扩展入口，不能全局放开additionalProperties来绕过封闭enum。
2. **资源和快照**：开放包资源贡献/捕获入口与受限包内文件读取，复用本批路径/UTF8/来源隔离机制。Decorations拥有displays.yml和XML源码语义，宿主快照保存稳定typeId及必要版本/通用payload，不链接插件定义的JVM类。扩展资源同样参与canonical hash、SNAPSHOT/FOLLOW_PACKAGE、prepare/publish失败保留；每车使用实际来源包，不借主包回退。具体格式需按独立边界/发布基线规则审查，不猜测升版、不顺手兼容任意旧screen配置。
3. **列车部件运行时**：公开受支持的挂载与生命周期合同，给扩展实际车厢实例身份、来源资源、父部件变换、受众变化和只读上下文。共享模板名/Car.id不能冒充实例身份；子树跟随父动画。宿主统一处理构造失败回滚、车厢重建/反向、世界卸载及异步销毁完成；扩展返回公开handle或值，不继承内部AbstractModel。
4. **通用文字显示能力**：扩展提交renderer-neutral文字/变换/受众状态，宿主两种backend补Text能力。输出不能要求主插件认识SimpleDisplays.TextDraw；Decorations进行该格式转换。动态节点数量和每观看者内容、受众退出停止发送仍需完整更新/清理合同，不能只扩充静态ModelPart.Item。
5. **注册/关闭顺序**：明确包准备/快照恢复与addon注册顺序，已加载车型如何接入。插件owner关闭时撤回注册、停止回调并完成全部装饰清理，宿主普通车体/运行状态继续由宿主拥有；禁止保留失效插件classloader或编译模板。通用注册窗口/晚注册补挂方式取决于缺失扩展策略，暂不提前冻结实现钩子。

## 本轮不扩展的事项

不实现接触网、立柱、轨道装饰编辑/存档系统或受电弓行为。现有models/s100/parts/s100/pantograph.yml仍是普通item_display，并非独立pantograph部件类型；将来迁移需另定需求。现有PartType.PLATFORM/PlatformModel是车辆碰撞平台机制，不能因名称和“站台”相似就迁走它。独立普通模型/轨道位置公开API继续复用；未来轨道装饰有需要时再补差额。

## 建议实施顺序（待方案批准）

- 先实现/验证宿主通用部件注册、通用资源快照及挂载生命周期合同，用最小真实测试addon证明inline/import、重复安装、来源隔离、缺失/退出owner和注册时序。
- 建立Decorations插件，将现有screen/displays加载及SimpleDisplays可选服务适配迁入；宿主删除专用依赖/字段。复用已有路径/快照测试和独立library410af2e，不重复实现JS或XML。
- 在新边界下接模板编译、通用文本双backend、车上显示/逐观看者数据和视距；随后进行原定一致性子任务及最终验收。

必须验证：无Decorations/无SimpleDisplays启动；addon注册/退出与已有列车；非法/未知类型定位；inline/import及重复实例；快照无插件类依赖、固定源内容；两个backend运动/动画/受众/销毁一致；Paper/Folia与客户端接受度分开记录。

## 当前最重要的产品问题

Decorations未安装/失效时，包含其部件的车包和已固定快照是“保留公共变换/子树，仅关闭装饰并报告诊断”，还是“拒绝该车包/车型”？前者更符合装饰可选能力及原先缺少SimpleDisplays不阻塞列车的目标，但要求宿主能在无provider时保存未解释的扩展payload，并定义该降级仅适用显式可选的装饰类型。不能因此把所有未知类型/拼写错误无声跳过。此项等待用户选择；不擅自决定丢数据或严格拒绝。

## 用户决策（2026-11-10）

用户已确定：未安装Decorations时列车仍正常加载，保留变换和子树，并授权开始修改；提交前须报告修改内容和模块设计。当前实施合同见[基础与迁移合同](decorations-foundation-contract.md)，本批不提交。
