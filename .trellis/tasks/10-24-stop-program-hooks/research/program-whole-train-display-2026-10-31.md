# 程序显示收缩为整列车 — 2026-10-31

用户确认暂时移除程序的编组单元选择与单元数量读取，等待完整分割/合并分组模型后再考虑局部程序操作。`(2+2)+4` 中将前两个单元当作一个4的逻辑目前无法由平铺单元槽位表达。本批不建立假分组、不扩展交路编组 UI、不提交或部署。

当前程序定义 SET_DISPLAY/RESET_DISPLAY 不含 unitSlots；参数类型和 Blockly 工具箱没有 UNIT_SLOTS。列车信息仅 NAME/CAR_COUNT/DIRECTION。站停运行模型和存档 DTO 同步删除选择器，ResetDisplay 为无参动作。交路编组、原生时刻表显示动作和运行线连续显示方案的单元身份保留；原生 SetDisplay/ResetDisplay 的空槽位列表明确表示执行时整列车，不能把这一含义套到 BeginOperationalTrip 等必须显式选择的动作。每次设置重新按真实列车组成取得所有登记单元，换向、解编不复用旧槽位，原子显示仍要求所有目标具有活动显示上下文。

未发布开发格式维持约定版本，不增加 runtime 兼容或针对旧类型的专用拒绝分支。独立离线 remove_program_unit_selectors.py 只转换程序库和编辑文档，验证冻结定义各自身份、移除对应调用参数并重算身份；不跟随最新程序库覆盖冻结快照。普通 JSON 数据、operationalTrips 和交路编组字段不改。服务器持久化文件、已生成 JS、运行快照和浏览器草稿未在本批访问/修改；部署前必须额外盘点这些边界，不能把 authoring 转换工具误当成整个服务器迁移。

关门耗时调查发现更底层的既有差异：CarBehavior.effect 在 animate 返回后结束效果，当前票据等到最晚效果启动点，而非动画播放结束。SimulationCarBehaviorExtension 也只校验动画能启动。模型动画具有场景时间，但 CapturedCarBehavior 当前只保留场景名，JS door 还可选择任意动作；没有现成可查询的可靠整列车关门耗时。S100静态配置关门效果延迟0.6秒，门场景差1.3秒，不能以固定20tick代替。

下一批关门修正必须一起处理 LIVE/模拟的动画完成语义、实际车辆耗时来源和发车准备入口的动态提前量，复用既有调度器及真实模型行为，不在活体 JS 上试执行查询，也不新建执行器。本批没有声称关门问题已修复。

## 验证

前端60文件415测试、TypeScript/Vite构建通过。实际HTTP浏览器验证工具箱、显示/重置无选择器、三种列车属性、编辑保存刷新重开，请求无已删字段；使用本地mock storage，不等同live游戏或物理验收。独立跨层审查发现frontend编译快照残留unitSlots声明，已删除并重新构建。

最终`./gradlew :SimpleATO:test :BukkitPlugin:test :WebUI:test :BukkitPlugin:shadowJar --offline --no-daemon -I /tmp/jr-wholetrain-tests.gradle`通过；初次SimpleATO默认小堆测试OOM，最终仅通过临时init给SimpleATO/Bukkit测试worker2GiB，不修改产品配置。各模块最终XML统计：{"SimpleATO": {"tests": 665, "failures": 0, "errors": 0, "skipped": 0}, "BukkitPlugin": {"tests": 699, "failures": 0, "errors": 0, "skipped": 1}, "WebUI": {"tests": 65, "failures": 0, "errors": 0, "skipped": 0}}。测试夹具由当前PROGRAM+boundPrograms及真实前端生成器重建，JVM断言显式验证任务起止、显示动作、精确trace与运行段边界；没有给生产代码补旧INLINE支持。

两个离线工具套件各12测试通过（真实候选JAR，包括一次性选择器转换与既有字符串类型转换回归）。`git diff --check`通过。浏览器证据`/tmp/jr-wholetrain-browser`；最终JVM日志`/tmp/jr-wholetrain-final-gate.log`。未提交、部署、归档或访问在线存档；已有无关heap dump保留。
