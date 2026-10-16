# SimpleSignals 模型包格式与加载

## 状态与负责范围

目录加载器和下述格式已实现（`CURRENT`）。场景按名称定义、不要求时间字段；导入参数与列车采用相同的合并语义。
加载器将完整包准备为不可变结果；插件启用时异步发现目录包，再用已加载结果准备设备显示。
公开显示服务的调用已接通，但真实客户端画面仍待验证。
它只规定 SimpleSignals 如何读取可复用的模型定义、外观映射和轨道摆放预设。
设备属于哪个信号、放在哪个世界和坐标，以及设备存档格式，不在模型包内规定。

SimpleSignals 负责目录发现、安全文件读取、包身份和信号 catalog；BukkitPlugin 的公共普通模型 compiler 负责
YAML 部件树、外观、场景、variant 与宿主摆放 metadata 的解析和 materialization。显示服务仍只接收不可变
`ModelDefinition`。不修改 Core、Basic、SimpleATO、SimpleATOBukkit，也不迁移主插件的列车/车厢或轨道包加载器。
已批准的默认显示、预设复制和摆放行为见[模型显示契约](model-presentation.md)，
部件与场景的运行语义见[主插件模型 API](../../bukkit-plugin/backend/model-presentation-api.md)。

## 统一信号包：model、logic 与一等 signal 类型

统一信号包的发现、严格文件解析、模型 materialization、两阶段跨包 signal 引用解析、JS 源捕获、
插件启动及世界逻辑／模型绑定已经实现（`CURRENT`）。Wand 和热重载尚未接线（`TARGET`）。后文
`manifest.yml/models/parts` 是附属信号和进路表示器继续使用的 model-only loader；
两个布局仍不能混合读取。统一包模型已经接入生产显示准备，但真实 Folia／客户端画面仍未验证。

```text
<package>/
  pack.yml
  model/
  logic/
  signal/
```

- `pack.yml` 只保存 `config-version`、稳定包名和可选显示名，不枚举 model、logic 或 signal。
- `model/**/*.yml` 每个文件定义并 materialize 一个普通模型；其 `modelId` 为 `包名:相对路径去掉.yml`。
  `import` 指向同包另一 model 的安全相对 model ID，只把目标 parts 树作为 identity-position `Group` 子树导入；
  不继承目标的 default、variants 或 rail-placement，首批不支持跨包 model import。模型身份不携带速度或信号语义。
