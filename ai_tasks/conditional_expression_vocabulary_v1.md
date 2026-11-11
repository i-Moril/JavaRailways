# 条件表达式词汇表 v1（已实施）

- 日期：2026-11-05
- 状态：**已实施（BRANCH 改写 + 积木条件 + 内置运行时条件下线），WebUI 表达式校验的整类删除留作后续**
- 前置：`ai_tasks/handoff_conditional_branch_simplification.md`
- 目的：把「积木条件」的第一版词汇表逐条列出来，并对照现有表达式实现，标出哪些能直接落地、哪些要小改机制、哪些现在做不了。

---

## 0. 范围

**做**：站台程序 `BRANCH` 的条件改成 Blockly 表达式树；删除 `typeId@version + JSON` 形状。

**不做**：运行时条件（`TimetableAction.Conditional`）、扩展条件（`EXTENSION`）、自定义函数、数值字段谓词。

**理由**：`validateDefinition` 现在直接拒绝运行时条件（`Timetable.kt:445`，"compile planned branches first"），加了也无处安放；`plannedContext` 的 7 个字段**全是字符串**（`ProgramSimulation.kt:146`），没有数值来源，数值谓词现在只会是死块。

**顺带下线**：现有 4 个内置运行时条件（`train_model`、`train_has_tags`、`train_has_capabilities`、`lateness_at_least`）。它们已经不可达（唯一的验收入口无条件拒绝 `Conditional`），当前唯一暴露点是把描述元数据发给前端（`TimetableHttpBridge.kt:424-427` 的 `extensionsJson`）。一并摘掉注册、元数据通道和前端 `conditions` prop；`TimetableConditionProvider`、`TimetableCondition`、`TimetableRuntimeContext` 这些 API 保留不动。

> 已实施：删除 `BuiltinTimetableConditions.kt`；`TimetableExtensionRegistry` 去掉内置注册、`registerBuiltIns` 构造参数和 `builtInConditions` 豁免；`extensionsJson` 不再输出 `conditions`；删除对应注册表测试。前端 `conditions` prop 链留到积木改造时随 `plannedConditions` 一并删除。

**编译期条件是刻意的选择**，不是妥协：条件在装表时折叠，同一份时刻表的执行路径在安装那一刻就固定，可以复现；运行期分支会让路径数随条件数量增长，而且没走到的分支永远得不到真实运行的检验。将来若重新开放运行时条件，"折叠"应视为“能折就折”的优化，而不是条件的语义。

---

## 1. 类型系统增量

`StationProgramParameterType` 增加 `'BOOLEAN'`。

| 规则 | 现状 | 改动 |
|---|---|---|
| 兼容性 | `compatibleProgramType` = 相等 ∨ 双方都是数值族 | `BOOLEAN` 只与 `BOOLEAN` 兼容（现有逻辑已经不满足前两条，天然正确） |
| 插座类型 | `setCheck(valueType)` | `setCheck('BOOLEAN')` |
| 输出类型 | `programOutputChecks(type)` | `'BOOLEAN'` → `'BOOLEAN'` |
| 字面量 | `validateProgramLiteral` 无 BOOLEAN 分支 | `require(value is JsonPrimitive && !isString && booleanOrNull != null)` |
| Java 侧 | `walk()` 已经把布尔字面量判成 `"BOOLEAN"`（`ProgramExpressionValidator.java:42`） | `compatible()` 的相等分支已覆盖，无改动 |

**不要**把 `BOOLEAN` 加进 `ProgramBlockEditor.tsx:26` 的 `parameterTypes`——那个循环会同时生成 `rail_literal_BOOLEAN`（文本框，而不是下拉）和 `rail_parameter_BOOLEAN`（库程序布尔参数，v1 不需要）。字面量块单独特殊处理。

---

## 2. 字段表（词汇表的全部）

每个字段是一个**零操作数操作**，输出 `STRING`；操作名到 `plannedContext` key 的映射由求值器持有。不引入新的表达式节点形态，也不引入 ID 专门类型——它们就是字符串。

