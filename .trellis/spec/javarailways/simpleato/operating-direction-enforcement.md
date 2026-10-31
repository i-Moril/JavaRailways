# 自动运行的轨道方向限制

## 1. 状态、范围与所有权

CURRENT。SimpleATO 使用 Basic 公共运行方向验证运营路径、车身与停车安全余量，并给自动运行增加方向边界。固定闭塞没有独立单双向权限；其完整物理保护后缀可能超过本次合法行驶范围。

Basic 保存唯一方向配置并处理物理准入、grant、资源冲突和车尾释放，公开 API 不变。SimpleSignals 负责闭塞几何、入口和附加保护资源，不再判断整块方向一致。不新增手动驾驶方向强制、制动算法或跨闭塞方向协调。

## 2. 一次运行怎样经过各模块

ATO 读取公共方向，组合选路限制并生成运营路径。终端策略在停车点后提供物理保护后缀，最终路径确定后，Dispatcher 检查真实停点、车身和本次终点余量（terminal StationCall0.3米，否则全局安全距离）所需的前缀，并生成绑定完整路径的方向结果。

Basic 仍可授予整个闭塞的物理资源；准入客户端如实保留该 grant，在同一限制快照中按需增加较近的方向边界。原有最严格约束管线同时消费物理边界、方向边界、信号和任务限制，因此保护后缀不会自动变成可行驶权限。

## 3. 内部表示与算法

`RouteOperatingDirectionLimit` 是内部不可变值，保存 `worldId`、精确 `RouteSnapshot` 和 `endRouteDistanceMeters`，不持久化。按完整路径顺序累加米数，第一个不允许该段 facing 的正长度片段起点就是方向边界；没有禁行片段时为路径末端。

计算要求传入的当前 `TopologyState` 与完整 `RailOperatingDirections.topology` 为同一对象，各轨道必须有唯一投影、方向条目和合法视角范围。null 仅在完整可信读取中表示两向许可。路线的 topologyRevision 是生成来源，不能要求它等于之后无关编辑产生的新世界 revision；有效性由当前相关轨道和原依赖保护判断。

`TerminalRouteMargin.requireLegalPrefix` 对最终路径重验必需前缀，覆盖原路径、复用后缀和插件补全；还检查新增移动余量没有进入未选 replacement。不能只调用 `propose`：后者在已有几何足够长时不会重验，也不能只看插件返回的总长度。

## 4. 接口与数值

内部接口：

```kotlin
RouteOperatingDirectionLimit.calculate(worldId, route, topology, directions)
limit.requireMatches(selection)
limit.requireLegalThrough(requiredEndRouteDistanceMeters)
```

所有距离按冻结路线 traversal 顺序递增，单位米。许可判断只容许既有 1e-6m 数值误差，不使用停车容差吞掉非法余量。

`FrozenRouteSelectionResult.Selected.operatingDirectionLimit` 可以在尚未完成候选时为空，但进入准入客户端前必须具有匹配结果；缺失或错绑是内部不变量错误，不能默认全程允许。完整有效选择的首次申请仍先发布 0m 授权；pending／拒绝维持较安全旧边界。

准入来源仍为 `simpleato:resource-admission`。真实物理边界使用 `simpleato:committed-authority`；方向比已发布物理边界更近时，额外发布 `simpleato:operating-direction` 的 `MovementAuthorityRestriction`。同源限制 ID 不冲突，不裁剪 grant；全程合法时不增加重复限制，尤其不能覆盖独立证明的零间隙连挂接触语义。对实际grant和方向前缀均已覆盖车站requiredEnd的terminal StationCall，资源与方向restriction携带车站0.3米余量；其余情况及独立信号来源保留默认全局值，详见[终点授权合同](terminal-route-completion.md)。

## 5. 生命周期、线程和版本

选路、最终补全、静态时刻表应用、同向接续、换向 rebase 和编组接管都必须携带匹配方向结果。接续 pending 不能提前放宽；换向前验证反向车身，成功 rebase 后重新绑定路线坐标，原资源不先释放再申请。

