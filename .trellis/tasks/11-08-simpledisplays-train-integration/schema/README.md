# 屏幕部件定义与示例

状态：已实现部件定义、严格解析和正式 schema（2026-11-09）。支持 `type: screen` 的
import、内联和嵌套；目前模型构建只保留变换、动画和子节点，不产生屏幕文字实体。
样式、实际渲染、视距配置和玩家命令尚未实现。

实现契约见[屏幕部件规范](../../../spec/bukkit-plugin/backend/screen-parts.md)。

## 文件和部件结构

- 正式 schema：`schemas/train-model/model-common.schema.json` 中的 `partDefinition`、`screen` 和
  `partReference`；继续由现有 `part-v1.schema.json` 和 car schema 复用。本任务不再保存平行的
  完整 schema 副本，避免与正式定义漂移。
- `screen-part.example.yml`：可供 import 的独立屏幕部件定义。
- `parts-tree.example.yml`：车厢 `body-parts` 片段，演示 import、内联和挂在 dummy 子树中的屏幕。
  坐标仅示意，不是已校准的车型数据；它不是完整可部署车包。

屏幕使用已有部件的 `name`、`offset`、`rotation`、`parts`、`animations` 字段。它可以作为
`body-parts` 的节点，也可以放入其他部件的 `parts`；不增加车厢根层 `screens` 列表，不新增
屏幕专用 `id`。部件名称及 import 的解析继续沿用已有体系，不额外要求名称全车唯一。
未来如需对某个引用实例单独绑定样式，实例寻址需结合现有部件树另行设计，不能把可复用的
部件定义名称冒充每次引用的唯一身份。

## screen 专属配置

`type: screen` 必须包含 `screen` 对象，其中四个字段均必填：

- `category`：非空、无空白、区分大小写的自定义类别；`head_led`、`side_led`、`cabin_side_lcd`、
  `cabin_end_led` 只是推荐名称，不限制枚举，也不推断内外属性。
- `interior`：布尔值，无默认值。`false` 随车身显示；`true` 首版使用 config/玩家命令控制的
  距离筛选。不要求玩家坐下或位于车内，不增加背面、视锥或遮挡检测。
- `width`、`height`：正数，单位米，分别沿屏幕局部 X/Y 方向。

`offset`、`rotation` 属于公共部件字段，不放进 `screen` 对象。位置相对父部件，采用现有模型
局部坐标和米制单位，不再额外加一次 `floor-height`。零旋转时屏幕平面为局部 XY、原点为中心、
布局正面朝 +Z；正面约定仅决定内容朝向，不增加背面 culling。

旋转单位度，沿用 `Quaterniond().rotateXYZ(radians(x), radians(y), radians(z))`；省略公共
向量分量时为 0，省略旋转/偏移时采用现有部件零变换。`import` 引用项仍仅支持已有的
`import`、`offset`、`rotation`，不新增引用项内 screen 覆盖字段。

## 校验与本批边界

对象拒绝未知字段；`screen` 只允许在 `type: screen` 部件上出现，且该类型必须有完整的
专属配置。数值必须有限，YAML `.nan`、`.inf` 在解析边界拒绝。JSON Schema 不执行
默认值填充、跨文件引用解析或 runtime 生命周期；解析与引用行为由已有部件加载流程负责。

公共变换、子部件和动画沿用既有模型生命周期；屏幕内容动画尚未实现。未来的屏幕玩家筛选
只影响屏幕输出，不能隐藏同树其他部件。

本轮只实现定义/schema/解析和现有树/快照的元数据保留，不定义样式路径、XML、默认样式引用、数据绑定、动画新指令、逐屏视距、配置键或命令。
视距留在服务端和玩家设置中；车内外属性留在部件定义中。无屏幕车包无需新字段。
不为 screen 嵌套配置新增 schema version，不修改现有版本常量；后续格式变化仍遵守发布基线政策。

## 显示模板配置草案（未实现）

新增的 `displays-draft.schema.json` 和 `displays.example.yml` 是后续模板加载批次的审阅材料，不属于已经实现的screen-part合同。模板统一引用XML，动态脚本写在XML内；不包含type或重复的逻辑尺寸。共同表达规则及现有格式差异见[配置规范草案](../research/config-schema-conventions.md)。当前正式模型schema、版本及解析器均未修改。
