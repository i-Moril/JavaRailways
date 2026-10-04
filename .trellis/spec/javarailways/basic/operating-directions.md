# 轨道公共运行方向

## 1. 当前状态与负责模块

CURRENT。轨道长期允许运行方向由 Basic 保存；用户已明确当前格式从未部署。
本次直接修正未部署的开发稿，不升版，不增加旧方向格式迁移或兼容路径。
Basic 负责这一公共线路配置；SimpleATO 负责车次、停站和时刻表等规划策略；SimpleSignals 读取公共配置判断闭塞是否有效。
Core 的几何、连接与 AB/BA 坐标不迁移。手动驾驶不增加方向强制、限速器或 ATS。

## 2. 一次使用经过哪些步骤

玩家设置轨道仅允许运行 AB。线路编辑更新 Basic 的唯一方向存储，中央写入把实际变化的轨道交给现有信号更新流程。
SimpleATO 规划时读取方向，转换为现有中立寻路限制，叠加其他插件限制，调用 Basic 的纯搜索器一次。
SimpleSignals 从原有宿主接口取得相关轨道的完整方向子集，沿用闭塞校验和低速降级规则。
搜索器仍只接受拓扑与显式限制，不自行读取可变存储，不在搜索展开时调用插件。

## 3. 如何表示和更新

`BasicWorldData.operatingDirections` 是唯一可写存储，类型为 `basic.rail.OperatingDirectionStore`。
现有稀疏存储及通用转换代码整体迁入 Basic，不保留 SimpleATO 副本，也不重排其他轨道元数据。
记录 `rail UUID → 唯一许可 ViewDirection`；无记录表示两向许可。
单次相关轨道读取、分割/合并方向转移保持现有锁内原子操作；通知在存储锁外执行，相同值写入不通知。
Basic 不依赖 SimpleATO 的信号运行时。`RailWorldFactory` 创建原有 `SignalEpochRuntime` 后，
将 `publishOperatingDirectionChanges` 作为普通变化回调传给方向存储；隔离仿真用相同组合方式。
不增加第二套事件总线、监听生命周期、轮询或版本号。

## 4. 接口与存档

现有方向读取宿主入口 `InfrastructureResourceService.operatingDirections(worldId, topology, railIds)` 保留；
返回的不可变值是 `basic.api.route.RailOperatingDirections`，不新增服务注册层，不保留 SimpleATO 转发别名。
requested rails 完整覆盖、null 表示两向、精确拓扑根与世界有效性校验保持不变。
选路前使用 `basic.api.route.planning.OperatingDirectionOverlay.toRestrictions(topology)`，
将稀疏许可记录转换为相反方向的全轨道 `forbiddenTraversals`；SimpleATO 再叠加其他选路限制。

已确认的存档方案是：Basic infrastructure format 2 新增
`operatingDirections: List<SavedOperatingDirectionV1> = emptyList()`；该嵌套记录仍只有轨道 UUID 与许可方向。
SimpleATO infrastructure 保持 format 1 并删除方向字段；这是用户明确授权的未部署格式修正，不是发布后的兼容升级。
Core、外层 infrastructure、全部 runtime 边界和 SimpleSignals sidecar 不随之升版。

## 5. 生命周期和必须保持的规则

一个世界始终只有一份方向存储，跟随 Basic 世界创建/卸载，不跟随某次 ATO 任务创建/删除。
现有轨道创建、重建、分割、合并、删除和撤销继续使用相同方向变换，并维持静态时刻表与动态运行资源的编辑保护。
缺少记录的两向默认只适用于可信的完整存储读取；缺世界、缺轨道或读取过期不能被解释为两向许可。
本次归属调整不改变冻结路线、grant、设备准备或车尾释放；所有业务 reader/writer 只使用当前格式。

## 6. 校验与失败处理

方向存档的非法 UUID、重复记录、缺轨道引用必须在 materialize 任何 Basic 数据之前拒绝。
捕获时拒绝指向已不存在轨道的记录。基础设施恢复保持 Core/Basic/SimpleATO 的 revision 配对与写入前完整校验。
当前模型只允许 Basic 保存方向，不保留 SimpleATO 的方向字段或双写状态。
既有正常、方向失配降级和未知故障为0的闭塞行为不改变。

## 7. 性能

保持稀疏存储：一次相关轨道读取为 O(U)，U 是请求轨道数；完整方向快照按已记录方向数 D 处理。
不得改成每次从全世界 rail metadata 提取方向的 O(N) 扫描。
只迁移位置和所有权，不改变 Dijkstra、信号兴趣索引或控制器区间边界判断的渐进复杂度。

## 8. 具体情形

没有 ATO 任务时，轨道方向仍可保存、编辑和读取。
路径 `A AB → B BA` 的单向闭塞仍要求 A=AB、B=BA，不要求两个 canonical enum 相同。
轨道分割或合并后原记录随 UUID 转移，其他轨道记录不变；手动驾驶仍能按原机制运行。

## 9. 必需验证

验证 Basic 独立世界的方向读写、默认两向、原子子集读取及变化通知；SimpleATO 不保留方向成员或兼容别名。
验证正反向寻路、分割/合并/删除/撤销、编辑保护、闭塞降级、无关编辑 IDLE 和原有手动路径。
当前 Basic format 2 fixture 缺新增列表时得到空列表且版本不变；当前格式 round-trip 保留方向。
当前完整存档只在 Basic 保存方向，SimpleATO format 1 不含该字段，Destination/Line/revision 和 runtime 数据不变。
覆盖重复、孤儿和损坏输入拒绝；不新增旧 SimpleATO 方向格式的 reader、转换或兼容测试。
重跑 Basic、SimpleATO、BukkitPlugin、SimpleSignals 相关测试及 Java consumer/JAR 检查。

## 10. 开发稿修正与后续边界

用户已批准方向归属 Basic，并否决为从未部署的本次格式升版或新增搬迁转换。
直接修改当前 DTO、capture/restore 和固定当前 fixture，Basic 保持2、SimpleATO保持1；正常加载器只处理修正后的当前模型。
已有历史 metadata 纯转换代码只调整其输出目标，让方向进入 Basic；不扩展历史输入支持、不新增中间格式 reader。
文件发现、dry-run/apply、不可覆盖恢复点与自动启动迁移仍归独立09-24任务，本批不实现其编排，也不改其未提交计划。
不得以这次未部署稿修正为理由删除独立任务已确认的历史世界升级要求，也不得新增业务层版本分支或双写。