| 字段 | 操作 | 类型 | 参考取值域（**不进入编辑器**） | 形制 |
|---|---|---|---|---|
| 运行类型 | `PLAN_RUN_TYPE` | STRING | `PASSENGER` / `DEADHEAD` | 报道块 + 比较块 |
| 线路方向 | `PLAN_LINE_DIRECTION` | STRING | `UP` / `DOWN` | 报道块 + 比较块 |
| 停站类型 | `PLAN_CALL_STATUS` | STRING | `ORIGIN` / `STOP` / `TERMINAL` | 报道块 + 比较块 |
| 车站 | `PLAN_STATION_ID` | STRING | 开放（文档内车站 id） | 报道块 + 比较块 |
| 车次 | `PLAN_RUN_ID` | STRING | 开放（文档内 run id） | 报道块 + 比较块 |
| 线路 | `PLAN_LINE_ID` | STRING | 开放（文档内 line id） | 报道块 + 比较块 |
| 交路 | `PLAN_DUTY_ID` | STRING | 开放（文档内 duty id） | 报道块 + 比较块 |

**编辑器不认识取值域。** 没有枚举块、没有值下拉、没有域表。“哪些值合法”不是编辑器维护的信息：字面量一律是自由文本的 `rail_literal_STRING`，7 个字段用同一套积木拼。

**因此只有一种积木形制**：报道块 + 比较块。不存在“同一个 AST 有两种积木外形”的还原问题。

**已知代价**：枚举值靠作者手打（`TERMINAL`、`DEADHEAD`……），拼错时编辑期无提示，条件恒为假。这与手打车站/车次 id 是同一类风险。若将来要反馈，唯一的位置是 ScriptWorker 在字面量直接与 `PLAN_x` 比较时比对声明值集——可选项，v1 不做，避免把“值集”概念重新引回。可达域（例如 `PASS` 在站台程序里不可达）**不做任何建模**，交给编译期已有校验去拒绝。

字段表带一列 `scope`，v1 只有 `PLANNED`。将来运行时字段加进来是 `RUNTIME`，校验器按求值位置拒绝越界字段。

ID 字段**不设专门类型**：`PLAN_RUN_ID` 的输出就是 `STRING`，配 `rail_literal_STRING` 字面量块，和后端 `STRING` 校验完全一致。

---

## 3. 谓词表

### 3.1 新增操作（5 条 + 7 个字段操作）

| 操作 | 形制 | inputs | output |
|---|---|---|---|
| `EQUALS` | `[a] [=] [b]` | STRING, STRING | BOOLEAN |
| `NOT_EQUALS` | `[a] [≠] [b]` | STRING, STRING | BOOLEAN |
| `AND` | `[a] [且] [b]` | BOOLEAN, BOOLEAN | BOOLEAN |
| `OR` | `[a] [或] [b]` | BOOLEAN, BOOLEAN | BOOLEAN |
| `NOT` | `[非] [a]` | BOOLEAN | BOOLEAN |

`AND`/`OR` 保持二元——Scratch 的 and/or 也是二元，靠嵌套组合，符合作者直觉。

### 3.2 不做

- `IN`（属于）：多值用 `或` 连接即可。加它只会引入可变元数契约（两处校验器都要放宽），而 7 个字段的取值规模用不上。
- `LT` / `LE` / `GT` / `GE`：没有数值字段可读（见 §0）。等第一个运行时数值字段。
- `CONTAINS`：7 个计划字段都是 id/枚举，子串匹配没有语义。等运行时文本字段。

---

## 4. 序列化形态

### 4.1 不新增节点形态

字段读取复用现有的 `{operation}` 节点，只是操作数为空：

```json
{"operation": {"type": "PLAN_CALL_STATUS", "operands": []}}
```

因此 `StationProgramValue` 的联合类型、`validateShape` 的 `when`、`programBlocks.ts` 的读写转换**都不需要改**——只在操作表里加行。

### 4.2 BRANCH

```json
{
  "id": "arr-1",
  "type": "BRANCH",
  "condition": {"operation": {"type": "EQUALS", "operands": [
    {"operation": {"type": "PLAN_CALL_STATUS", "operands": []}},
    {"value": "TERMINAL"}
  ]}},
  "thenSteps": [],
  "elseSteps": []
}
```

