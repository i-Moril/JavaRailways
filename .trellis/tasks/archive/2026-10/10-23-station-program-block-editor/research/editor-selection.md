# Scratch 式编辑器选型

调研日期：2026-10-23。范围已由用户确认：同时实现共享程序库和到站动作。以下是文档与当前源码分析，不代表已完成接入或浏览器验收。

## 项目需要什么

现有 React/TypeScript/Vite + Ant Design。`types.ts` 中 `StationProgramDefinition` 是有序 steps 树，包含12种步骤类型（COUPLE/SPLIT 共享一个联合成员）、10种参数类型、literal/parameterId 表达式及嵌套 BRANCH。编辑器应表达有序执行与嵌套分支，而不是任意节点连线图。程序 ID/步骤 ID、库 revision/归档、绑定定义快照、内容哈希和现有后端校验必须继续有效。

## 推荐：Blockly + Zelos renderer + 铁路领域自定义积木

- 官方提供可定义字段、statement/value connection 和连接检查的积木模型；现有有序树可直接映射。
- Zelos 官方定位就是基于 Scratch 3.0 外观，无须为了积木外观引入整套 Scratch 产品。
- 主题可控制画布、积木、工具箱和字体，支持动态更换，可接入已有明暗主题。
- 官方提供本地化和键盘操作机制；具体锁定版本与自定义字段仍需验证，不能把存在官方能力当作本项目已可无障碍操作。
- 只封装一个 React workspace 生命周期组件，两处使用同一编辑核心；组件卸载须 dispose，隐藏后展开须重新计算尺寸。
- 自定义参数连接检查区分 TICK/DURATION、DESTINATION/STATION/DEPOT、JSON_OBJECT 等。连接检查不能代替上下文/业务校验；例如重联和回库不能放进分支的现有规则仍由领域 validator 拒绝。

资料：
- https://docs.blockly.com/guides/create-custom-blocks/renderers/overview/
- https://docs.blockly.com/guides/create-custom-blocks/inputs/connection-checks/
- https://docs.blockly.com/guides/configure/serialization/
- https://docs.blockly.com/guides/configure/appearance/themes/
- https://docs.blockly.com/guides/configure/translations/
- https://docs.blockly.com/guides/configure/keyboard-nav/
- https://raw.githubusercontent.com/RaspberryPiFoundation/blockly/main/package.json （仓库声明 Apache-2.0；main 不是待锁定发行版本）

## 其他候选

### Scratch Blocks 2

优点是最直接的 Scratch 交互和外观。当前官方 README 明确2.0已从旧分叉改为依赖 Blockly，不能再引用“老 fork 无维护”的旧结论。当前 develop package 声明 Apache-2.0、TypeScript 类型和 Blockly 依赖。

其官方设计更围绕 Scratch VM 生态，不提供 Blockly 式代码生成路径；这不代表强制依赖 VM，但我们的应用仍需自行把积木映射回铁路模型，并处理 Scratch 专用约定。与直接使用 Blockly+Zelos 相比，没有发现足以抵消额外适配成本的项目收益。若要求精确复制 Scratch 的所有行为，才重新评估。

- https://github.com/scratchfoundation/scratch-blocks
- https://raw.githubusercontent.com/scratchfoundation/scratch-blocks/develop/README.md
- https://raw.githubusercontent.com/scratchfoundation/scratch-blocks/develop/package.json

### Microsoft MakeCode / PXT

本身使用 Blockly，提供 Blocks/Static TypeScript/模拟器等完整编程平台。我们已有 JavaScript DSL、编译器和游戏运行时；引入整个平台再适配两套语言/运行链路范围过大。可参考交互，不选为依赖。

- https://makecode.com/language
- https://github.com/microsoft/pxt

### Rete.js 等节点编辑器

Rete 提供 React 渲染、连线、嵌套 scope 等能力，适合节点图。我们的核心是从上往下的 statement tree；做成 Scratch 插槽、C形分支和有类型参数需要额外实现较多规则，不优于 Blockly。

- https://retejs.org/docs/api/
- https://retejs.org/docs/guides/scopes/
- https://retejs.org/docs/api/rete-react-plugin/

### 完全自研 React 积木

可以贴合 Ant Design，但吸附/连接、拖动插入、分支嵌套、缩放、键盘和 Undo 都成为长期维护责任。现有步骤数量已经超出值得自研简单列表的规模，不推荐。

## 与现有模型接合的关键边界

1. 保存和执行仍以铁路领域模型为准：积木 -> StationProgramDefinition -> 现有校验/绑定/JS生成/编译。不要直接用通用 JS generator 绕过领域模型，也不引入 Scratch VM。
2. Blockly 的 JSON 适合作为编辑工作区状态，不直接替换已有库记录。语义定义与画布布局、未连接积木和未完成字段是不同状态。未完成编辑不能被静默丢弃；需在设计中明确草稿恢复及保存阻止规则。
3. 稳定步骤 ID 必须在双向转换和复制时正确处理；复制产生新身份，简单拖动不更改身份。程序内容哈希不能因画布平移和布局而变化。
4. 一次拖拽及其自动连接作为一次编辑事务，避免 Blockly Undo 与现有 documentHistory 各维护一套互相矛盾的领域历史。
5. 程序库和到站动作共用组件及积木定义，通过上下文配置限制允许的动作；到站触发和发车衔接由运行时设计决定，不能由 UI 插槽推断。
6. 先做小型技术验证：既有每种步骤双向无损转换、嵌套条件/参数、键盘路径、主题、卸载重挂载和未完成积木保护。再锁定 npm 稳定版本；目前未安装新依赖。

## 结论

推荐直接采用 Blockly，使用 Zelos 的 Scratch 式积木形状，叠加铁路动作、参数和条件。这是基于项目模型与官方能力的工程判断；体积、性能、具体 React 接入和自定义字段操作仍需实际验证。
