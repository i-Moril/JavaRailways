# Feedback round3 — numeric authoring IDs and understandable unit selection

User requested anotherbatch from existing feedback after accepting duty timeline direction; timeline detailpolish deferred. Root selected8 and15, plus spacingonlyon touchedcontrols. Existing task remainsin_progress/unarchived.

## Behavior boundary
- New/copy RUN and new DUTY currently get visible randomprefix/time IDs; choose shortnumericstrings in their existingIDfield. Keep saved custom IDs and internalcall/element/program/libraryidentities; no newnamefield, automaticrenaming, schema, persistedcounter or data migration. Allocate numericIDs without numericrounding/collision acrosssamebatch; reserve currentdocumentreferenced identities includingdanglingknownrun/dutyreferences so creationdoesnotrepairdanglingreferences byaccident. Documenthistory remainsoneowner; old async/recovery isolationretained.
- UNIT_SLOTS currently appears as unexplainedrawJSON/numbertags. Use shared compact picker with numberedcompleteunit chips and add/remove controls in programinspector and existingProgramLiteralInput callers, clearhead1-based/non-car/reversaldescription. Keep array<number> protocol and actualruntimevalidation; do notinventliveunitcounts or showfakevehicleinformation. InvalidBlockly rawliteral must remainrecoverable and blocksave, not silentlybecomeempty/default. Parameters keepreference mode. Eachacceptedchange oneexistinghistorytransaction; selectedblock/world changes discarduncommittedUI-onlyinput, neverwriteoldworkspace.
- No formation-event redesign/runtime train-data expressions, sourceDSL semantics, Blocklygeneralrewrites, timeline layout tweaks, compatibility or persistenceevolution thisbatch.

## Ownership
numeric_authoring_ids: DutyEditor.tsx, newpure allocator/helpertests and relevantpureexistingtests.
unit_selection_ui: ProgramBlockEditor/ProgramFields, newsharedunitpicker/helpers/tests, i18n/scopedCSS; noDutyEditor edits.
root: docs/backlog/specsync, fullchecks, actualbrowseracceptance, commit and safe deployment.

## Acceptance
Newrun/duty IDs numeric, copybatch unique and short, existingnames stable, overflow-safe, references/undo preserved. Picker communicates completeunit notcar, positiveunique1-basednumbers up toexistinglimit128entries/maxInt32, noNaN/fractionalrounding/coercion; rawinvalidworkspace persists and gates save; reference binding unaffected. Realbrowser test programlibrary/stationpicker edits+Undo and save/reload, invalidraw recovery, numericnewrun/copy/duty, light/dark/margins. Frontendfulltest+TypeScriptbuild and WebUItests, rebuildcandidate and deploy afterbackup; no actualgamebehaviorclaim frombrowserQA.

## 验证（2026-10-27）

- 前端完整测试：39 个文件、234 项通过；TypeScript 与生产构建通过。WebUI Java 测试通过；本轮没有服务器语义或持久化格式改动。
- 只读复审确认编号命名空间、悬空引用预留和批量复制历史；发现时间轴参数行 label 包含多控件，已改为独立 div，并修正网格拉伸。
- 实际打包 WebUI + 磁盘文档的浏览器验证：新建 RUN=1，批量复制=2/3，单次 Undo 删除整批、Redo 恢复；新建 DUTY=1。服务器保存保留 R1/R2/D1 自定义编号，新增数字编号正确。
- 停站积木：候选文字不创建历史；选中其他积木后候选丢弃；添加/删除分别一次 Undo/Redo；重复编号禁止添加，Esc 不关闭弹窗。发现 Blockly 焦点转到侧栏输入时卸载侧栏，已保留侧栏焦点对应选择并复测通过。
- 原始 [1,1] 保留并阻止保存，关闭重开仍阻止保存；修复 [1,2] 后可保存，Undo 可恢复原始错误。服务器确认 stationPrograms 的 unitSlots=[1,2]。
- 程序库 UNIT_SLOTS 参数引用仍显示参数选择器；时间轴调用同类参数使用共享选择器，服务器保存 arguments.units=[1,2]。浏览器未执行游戏动作，编组运行行为仍待玩家验收。
- 部署与最后视觉复查证据在本文件后续补录；任务不归档。
