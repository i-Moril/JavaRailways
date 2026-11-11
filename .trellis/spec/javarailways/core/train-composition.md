# 固定单元组成

## 范围与所有权

修改列车创建、重联、解编、组成快照或恢复时读取本页。当前实现及测试证据归显示集成任务的固定单元与去库存批次。

Core 是单元身份及车辆成员的唯一 owner。SimpleATO 可维护单元调度占用及可选控制兼容声明，不再保存另一份可编辑成员表；车辆段不是单元创建的前提。

## 一列普通列车如何成为固定单元

创建列车时若未提供组成，Core 将全部车辆作为一个单元，沿用已有列车 `id`。不生成另一套随机单元 UUID。单元随列车保存，读取时保留原 ID。重联将两个来源的单元一起带入结果，不能用结果列车 ID 重新生成单元。普通折返只改变运行端，单元 ID 和 canonical 车辆顺序保持不变。

Bukkit 新建列车以创建时的列车名作为稳定 train ID，后续显示名称重命名不改变 train/unit/car 身份；
调用及展示合同见 [列车稳定 ID 与显示名称](../../bukkit-plugin/backend/interaction-render-persistence.md#列车稳定-id-与显示名称current)。
`CoreWorld.validateDefaultTrainCreationIdentity(id, name = id)` 提供分配车辆前的只读 owner 预检，
复用已有身份索引并检查原始注册表（含 staged 列车）中保留的单元 ID；不预留身份，构造器的最终校验仍必须执行。

## 数据与查询

`Train.composition: TrainComposition` 和 `TrainFrameSnapshot.composition` 提供不可变值。
`TrainComposition.units` 中每项是 `TrainUnitComposition(unitId, carIds)`；`carIds` 是单元的 canonical 顺序。
`TrainComposition.create(units, physicalCarIds)` 验证每车恰好属于一个单元、单元 ID 唯一、每单元完整连续；允许整单元相对实际车序反向。
`unitsFor(physicalCarIds)` 返回输入中完整连续的单元并按实际顺序排序，不把部分单元当成完整证明。要求整列证明的 consumer 必须另外验证覆盖全部输入车辆。
`unit.isReversedIn(physicalCarIds)` 比较 canonical 与实际车序；不读取列车运营方向，也不根据世界坐标猜朝向。

## 结构事务及发布

组成与列车结构在同一准备、提交边界处理。重联先根据最终稳定车序构造候选组成，与几何候选一起发布；扩展或控制器准备失败时保留来源组成。单元间解编将完整单元分配给两个结果，保留 ID。底层同样允许单元内部解编：被切单元按实际 prefix/suffix 分车，但各片段内部保留 canonical 子序列；原列车中的片段保留原单元 ID，分离片段沿用分离列车 ID，其他完整单元不改名。不增加历史谱系，也不新增玩家操作入口。

`splitAt(physicalCarIds, index, separatedTrainId)` 统一准备两侧 `TrainComposition`。新单元 ID 若与其他完整单元或世界内既有单元冲突，准备阶段拒绝，不随机换 ID。

解编准备中的新分离端会预留 ID 和名称，但在两侧结构安装前不进入 `world.allTrains`。其 extension 初始化可以读取自身组成，枚举世界成员时仍只看到原来的唯一 owner。提交后才公开分离端；初始化失败则注销预留身份并保留来源。不能通过让 SimpleATO 静默合并重复成员来遮蔽中间状态。

世界内同一单元 ID 不得属于两列。CoreWorld 的构造准入检查原始列车注册表，包括尚未公开到 `allTrains` 的分离端；否则其初始化回调可抢占内部拆分产生的新单元 ID。身份转移只能豁免确切来源实例，不能放宽成按列车 ID 或全局跳过检查。跨线程 consumer 从同一 published frame 读取组成和车序，不混读 live cars。

## 校验与失败

- 重复单元 ID、重复车辆、缺失车辆、单元交错或不连续：创建候选失败，不安装部分组成。
- 新建列车显式引用其他列车已有单元：拒绝；原列车、索引及组成保持不变。
- 反向重联：保留 canonical 成员次序，以最终实际车序表达相对朝向。
- 单元间解编准备失败：两侧候选都不发布，原列车保留完整组成。

## 成本与例子

组成只在创建、恢复和结构变化时重建；正常物理帧复用不可变组成值，不逐 tick 重建单元。当前查询构造车辆位置索引并对单元排序，成本随车辆与单元数增长；世界唯一性检查还遍历已存在列车的单元。

例如 U1=[a,b]、U2=[c,d] 重联后可为 [a,b,d,c]；仍是两个单元，U2 相对实际车序反向。列车折返后仍是 U1、U2，不能把整列改名为一个新单元。

## 测试要求

覆盖默认单元、不可变集合、完整/反向/非法分区、重联接触方向、单元间与内部解编、反向单元裁切、折返身份、重复世界身份、结构准备失败、保存恢复。对准备失败同时断言旧 frame、car owner 与组成未变；只比较候选 helper 不足以证明原子性。初始化回调尝试抢占 staged 分离端的新单元 ID 必须拒绝。

## 持久化及迁移

`SavedTrainV1.composition` 保存单元 ID 与 canonical `carIds`。没有组成标记的普通列车初始化为单个单元；writer 写出完整组成。新增有明确默认行为的字段保持 Core runtime 当前版本，不增加内部 DTO 代际。

历史开发数据若只有 SimpleATO 单元成员而缺少 Core 组成，应在部署前用独立、可审计的离线迁移保留这些身份，不能在 Core 业务层反向依赖 SimpleATO 或静默猜测旧重联边界。本批不运行迁移、不改服务器存档。
