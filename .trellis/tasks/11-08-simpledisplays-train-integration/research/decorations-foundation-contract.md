# Decorations扩展基础与既有display能力迁移 — 2026-11-10

用户已批准开始修改，且明确缺少Decorations时列车正常加载、保留变换和子树。提交前报告模块设计/改动/验证，本轮不创建commit、不push、不部署。已有工作区4个规划文件改动为本任务自己的调查记录，保留它们。

## 本批交付边界

开放可注册的装饰性parts类型/配置验证、通用包资源捕获/快照，并建立真实独立Decorations插件，将已有screen定义、displays.yml解析及SimpleDisplays可选服务适配迁入。保持之前“定义/原文资源可加载、screen尚无文字实体”的能力边界。列车runtime挂载factory、通用Text双backend、模板编译缓存、列车数据和视距仍是下一批，不能用空renderer假装已完成它们。本批不新增假的runtime handle接口或noop视觉实现。

## YAML/持久化目标

原生part继续既有type和各字段，不变更其动画list或内联config-version（延期子任务）。新扩展部件采用稳定namespaced type：

```yaml
config-version: 1
name: cabin_lcd
type: decorations:display
schema-version: 1
config:
  category: cabin_side_lcd
  interior: true
  width: 0.8
  height: 0.45
parts: []
```

通用offset/rotation/animations/parts仍由宿主解析。schema-version是独立扩展payload合同版本，缺省1；config为对象原文。宿主内部PartType新增EXTENSION并移除SCREEN，PartDefinition用generic extension payload取代screen。短未知type仍拒绝，合法namespaced type即使provider不在也保留payload，构造Dummy变换节点、动画和完整子树。扩展配置未知字段只由注册provider校验，不全局放宽原生schema。不添加旧screen alias/reader。

包manifest新增可选extensions命名map，key为稳定typeId，与部件type一致：

```yaml
extensions:
  decorations:display:
    schema-version: 1
    directory: decorations
```

资源布局示例：decorations/displays.yml、decorations/displays/destination.xml。displays.yml内部templates/defaults结构保持上一批，file相对这个资源目录。宿主不知道文件语义，只以通用安全目录捕获器保存相对文件路径→严格UTF8原文，包含声明目录全部普通文本文件；Decorations在捕获map上解析displays.yml和XML引用。此显式目录声明保证无addon时仍能固定原文，不借当前磁盘填充旧SNAPSHOT。其他装饰资源未来另扩展，不在本轮设计binary资源格式。

目录必须包内相对安全路径；普通文件、目录及符号链接均做真实路径包含校验；禁止越界/断链/循环，目录捕获使用稳定路径排序。可以从上一批安全路径逻辑提取通用包资源helper，不能增加通用全局资源manager。规范路径不含空/点/父级段、反斜杠/NUL/绝对/Windows盘符；POSIX普通段可有冒号。保存原文，不自行编译XML/JS。

LoadedTrainPackage/SavedTrainPackageSnapshot把专属displays替换成默认空extensions map；内容为typeId对应的schemaVersion和files，不持有addon JVM类型。Map/payload深度canonical且不可修改，hash涵盖它们、顺序无关，空map省略保持无扩展包既有hash。主包和每车来源包两条路径保持精确来源。Store读取/重算完整纳入extension资源；无provider也能读写未知payload，禁止数据丢弃。所有变更为开发格式current修正，既有版本数字不推测升版、不加runtime兼容；此前固定无显示fixture继续验hash。本批不转换任何实际世界数据。

## 主插件API/生命周期

公开独立的受plugin owner拥有的PartExtensionService（建议api.model.extension包），通过Bukkit services registry提供。类型注册至少覆盖稳定type ID、payload schema version支持/校验和包资源校验；返回可撤销registration，重复注册拒绝，owner关闭撤回。扩展只访问公开API/Json值，不导入config/model、render/model、extension.train内部类。

宿主初次加载包早于Decorations onEnable，所以解析/捕获不依赖provider。provider可在宿主启用后注册；需要验证已发布的相关包/部件并报告每包错误，未来reload准备阶段使用当前provider验证，失败保留旧catalog。注册与读取callback不能持锁执行任意插件代码；旧owner失效不能继续调用。具体轻量公开上下文类型由实现agent提出并和Decorations consumer对齐，避免平行model类型。host API允许取得已捕获资源/配置进行验证；不为本批新增没有生产用途的挂载/显示接口。

ModelManager可注入通用validator/registry（默认无provider便于pure loader tests），主插件composition创建/注册并连接它，生命周期listener按实际owner撤回；测试覆盖late registration和退出，无Decorations时照常加载与快照。

## Decorations模块

独立Gradle模块/JAR，名称Decorations，依赖JavaRailways、softdepend SimpleDisplays以保留原可选退化。compileOnly宿主公开API和SimpleDisplays API，host JAR不再链接或softdepend SimpleDisplays。Kotlin/serialization等宿主提供的runtime类不复制，复用SimpleSignals的依赖/打包方式。宿主Core/Basic/JR类与SimpleDisplays API均不能装进addon JAR。

Decorations注册decorations:display provider：严格校验四个字段/尺寸/类别和版本；在捕获的资源map上复用displays配置定义、默认映射/本地引用及XML原文语义。缺少资源声明或displays.yml等于无模板；声明但坏引用仍报错。迁移OptionalSimpleDisplays/typed binding、事件与7项边界测试；缺少SimpleDisplays不阻碍Decorations注册和普通列车加载，plugin服务owner/ABI/退出语义保留。

PartScreenConfig、TrainDisplayResources和displays parser及其使用文档/schema专属内容迁往Decorations。宿主保留通用extension模型/schema/API/捕获器，不保留SimpleDisplays package、专属display fields或compat aliases。

## 实施分工

宿主implement agent：通用part payload和资源模型/manifest/loader/snapshot/ModelManager/API注册service、已有屏幕/显示测试迁移为通用extension测试，保留固定hashfixture；不修改BukkitPlugin.kt、build.gradle、settings.gradle、Decorations、官方schema/docs（root协调）。及时发API签名和必需composition接线。

Decorations implement agent：只写Decorations模块生产代码/测试/build脚本，等待并使用宿主API；迁移既有可选服务和display解析测试（原宿主文件删除由root协调），不修改宿主文件或settings。

root：composition/build/settings移除旧适配/依赖、模块接线、schema/文档/样例/任务记录和整体验证。checker最终全范围审查并串行修正。

## 验证

- 真实无provider inline/import/嵌套树：变换/动画/子节点和generic payload保留，snapshot往返无需addon类，原生/无扩展hash不变。
- provider类型重复/退出/late registration、已加载包和未来reload验证、错误不污染已发布catalog；无反射类加载、关闭后无回调。
- 通用资源捕获顺序/hash、未知addon、来源车厢隔离、UTF8、遍历/符号链接逃逸/循环、缺失目录、篡改重算。
- Decorations字段、displays/templates/defaults、XML原文、可选SimpleDisplays classloader/owner/ABI清理；只依赖公开host API。
- 宿主和Decorations定向测试、完整Bukkit/Decorations测试、两个JAR与Dev编译、artifact边界。Paper至少无addon及有Decorations无SimpleDisplays/有SimpleDisplays启动验证；Folia/client渲染不在本批能力声明中。