公共方向存储在释放自身锁后调用原 `SignalEpochRuntime.publishOperatingDirectionChanges`，该入口立即通过 `InspectionRuntime` 的 rail 索引使当时相交的自动路线不可用，并合并 ID 供后续信号来源通知。下一信号 epoch 不能再次按这批旧 ID 失效路线，否则编辑后新建的合法绑定也会被误伤。无 source 时同样立即生效；无关路线和原 grant 身份不变，失效路线沿原拒绝／显式重规划流程处理。

编组交接初始仅绑定实际 body-only 路线及相应 grant；带移动任务的 `AdmissionRoute.requiresTerminalCompletion` 使第一次候选绕过 bootstrap 的同 mission 快速复用，走原 append／补全／方向余量验证。已经准备的候选优先于 bootstrap，避免 pending 时重复补全。静态应用同样补全结构输出的依赖快照，但不修改已认证 corridor proof 的含义。

滚动续申请阈值至少达到控制器最大速度下的现有停车前视包络（包含同一全局 safetyDistance），否则默认5m余量可能让列车停在小于5m的旧 timing 申请阈值之外。阈值取两者较大值，不更改制动算法或增加配置。

这是 SimpleATO 的现有路径生命周期，不增加监听总线、world registry 或逐 tick 配置查询。内部结果不写入存档，不新增 schema／epoch。

## 6. 校验与失败处理

- 缺轨道、缺方向条目、错世界、错绑定、错当前拓扑根或范围越界：拒绝方向结果／候选。
- 实际运营路径、反向车身或停车余量越过禁行点：拒绝，不因完整保护后缀够长而放行。
- 仅保护后缀在禁行点之后：允许申请其物理保护，使用较近方向限制阻止实际运行进入。
- pending、拒绝、失效或来源失败：不放宽旧限制、不清除 claim；继续按原生命周期停止或要求重规划。
- 物理 grant 未取得：方向结果本身不能授权运行。

## 7. 性能与退化

方向子集读取和扫描按相关轨道／路线片段线性进行。最终前缀的通用限制检查沿现有 forbidden/replacement 列表计算，约为 O(R×(F+P))，R 为检查片段数；不能宣称已建立常数时间限制索引。组合公共稀疏方向 overlay 还可能按已记录方向数处理。

这些计算只在候选／绑定变更时发生。正常控制周期消费现有不可变限制，不增加世界轨道扫描或第二次寻路；方向失效按 rail 反向索引路由。未知数据直接拒绝，不以默认双向退化。

## 8. 具体情形

A 单向、B 双向，组成同一闭塞。整列车在 B 内停车后折返至 B 内车站，保护后缀可以延伸到 A；方向约束位于 B/A 接头，原物理 grant 仍如实保存。

停车点在 B 但 5m 安全余量伸入 A，仍拒绝。车头已在 B 而反向车身仍涉及 A，也拒绝。新物理 grant 很长，不能冲掉更近方向限制；不相关拓扑 revision 改变但相关轨道有效时不凭版本号拒绝。

## 9. 必需验证

`RouteOperatingDirectionLimitTest` 覆盖混合方向、反向车身、缺字段／轨道、当前 root 和旧来源 revision；`TerminalRouteMarginTest` 覆盖插件后缀掩盖失败、已有后缀复用和 BA 范围；`RouteAuthorityAdmissionClientTest` 检查真实 grant 加较近限制、pending／接续／adoption 和错误绑定；`SignalEpochRuntimeTest` 检查无 source 的方向失效和无关编辑稳定。

还须通过真实 Dispatcher 混合轨道折返与编组接管回归，断言资源身份持续、方向边界收紧及真实车尾释放。单测替身、真实离线物理和游戏内验收分开记录。

## 10. 迁移与禁止做法

不改变 Basic 的请求／grant 或方向存档，不添加旧开发运行模型。SimpleSignals 当前文件删除旧闭塞 flag，迁移由其 owner 合同处理。

应避免：把保护后缀所有方向都当作本次必经路径，或者把物理 grant 改成运营停点来伪装更短授权。
正确做法：验证实际所需前缀，保留真实物理资源授权，并通过现有 ATO 限制管线单独收紧。
