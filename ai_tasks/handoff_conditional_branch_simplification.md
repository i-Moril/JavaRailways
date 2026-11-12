# Handoff: 条件分支简化（Conditional Branch Redesign）

- 日期：2026-11-05
- 当前分支：`codex/ato-per-car-rebuild`
- 当前提交：`62ce114359bb9c35955c857581b82d18d7cc81a4`
- 当前线上：同一提交已部署到 JavaRailwaysTest（MCS instance `836082a48c4f48fcb1fea4e83c57dc96`）
- 本文件只是 handoff，**没有修改条件分支代码**

## 1. 任务结论

用户认为当前“条件分支”实现是过度设计，需要重做。核心诉求：

> 一个条件分支指令，直接传一个能在运行时得到 boolean 的语句/表达式即可。  
> JSON 序列化、解析、版本管理是另外的问题，不应暴露给条件分支作者。

当前 UI 让作者选择：

```text
typeId@version + parameters(JSON)
```

用户不接受这个模型，尤其是：

- 一个简单分支却要理解 provider、contract version、parameter schema、JSON；
- `@version` 的契约含义不清晰；
- 版本升级不应该必然破坏旧时刻表；
- 条件本来应该是 boolean expression，而不是“命名 provider + JSON 参数”。

## 2. 当前实现事实（先读，避免混淆）

当前仓库里有**两套不同的“条件分支”**，新会话必须先确认要改哪一套，或是否统一。

### A. Station Program 的 `BRANCH`（Blockly 编辑器里看到的那个）

- 前端类型：`StationProgramStep` 的 `type: "BRANCH"`。
- 字段：
  - `typeId`
  - `version`
  - `parameters`（JSON）
  - `thenSteps`
  - `elseSteps`
- UI：
  - `ProgramBlockEditor.tsx` 把 `BRANCH` 渲染成 Blockly block；
  - 条件下拉框显示 `${typeId}@${version}`；
  - 下方显示 provider 的 `parameterSchema` 和 JSON 参数编辑器；
  - `thenSteps`/`elseSteps` 是嵌套 block。
- 条件集合：
  - `WebUI/frontend/src/stationPlans.ts` 的 `plannedConditions`
  - 只允许 7 个 plan 字段：
    `javarailways:plan_run_type`、`plan_line_direction`、`plan_station_id`、`plan_run_id`、`plan_line_id`、`plan_call_status`、`plan_duty_id`
  - 参数固定为 `{ "values": ["..."] }`，version 固定 1。
- 运行语义：
  - **不是运行时 if**。
  - `BukkitPlugin/.../scriptworker/StationStopPrograms.kt` 的 `evaluatePlannedCondition` 在 ScriptWorker 编译期，用 `plannedContext` 选择 `thenSteps` 或 `elseSteps`，然后只把选中的分支编进程序。
  - `stationPlans.ts` 注释也写明 “ScriptWorker 在编译时选择分支”。
- 校验：
  - `WebUI/frontend/src/stationPlans.ts` 的 `validateStopProgram` 只允许上述 `plan_*` provider；
  - `WebUI/src/main/java/.../StationProgramValidator.java` 也硬编码同样的 7 个 typeId。
- 测试：
  - `BukkitPlugin/src/test/.../StationStopProgramsTest.kt`
  - `.../ProgramExpressionsTest.kt`
  - `.../TrainInfoCompilerTest.kt`
  - `.../ProgramSimulationTest.kt`
  - `WebUI/frontend/src/stationPrograms.test.ts`
  - `WebUI/frontend/src/programBlocks.test.ts`
  - `WebUI/frontend/src/ProgramBlockEditor.test.tsx`
  - `WebUI/frontend/src/programSimulation.test.ts`
  - `WebUI/frontend/src/trainInfo.test.tsx`

### B. Timetable 的 `TimetableAction.Conditional`（运行时分支）

