# 开发编辑文档离线转换

适用：旧 `formatVersion:1` editor document 的 run call 含 `arrivalProgram`。这是开发数据结构变更，保持当前约定版本1；未添加任意旧数据 runtime reader。

先复制整个编辑文档目录作为恢复点。工具默认只读取源文件和打印审计，不替换服务器文档：

```sh
python3 scripts/migrate_station_programs.py /path/to/original.json
python3 scripts/migrate_station_programs.py /path/to/original.json --output /path/to/candidate.json
```

输出必须为新路径，审计写入 candidate.json.audit.json。审计包括源绝对路径、源与候选 SHA256、每处转换和阻塞原因，源文件始终保留。存在阻塞时退出2，不写候选；无阻塞退出0。不得将新候选直接覆盖源文件或丢弃恢复点。此轮没有修改任何真实服务器数据。

同站全部实际到达调用具有相同旧定义时，转换为共享 arrival 的 OPEN_DOORS→旧步骤→END、准备 CLOSE_DOORS→END，提前量20tick可在编辑器调整。同站部分调用无覆盖、不同覆盖、已有共享定义、PASS/ORIGIN 携带旧覆盖均拒绝自动决定。运行时车型/编组等条件以及动态选站不能被猜测改写成计划条件，须作者明确重写并重新审核。

工具只转换 editor document，不转换 Core world、operational runtime、checkpoint、正式脚本或已编译 Timetable snapshot。恢复后的当前文档须经真实保存边界验证、重新生成及编译，正式脚本与运行定义分别审核替换；不能声称保存 JSON 就恢复了运行任务。旧 Blockly 草稿含 per-call 输入会明确拒绝自动恢复，可从备份读取原定义再人工处理。

验收：检查审计与 JSON 差异，特别是终点新默认保持开门、准备入口提前量、跨运行线共享行为；冲突选择一份定义或用计划条件表达差异。当前约束不支持原来依赖实际车辆属性的条件，不能用参考车辆代替实际运营条件。导入后检查每条运行线经停点与路径唯一性，再做游戏验收。
