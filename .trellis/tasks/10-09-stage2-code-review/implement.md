# 执行与审查计划

当前阶段：in_progress。用户于 2026-10-09 批准修复已知问题；按小批次实施并逐次检查，不要求等待全部审查结束才修复已确认项。

## A. 完成审查

- [x] 记录当前 HEAD、根仓与 docs-site 脏状态；加载第一批涉及的 Basic/BukkitPlugin 规范。
- [ ] 复核 `research/initial-findings.md`，建立带证据的问题台账。
- [ ] 检查废弃代码、模块归属与公共 API、重复模型、规范一致性、复杂度和安全兜底。
- [ ] 盘点四类文档与父任务清单，区分已实现、未实现和仅文档过期。
- [ ] 输出阻塞项、可延期项及建议修复批次，提交用户检查。

## B. 经批准后分批修复

- [x] 第一批：删除 AccelerateCommand、BasicControlCommand、CreateRail 和 Basic 的未使用 Lombok 配置；旧寻路/索引另批处理。源码独立复核与受影响模块测试通过，等待用户检查提交。
- [ ] 第二候选批次：明确文档职责并修正当前状态；docs-site 单独处理，不覆盖既有修改。
- [ ] 后续批次：按台账评估适配器归属、公共 API 签名和复杂状态/事务代码，不预先批准大重构。
- [ ] 每批先说明目的和方法；实现、验证、提交、打开预览后停下等用户检查。

## 验证与关闭

- 任务文档：`python3 .trellis/scripts/task.py validate .trellis/tasks/10-09-stage2-code-review`、`git diff --check`。
- 代码改动按 `.trellis/spec/javarailways/shared/quality.md` 选择 touched modules 及直接 consumers，Gradle 使用 `/workspace/.gradle`。
- 公共 API/模块边界改动检查下游编译、Dokka、Java consumer 和现有模块打包门禁；平台改动补充调度/线程归属审查。
- docs-site 改动在其独立工作树验证；不把既有修改混入提交。
- [ ] 汇总问题处置与验证结果，核对父任务未完成项，列出正式游戏测试前剩余阻塞和现场测试清单。