- 领域模型在 `SimpleATO/.../timetable/Timetable.kt`：
  ```kotlin
  class Conditional(
      val condition: TimetableCondition,
      thenSteps: List<TimetableStep.At>,
      elseSteps: List<TimetableStep.At> = emptyList()
  ) : TimetableAction()
  ```
- `TimetableCondition` 已经是 boolean expression tree：
  ```kotlin
  sealed interface TimetableCondition {
      data class Predicate(val call: TimetableExtensionCall) : TimetableCondition
      data class All(val conditions: List<TimetableCondition>) : TimetableCondition
      data class Any(val conditions: List<TimetableCondition>) : TimetableCondition
      data class Not(val condition: TimetableCondition) : TimetableCondition
  }
  ```
- 运行时求值：
  - `TimetableDispatcher` 约 4831 行，`TimetableConditionScope.RUNTIME_BRANCH`；
  - `TimetableConditionProvider.evaluate(parameters, TimetableRuntimeContext)` 返回 `True/False/Unavailable/Error`。
- 持久化：
  - `SavedTimetableConditionV1`（PREDICATE/ALL/ANY/NOT）
  - `SavedTimetableExtensionCallV1`
  - `SavedTimetableActionV1.CONDITIONAL`
- 当前 UI 并没有把这套完整 runtime AST 作为通用表达式编辑器暴露出来；如果用户说的“条件分支”是 Blockly 里的 `BRANCH`，主要问题在 A，不是 B。

### C. 条件 provider 的“契约版本”

- `TimetableExtensionCall(typeId, version, parameters)`：
  - `typeId`：provider 身份；
  - `version`：该 provider 的接口/参数 schema/语义版本；
  - 不是时刻表格式版本，也不是分支指令版本。
- 旧表钉在旧 version：
  - 旧 version 仍注册 → 不破坏；
  - 旧 version 被移除或同 version 语义原地改变 → 才会破坏；
  - 因此“版本升级必然破坏”是错误说法。
- 用户希望：
  - 对内置/简单条件，不暴露 `@version`；
  - 版本放在文档/表达式 schema 或内部兼容层；
  - JSON 只是序列化，解析器单独处理。

## 3. 建议的目标设计

### 3.1 统一为“布尔表达式 AST”

建议新增/演进为显式的 boolean expression，而不是 provider call 直接暴露给作者：

```text
ConditionExpression
  - Const(true/false)
  - Field(ref)                  // 计划字段或运行时字段
  - Call(name, args)            // 内置函数；扩展条件作为 escape hatch
  - Compare(left, op, right)    // ==, !=, in, >=, ...
  - All(children)
  - Any(children)
  - Not(child)
```

编辑期保存的是表达式树；JSON 只是 transport。  
运行时/编译期用对应 evaluator 求值。

### 3.2 两种求值时机要显式，不要混

- **Station Program BRANCH**：编译期计划条件。  
  只允许引用 `plannedContext` 字段（runType、lineDirection、stationId、runId、lineId、callStatus、dutyId）。  
  例：
  ```text
  callStatus in ["ORIGIN", "TERMINAL"] and lineDirection == "UP"
  ```
  编译时直接折叠为选中分支，不需要运行时 provider。
- **Timetable Conditional**：运行时条件。  
  允许引用 `TimetableRuntimeContext`（timetableElapsedTicks、latenessTicks、currentWayPointId、train.modelPackageId/tags/capabilities 等）。  
  例：
  ```text
  latenessTicks >= 300 and train.modelPackageId == "s100"
  ```

分支语法可以统一，但必须知道表达式引用的是哪一类字段：
- 计划字段 → 编译期可折叠；
- 运行时字段 → 运行时分支；
- 两者混用 → 编译期报错，除非该分支明确是 runtime branch。

### 3.3 UI 目标

对内置条件：

- 不再显示 `typeId@version`；
- 不再显示原始 JSON 参数框；
- 显示表达式/条件构建器，例如：
  ```text
  [晚点] [>=] [300]
  [列车车型] [==] [s100]
  ```
- `All/Any/Not` 可视化组合；
- 无效字段/类型在编译期给出明确错误。

