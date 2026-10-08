# JR 四灯灯头示例

这是一个可直接用于开发服烟测的完整示例：`server/signal-packs/jr-four-light/` 是当前统一信号包，
包含真实灯头模型、最小自动闭塞 JS 和玩家可选信号类型；`resourcepack/` 提供四种客户端模型和共用贴图。
旧的 `model-package/` 仍保留为附属显示器复用的 model-only 示例。**只有灯头，没有立柱。**
来源是用户提供的独立模型工作区，其中的 `models/jr_four_light/pack/assets/jr/`；外部原文件未修改。

## 外观与客户端资源

模型 ID 是 `jr_four_light:head`，动画名为 `lamp`。四个外观 `stop`、`caution`、`slow`、`proceed`
分别引用同名场景和 `jr:four_light_signal_<名称>` 物品模型键；灯头显示红灯、黄灯、黄绿灯、绿灯。
每个键通过 `assets/jr/items/` 中的现代物品定义引用 `assets/jr/models/block/` 的模型，
再引用共用 `assets/jr/textures/block/four_light_signal.png`。
没有覆盖 `minecraft:paper`，没有 CustomModelData 别名。

`default: {lamp: stop}` 是本示例作者选择的初始/不可用画面，不是通用加载器内置的红灯规则。
这些外观名没有内置限速；实际速度和是否放行仍由信号规则及 Basic/ATO 授权决定。
烟测规则仅用本区间占用在 `stop/0 m/s` 与 `proceed/25 m/s` 之间切换，明确不代表完整日本多灯位制式；
显示 `proceed` 也不会让列车绕过 Basic 的资源授权。

资源包只声明 Minecraft **1.21.11、资源格式 75.0**，使用 `min_format`/`max_format`。
版本来自本机缓存的该版本 `version.json`，不是原始资产目录的版本推断。

## 原始模型的适配与摆放

原始几何的 Y 为 0..42；Minecraft 1.21.11 的方块模型元素坐标要求
[-16,32]（[该版本的模型文档](https://docs.fabricmc.net/1.21.11/develop/blocks/block-models)）。
因此示例副本只把每个元素的 `from`、`to` 的 Y 减 16，使范围成为 -16..26。
形状、UV、材质、GUI 配置保持原样；PNG 逐字节复制。没有缩小灯头或增加部件。

宿主物品显示采用 `NONE`，不使用原模型的 GUI 旋转/缩放。按方块模型的中心 (8,8,8)
和 16 模型单位/米换算，减 16 后灯头基底 Y 为 -1.5 米；共享部件的 `offset.y: 1.5`
把基底恢复到附着点高度。保持单位缩放，因此灯头高 2.625 米、宽 1.25 米；这是资产的原始
比例，并非声称与实物尺寸一致。需要架高时由设备摆放偏移决定，本例不虚构立柱。

原始灯面朝 -Z。部件 `rotation.y: 90` 将它转向模型局部 -X，也就是朝向驶来的列车；
轨道坐标的有向前方为 +X、上方为 +Y、右侧为 +Z。`left`/`right` 预设仅给出 -2/+2 米侧偏，
不再次旋转模型。AB/BA 附着方向改变时，左右与灯面一起跟随方向。默认不跟随轨道坡度/超高；
玩家将来可以覆盖。地面摆放也使用这份部件定义，但不应用轨道预设。

以上是现有模型与轨道姿态的离线推导和测试，**尚未由真实客户端画面验证**。

## 一条命令启动开发服

在仓库根目录执行：

```text
GRADLE_USER_HOME=/workspace/.gradle ./gradlew :BukkitPlugin:runSignalSmoke
```

该开发任务会在隔离的空世界中构建并加载 JavaRailways、JavaRailwaysDev 和 SimpleSignals，并把本示例统一信号包复制到
`BukkitPlugin/run-signal-smoke/plugins/SimpleSignals/signal-packs/jr-four-light/`。这只影响本地烟测开发服，
不会把示例写入生产插件默认数据。客户端资源包生成在
`SimpleSignals/build/distributions/jr-four-light-resourcepack.zip`，需手动装入 Minecraft 1.21.11 客户端。
默认连接地址为 `localhost:25565`。

首次进入后，在服务器控制台执行 `op <玩家名>`，再运行下面的管理员命令。独立目录不会读取或覆盖旧 `run/`
中的世界快照与配置；需要退出时在控制台输入 `stop`。

进服后以 OP 身份按以下最短流程测试：

1. `/phys create` 创建测试环线；
2. `/simplesignals wand block create`，在同一无分支线路依次右键两个点创建固定闭塞；
3. `/simplesignals wand signal`，确认状态栏类型为 `jr_four_light:automatic-four-light`；
4. 左键有向闭塞入口并朝向闭塞内部，再右键轨道选择模型摆放点；
5. 空闲时应显示 `proceed`；执行 `/phys train 2` 产生占用后应显示 `stop`。

首次画面验证先使用 Paper 1.21.11。`runFolia` 当前目标为 26.1.2，而本资源包只声明 1.21.11；
在更新并验证 pack metadata 前，不把 Folia 画面结果作为本示例验收。

## 手动安装文件

完整信号包把 `server/signal-packs/jr-four-light/` 复制到
`<SimpleSignals dataFolder>/signal-packs/jr-four-light/`，确保 `pack.yml` 位于包目录根部。
只需要复用模型时，也可把 `model-package/` 放到 `<SimpleSignals dataFolder>/models/jr_four_light/`，
确保 `manifest.yml` 位于包目录根部。
资源包则把 `resourcepack/` 本身放到客户端资源包目录，或将其内容打包，使 `pack.mcmeta`
位于资源包根部。资源包与服务器模型包是两个不同的目录，不可互相替代。

统一包提供玩家可见类型 `jr_four_light:automatic-four-light`；model-only 用法的模型引用仍为
`jr_four_light:head`。摆放格式见[插件说明](../../README.md#保存与编辑)。
插件启用时异步发现这些目录；复制文件后需重新启用插件，当前没有包热重载。
在设备定义里添加模型引用与实际摆放参数，并确保设备输出名属于本例的四个外观，再通过
`export → validate → apply` 应用草稿。单纯复制模型包不会自动创建信号设备，也不会赋予行车授权。
客户端需单独安装资源包；没有自动 ZIP 安装或向玩家发送资源包。

## 验证范围

`JrFourLightDemoTest` 直接读取这里的示例，不复制另一套测试专用资产：

- 统一信号包及 model-only 包、四个场景、默认外观、左右预设能够被真实加载器读取。
- 统一包的 signal type 精确绑定真实模型和已捕获的 JS 逻辑。
- 物品定义 → 方块模型 → PNG 引用完整，PNG 可以解码；真实 1.21.11 元数据 codec 接受包格式。
- 元素坐标都在允许范围内，原始宽高不变；真实轨道 AB/BA 姿态下灯面朝来车、灯头基底在附着高度。

生产绑定测试另行验证公开显示服务的调用、信号变化与卸载清理，使用记录式服务而非实际客户端。
这些回归仍不证明客户端已安装资源包，或完整的 Folia 显示生命周期和真实画面。