`typeId` / `version` / `parameters` 三个字段整体删除。

### 4.3 编译期求值

`ProgramExpressions.evaluate(condition, "BOOLEAN", context = plannedContext)`，返回 `true`/`false` 后内联选中分支——和现在 `evaluatePlannedCondition` 的时机完全一致，只是输入从 `{values:[...]}` 变成表达式树。

> 已实施：`ProgramExpressions.kt` 的操作表、`outputOf`、`PLAN_*` 上下文解析、布尔字面量校验，以及 `plannedContext` 的透传（`StationPrograms.kt`、`StationStopPrograms.kt`）。BRANCH 与 UI 尚未改。

### 4.4 折叠时机（已确认保持现状）

站台分支**不是运行期的**。它能读的只有 `plannedContext`，7 个字段在编表前全部已知，没有需要等运行时才知道的输入。真正运行期的分支是另一套（`TimetableAction.Conditional`），当前编辑路径不产生它。

折叠只有三个候选时机：

| 时机 | 在哪 | 结论 |
|---|---|---|
| 生成 JS 时 | 浏览器 | 否 |
| 编译时刻表时 | ScriptWorker | **采用（现状）** |
| 每个 tick | dispatcher | 不适用 |

**为什么不在生成期折叠**：服务器不只在装表时展开程序，编辑器的“模拟运行”也走同一条 `expandStationProgram`（`ProgramSimulation.kt` → `task.program`），而模拟是交互式的、每次重发定义。生成期折叠省下的只是 6 条条件操作和一个 `{field}` 叶子，代价是多一份浏览器侧求值器并与装表路径分叉。

**因此**：浏览器负责“能拼出什么”，ScriptWorker 负责“能不能跑、折叠成什么”，两者不是同一件事，也不是重复实现。详见 §6.2。

---

## 5. Blockly 形制

| 需求 | 做法 | 原生？ |
|---|---|---|
| 布尔外观 | `setOutputShape(SHAPES.HEXAGONAL)`；`ws.getRenderer().getConstants()` 取常量（`renderer:'zelos'` 已启用） | 是 |
| 布尔插座 | `setCheck('BOOLEAN')` | 是 |
| 枚举谓词块 | `[字段 ▾] [是/不是 ▾] [值 ▾]` 一个块两下拉；值下拉 options 传函数，读同块字段当前值 | 是（`FieldDropdown` 函数式 options） |
| 报道块 | 7 个 0 元操作块，由 `operationTypes` 自动生成 | 是 |
| 可变元数 `IN` | `extraState:{count}` + 加减按钮，照 `rail_object` | 是（已有先例） |
| `rail_literal_BOOLEAN` | 单独定义，`FieldDropdown` 真/假 | 是 |

枚举谓词块和报道块**序列化到同一个 AST**：前者是 UX 糖，产出 `EQUALS({field},{value})`；后者产出 `{field}` 叶子配合通用比较块。

---

## 6. 实现位置（含一处应当删除的重复）

### 6.1 事实：编辑器文档不执行

- 浏览器把文档变成一段 JS（`authoring.ts`），WebUI 把它存成 `worlds/<world>/scripts/<formalScriptName>`，ScriptWorker 直接 `eval`（`Main.kt:53`）。
- `editor-documents/*.json` 只用于以后重新打开编辑器。**它永远不被执行。**
- 因此 `EditorDocumentValidator` / `StationProgramValidator` / `ProgramExpressionValidator` 校验的是一个不会运行的中间产物：它能防的只有“文档写坏、编辑器打不开”，防不了运行。

### 6.2 结论：删掉 WebUI 的表达式语义校验

三道关卡里只有两道在回答真问题：

| 关卡 | 位置 | 回答的问题 | 去留 |
|---|---|---|---|
| 浏览器 | `programExpressions.ts`、`ProgramBlockEditor.tsx` | 能拼出什么 | 保留 |
| WebUI 后端 | `ProgramExpressionValidator.java`、`StationProgramValidator.java` | 这份**文档**的名字/形状是否合法 | **表达式语义部分删除** |
| ScriptWorker | `ProgramExpressions.kt` | 这段**程序**能不能跑 | 保留 |

