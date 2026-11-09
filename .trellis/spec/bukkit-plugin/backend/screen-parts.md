# 屏幕部件定义

## 当前范围与正常流程

CURRENT：Bukkit 车包可以在现有部件树中声明 `type: screen`。`PartRegistry` 读取独立 part YAML 或
内联 part，严格校验后生成 `PartDefinition.screen: PartScreenConfig?`。安装位置、旋转、名称、
子部件和动画继续使用公共字段；引用仍使用现有 `import`，不增加车厢根层列表或屏幕专用 ID。

本阶段只实现定义、解析及元数据保留。`ModelTreeBuilder` 将 screen 构造成仅有变换和子节点的
`DummyModel`，不生成屏幕文字实体，不编译模板，也不执行视距筛选。不要把成功加载定义报告成
屏幕已显示。其他普通子部件仍按原规则构建、更新和销毁。

正常路径是 `PackageLoader`/`CarLoader` 经 `PartRegistry.parsePartDefinition` 处理部件和引用，
之后沿既有 `PartResolver`/模型树路径构造。引用覆盖只使用已有的 `offset`/`rotation`；不能在
`import` 节点中增加 `screen` 配置覆盖。嵌套定义的变换相对父节点，不是全车绝对坐标。

## 配置与不变量

```yaml
config-version: 1
name: cabin_lcd
type: screen
screen:
  category: cabin_side_lcd
  interior: true
  width: 0.8
  height: 0.45
```

`screen` 对象的四个字段均必填；对象拒绝未知字段。`category` 为非空、无空白的字符串，类别可
自定义，不用 enum 限制，也不根据类别名称推断车内外。`interior` 为严格 boolean；宽高为有限
正数，单位米。公共 `offset`/`rotation` 沿用现有规则，角度为度；屏幕局部平面为 XY，原点为
中心，宽沿 X、高沿 Y、内容正面朝 +Z。这不隐含背面 culling。

`PartScreenConfig` 构造时验证类别与尺寸；`PartDefinition` 构造时保证
`(type == SCREEN) == (screen != null)`，所以直接创建对象和 JSON 解码也不能绕过类型一致性。
YAML 解析器另外负责必填字段、类型和未知键校验，拒绝缺失/字符串 interior、零/负/非有限尺寸、
无 screen 配置的 SCREEN，以及其他类型携带 screen 配置。错误不得静默回退成普通部件。

正式配置依据为 `schemas/train-model/model-common.schema.json` 的 `partDefinition` 和 `screen`；
现有 `part-v1.schema.json` 与 car 的 `partReference` 继续复用它，覆盖内联和递归子树。
JSON Schema 不解析跨文件 import、不填充默认值，也不替代 Kotlin 的有限数值校验。

## 复制、快照与生命周期

`PartDefinition.resolveTree` 必须先选 `entry.inline`，否则走 registry；通过 `copy` 合并公共
引用变换，保留 screen 元数据和其他既有字段。内联定义本身已有位移，不能在 reference 再存一份
导致叠加两次。`ModelTreeBuilder` 继续使用其原来的 inline/import 解析和变换规则。

`screen` 是已有序列化模型里的新增 nullable 字段，默认 null，不是独立持久化边界，不添加
schemaVersion 或新旧 reader。既有模型树 canonical JSON 已使用 `explicitNulls=false`，无屏幕
部件不会多写 null，因此旧模型的 canonical 字节/哈希应保持不变；必须用固定基线哈希验证。
有屏幕的树须在既有快照往返中保留四个配置字段，配置变化须影响内容哈希。本阶段不改包快照布局、
版本常量或模板资源固定机制。此规则也不承诺旧插件能读取新增 screen 类型。

解析/定义不创建 listener、scheduler 或实体，也不增加跨 region Bukkit 访问。screen 的模型
构建、变换及销毁沿已有 group 生命周期；后续 renderer 的所有权和接收者筛选需要另行实现。
新增元数据的单节点校验和复制是 O(1)；完整树仍按原有遍历处理，类别扫描为 O(字符串长度)。
不加入每 tick 的类型发现、模板编译或全玩家扫描。

## 必需验证与后续

验证独立 YAML、真实 import、内联及混合嵌套；检查 metadata 在引用变换、树复制和现有快照中
保留，且位移只合并一次。负例覆盖缺失/错误类型/未知键/非法类别与有限正尺寸；screen-free
固定哈希不变，screen 配置改变哈希。编译所有 PartType consumer，并运行 Bukkit 模块测试与打包。

TARGET：车外屏幕与车身使用相同显示策略；车内屏幕首版按距离筛选，配置默认值和玩家命令覆盖。
这些需求尚未在本批实现。样式文件、默认绑定、实际文字绘制和逐玩家发送同样留待后续。
不要给屏幕建立独立顶层列表，不要把可复用 part 名称当作每次引用唯一 ID；若未来需要某次引用
的样式覆盖，先沿现有树身份设计寻址契约。不要因 screen 暂无 renderer 而跳过其正常子节点。
