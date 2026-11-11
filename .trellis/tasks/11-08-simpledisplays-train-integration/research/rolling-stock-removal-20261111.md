# 去除车辆库存登记

## 用户已确定的行为

- 普通列车整列默认是固定单元，默认单元沿用已有列车 ID，不增加另一套随机 UUID。
- Core 底层允许在单元内部合法解编；本批不新增玩家编组操作入口。完整单元保持 ID；被拆开的单元按实际两侧分车，原列车片段保留原标识，分离片段沿用分离列车标识，不建立历史谱系系统。
- 车辆段不是车辆或单元的永久归属。取车和回送由时刻表动作指定，重置位置只认现有 `initialPositions`。
- 删除必须先登记的 `RollingStockRecord` 及独立 `AVAILABLE/RESERVED/IN_SERVICE` 库存生命周期。不能仅重命名并保存同一份车辆属性副本。
- 车辆筛选将使用列车 metadata。WebUI 入口现已暂停编辑；不把车型名或动作名伪装成人工标签/能力。
- 结构变化完成后以实际新编组继续执行任务；任务等待资源时不将物理变化当作尚未发生。保留必要的路线保护与停止约束。

## 实施边界

Core 拥有组成、物理车序、几何、结构提交与 immutable frame。SimpleATO 通过宿主注入的同步只读查询取得候选事实，静态分析和模拟消费 detached 值，不查询 Bukkit live 对象。查询不创建 ATO，不登记车辆，不新建长期属性缓存。

`TrainCandidateFacts` 是临时查询值，仅包含车型、人工分类及可空最高速度，不包含车辆段、位置、车数、长度或库存状态；物理事实取自同一 Core frame。宿主暂无通用列车 metadata 容器时不生成虚构标签，未知字段不能满足对应筛选条件；本批不声称新增了完整 metadata predicate API。

重联/解编的查询必须和静态分析、模拟一致，保留逐车配置来源以便拆开后重新汇总；归约算法及失败行为统一见 [Depot 合同](../../../spec/javarailways/simpleato/depots.md)。不把这些低频捕获值持久化为另一份库存表。

DepotStore 保留车辆段/停车点定义。取车、入库使用同一流程，不再按“登记/未登记”分流。任务归属和必要结构操作状态与实际 Core 成员对账，不能借删除库存而放宽源保护、车尾释放、完整编组约束或重复任务检查。

独立车辆段库存 reset 与命令删除，复用现有 timetable reset/`TimetableInitialPositioner`，不新增另一种摆位合同。`initialPositions` 的两阶段位置校验、包络检查和失败恢复保持。

本批继续暂缓默认运营车次归一化、段外屏幕关闭和 PAPI，不把前批未提交显示旁路计入本批成果。

## 持久化与验证

未发布开发格式直接修正，版本常量保持约定值，不添加旧库存 runtime reader。现有含库存字段的开发存档在以后部署前需要独立、可审计、保留恢复点的离线处理；本轮不写服务器数据、不部署。

验证覆盖底层完整/内部解编及失败原子性、默认 ID、创建与恢复；普通无登记取车/入库、竞争目标、初始位置 reset；结构资源保护与任务恢复；静态分析、模拟、宿主查询与命令移除。

## 2026-11-11 验证结果与剩余项

- `Core:test` 590、`Basic:test` 494 项通过，零失败/跳过。日志 `/tmp/jr-composition-bottom-tests.log`。
- `SimpleATO:test` 全量 841 项通过，零失败/跳过。日志 `/tmp/jr-no-inventory-validation-final.log`。
- `SimpleATOBukkit:test` 全量 2 项通过；`BukkitPlugin:compileSignalApiConsumerJava`、`verifySignalModuleBoundaries` 通过。日志 `/tmp/jr-no-inventory-boundaries.log`。
- 前端相关 7 个文件、117 项测试及 production build 通过，日志 `/tmp/jr-filter-ui-tests.log`、`/tmp/jr-filter-ui-build.log`。
- `BukkitPlugin:test` 全量 896 项：893 通过、2 失败、1 个实验 `measure()` 跳过。日志 `/tmp/jr-no-inventory-host-tests-final.log`。本批修改涉及的投影、快照、迁移、模拟、逐车配置和命令测试已通过，不能据此宣称全量 gate 通过。
- `git diff --check` 与独立源码复审通过；未提交、push、迁移或部署，也未做 Paper/Folia/游戏端验收。

剩余两项测试未在本批扩大修改：

1. `ExtensionPartModelTest.style revision retires old frame and redraws same source tick without rebuilding session`：两次全量均失败，一次首次 worker frame 等待超时，一次样式刷新后旧输出未按断言销毁。相关实现与测试相对 HEAD 无差异；根因和 HEAD 隔离基线尚未确定，不能仅称为随机超时或已证明既有故障。
2. `ProgramExpressionsTest.one string parameter composes into destination station depot and display fields`：旧断言从索引 2 检查移动动作，但纯展开实际产生四项，依次为显示、移动、选站、入库。测试和展开实现相对 HEAD 无差异；11-09 旧工作区的 `/tmp/simpledisplays-phase1-existing-failure.log:81` 已记录同名失败，这不是当前 HEAD 的隔离运行证据。

本轮检查修复还包括：真实旧迁移输入仍保留 `rollingStock:[]` 且严格拒绝非空库存，离线 converter 仅在写出 current SimpleATO 格式前移除这个已确认为空的字段。未新增 runtime reader。恢复诊断清理区分普通任务和结构保护：前者释放准确归属，后者只删诊断，保留 claims、HOLD 与运动围栏。