扩展条件保留 escape hatch，但不应污染核心分支 UI；可以放在“高级/扩展条件”里。

### 3.4 版本策略

- 文档 schema 版本留在 `SavedTimetableV1` / station program document 层，不按每个条件 provider 暴露。
- 内置表达式节点由文档版本管理。
- 第三方 extension call 如需独立版本，保留在内部 `ExtensionCondition(typeId, version, params)`，但作者不需要为内置条件填写。
- 先读：
  - `.trellis/spec/javarailways/shared/versioning.md`
  - `ai_tasks/persistence_schema_evolution_rules.md`
  - 不要在没有发布/冻结证据时递增格式版本。

## 4. 兼容与迁移

### Station Program

- 旧 `BRANCH` JSON 形状：
  ```json
  {
    "type": "BRANCH",
    "typeId": "javarailways:plan_duty_id",
    "version": 1,
    "parameters": { "values": ["duty"] },
    "thenSteps": [],
    "elseSteps": []
  }
  ```
- 可单向 decode 成新表达式：
  - `plan_*` + `values` → `Compare(Field(planField), IN, values)`
  - 未知 extension typeId → `ExtensionCondition`（仅当保留扩展）
- 新的 writer 只写新形状；不要同时保留 V1/V2 双写。

### Timetable Conditional

- `SavedTimetableConditionV1` 目前是 `PREDICATE/ALL/ANY/NOT`：
  - 旧 `PREDICATE` + `TimetableExtensionCall` → 新 `Call`/`ExtensionCondition`；
  - 旧 `ALL/ANY/NOT` → 同名表达式节点。
- 如果 `TimetableCondition` 是公开 API，直接替换是 breaking change。  
  两种路线：
  1. 新增 `ConditionExpression`，旧 `TimetableCondition` 只在 decode/extension 边界保留；
  2. 在 `TimetableCondition` 上增量添加 `Compare`/`Field` 等节点，保留旧节点。
- 需要确认发布/兼容基线后再决定；不要为开发数据造 runtime compatibility path，除非用户/合同要求。

## 5. 非目标

- 不改变分支的时刻语义：仍然是到达该 At tick 时求值一次。
- 不改变 `thenSteps`/`elseSteps` 的 finite timed branch 约束。
- 不改变 structural/formation/acquisition 不能进分支的限制。
- 不引入通用脚本/JS 求值器；只做受控表达式 AST。
- 不改 dispatcher 的 auto-start 性能改动（已提交/部署）。

## 6. 必须先确认的问题

1. 用户要简化的是：
   - 仅 Station Program `BRANCH`（Blockly 里那个）？
   - 还是也要统一 `TimetableAction.Conditional` / `TimetableServiceActivation.Conditional`？
2. 是否允许把 station program BRANCH 限定为“计划字段表达式”，彻底移除运行期 provider 模式？
3. 是否需要保留第三方 extension condition escape hatch？如果需要，是否只在“高级条件”中暴露？
4. 兼容策略：
   - 直接改当前开发格式并迁移现有测试服数据？
   - 还是保留旧 decode 入口？
   - 先确认是否有发布/冻结基线。
5. 表达式首批支持的字段/操作符范围：
   - planned：7 个现有字段；
   - runtime：lateness、elapsed、waypoint、train model/tags/capabilities；
   - 操作符：`==`、`!=`、`in`、`not in`、`>=`、`<=`、`>`、`<`、`and`、`or`、`not`。
6. UI 形态：Blockly 表达式积木、表单式条件构建器，还是文本表达式 + 解析器？

## 7. 建议实施顺序

1. 先写一个短 PRD/design，确认第 6 节问题；不要直接改代码。
2. 先做 Station Program BRANCH 的表达式化（范围小、用户直接可见）：
   - 新表达式模型/转换器；
   - 旧 JSON upcast；
   - ScriptWorker 编译期求值；
   - WebUI 条件编辑器；
   - 测试迁移。
