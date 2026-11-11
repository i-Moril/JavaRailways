# 固定单元归属修正

> 下文保留第一批实现及当时待定项。用户随后确认底层允许内部解编、默认单元沿用既有列车 ID、去除库存登记且 reset 只认 initialPositions；后续范围以 [库存清理合同](rolling-stock-removal-20261111.md) 和 [Core 当前契约](../../../spec/javarailways/core/train-composition.md) 为准。

## 本批目标与边界

用户确认：普通无特殊标记列车的整列编组默认是一个固定单元；重联结果在列车自身保留原单元组成。车辆段绑定由时刻表及运营状态处理，不是固定单元必填属性。

本批只处理 Core 固定单元组成、结构事务、存档和 SimpleATO 消费。默认运营车次归一化及离开运行线关闭显示属于后续批次。本轮开始时已有七个未提交显示修复文件，保存为 `/tmp/jr-before-fixed-composition-20261111.patch`；不将这些旁路变更当作本批成果或继续扩大。

## 跨模块设计

- Core 是实际组成及单元成员的唯一 owner。`Train.composition` 与已发布帧携带不可变单元组成；普通创建初始化一个有稳定 ID 的单元。
- 重联在准备阶段验证并合并组成，在既有结构提交边界一次安装；失败不能修改原单元。普通折返不重建单元 ID，朝向相对稳定车序定义。
- 组成随 Core train runtime 保存。新增有明确默认行为的组成字段保持 Core runtime 当前版本；不因开发提交增加 schema version。
- SimpleATO 从 Core 组成读取单元，不再维护可独立修改的成员镜像。保留调度 claims、编组事务恢复和真实控制兼容预检。
- 删除固定单元的强制车辆段归属；其他 rolling-stock 库存字段不在本批顺带重构。
- 模拟克隆保留单元身份和组成，不能重新登记另一份 SimpleATO 成员列表。

## 待确认与验证

单元内部解编的政策已向用户询问，回答前不实施依赖该政策的逻辑。已有单元之间的解编应保留身份。

需要验证默认单元、重联各接触方向、折返、解编、保存恢复、准备失败无部分提交、无车辆段的真实单元识别，以及模拟克隆。本批不提交、不部署、不改测试服数据。

## 已实现与检查（内部拆分规则仍待确认）

Core 实现 `TrainComposition/TrainUnitComposition`、默认 UUID、创建注入、frame/save/restore、重联和单元间解编。分离端初始化阶段只预留身份，不公开到 `allTrains`；两侧结构安装后才公开，避免初始化回调枚举到重复成员。帧复用已冻结组成，不重复构造内容相同的对象。

SimpleATO 移除成员注册表、固定单元 homeDepotId 和 saved fixedUnits；只读 Core provider 与独立的可选控制声明、claims、恢复 ledger 连接。无 stock 的普通单元可取车并恢复合法 claims；存在 stock 时仍严格检查状态与 assignment。自动重联缺少已知同族声明仍拒绝，不伪造控制兼容证明。

普通列车默认有单元后，不自动启用结构编组的库存循环准入阻断。分析仍提供真实库存状态及 code/detail；非结构 summary 的 blocking=false，结构需求保持阻断。相关差异有定向回归，未增加用于测试的 validator 参数。

Bukkit 删除旧 register-unit 命令入口；模拟克隆保留原 synthetic ID 映射，并完整映射 canonical 单元边界、朝向和控制声明。旧显示旁路仍是前批未提交工作，不计入本批实现。

Core、SimpleATO、Bukkit 生产及测试源码编译通过。第一次统一验证发现并修复普通取车的旧 stock 强制门、测试夹具重复 car IDs，以及默认单元意外触发库存阻断；没有放宽生产成员唯一性校验。最新 Core 55 项及 SimpleATO 251 项定向回归零失败/错误/跳过。Bukkit 定向 55 项中 54 项通过；唯一失败为 `mixed installation survives seed and split rebuilds ATO from exact current car profiles`，它拆开默认单元，触及仍待用户决定的政策。合计 361 项、360 通过、1 未解决，不把该次构建写成成功。日志 `/tmp/jr-fixed-composition-regression.log`。

只读审查未发现其他确认阻塞项。单元内部 split 目前明确报需要政策，尚非最终行为；未运行覆盖该未定行为的 Core 全量解编套件，不声称本批已经全部完成。

独立 `verifySignalModuleBoundaries` 通过，日志 `/tmp/jr-fixed-composition-boundaries.log`；`git diff --check` 通过。未提交、未部署。

## 后续讨论：车辆属性筛选（用户确认）

用户指定将来以列车 metadata 作为筛选来源，目前先禁用 WebUI 编辑入口，不实现新的 metadata predicate/API。本次仅禁用 DutyManagement 中车辆标签选择；旧值仍可见并随文档原样保存，车辆段选择、取车方式及列车名选择不受影响。添加中英文说明，同步现有可见性用例。

相关前端 7 个测试文件、117 项测试通过；TypeScript/Vite build 通过，保留既有 bundle 大小提示。日志 `/tmp/jr-filter-ui-tests.log`、`/tmp/jr-filter-ui-build.log`。未提交、未部署。停车预约与结构操作保存边界仍在讨论，未据此修改运行时或移除恢复记录。
