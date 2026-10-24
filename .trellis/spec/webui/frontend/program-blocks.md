# 共享程序与到站动作的积木编辑

## 范围与数据权威

程序库与到站动作使用同一个 Blockly/Zelos 编辑组件。页面仍使用既有 Ant Design、i18n 和 theme；Blockly 只负责积木交互，不拥有服务器执行引擎。铁路语义定义仍为 `StationProgramDefinition`，库版本/归档/绑定快照与内容身份规则不变。

正常编辑流程是从定义建立唯一入口和有序连接积木，玩家从工具箱添加动作、连接顺序并在分支内部编辑两臂；参数插槽区分 literal 与声明引用。只在得到完整、合法的语义定义时向所属文档提交变更，经既有 validation、JS生成和服务器编译后执行。绝不使用通用 JavaScript generator 绕过铁路模型。

## 工作区与领域文档

画布布局、缩放、选中状态与未完成积木不是领域内容。有效定义的内容身份不因布局变化而改变；已有 step ID在拖动时保持，复制则生成新身份。定义/画布双向转换必须覆盖每一种已支持步骤和表达式，不能借替换编辑器删减能力。

固定入口连接唯一可执行序列。游离积木、未完成输入或不允许的上下文结构必须明确阻止保存/编译，不能只导出入口子树而丢弃其余内容。未完成工作区本地恢复按 owner/world/fieldScope 隔离，并同时核对原始服务器基线与最后接受的语义定义；语言切换重建画布时必须保留原始基线，不能将未保存内容当成新服务器基线；它是 best-effort 草稿，不能替代服务器保存的程序。受控父组件接受同一语义定义时，不得重建工作区并丢掉游离积木。

一次拖动及自动连接按一个事件组形成一次领域编辑。到站有效领域编辑使用既有文档 history；尚未提交领域的无效画布编辑先撤销本地草稿，不能让父文档 Undo 丢弃游离积木。库可使用画布历史。不得让两个独立 Undo 历史相互覆盖。卸载注销事件并 dispose；弹窗或隐藏容器显示时重新计算尺寸，禁止旧回调写入已切换世界/文档。

## 两种编辑上下文

库上下文保留完整步骤、声明参数和表达式类型。到站上下文为零声明参数的内联 `arrivalProgram`，允许显示/重置显示、绝对等待、持续等待、条件与扩展；改变方向、路线或编组的动作保持交路程序职责。禁用动作须有明确文案，传入不支持的结构必须报错，不能静默转换为空程序。

到站画布从选中停站详情的大弹窗打开，允许 STOP/TERMINAL；ORIGIN/PASS 不提供触发。无效草稿关闭弹窗后仍保持文档保存/编译阻塞，重新打开应可修复，不得在组件卸载时无条件恢复“有效”。实际执行边界由 [Dispatcher 契约](../../javarailways/simpleato/dispatcher-runtime.md) 负责。

## 必需验证

纯映射测试覆盖所有步骤、literal/参数引用、分支深度、可选字段保持、复制身份与不完整/游离结构。组件验证中英文、明暗主题、禁用与归档、字段校验、非拖动操作、来源聚焦和 Undo。浏览器验证真实拖动/连接、嵌套分支、关闭重开草稿、保存后重新载入；纯函数或静态 HTML 测试不证明这些浏览器行为。

新字段均为可选或有明确空默认，保持既有格式版本。工作区缓存属于可丢弃本地草稿，不建立任意旧开发数据兼容链。

## HTTP deployment acceptance

The deployed WebUI is accessible over HTTP. Step IDs use `crypto.getRandomValues`, which works in this environment; do not require secure-context-only `crypto.randomUUID` during toolbox initialization. Test without `randomUUID` and verify both library and arrival editor initialization on an HTTP origin. A localhost-only browser check does not cover this deployment condition.