删完之后剩两份实现，各自回答不同的问题，**不是重复**：

- 浏览器：有哪些积木、长什么样、能拼出什么（`operationTypes` 同时驱动积木生成）。
- ScriptWorker：定义以数据形式进 DSL，它必须再解释一遍才能执行（`ProgramExpressions`）。

`types.ts` 的联合类型不算第三份实现，它是 TS 的编译期约束，漏了编译不过。

保留边界建议：WebUI 侧留**结构性**检查（`formatVersion`、id 非空、字段集合、数组形状）——它保护的是“文档还能被编辑器打开”，与执行无关；表达式的类型与求值逻辑整体删除。

代价（需明确接受）：手写或被旧前端写入的畸形文档可以存进服务器，直到重新生成脚本或执行时才报错。这是拿“保存时的即时反馈”换“少一份会漂移的实现”。

**实施进度（本次）**：

- 已做：`BRANCH` 改为 `{condition}`；`StationProgramValidator` 对 `condition` 只做「是对象」的结构检查，**不引入 BOOLEAN/`PLAN_*` 的语义校验**，因此没有出现第三份操作表。
- 未做：删除 `ProgramExpressionValidator.java` 及其 6 个调用点。它仍在为 `WAIT_UNTIL`/`WAIT_DURATION`/`SET_DISPLAY`/`MOVE` 等**旧字段**做类型校验，与 `BRANCH` 无关。整类删除会一并去掉这些编辑期反馈，需要单独一刀。
- 副作用（已知且接受）：`BRANCH.condition` 里的类型错误、`trainInfo`、未知操作，现在只在编译期被拒绝，保存时不再拦。两个相关断言已从 `SharedStationProgramValidatorTest` / `TrainInfoProgramValidatorTest` 移除。

### 6.3 改动清单

另外 `types.ts:122` 的联合类型、`ProgramBlockEditor.tsx:21` 的 `operationOperandLabels`（`Record`，TS 强制补齐）和 i18n 的 `programs.operation.*` / `programs.operand.*` 都要同步。

### 前端

| 文件 | 改动 |
|---|---|
| `types.ts:118` | `+ 'BOOLEAN'` |
| `types.ts:122` | `+ 6 个操作名` |
| `types.ts:134` | BRANCH 形状：`condition: StationProgramValue<boolean>` |
| `programExpressions.ts:2` | 6 条操作；`output` 类型放宽到 `'NUMBER'\|'STRING'\|'BOOLEAN'` |
| `programExpressions.ts` | `evaluateProgramExpression` 分支；`validateProgramExpression` 的字段叶子和布尔字面量 |
| `programBlocks.ts:10` | `BRANCH:{condition:'BOOLEAN'}` |
| `programBlocks.ts:17,44,94,111` | 删除 `provider` 参数与 `TYPE_ID`/`VERSION` 读写 |
| `programBlocks.ts:70` | `rail_literal_BOOLEAN` 的 `'true'`/`'false'` 往返 |
| `ProgramBlockEditor.tsx:19,58` | 删除 `plannedConditions` |
| `ProgramBlockEditor.tsx:38` | 删除两个 `rawField` 的 TYPE_ID/VERSION |
| `ProgramBlockEditor.tsx` inspector | 删除条件下拉框、描述、`<pre>parameterSchema</pre>` |
| `ProgramBlockEditor.tsx:48` | 按 `spec.output==='BOOLEAN'` 设六边形；特殊处理 `rail_literal_BOOLEAN` |
| `stationPlans.ts:5,34` | 删除 `plannedConditions` 与 `validateStopProgram` 的白名单检查 |

### 后端

