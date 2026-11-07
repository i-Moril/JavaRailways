# F9 车门状态静态验证最终独立检查

## Findings (fixed)

- File: `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/Timetable.kt`
- Issue: 首轮实现重复了一套独立的车门 cursor/分支/预算传播，并曾遗漏 `StationStopPrograms.doorSide` 优先级、继承 `OpenDoors(null)` 和 `SelectStop` 单次错误语义。
- Fix: 独立 `validateDoorState/DoorValidationState/sequence` 已完全删除。`DoorProof` 只并入既有 `validateService.State/process/walk`，复用同一cursor、continuation memo、32层限制、4096项预算和 `SelectStop` 候选展开。有效门侧为 `program.doorSide ?: fallback`；确定冲突只降当前路径为 `UNKNOWN`，没有service级全局抑制标志；之后的新显式OPEN仍能产生独立错误。

- File: `SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableValidatorTest.kt`
- Issue: 需要证明真实terminal-open→reverse→doorless-origin、显式关门修复、UNKNOWN保守性、门侧优先级和继承开门的语义。
- Fix: 19项validator回归全部通过。错误定位在doorless origin地址，Reverse本身不报；`OpenDoors(null)` 在已知OPEN时保留OPEN，其他情况保守为UNKNOWN。

- File: `SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableStationCallDispatcherTest.kt`
- Issue: 三项旧runtime安全测试通过公开 `startService` 提交已能被F9静态证明为错误的定义，因而无法再抵达运行期门禁。
- Fix: 测试分层保留了真实runtime证据，没有偷换成只测validator：
  - ordinary movement用已确认LEFT站台与 `OpenDoors(null)` 让静态状态保持UNKNOWN；运行期实际发出 `OpenDoors(LEFT)`，ticket成功后在movement door-closing gate失败。
  - ticket错配测试同样真实进入operation gateway；伪造ticket identity被检测并调用 `cancel()`，随后world stall。
  - terminal-open→store原作者链仍断言静态拒绝；另一条runtime链在实际到站后继承LEFT并发出真实OpenDoors ticket，成功开门后在StoreAtDepot安全门禁失败，列车未移动。

- File: `.trellis/spec/javarailways/simpleato/timetable-model-and-validation.md`
- Issue: 需要把这次非显然的确定证据语义、复用边界和错误实现持久化。
- Fix: 新增CURRENT十节合同与生产代码一致：`UNKNOWN/SECURED/OPEN`、program-first门侧、TERMINAL/ORIGIN/STOP实际执行切片、Async保守性、单遍有限证明、无WebUI副本、无自动关门与无schema变更。

- File: `.trellis/tasks/11-06-simpleato-snapshot-engineering-audit/research/door-state-static-validation.md`
- Issue: 因果段仍用现在时描述validator“没有传播”，会与已实施代码矛盾。
- Fix: 已明确该描述是F9实施前的历史根因，并补充最终分层测试、全量测试和原始回放证据。

## Findings (not fixed)

无。

## Verification

- Lint: 项目未提供本范围独立lint任务；`git diff --check` 通过。
- TypeCheck: pass（两次强制Gradle重跑均完成 `compileKotlin/compileTestKotlin`）。
- Focused tests: pass，`TimetableValidatorTest` 19/19；`TimetableStationCallDispatcherTest` 56/56；均0失败、0错误、0跳过。
- Full tests: pass，独立强制重跑 `:SimpleATO:test` 809/809，0失败、0错误、0跳过。
- Original replay: `/tmp/jr-authority-fixed-mcUBF5/replay.log` 在模拟前输出 `VALIDATION success=false`，唯一错误为 `[new-timetable/4] step#29 ... DoorSide.NONE ... CLOSE_DOORS`。后续JavaExec非零来自脚本对validation success的`check`，是本次验收所预期的静态拒绝，模拟未开始。

## 结论

F9静态提示、运行期fail-safe、单遍有限证明、错误展示链和回归证据全部满足任务合同，无剩余阻断。
