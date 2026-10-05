# 当前通路预览批次

## 已批准边界

玩家选保护区域入口/出口，只沿真实当前道岔选择计算通路；显示路径、方向、实际终点和不能到达的原因。
相关选点、道岔或轨道改变时异步重算。先做预览，下一批才保存固定进路。
具体算法、线程、失败和测试合同见[当前通路预览](../../../spec/simple-signals/backend/current-route-preview.md)。

## 最小行为缺口与 owner

已有闭塞/区域建造 Wand、道岔 Wand、公开同周期选择观测及轨道事务通知；尚无区域入口到出口的当前通路工具。
领域和玩家会话都属于 SimpleSignals。Basic/Core 不应知道“预览进路”，也不需要修改公共 API。

## 预计文件边界

- SimpleSignals authoring：纯当前通路计算、关闭式玩家会话、独立预览工具与针对性测试。
- SimpleSignals runtime：必要的局部定义发布通知，回调只失效所选区域；不建立通用事件框架。
- SimpleSignalsPlugin/命令描述：工具命令和关闭接线。
- 本任务和 SimpleSignals spec/README：工作方式、成本和实际验证结果。

不改 Core/Basic/SimpleATO/宿主 API、不读写存档、不加 token/兼容、资源队列、进路保存、完整叠加显示或信号机放置。
既有短时粒子高亮用于这一批真实路径/方向预览；完整 TextDisplay 显示另做。

## 验证

纯轨道方向/切口/循环/道岔真实选择测试；事件只重算相关依赖、合并计算、旧结果丢弃和关闭测试；
玩家选择/取消/权限/跨世界测试；完整 SimpleSignals check、Java consumer 和独立 JAR检查。
只在发现跨层改动时扩大对应模块测试，先说明范围；没有 live Folia 时明确不声称画面已经实测。

## 完成证据

2026-10-05：本批实现已通过独立复核。新增五个类33项测试，另补1项真实 manager 的失效→同定义恢复回归。
最终 SimpleSignals 40套/392项全部执行通过，零失败/错误/跳过；插件 JAR隔离检查执行，未变 Java consumer 编译复用缓存。
完整检查日志 `/tmp/javarailways-current-route-preview-check.log`；实际测试结果在模块 `build/test-results/test/`。
未实施录制、TextDisplay 全套显示或真实平台验证。现有域禁止精确端点区域切口，选点只匹配合法同轨边界；不新增不可达兼容逻辑。
