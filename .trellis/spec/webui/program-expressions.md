# 可组合的程序表达式

程序库和本站程序继续使用现有 `StationProgramDefinition`。表达式由 ScriptWorker 在编译时对调用参数求值，生成既有动作；不引入运行时 JS、新执行器、动态列车条件、schema 升版或旧开发格式兼容分支。

## 数据形状与类型

已有 `{value: literal}`、`{parameterId: id}` 保持为当前模型的一部分。新增 `{operation:{type,operands:[expression,...]}}` 和 `{object:{key:expression,...}}`，表达式根只能有一个分支。普通 literal JSON 内的同名字段始终是数据，不进行隐式插值。

新增 `NUMBER` 参数/常量，允许有限小数和负数，整数必须为 JavaScript 安全整数。`NUMBER/TICK/DURATION` 可作为数字操作数；最终 TICK/DURATION 输入仍须为非负安全整数，WAIT_DURATION 须为正。`STRING` 可为空、可含空白且最多8192个UTF-16单元；`STRING/DESTINATION/STATION/DEPOT` 可组合，但最终 ID 仍遵守原有非空白、长度等领域校验。不存在隐式字符串/数字强制转换。

`object` 构造 JSON_OBJECT，每项值可以是嵌套表达式、参数或有限 JSON 常量。最多128键，每键非空白且最多256个UTF-16单元；键保持原样，`__proto__`、`constructor`等键仅作自己的数据属性。界面编辑重复键时保留原始工作区并阻止保存，不可先转成map而丢失条目。

原 `trainInfo` 是动作开始时读取的完整 JSON 绑定，仅在 SET_DISPLAY.values/EXTENSION.parameters 直接使用；不能嵌入本批编译时表达式或改变计划条件能力。

## 运算语义

- 双数字：ADD、SUBTRACT、MULTIPLY、DIVIDE、MODULO、MIN、MAX。
- 单数字：ROUND（半值向正无穷）、FLOOR、CEIL。
- CONCAT拼接两个字符串；TEXT_LENGTH返回UTF-16长度。
- SUBSTRING接受文本、开始、结束，索引从0开始，左闭右开；要求整数且 `0 <= start <= end <= text.length`，不交换或裁剪非法范围。
- REPLACE替换所有字面匹配，不使用正则，查找串不能为空；先计算结果长度再分配。
- TO_STRING将数字转为最短可往返的普通十进制，去除无意义`.0`、展开科学计数法、`-0`写为`0`。JVM使用精度1至17的邻近十进制往返选择以匹配浏览器，包括subnormal，不依赖JVM与ECMAScript不同的默认Double格式。
- TO_NUMBER先按ECMAScript空白规则trim，再解析带可选符号、小数及十进制指数的数字；不接受空串、十六进制、布尔值、NaN、Infinity或不安全整数。

每棵表达式最多16层、4096节点。每个中间数字必须有限，整数必须安全；中间字符串长度最多8192。JSON和显示字段的现有预算继续生效。除零、错误类型、参数引用、操作数数量、未知操作和不完整连接明确失败，不返回空值或自动截断。

## 跨层验证与交互

TypeScript、Java保存端、Kotlin编译端必须对相同输入采用相同语义。保存无绑定参数的定义时校验结构和可知类型；绑定具体参数后再次校验最终领域类型及范围。两个分支的表达式均受校验，错误保留步骤定位。独立函数模拟的绝对等待范围复用同一表达式求值，不能只读取literal或parameterId漏掉operation。

Blockly递归映射覆盖表达式、对象键和操作数。运算积木不改变动作可用范围；literal JSON仍可直接使用。选择已有动作、侧栏编辑、撤销/重做、本地草稿、语言切换和保存重开均不得把表达式重置成常量。无效或游离积木仍阻止保存。

质量验证覆盖真实Blockly连接/序列化、组合等待与显示对象、参数绑定、非法结果、预算边界、emoji索引和特殊键；实际浏览器检查画布/侧栏/撤销/保存重开，服务器编译测试与HTTP存储夹具分开报告。