| 文件 | 改动 |
|---|---|
| `ProgramExpressions.kt` | `inputs` + 12 条（5 谓词 + 7 字段）；`outputOf`；`planFields`；`evaluate` 加 `context` 并解析 `PLAN_*`、布尔运算 |
| `StationPrograms.kt:82` | `value()` 透传 `plannedContext` |
| `StationPrograms.kt:198` | `validateProgramLiteral` 加 BOOLEAN |
| `StationStopPrograms.kt:56,88` | 表达式求值透传 `plannedContext` |
| `StationStopPrograms.kt:62-67` | `validateSource` 的 BRANCH 分支改走 `validateShape` + `evaluate` |
| `StationStopPrograms.kt:155-165` | 删除 `evaluatePlannedCondition` |
| `StationProgramValidator.java:121-128` | BRANCH 字段集改为 `{id,type,condition,thenSteps,elseSteps}` |
| `StationProgramValidator.java:66-67` | 删除 `plan_*` 白名单 |
| `ProgramExpressionValidator.java` | **整体删除**（表达式语义只保留浏览器与 ScriptWorker 两处） |
| `ProgramExpressionValidatorTest.java` | 随 `ProgramExpressionValidator` 一并删除 |

---

## 7. 可行性核对结论

### 7.1 零新机制，纯加表

- `BOOLEAN` 类型与字面量
- `EQUALS` / `NOT_EQUALS` / `AND` / `OR` / `NOT`
- 7 个字段报道块（由操作表自动生成）
- 六边形外观、类型化插座

全部落在 `operationTypes` 这类「一张表 + 自动生成块」的既有模式里。

### 7.2 要小改机制（1 处）

1. **`rail_literal_BOOLEAN` 的块生成。** `parameterTypes` 循环会生成文本框版，需要单独定义成下拉。

原先设想的另外两处机制改动都不需要了：

- 字段读取复用零操作数 `{operation}`，**没有新序列化形态**；
- `IN` 不做，**没有可变元数契约**。

### 7.3 现在做不了 / 不该做

- **数值谓词**：`plannedContext` 7 个字段全是字符串（`ProgramSimulation.kt:146` 强制 `text(...)`），没有数值来源。硬加只会得到永远接不上字段的块。
- **运行时字段**：编辑路径根本不接受运行时条件（`Timetable.kt:445`）。加了也没有位置放。
- **`EXTENSION` 的 `JSON_OBJECT` 参数**：仍在，是积木层最后一口 JSON。v1 不动，但它就是将来「自定义函数」的替换点。

---

## 8. 已定 / 待定

已定：

1. **ID 字段不设专门类型**，输出就是 `STRING`，配 `rail_literal_STRING`。
2. **只有一种形制**：7 个字段全部是报道块 + 比较块。没有枚举块、没有值下拉、没有域表——编辑器不认识取值域。
3. **不做 `IN`**：全部用 `=` / `≠` 和 `或` 组合。
4. **不做可达域建模**：`PASS` 之类的不可达值交给编译期已有校验拒绝，编辑器不区分。
5. 字段读取用零操作数 `{operation}`，不新增序列化形态。

待定：

1. **枚举字面量拼错是否要在编译期报错。** 现在不做：拼错后条件恒为假，与手打 id 同类。若以后要做，唯一位置是 ScriptWorker 在字面量直接与 `PLAN_x` 比较时比对声明值集，且不在浏览器复制这份信息。

---

## 9. 测试面

现有会受影响、需要同步改的：

- `BukkitPlugin/src/test/.../ProgramExpressionsTest.kt`
- `BukkitPlugin/src/test/.../StationStopProgramsTest.kt`
- `SimpleATO/src/test/.../StationStopProgramSnapshotTest.kt`
- `WebUI/frontend/src/programExpressions.test.ts`
- `WebUI/frontend/src/programBlocks.test.ts`
- `WebUI/frontend/src/ProgramBlockEditor.test.tsx`
- `WebUI/frontend/src/programSimulation.test.ts`
- `WebUI/frontend/src/stationPrograms.test.ts`
- `WebUI/frontend/src/trainInfo.test.tsx`
- `WebUI/src/test/java/.../ProgramExpressionValidatorTest.java`（随该类一并删除）

新增建议：一份「两份实现一致」的对照用例——同一组表达式同时喂给 TS 与 Kotlin，比对接受/拒绝和求值结果。这是防止两份表漂移的唯一手段。Java 侧删除后不再需要第三份对照。

---

## 10. 版本号

不涉及。文档边界仍只有 `EditorDocumentValidator` 的 `formatVersion: 1`；BRANCH 形状变化属于未发布的当前格式修正，直接改模型与 fixture，不升版，不加旧输入 reader。