3. 再评估是否统一 runtime `TimetableAction.Conditional`：
   - 复用表达式 AST；
   - 增加 runtime field evaluator；
   - 保留 extension provider escape hatch。
4. 最后处理版本/持久化；先读 versioning gate。
5. 全量相关测试 + WebUI 测试 + 构建；**不要直接部署**，部署是单独授权步骤。

## 8. 关键文件地图

### 领域/运行时

- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/timetable/TimetableExtension.kt`
  - `TimetableExtensionCall`
  - `TimetableCondition`
  - `TimetableConditionProvider`
  - `TimetableRuntimeContext`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/api/timetable/BuiltinTimetableConditions.kt`
  - `train_model`、`train_has_tags`、`train_has_capabilities`、`lateness_at_least`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/Timetable.kt`
  - `TimetableAction.Conditional`
  - 条件/分支校验
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcher.kt`
  - 运行时分支求值（约 4831 行）
  - `TimetableConditionScope.RUNTIME_BRANCH`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableBranchGuard.kt`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/persistence/TimetableSnapshot.kt`
  - `SavedTimetableConditionV1`
  - `SavedTimetableExtensionCallV1`
  - `SavedTimetableActionV1.CONDITIONAL`

### Station Program / ScriptWorker

- `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/StationStopPrograms.kt`
  - `evaluatePlannedCondition`
- `WebUI/src/main/java/work/art1st/javarailways/webui/StationProgramValidator.java`
  - 硬编码 `plan_*` provider 列表
- `WebUI/frontend/src/stationPlans.ts`
  - `plannedConditions`
  - `validateStopProgram`
- `WebUI/frontend/src/ProgramBlockEditor.tsx`
  - `BRANCH` block、条件下拉框、JSON 参数 UI
- `WebUI/frontend/src/programBlocks.ts`
  - `BRANCH` ↔ block JSON 转换
- `WebUI/frontend/src/i18n.tsx`
  - `programs.step.BRANCH`、`programs.thenSteps`、`programs.elseSteps`、`programs.branchHelp`

### 测试

- `SimpleATO/src/test/.../timetable/TimetableBranchValidationTest.kt`
- `SimpleATO/src/test/.../timetable/TimetableDispatcherTest.kt`
- `SimpleATO/src/test/.../persistence/TimetableSnapshotTest.kt`
- `BukkitPlugin/src/test/.../scriptworker/StationStopProgramsTest.kt`
- `BukkitPlugin/src/test/.../scriptworker/ProgramExpressionsTest.kt`
- `WebUI/frontend/src/stationPrograms.test.ts`
- `WebUI/frontend/src/programBlocks.test.ts`
- `WebUI/frontend/src/ProgramBlockEditor.test.tsx`
- `WebUI/frontend/src/programSimulation.test.ts`
- `WebUI/src/test/java/.../ProgramExpressionValidatorTest.java`

## 9. 构建/测试注意事项

- Gradle wrapper 曾出现 daemon manifest 损坏；当前已能运行。若再次出现 `NoClassDefFoundError`，不要盲目改仓库源码，先检查 Gradle 安装。
- `TimetableDispatcherTest` 默认 512 MiB test heap 会 OOM（既有问题）。本地验证可用临时 init script：
  ```groovy
  allprojects {
      tasks.withType(Test).configureEach {
          maxHeapSize = '3g'
      }
  }
  ```
  该脚本不要提交。
- WebUI 测试在 `WebUI/frontend` 下执行；不要只跑后端。
- 当前工作区有用户自己的 `TODO.md` 和未跟踪研究文件，不要擅自 stage/commit。

## 10. 当前线上状态（供参考，不属于本任务）

- 提交 `62ce1143` 已部署：dispatcher auto-start 性能优化。
- 模拟性能约 1.8–2.0 ms/tick（1500 tick 约 2.9 s），之前 35.4 ms/tick。
- 部署记录：`ai_tasks/deployments/dispatcher-auto-start-2026-11-05/`。
- 条件分支重做不应顺带改 deployment 脚本；部署需单独授权。