- `logic/<路径>.yml` 与同目录同 stem 的 `logic/<路径>.js` 共同定义一个逻辑，`logicId` 同样从 YAML 相对路径派生。
  YAML在`scan-distance-metres`和`reference-deceleration-metres-per-second-squared`中恰选一项，均须有限正数；固定距离或参考减速度属于逻辑，不属于模型、signal类型、已放置设备或SimpleATO。后者在设备准备时按受控区间内最高静态有效速度计算实际扫描距离，机制归[JS观察计划](js-signal-rules.md#准备时按静态轨道速度计算范围)。
  缺任一配对文件均拒绝整个候选，JS 在 catalog 准备时捕获，运行期不重读。
- `signal/**/*.yml` 每个文件定义一个玩家可见信号类型；其 `signalType` 从文件相对路径派生，文件包含非空
  `display-name` 以及 namespaced `model`、`logic` 引用。只有 signal 文件进入未来 Wand／UI；存在但未被 signal
  引用的模型或逻辑仍可被跨包 signal 引用，不自动成为可放置信号类型。
- 一个模型可以被多个 signal 使用，一个逻辑也可以被多个 signal 使用。signal 可引用其他包的 `modelId`／`logicId`；
  引用不使用 JVM 类名、相对逃逸路径或加载顺序。
- 当前世界设备以 JSON `type`／Kotlin `signalType` 保存公开 catalog signal identity，并只保存自己的可选 `placement`；modelId 和 logicId
  都从同一个不可变 catalog signal 取得，不在设备中重复。定义中的 `signalId` 继续是已放置逻辑节点的 deviceId，
  不是 catalog signal type，不能按同名、模型或路径猜绑定。设备不保存 JS 源码、宿主类名、任意文件路径或每设备扫描距离。

`SignalPackLoader.load(directory: Path): SignalPackCatalog` 同步准备根目录的直接子目录。调用方负责把文件读取放在
低频 worker；该函数不创建 executor、世界对象或 live publication。严格 current schema 1 示例为：

`pack.yml`：
```yaml
config-version: 1
name: example
display-name: Example signals
```

`logic/automatic.yml` 与 `logic/automatic.js`：
```yaml
scan-distance-metres: 800
```

也可改为计算模式（不能同时保留上述字段）：

```yaml
reference-deceleration-metres-per-second-squared: 0.5
```

这里0.5仅为配置语法示例，不是加载器默认值，也不冻结正式四灯包的参数。

`signal/automatic-four-light.yml`：
```yaml
display-name: Automatic four-light
model: example:four-light
logic: example:automatic
```

包入口只允许两个必填字段和可选 `display-name`；model/signal 只接受 `.yml`，logic 只接受成对的 `.yml`/`.js`。
包名、派生本地 ID 和完整 `包名:本地ID` 均使用严格稳定身份，不使用类名。相对路径中的每一段都必须符合小写
稳定 ID 规则；真实路径必须仍位于对应包和子目录内，符号链接不能逃逸。logic 的
两种扫描字段必须恰好提供一个有限正数；缺失、并存、null或非法类型均拒绝候选。固定距离模式是合法并存能力，不是旧开发格式兼容路径。

发现使用两阶段解析：第一阶段读取全部包身份、模型文件和逻辑文件，捕获 JS 源并检查身份；第二阶段解析全部 signal
文件的跨包引用。因此包目录枚举顺序不影响结果。包 A 的 signal 可引用包 B 的模型或逻辑，只要稳定 ID 能解析，
就不能因发现顺序拒绝。缺包、未知引用、引用类型错误、配对缺失或路径逃逸使完整 catalog candidate 准备失败；
当前函数本身没有 live publication 可部分替换。全部模型通过宿主公共 compiler 完整解析、展开并校验，任一模型错误拒绝
整个 catalog candidate；model import 的缺失引用和环也在此阶段拒绝。返回的 catalog、package、model、logic 和 signal 表不可变；
模型和 JS 源在 catalog 准备时捕获，后续读取 immutable value，不重读文件，`SignalLogicRuntime` 也不重读 JS，
避免校验完成后文件变化造成候选内容漂移。正常 tick 不读取包文件。

这是未发布配置边界的 current model，保持 `config-version: 1`，没有旧布局 alias、upcaster 或运行时兼容 reader。
后续格式变化仍须依照[版本号与发布基线](../../javarailways/shared/versioning.md)，不能按提交次数升版。
JS 的执行 foundation、同步信号阶段目标和树形输入见[JS 信号规则契约](js-signal-rules.md)。

### 统一包可执行合同

1. **状态与 owner**：目录发现、身份派生、跨包解析、模型 materialization、JS 源码捕获、世界 epoch 与模型绑定均为
   `CURRENT`；SimpleSignals 拥有包/catalog，BukkitPlugin 公共 compiler 拥有普通模型 YAML 语义。
2. **工作流**：`load(root)` 先按目录名排序读取所有 `pack.yml`，再递归发现三类文件；
   每个 package 的 model 捕获后作为一批交给 compiler，logic 进入全局表，signal 仅暂存引用，待所有包就绪后再统一解析，最后一次性返回
   `SignalPackCatalog`。
3. **内部机制**：ID 由包名与去扩展名的相对路径派生；路径每段单独验证且拒绝空段、`.`、`..`。
   递归发现跟随目录符号链接，但每个候选节点都先转换为 real path 并证明仍在包目录内；
   这使逃逸链接成为错误，而不是被静默忽略。
4. **签名与结果**：`SignalPackLoader.load(Path): SignalPackCatalog`。catalog 分别以完整 ID 索引
   `models`、`logics`、`signals`；`SignalPackSignal` 同时暴露 `signalType`、`modelId`、`logicId`及已解析对象。
   所有表均为不可变快照。
5. **不变量与版本**：一个 logic 必须恰有同 stem 的 YAML 和 JS；一个 signal 必须解析到一个
   modelId 和一个 logicId。候选准备不发布部分结果。未发布格式仍为 schema 1，不提供旧
   `models/logics/combinations` 入口的运行时兼容。
6. **错误矩阵**：未知字段或扩展名、空显示名、非法 ID 段、缺失 logic 配对、两种扫描参数缺失/并存或任一非正有限、
   重复包或 ID、未知／错类型引用、符号链接逃逸均拒绝完整 candidate；错误包含包、文件和字段位置。
7. **复杂度与降级**：设候选目录节点数为 F、实际读取总字节为 B、signal 引用数为 R、模型展开/校验实际访问量为 V，准备成本为
   `O(F log F + B + R + V)`，内存为 `O(F + B + V)` 量级；只在低频准备阶段发生。任一错误使本次加载失败，
   不伪造模型、逻辑或 signal 占位值。
8. **案例**：基本案例是同包 signal 引用本包 model/logic；合法扩展案例是包 A 的 signal 引用包 B
   的 model 与 logic；错误案例是孤立 `.js`、逃逸目录链接或把 logicId 写到 model 字段。
9. **必需测试**：固定验证嵌套路径派生、跨包与反向枚举顺序、同 stem 配对、未知字段、
   `.`/`..` 段、文件与目录符号链接逃逸、实际模型 materialization、model import/cycle/variant、
   捕获后不重读源文件和返回表不可变；两种扫描模式的互斥、无默认减速度、零/负/非有限值及模式解析均须直接过真实loader。生产绑定还须证明同一 catalog signal 同时选择 JS 与模型、未知类型不创建 handle、
   非法改绑保留旧磁盘／publication／source／模型。
10. **迁移与错误用法**：正确做法是由 `signalType` 显式引出 modelId/logicId，并让世界 deviceId 保持独立；
    错误做法是从已放置设备 ID、模型 ID 或文件名猜测逻辑，或在主信号内再保存可冲突的 modelId。

## 一次加载如何工作

1. 读取包入口，知道包名及本包提供哪些模型。
2. 安全读取模型文件和共享部件文件，把捕获文本交给 BukkitPlugin 公共 compiler 检查字段、引用、循环和数值。
3. 展开模型的部件引用，把米制偏移和角度转换为公开模型值；检查所有默认和普通外观映射。
4. 返回完整、不可变的加载结果：每个模型的部件树、`default`、`variants` 和摆放预设。
   任一文件错误就不返回这个包的部分结果。
5. 后续设备绑定按模型 ID 取得结果。正常信号输出只按 `modelVariant` 查表，不重新读取文件。

加载器只接受包目录并返回结果，不创建实体，不安装世界状态，也不实现热重载管理器。
文件读取、引用展开和校验由调用方安排在工作线程，不放进物理或信号更新周期。
启用时的发现与世界绑定由运行时接线负责；当前没有包热重载或运行期包替换。

### 加载入口和结果

既有模型-only 入口属于 SimpleSignals 私有的 `presentation` 包，但模型语言编译复用 BukkitPlugin 公共边界：

```kotlin
ModelPackageLoader.load(directory: Path): LoadedModelPackage
LoadedPackageModel.variant(name: String): Map<String, String>
```

加载结果保存包名、可选显示名，以及以完整 `包名:模型名` 为键的模型表。每个模型保存已有
`ModelDefinition`、完整的默认场景与普通外观映射、模型默认倾斜值及已解析的 `RailPlacementPreset` 表。
名称表与嵌套外观映射在入口复制为不可修改的集合；公开模型树本身也已不可变，可供多台设备共享。
`variant` 找不到明确名称时抛出带模型 ID 的配置错误，不使用 default 替代，不访问文件。

`load` 本身是同步的准备函数，调用方负责将它放到工作线程；它不创建额外执行器，也不自行
发布包或登记设备。返回结果不保留解析器、文件句柄或以后重新读取配置的回调。

## 目录和身份

包存放在 `<SimpleSignals dataFolder>/models/<包目录>/`。启用时在 Paper 工作线程加载直接子目录，
根目录缺失返回空表；每个子目录都须是完整模型包。包错误或重复包名拒绝整次发现，
不发布部分结果；后端世界安装不等待这一步。读取一次后复用不可变模型 ID 表，无定时扫描。
下列布局已经由固定测试文件验证：

```text
demo_signals/
  manifest.yml
  models/
    home.yml
    wall.yml
  parts/
    mast.yml
    head.yml
```

- `manifest.yml` 的 `name` 是稳定包名；模型 ID 为 `包名:模型名`，例如 `demo_signals:home`。
  目录名只负责存放文件，不是另一份身份；不同目录出现相同包名时，安装阶段应报错。
- `models` 明确列出本包的模型名；模型名对应 `models/<名称>.yml`。
  部件用 `parts/` 下相对文件名识别，例如 `head` 或 `shared/head`，不在文件里重复写 `name`。
- 包名使用 `[a-z0-9._-]+`；模型名和部件引用允许同样字符组成的多级 `/` 路径，但禁止空段、`.`、`..`、
  绝对路径与反斜杠。实际读取路径必须留在本包目录内，包括解析符号链接后的路径。
- 首批只支持包内共享部件、目录和 `.yml` 文件，不支持跨包导入、ZIP 安装或自动下载资源包。
  这是当前功能边界，不是对旧信号格式的兼容策略。
- 仅入口声明 `config-version: 1`，覆盖整个包；内部文件不独立加载，不另加版本号。
  它与列车包当前的版本无关，也不是包作者的发布版本。

## 完整示例

下面五份 YAML 组成完整的插件模型定义示例，对应 `SimpleSignals/src/test/resources/model-packages/demo_signals/`。
`demo_signals:signal/...` 是客户端资源包的物品模型键，
资源包中的网格、纹理和物品模型 JSON 需另行提供；本示例不声称这些客户端资产已存在。

`manifest.yml`：

```yaml
config-version: 1
name: demo_signals
display-name: 示例信号模型
models:
  - home
  - wall
```

`models/home.yml`：带立柱的模型，提供左右两种轨道摆放预设。

```yaml
parts:
  - import: mast
  - import: head
    offset: {y: 3.2}
default:
  lamp: dark
variants:
  stop: {lamp: red}
  proceed: {lamp: green}
rail-placement:
  follow-rail-tilt: false
  presets:
    left:
      offset: {z: -2.0}
      rotation: {y: 180.0}
    right:
      offset: {z: 2.0}
      rotation: {y: 180.0}
```

`models/wall.yml`：只复用灯头，可由玩家直接摆放在墙边或地面位置，不依赖立柱模型。

```yaml
parts:
  - import: head
default:
  lamp: dark
variants:
  stop: {lamp: red}
  proceed: {lamp: green}
```

`parts/mast.yml`：

```yaml
type: item_display
item:
  type: PAPER
  item-model: demo_signals:signal/mast
```

`parts/head.yml`：

```yaml
type: item_display
item:
  type: PAPER
  item-model: demo_signals:signal/head_dark
animations:
  - name: lamp
    scenes:
      dark: {}
      red:
        item:
          type: PAPER
          item-model: demo_signals:signal/head_red
          brightness: {block: 15, sky: 15}
      green:
        item:
          type: PAPER
          item-model: demo_signals:signal/head_green
          brightness: {block: 15, sky: 15}
```

这里 `lamp` 沿用主插件“动画名 → 命名场景”的表达，`scenes` 直接按名称定义静态外观。
作者不填写 `at`、时长或帧序号。例如输出 `proceed` 时直接选 `green`；没有有效结果时选 `dark`。
场景在配置中的排列顺序不决定信号变化顺序，也不影响按名称选择的外观。
当前不支持时间轴播放、闪烁、循环或动画命令，不能把静态场景切换说成已经支持这些功能。

`stop`、`proceed`、`lamp`、`dark` 等名称由作者定义，没有内置的灯色或速度含义。
包中不写 `setSpeedLimit` 或运行规则；信号规则只输出不透明的 `modelVariant`，模型包负责把它显示出来。
`wall` 没写轨道预设，并不意味着只能地面摆放；是否附着轨道由设备实例决定。

### 带客户端资产的 JR 灯头示例

[JR 四灯包](../../../../SimpleSignals/examples/jr-four-light/README.md)复用用户提供的灯头网格和贴图，
分别提供 `stop`、`caution`、`slow`、`proceed` 外观；模型中的名称仍然只是配置数据。
分发目录现为完整统一信号包，具体速度和距离敏感判断由其中的JS承担，见[四灯规则](formal-four-light.md)；
不再为同一个灯头维护第二份model-only YAML，也不改变通用model-only加载器。
服务器模型包和客户端资源包分别保存，不把客户端网格解析进服务器部件树。
客户端物品定义用各自的 `jr:` 模型键引用相应网格，不覆盖 `minecraft:paper`。
这个模型没有立柱，`default` 明确选择停止外观，不增加不存在的熄灯模型。
默认外观只负责显示，后端不可用状态的零限速仍由既有信号流程提供。

原始网格纵向范围为 `0..42` 个模型单位。分发副本将全部元素纵坐标减去16，
满足客户端元素坐标范围；部件摆放再补偿一个方块，保留原尺寸和灯位。
不修改原始模型工作区，也不把这种示例资产调整做成运行时转换器。

示例测试必须读取同一份分发文件，并验证外观引用能沿“物品定义 → 网格 → 贴图”找到实际资产，
同时验证安装朝向和左右摆放预设。此类本地文件检查不等于真实客户端已显示，
本示例文件测试本身不证明生产实体已经创建；显示接线回归与真实客户端验证分别进行。

## 字段和转换规则

### 入口与模型

入口必填 `config-version`、`name`、非空且不重复的 `models`；`display-name` 可省略。
模型文件必填非空 `parts` 和 `default`；`variants` 默认 `{}`，`rail-placement` 可省略。
每个模型都有自己的完整 `default` 和普通外观映射，不建立包级默认值继承或模型继承。

统一 `model/` 布局没有独立 `parts/` 目录。它的 `import` 以安全相对 model ID 指向同包另一个模型，导入目标
parts 树并包成 identity pose `Group`；目标模型的 `default`、`variants`、`rail-placement` 都不继承。
被导入模型仍作为独立模型完整解析和校验。既有 model-only 布局继续让 import 指向 `parts/`；两种模式由调用方
显式选择，不在一个 candidate 中混用，也不根据文件内容猜测。

`default` 和每个 variant 都是“动画名 → 场景名”的完整映射，允许显式 `{}` 恢复基础外观。
不把 variant 与 default 逐项合并。名称按原字符串精确匹配，不改大小写、不猜拼写、不添加别名。
模型包加载时还不知道任意脚本将输出什么；因此只能验证已声明映射，未知运行期 `modelVariant`
仍由绑定报告错误，不能宣称加载检查能证明所有脚本输出都存在。

### 部件、导入和坐标

`parts` 中每项或者是导入，或者是内联部件，两种形式不能混写。

- 导入只允许 `import`、`offset`、`rotation`；共享部件文件本身保存一个具体部件，不保存另一层导入别名。
- 具体部件使用 `type: dummy`（容器）或 `type: item_display`（物品叶子），可带 `offset`、`rotation`、`animations`。
  容器通过 `parts` 保存子部件，不能带 `item`；物品必须带 `item`，不能带子部件。
  不接受列车的驾驶座、交互和站台碰撞类型。
- 偏移为米；`offset` 与 `rotation` 都使用 `{x, y, z}`，缺失分量为 0。
  旋转使用度，按现有模型的 `rotateXYZ` 约定转换：`q = qX × qY × qZ`，不是 Bukkit 的 yaw/pitch。
- 导入语义与列车保持一致：引用上的偏移和角度分别与部件定义的对应分量相加，
  再按上述约定转成一个部件姿态，不修改共享定义。不能仅因用途是信号，就把相同写法改成另一种装配操作。
  不因导入自动增加父容器。
- 整套部件需要绕共同安装点旋转时，显式放在 `dummy` 容器下。现有列车渲染已经支持这种父子变换：
  子部件的偏移随父旋转转动，旋转按父子顺序组合。导入时的参数合并和树内的父子变换是两种不同操作，
  不应把现有导入规则描述为列车不支持正确的层级变换。
- 模型自身沿用 X 前、Y 上、Z 右。轨道摆放预设也使用这套有向参照，但与模型内部部件变换是两回事。
  部件树变换、缩放与完整复位由主插件契约决定，本模块不再建立第二套渲染变换算法。

`item` 必填 Bukkit 物品 `type`，例如 `PAPER`；可选 `item-model`、`custom-model-data`、`brightness` 和 `scale`。
模型键必须包含命名空间；也允许作者主动只使用普通物品。缩放默认三轴 1，非负且有限，0 可隐藏物品。
亮度省略表示自然光；提供时必须完整给出 `block` 和 `sky`，都是 0..15 的整数。
CMD、物品种类和模型键沿用公开 `ItemAppearance` 的校验，不复制一套更宽松的规则。

### 命名场景

`animations` 默认空列表；每项必填 `name` 和非空 `scenes` 映射。场景名称是映射键，必须非空白且不重复；
场景值可以是显式 `{}`，或包含 `offset`、`rotation`、`scale`、`item`。偏移/旋转默认 0，缩放因子默认 1。
容器场景只能提供位移与旋转，不提供 `scale` 或 `item`。

加载器将场景转换为已有公开 `ModelAnimation.Frame`。公开帧仍要求首帧时间为 0、后续时间递增；
适配时按场景读取顺序填入 0、1、2……，仅满足既有值类型的构造约束。这些值不出现在信号包配置中，
不用于等待、播放或信号调度；所有场景都按名称定位。无需为此新增另一套主插件模型类型。

场景的 `item` 是完整目标外观，而不是上一次外观的补丁；省略整个 `item` 则使用部件基础外观。
提供时 `type` 必填，省略模型键、CMD 或亮度就清除相应覆盖；缩放写在场景的 `scale`，场景的 `item` 不接受 `scale`。
这刻意不同于列车配置中的零散 `item-type` 等覆盖字段，避免场景切换后残留上一次灯色。
同名动画可分布在多个部件，但场景名集合必须一致；同一部件不能重复声明同名动画。
一个完整外观不能同时选中同一部件的两个动画，加载时即拒绝，不交给实体线程试错。

### 轨道摆放预设

`rail-placement` 只允许 `follow-rail-tilt` 和 `presets`；预设以非空名称为键，每项允许
`offset`、`rotation`、`follow-rail-tilt`。位移和旋转默认 0，不提供“默认自动选第一个预设”的规则。
创建或重新应用预设时，倾斜选项按“玩家明确选择 > 预设值 > 模型值 > false”确定。
加载器先把模型默认值解析到各预设，复用已有的 `RailPlacementPreset`；应用时复制参数到设备。
已有设备不保留逐字段继承，包文件改动不会自动移动它；地面摆放不用这些轨道预设。

## 校验、内部机制与成本

YAML 只读取普通值，不允许任意对象标签、重复键或合并键/别名；未知字段、显式 null 和类型错误均报错。
字段可省略不等于允许 null。读取全部本包部件，建立“部件名 → 定义”表；用当前递归路径检查循环引用，
共享同一部件是合法的，不能把“曾经访问过”误认成循环。每个共享定义的展开结果只构造一次；
非零导入参数只替换该引用根节点的姿态，其已有不可变子树可复用，不为每台设备深拷贝模型树。
所有共享定义都做校验，即使本包的模型尚未引用它，也不能隐藏损坏文件或引用环。
完成展开后，从没有被其他共享定义引用的根部件检查整棵树的场景一致性。引用图已排除循环，
每个内部共享部件都能被至少一个根覆盖，不必把一条嵌套链的每个后缀再次完整校验。
不同根的场景命名空间分别检查，不能把两套独立模型里同名但不同场景的动画错误地混在一起。

公开模型构造器负责数值和树内不变量。BukkitPlugin 公共 compiler 在加载阶段建立局部的动画命中部件索引，
检查 `default` 和全部 variants 中的未知动画、未知场景及同一部件多重命中；不导入宿主私有 `OrdinarySceneIndex`，
也不为了校验而创建世界或模型操作对象。索引按树中实际摆放位置记录命中，不把两个复用同一值对象的
部件误认成同一个位置。该临时索引在校验结束后可丢弃，不做第二套长期显示索引。

错误至少包含包、文件与字段路径，例如：

```text
demo_signals/models/home.yml: variants.proceed.lamp — 未知场景 grean；可用场景 dark, red, green
demo_signals/parts/head.yml: parts[0].import — 循环引用 head -> bracket -> head
```

错误不产生实体、不申请行车资源、不修改源文件。缺少引用不能用石头模型、红灯或空树替代。
外部资源包键只能校验语法，不能从服务器证明客户端纹理/网格存在。

设 YAML 总字节为 B、共享定义与引用总数为 D、实际构造的部件/动画/帧总数为 N；
V 是共享定义和模型的树校验、场景索引建立过程中实际遍历的节点/帧次数。
共享树一致性只从共享根校验，随后每个声明模型分别建立索引和校验，不再逐层扫描嵌套链的每个后缀。
重复放置共享子树、多个根或模型复用同一子树，仍增加实际遍历量 V；不能只按不同源文件数量计算成本。
检查全部外观映射另需 `O(M + H)`，M 是映射条目总数，H 是这些条目命中的部件总数；
完整加载为 `O(B + D + N + V + M + H)`。这些访问只发生在工作线程的包准备阶段。
加载期内存为 `O(B + D + N + M + I)` 量级，I 是当前模型的场景命中索引大小；原始解析树和校验索引随后释放。
结果使用只读名称表，运行期选择 default 或按 variant 名查找为期望 `O(1)`；主插件的场景比较和实体更新另计。
不新增逐 tick 文件扫描、后台永久轮询或与模型包无关的世界锁。

## 实现批次的验证与后续边界

已完成加载器和完整包回归；测试读取固定五文件包，而非只验证 YAML 数据类：

- 完整五文件 fixture 可读取为两个模型，共享部件正确展开；重复导入与内联部件可用。
- 验证导入参数合并、显式容器的父子变换、度转四元数、场景完整替换、自然光恢复，以及预设默认值解析。
- 场景配置不要求时间字段；改变场景排列不改变按名称选出的外观，运行期不播放适配生成的帧时间。
- 缺失部件、循环引用、非法路径、重复/未知 YAML 字段、非法数值与非法场景拒绝整个包。
- 缺少 default 拒绝，显式空映射有效；同名动画广播及同一部件选择冲突有断言。
- 相同已加载模型可复用，修改一个设备的摆放值不修改模型或其他设备；选择外观不再访问文件。
- 未被模型使用的嵌套共享定义也拒绝非法场景集合；独立共享根保留各自场景命名空间。
  从调用方传入的嵌套 Map 构造结果后，调用方修改不会影响已加载值，结果也拒绝直接修改。

既有 `ModelPackageLoaderTest` 的 27 项回归继续通过，使用固定 YAML 与真实公开模型值、Paper 物品注册表初始化；
宿主 compiler 聚焦测试覆盖两种 import mode、不可变值、循环和非法选择，统一包测试覆盖 materialization、
同包 model import、原子失败及捕获后不重读。这些证据验证配置准备，不证明真实 Folia 的实体显示或客户端资源包内容。

本批不改世界定义 schema，不迁移列车模型包，不支持任何先前草稿格式的兼容别名。
设备模型/摆放存档及显示接线已完成，后续为 Wand 编辑与真实 Folia 显示验证。
场景时间轴和跨包 model import 等尚未支持的能力，出现需求后再讨论，不以额外框架预先占位。
