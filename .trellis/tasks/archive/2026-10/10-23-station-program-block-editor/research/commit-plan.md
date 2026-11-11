# 提交计划

1. `feat(webui): add shared Blockly editor and arrival programs`

包含以下本任务文件，全部由本会话及受委派代理修改；隔离worktree开始时为clean，无未识别改动。

- `.trellis/spec/javarailways/simpleato/dispatcher-runtime.md`
- `.trellis/spec/javarailways/simpleato/timetable-model-and-validation.md`
- `.trellis/spec/webui/frontend/index.md`
- `.trellis/spec/webui/frontend/program-blocks.md`
- `.trellis/tasks/10-23-station-program-block-editor/check.jsonl`
- `.trellis/tasks/10-23-station-program-block-editor/design.md`
- `.trellis/tasks/10-23-station-program-block-editor/implement.jsonl`
- `.trellis/tasks/10-23-station-program-block-editor/implement.md`
- `.trellis/tasks/10-23-station-program-block-editor/prd.md`
- `.trellis/tasks/10-23-station-program-block-editor/research/arrival-runtime-contract.md`
- `.trellis/tasks/10-23-station-program-block-editor/research/commit-plan.md`
- `.trellis/tasks/10-23-station-program-block-editor/research/editor-selection.md`
- `.trellis/tasks/10-23-station-program-block-editor/research/verification.md`
- `.trellis/tasks/10-23-station-program-block-editor/task.json`
- `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/Dsl.kt`
- `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/Model.kt`
- `BukkitPlugin/src/main/kotlin/work/art1st/javarailways/scriptworker/StationPrograms.kt`
- `BukkitPlugin/src/test/kotlin/work/art1st/javarailways/scriptworker/StationProgramScriptTest.kt`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/persistence/TimetableSnapshot.kt`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/Timetable.kt`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableArrivalProgram.kt`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableBranchGuard.kt`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDispatcher.kt`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableDumper.kt`
- `SimpleATO/src/main/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableProgramCursor.kt`
- `SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/persistence/TimetableSnapshotTest.kt`
- `SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableProgramCursorTest.kt`
- `SimpleATO/src/test/kotlin/work/art1st/javarailways/simpleato/timetable/TimetableStationCallDispatcherTest.kt`
- `WebUI/frontend/package-lock.json`
- `WebUI/frontend/package.json`
- `WebUI/frontend/public/blockly-media/1x1.gif`
- `WebUI/frontend/public/blockly-media/LICENSE`
- `WebUI/frontend/public/blockly-media/README.txt`
- `WebUI/frontend/public/blockly-media/delete-icon.svg`
- `WebUI/frontend/public/blockly-media/dropdown-arrow.svg`
- `WebUI/frontend/public/blockly-media/foldout-icon.svg`
- `WebUI/frontend/public/blockly-media/handclosed.cur`
- `WebUI/frontend/public/blockly-media/handdelete.cur`
- `WebUI/frontend/public/blockly-media/handopen.cur`
- `WebUI/frontend/public/blockly-media/pilcrow.png`
- `WebUI/frontend/public/blockly-media/quote0.png`
- `WebUI/frontend/public/blockly-media/quote1.png`
- `WebUI/frontend/public/blockly-media/resize-handle.svg`
- `WebUI/frontend/public/blockly-media/sprites.svg`
- `WebUI/frontend/src/App.tsx`
- `WebUI/frontend/src/AuthoringValidation.tsx`
- `WebUI/frontend/src/DutyEditor.tsx`
- `WebUI/frontend/src/ProgramBlockEditor.css`
- `WebUI/frontend/src/ProgramBlockEditor.tsx`
- `WebUI/frontend/src/ProgramFields.tsx`
- `WebUI/frontend/src/RunRouteEditor.tsx`
- `WebUI/frontend/src/StationProgramLibrary.tsx`
- `WebUI/frontend/src/arrivalAuthoring.test.ts`
- `WebUI/frontend/src/authoring.ts`
- `WebUI/frontend/src/authoringRecovery.ts`
- `WebUI/frontend/src/authoringViews.test.tsx`
- `WebUI/frontend/src/i18n.tsx`
- `WebUI/frontend/src/programBlockDrafts.ts`
- `WebUI/frontend/src/programBlocks.test.ts`
- `WebUI/frontend/src/programBlocks.ts`
- `WebUI/frontend/src/programNavigation.ts`
- `WebUI/frontend/src/stationPrograms.ts`
- `WebUI/frontend/src/types.ts`
- `WebUI/src/main/java/work/art1st/javarailways/webui/EditorDocumentValidator.java`
- `WebUI/src/main/java/work/art1st/javarailways/webui/StationProgramValidator.java`
- `WebUI/src/test/java/work/art1st/javarailways/webui/ArrivalProgramValidatorTest.java`
- `WebUI/src/test/java/work/art1st/javarailways/webui/EditorDocumentRestartTest.java`

后续按项目流程单独自动提交本任务归档与会话日记，不混入功能提交。不push，不部署。

验证：192项前端测试、692项后端相关测试、前端构建、WebUI shadowJar、独立检查和真实浏览器存储验证通过。详细范围与限制见verification.md。

用户于2026-10-24明确回复“可以”，已授权执行本计划。
