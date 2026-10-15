# 闭塞与保护区域建造工具

## 1. 状态与负责模块

SimpleSignals 拥有工具物品、玩家选点、建造算法和命令；宿主只提供公开的轨道定位、运营方向和编辑许可。
本合同覆盖闭塞建造/删除，以及保护区域有向边界的放置/删除/自动补全。
Signal Wand 已把逻辑入口与模型位置分成两步，覆盖固定闭塞、保护区域当前入口和保护区域固定进路三类主信号，
并覆盖附属信号的完整建造及既有进路表示器的编辑／删除：
放置模式左键绑定入口并明确选择新建或精确既有设备，右键轨道或空气使用轨道附着位置，右键真实方块面使用地面位置，
然后更新位置和类型；删除模式精确选择并删除一台主信号及其直接附属设备。
现已提供[进路Wand删除与保护主信号重绑](#wand删除进路与保护信号重绑)：前者从已保存进路列表确认删除，后者从设置页确认逻辑目标，均不靠重新摆放模型完成。
新建设备的 `inspectionLeadTimeSeconds` 固定为明确策略值 `0.0` 秒，表示不要求信号插件增加额外准备提前量；
SimpleATO 的 inspection threshold 取 `max(reachableDistance(leadTime), dynamicLookahead)`，route perception horizon 也取
controller 制动／反应／安全距离与 `maxSpeed * leadTime` 的最大值，而不是把两者相加。同步信号求值本身没有 setup delay；
因此0秒只是不抬高这两个既有 minimum horizon，固定闭塞授权 floor 和入口停车约束也不消失，所以该值不是临时占位。
命名轨道摆放预设已接入设置页；测距、inspection lead time 编辑、自由摆放/样式参数编辑、进路表示器新建和完整轨道可视化仍未实现；
进路录制另见[录制合同](current-route-recording.md)。
本批离线门禁与审查结果记录在任务 `implement.md`，不代表真实 Folia 客户端验证。

## 2. 一次建造如何完成

玩家使用工具选中轨道位置。平台层捕获世界、玩家动作、视线和当前定义，异步定位轨道后在 worker 计算修改。
闭塞由两点间的无道岔路径生成；保护区域由玩家放置的有向边界自动判断是否封闭。
工具只构造 `SimpleSignalsDefinitionPatch`，保存仍调用 world manager 的唯一事务：校验候选、取得编辑许可、
写临时文件、原子替换定义和观测 source。成功后再更新玩家的接续选点并回复，不绕过运营路线与运行占用保护。

## 3. 路径与自动补全的内部方法

闭塞沿 Core 已发布的普通端点连接行走，遇到任何道岔就停止，包括道岔直向；访问集合保证环线搜索会结束。
先得到两点间明确的物理路径，再一次读取所需轨道的公共运营方向。
全部允许两向则生成双向闭塞；全部与路径一致则保持路径，全部相反则反转路径；混合配置拒绝建造。
固定闭塞的禁止道岔规则也用于正式定义校验，但不能禁止保护进路借用同一几何编译器通过道岔。

保护区域搜索把作者边界视为轨道切口，沿边界指向的内侧展开。轨道连接直接读取现有拓扑，不在每次点击时重建整张图。
到达另一边界时检查其朝向；遇到未标记尽头、外侧重连或超出范围，都不视为闭合。
距离使用按轨道长度排序的搜索队列：上限是起点到区域最远位置的最短轨道距离，包含长轨道中部，不把各支路长度相加。
环线因此具有有限范围，不把无限绕行长度当作区域大小。正式候选仍复用现有区域内部与重叠校验。

新区域完整闭合后，在同一 worker 为各有向入口—出口组合自动生成最短合法进路，与区域一起原子保存。
多通路警告标在有关出入口，区域工具左键已有边界可查看警告；不自动修改道岔或覆盖修复区域的原表。
工作方法、无合法进路的拒绝及复杂度见[自动进路合同](automatic-protection-routes.md)。

## 4. 数据和单位

轨道位置仍为 `RailPositionSnapshot`：rail UUID、canonical AB 米数、朝向；保护边界朝向指向内部。
命令为 `/simplesignals wand block|region [create|delete]`，默认 create，权限 `simplesignals.admin`。
信号工具命令固定为 `/simplesignals wand signal`，只在 catalog 已就绪且非空时发放一个通用 Signal Wand。
新物品从按 `signalType` 排序的当前 catalog 选择第一项作为确定性默认值；命令不接受类型参数，也不承担 catalog 列表界面。
Shift+右键打开 SimpleSignals 自有的 Paper 原生设置页，选择放置／删除模式、五种控制类型，按 `signalType` 排序和标记当前项，
并从独立 model-only catalog 选择 presentation model；统一信号包模型不冒充 presentation-only 模型来源；
选择后才修改物品状态。PDC 使用稳定 marker 识别工具，并另存包含随机 `itemId`、非负单调 `stateRevision`、模式、
控制类型、namespaced `signalType`、可空 `presentationModelId` 与严格 tagged binding 的完整状态。固定闭塞 binding 保存 world/block/entry direction；
保护当前入口保存 world/entry boundary，固定进路另存 route ID；两者还明确保存“新设备”或精确既有 `signalId + revision`。
绑定随物品移动，且通过附魔光泽提示；
状态跟随物品转移、换手和重启，不是玩家全局设置；格式损坏时不能编辑或自动覆盖。本工具状态尚未发布，直接修正当前格式，
不增加旧 `signal_wand_type` reader、upcaster 或世界 schema 版本。
选轨最大视线距离 64 米、命中半径 0.75 米；同轨道已有边界在 0.75 米内可吸附。
Signal Wand 右键方块面时不执行选轨：模型根位于方块面中心，保持世界竖直，局部 +X 沿玩家当时的水平朝向、
局部 +Y 朝上、局部 +Z 朝玩家右侧。该姿态在玩家 owner 线程从方块坐标、面方向和 yaw 一次性复制，worker 只接收不可变
`ModelPlacement.Ground`。右键空气继续使用既有选轨路径；两种摆放都不改变左键保存的闭塞／入口／主信号逻辑 binding。
真实普通连接的两个轨道端点另按拓扑视为同一位置，不以空间接近代替连接。
保护边界方向取视线与轨道 AB 切线的点积符号，绝对值小于 0.15 时要求玩家调整视线，不猜测方向。
配置 `protection-region-max-distance-meters` 默认 1000，要求有限正数，启动时读取。
`pendingProtectionBoundaries` 保存尚未组成正式区域的边界，默认空列表；它们本身不是已生效保护区域。
闭塞分界点由各闭塞路径两端取得，不另存一批无人引用的空点。

已有区域的 `boundaries` 保留上一次认可的完整边界；可空的 `boundaryDraft` 保存编辑期间当前剩余的边界。
null 表示没有未完成边界编辑，空列表表示全部边界被删但区域和配置仍保留。
保留原完整边界是为了重启后仍知道应禁止 ATO 进入哪里，不是另外存储一份派生拓扑图。
补全成功后用新边界替换完整边界并清除 draft，区域 ID 不变。

## 5. 编辑和线程规则

Wand 编辑流程的图搜索、候选编译、JSON 和文件 I/O 均在 worker；玩家物品和回复在玩家所属线程。
捕获 runtime 对象、定义版本和本次拓扑；世界卸载重载、外部编辑或失效玩家动作不能提交旧选择。
成功提交只替换已经准备好的状态。定义比较和保存仍可随定义规模增长，但不阻塞物理线程等待平台交互。
同一拓扑 root 的派生连接索引在候选间复用；普通点击不能反复从全世界 rails 重建索引。
两点选择在已保存第一点之后发现publication过期时，同时清除`firstPoint`与其capture；下一次点击必须重新capture，且backend收到的
第一点为null，不能把旧位置嫁接到新publication。定义事务进入host原子source callback后仍执行最后一次authoring guard；该guard失效
返回`WORLD_UNAVAILABLE`且不改disk/source/publication，只有真实staging或primary commit I/O失败才返回`PERSISTENCE_FAILED`。

信号工具通过固定闭塞 snapshot 的 rail 反向索引筛选候选，只允许距有向入口0.75米内且视线朝向区间内部的左键绑定。
共享入口歧义、区间内部点击和单向闭塞反向入口拒绝；入口尚无主信号时仍可形成稳定绑定并在右键时创建，
右键放置重新用当前 snapshot 校验绑定的 world、block 和 entry direction；已有设备保留 ID、受控入口与 inspection lead time，
用 checked revision +1 替换 `signalType` 和本次 rail/ground placement。空入口则生成新的 namespaced UUID 设备 ID、revision 0 和明确的
0秒额外 lead time。生成 ID 在 patch 前对四类现有设备身份检查；碰撞直接拒绝，不能把已有设备当作 update 或重新绑定。
两种情况都走同一个 manager patch 事务。删除模式不使用旧绑定，而是按本次左键重新唯一解析入口。
保护入口解析同样先用 region rail 反向索引缩小候选，再只比较同 rail 的 canonical AB 距离、0.75米容差和
朝向内部的方向；不执行图搜索或全区域扫描。同一保护入口允许多个主信号，因此左键后由 SimpleSignals 的玩家／物品接续页列出：
当前入口类型的明确新建设备和全部精确既有设备；固定进路类型则按稳定 route ID 列出当前可用作者进路的新建设备，
并列出该入口全部精确既有固定进路设备及可用状态。暂时不可用的作者进路不能新建／改绑，但既有设备可更新摆放或删除。
创建使用共享四类设备 ID namespace 中无冲突的 UUID、revision 0和0秒额外 lead time；更新保留逻辑 binding、ID与 lead time。
删除只移除所选设备和直接附属设备，同入口／进路的其他主信号不变。
附属信号控制类型按轨道附着位置、方向和0.75米容差寻找已经摆放的固定闭塞／保护主信号；候选保存主信号
`signalId + revision`，并列出新建设备和每个精确既有附属设备。右键使用所选 model-only ID 建立或更新
`DeviceModelDefinition`，仍由 manager 验证该模型能表达主信号全部外观；rail/ground 都只改变附属显示位置，
删除只删除精确附属设备，不删除主信号。
进路表示器控制类型复用有向保护入口解析，列出该入口全部精确既有表示器；右键只替换其模型和 rail/ground placement，保留
`noRouteModelVariant` 及完整 route→variant 映射，删除只删除该表示器。当前没有已确认的映射作者来源，空入口新建会明确拒绝，
不能用 `default`、同一个 variant 或 route ID 猜测映射。
设置页回调必须重新读取主手物品，确认 exact `itemId + stateRevision`、权限、插件状态和当前 catalog 项；成功使用
`Math.addExact` 增加 revision 后写回。普通点击捕获完整物品状态，所有返回玩家 owner 的 continuation 都要求 exact 相等；
即使先切走类型再切回来，revision 变化也会使旧操作失效。

主手持有 Signal Wand 时，每位玩家最多运行一个 entity-scheduler 周期任务，约每10 tick刷新 action bar，显示
放置／删除模式、当前 `signalType`、presentation model 和绑定状态。catalog 未就绪或所选项已删除时明确显示 unavailable，
但不静默重写物品。
join、切槽、换手、背包点击、丢弃、拾取和交互后在玩家 owner 上刷新；离手、权限/插件/玩家失效、entity retired、quit
或插件关闭时取消。状态栏任务与一次编辑 session 相互独立；它只是 SimpleSignals 工具显示，不是宿主级 action-bar 仲裁 API。

### 命名轨道摆放预设的设置页接线（CURRENT）

本批只把已有命名预设接到Signal Wand，不新增渲染器、任意角度输入或共享样式继承。主信号从当前`signalType`的catalog模型取得预设，附属信号/既有进路表示器从当前`presentationModelId`的model-only catalog取得预设。设置页按预设名稳定排序并列出清除选择项；提供不可变的模型身份、名称、局部`ModelPose`及已确定的倾斜值，不把模型树交给玩家UI。

选择在玩家owner重新核对完整物品状态和当前catalog项，再将当时参数复制到物品偏好（概念形状：`modelId + name + adjustment + followRailTilt`），增加现有stateRevision并使旧continuation失效。显式预设的倾斜取预设值，再取其模型默认，再回退竖直；清除偏好保持原有无偏移/竖直轨道摆放行为。偏好随物品保存，不建立玩家全局设置或另一个存档边界，不升未发布PDC/世界schema，也不加旧开发reader。

仅修改设置不改任何已放设备。下一次明确的rail放置/更新以本次选轨位置构造`RailAttached`，调用既有`applyPreset`复制保存的值，继续走原manager patch；不重读包中的预设来动态继承。原预设被修改或删除不改变物品中已有复制值和已摆设备；主动选择/重新选择预设时必须在当前表中找到。控制来源、类型或模型改变导致有效模型改变时清除旧偏好，不将前一模型预设静默用于另一模型。模式切换可以保留偏好，逻辑binding仍按原规则处理。

Ground保持方块面捕获的绝对姿态，忽略rail预设。状态栏标明预设只适用于rail。主信号ID、控制区间/入口/进路、lead time、表示器映射和其他设备不因预设改变；旧物品回调、模型/catalog失效及事务拒绝仍保留原磁盘和live状态。

例如选择右偏2米的预设后，包作者把该预设改为右偏3米，已有物品和设备仍保存2米；玩家在设置页重新选择才复制3米。切换到另一个实际模型时清除旧偏好；直接右键方块面则始终使用方块面姿态，不叠加这2米轨道偏移。

预设投影为`RailPlacementModel(modelId, presets)`，`presets`是按名称排序的不可修改参数表；物品偏好为`SignalWandRailPreset(modelId, name, adjustment, followRailTilt)`。查询投影会复制并排序该模型的预设，P个预设成本为O(P log P)，只在设置/放置交互执行，不增加tick扫描。单次应用复制参数不扫描其他设备。必需断言包括选项排序和有效模型来源、PDC round-trip、过期选择拒绝、设置不写定义、复制值不随源改变、ground不变，以及真实manager保存参数且保留逻辑binding和无关设备。既有数值算法测试不复制一套；本批不声称完成自由实例参数编辑、测距或真实客户端验证。

## 6. 删除与失效处理

### 删除进路保留设备与显式重绑

状态：后端定义事务、安全输出及下述Wand删除/保护主信号重绑均已实现并通过独立复核、离线门禁（2026-10-15）。本批由SimpleSignals负责，不修改Core/Basic/ATO，不新增锁或资源管理器。
正常流程为：编辑已有表删除所选进路 → 在同一候选中解除因此失效的设备绑定 → 完整编译、取得原有编辑许可并保存 → 玩家选中保留设备、选择新入口/进路并确认重绑。
删除最后一条进路保留同ID的空表；现有catalog将该区域标为不可用并禁止穿越，不能删除空表恢复自由选路。
这约束的是“删除进路”操作；管理员明确通过原有低层patch删除整张进路表属于移除固定进路策略，不在这里新增禁止规则，也不暗中重建被明确删除的表。Wand删除进路不得使用该入口。

保护主信号的`bindingId: String?`以null明确表示未绑定；有值时仍须匹配真实作者进路/区域边界入口，不接受拼错的ID。删除进路不等于删除物理入口，因此当前入口信号和表示器保留仍存在的入口绑定；表示器`entryBoundaryId`不改为可空。
未绑定固定进路信号保留其kind，不能把null当成“当前入口”或任选进路。主信号无可用观察根时复用已有Unavailable安全输出；附属设备仍引用原主信号。
入口没有可用进路时，表示器使用自己的无进路外观，不建立行车限制；首个baseline也必须有此结果，不能依赖模型默认场景。模型和摆放仍独立保留，不能沿模型位置猜测逻辑绑定。

事务在patch合并之后比较旧、新作者表，确定真正删除的进路；只规范化保留且未显式upsert的设备：

```text
removedRoutes = oldAuthoredRoutes - newAuthoredRoutes
for retainedDevice not explicitly upserted:
  fixed route removed -> binding = null
  indicator -> remove only deleted-route mappings
  if changed -> checked device revision + 1
compile candidate using normal strict reference validation
```

清理表示器映射只移除已删除进路，其他映射、无进路外观、设备ID、类型、摆放、提前时间及附属关系保持不变。明确upsert的设备由调用者提供合法新绑定或null，不能由规范化吞掉错误引用。
重绑仅以选中设备的精确ID/revision更新逻辑绑定，不改模型位置；新目标必须当前有效，同名ID以后重新出现也不能自动重新绑定null设备。
候选构造、比较和编码在worker完成；最终提交沿用原base/topology核对和原子提交。受运营依赖、动态claim或编辑冲突保护时拒绝，失败不改磁盘、source或publication。
revision溢出明确拒绝，不允许半解除绑定。空表恢复需显式录制/添加合法进路，不自动生成替代路线。

新增比较及清理成本随进路、设备和映射引用总数线性增长，只发生在低频编辑，不新增tick扫描。完整编译和写盘仍有既有规模成本。
例如删除A而B仍存在：绑定A的固定信号未绑定，当前入口信号继续检查B，表示器只移除A映射；删除最后的B后入口设备保留真实边界绑定，但无可用进路、表示器显示无进路且区域禁行。
必须验证真实manager删除、无关设备不变、明确未知引用拒绝、空表阻断实际规划、重启保留null与空表、Unavailable输出不复用旧许可、附属关系保留，以及编辑许可/保存失败原子性。
回归入口为`SimpleSignalsWorldRuntimeManagerTest`及`ProtectionRouteTableCatalogTest`：前者覆盖删除、清空、重载、相同route ID重建不自动绑定及显式重绑，并检查主信号/附属外观；后者先证明无限制时正反路线可达，再证明空表双向拒绝。manager场景的准入、净空及平台调度使用已有fixture，不宣称验证真实运行资源申请或客户端画面；真实JS、定义事务、编译器、store和Basic寻路实际执行。
Wand批次另验证设备选择、目标选择、确认前无写入、过期回调和只改逻辑绑定；尚未接线时不能宣称游戏内可操作。

本后端批次直接修正未发布current sidecar，保持schema 1，不修改PDC或新增旧格式reader。禁止“允许所有未知ID”或“删除设备/空表”作为实现捷径。

### Wand删除进路与保护信号重绑

状态：交互已接线并通过独立复核和离线门禁（2026-10-15）；真实服务器/客户端尚未验证。复用既有原生设置/选择页、worker和玩家owner接续，不增加工具模式、PDC字段、存档边界或新的编辑管理器。
进路Wand原有左键入口、潜行左键出口、普通右键录制/替换不变；选中区域后Shift+右键列出该区域作者表内的进路（按稳定ID排序），选择精确进路后另行确认删除。列表不按当前道岔选通路径过滤，删除不要求该路径当前连通。
`CurrentRouteRecording`在worker准备删除选择并捕获runtime、publication、topology及表/进路身份；确认后检查仍是原基线，只upsert保留ID的表、checked递增表revision，最后一条也保留空表。设备解除绑定及映射清理由现有manager完成，工具不复制该算法。

Signal Wand设置页增加“重绑已有保护信号”：worker从当前世界定义列出固定进路/当前入口主信号，按signalId排序，含bindingId为null、无placement或ground摆放的设备。选中设备后，固定进路类列出当前可用进路，当前入口类列出拥有可用进路的入口；按钮明确显示目标入口及进路身份，再经确认页提交。
后台接口在既有`SignalWandBackend`增加不可变的设备/目标选择值及重绑方法，`RuntimeSignalWandBackend`复用capture与唯一manager事务；不调用`place`，只checked递增所选设备revision并修改bindingId。控制kind、signalType、提前时间、placement及附属关系保持不变，不读取Wand当前模型/预设去重写设备。新入口设备与表示器映射作者仍属于下一批；表示器跨入口重绑不能在缺少映射选择时猜测variant。

私有工作线程入口：`CurrentRouteRecording.deletionChoices(selection): List<RecordedRouteDeletion>`与`delete(choice, active): String`；选择值持有不可变selection、publication、topology、table、route，不把运行时选择重新解释为另一条进路。
重绑接口为`rebindDevices(capture): List<ProtectionSignalRebindChoice>`、`rebindTargets(capture, device): List<ProtectionSignalRebindTarget>`及`rebind(capture, device, target, active): SignalWandOutcome`；device保留完整不可变设备定义用于精确修订核对，target明确保存entryBoundaryId和bindingId。返回值沿用既有工具结果/诊断，不新增公共API。

```text
owner确认权限/世界/仍持原工具 -> worker捕获候选与精确修订 -> owner显示选择
  -> 设备选择/目标选择/最终确认均核对原continuation与工具状态
  -> worker核对runtime/publication/topology及所选设备/目标仍有效
  -> 原manager patch（含active guard） -> owner回复
```

对话期间不写定义；取消或直接关闭不提交，也不能永久占住busy状态。重新打开、切工具/物品状态、离线、换世界、卸载、停用或旧回调迟到时，沿用既有会话/token清理；提交guard只读会话原子状态，不跨线程读取Bukkit。捕获后任何相关基线变化均拒绝，不自动重试旧意图。
未知/不匹配ID、过期revision、不可用新目标、溢出或事务拒绝时保留原状态并报告既有诊断；同一绑定无需写盘或推进revision。选择列表是在低频操作上扫描并排序世界定义，设备/路线数量D/Q时约O(D log D + Q log Q)，不增加tick扫描；完整候选编译仍保留既有成本。

必需断言：原录制手势不变、只列所选区域真实进路、确认前零写入、取消/重复点击/过期回调不提交；真实manager验证删除最后一条保留空表、设备保留、重绑只改binding/revision、地面/未绑定设备可恢复、不可用目标和编辑保护拒绝。平台调度替身与真实manager/store测试分开报告，不称为Paper/Folia或客户端验证。
当前回归使用两个handler测试及`SimpleSignalsWorldRuntimeManagerTest`：平台层验证多页确认和失效接续，manager层调用真实删除/重绑后台方法、store及Basic registry，检查实际静态依赖和动态claim阻止编辑；不为测试放宽无轨道影响的候选校验。完整SimpleSignals 48套517项通过，具体命令、快照与未验范围归任务`implement.md`。
错误做法是通过右键重新摆放实现重绑，或打开列表就保存；正确做法是独立选择和确认后用原事务提交逻辑变更。

信号工具在 catalog 未就绪、信号类型或 presentation model 未知、入口不唯一、生成 ID 碰撞、revision 溢出、捕获的 catalog／runtime／
publication／topology 过期或 manager patch 被拒绝时都只返回失败；不得改变 sidecar、live publication、inspection source、
JS 输出或模型 handle。PDC 中即使仍有格式合法但已删除的 `signalType`，也必须由当前 catalog 和候选编译拒绝，不能视为授权。
删除固定闭塞主信号时，同一个 patch 还删除所有 `primarySignalId` 直接引用它的附属设备；区间、其他入口主信号及其附属设备不变。

删除闭塞分界点会在同一事务删除两侧相接闭塞；删除区间本体只删除选中的闭塞。
闭塞的主信号及附属、中继显示一并删除；其他信号若依赖已删除目标，保留配置但结果不可用，不能丢掉依赖后放行。
闭塞分界点与保护边界重合时，必须先删除闭塞一侧的角色，不能一次点击删掉整个咽喉。

删除保护边界只进入未完成状态。原区域、进路表、信号和摆放继续存在；规划禁止经过其原有范围，
检查绑定和直接资源申请也明确拒绝该范围，不能只阻止寻路而允许已冻结路线申请通过。
补齐边界后逐条检查保留进路；旧进路表版本不等于自动有效，只有满足当前区域和物理路径的进路可恢复。
已有资源仍由 Basic 的车尾释放机制管理，不因为编辑状态或方向失配被主动清除。

## 7. 成本与限制

闭塞路径成本随经过轨道数线性增长；有限环线由访问集合终止，不套用保护区域的距离上限。
保护区域搜索为局部图上的加权搜索，约 `O((V + E) log V)`；作者边界分组/排序另按边界数量计算。
候选整体编译与写盘沿用现有低频事务，仍不是全量增量编译。本工具不增加每 tick 扫描，也不产生新的列车资源管理器。
失效区域按轨道建立禁行范围索引，准入只检查与冻结路线相交的范围。
固定闭塞入口解析先用 `blockIdsByRail` 找到命中轨道的 `K` 个候选闭塞，再各检查正反入口，成本为 `O(K + S)`；
保护入口解析用 `regionIdsByRail` 找到该 rail 的 `G` 个区域，再检查其 effective boundaries，成本为 `O(G + B + D log D)`，
其中 `B` 是候选区域边界数，`D` 是该入口展示的进路／设备数；不运行 graph resolver。catalog 排序只在发放或打开低频设置页时完成；
状态栏每10 tick对当前不可变 catalog 做一次类型存在性检查，点击和 signal epoch 不重新寻路。
附属信号左键在低频作者操作中扫描 `P` 个主信号的已摆放 rail anchor，再对命中的主信号收集 `A` 个附属设备，成本
`O(P + A log A)`；它不进入 tick 或信号求值。进路表示器沿用保护入口索引，再扫描定义中的表示器并稳定排序。
地面摆放只做常数次方块面中心和四元数计算，不查询曲线或全世界轨道；后续候选准备仍复用现有定义规模成本。

## 8. 具体操作情形

连续建闭塞：从旧分界点开始，放下下一点后生成一个闭塞，下一点继续作为后续建造的起点。
咽喉增加边界：先放两个入口但还有未标记支线时，只保存待补全边界；补上支线边界后自动生成区域。
已有咽喉改边界：删除一处后区域停用；在原位置附近补放时恢复原边界身份，不要求重建所有显示设备。
在建线路不被时刻表使用时仍可编辑；被静态路线依赖或动态资源占用的相关编辑返回原宿主拒绝原因。
既有固定闭塞主信号：玩家选择另一 `signalType`，左键逻辑入口再右键实际摆放轨道或方块面；设备 ID 和 inspection lead time 保持不变，
下一 revision 同时切换 JS 逻辑、catalog 模型和实例位置。没有主信号的入口按相同两步操作创建 revision 0 设备，
明确使用0秒“无额外准备提前量”策略；之后的更新同样保留该值。切换模式会清除旧绑定，避免删除操作误用过去的放置目标。
相邻闭塞共用同一点且同方向都可匹配时会报告歧义，玩家必须选择可唯一识别的入口；工具不能按集合顺序修改其中一个。

## 9. 必须验证

两点/接续放置、跨轨道 AB/BA、方向混合拒绝、双向、直向道岔拒绝而保护进路允许道岔；
同轨道吸附且空间相邻的另一轨道不吸附；重合删除顺序、闭塞设备连带删除和外部依赖不可用；
未标记尽头、反向边界、长轨道、分支不累加、环线有界、零剩余边界、重启保留禁行范围和修复身份；
无关线路仍可规划、直接资源准入不能绕过、旧资源不清除、编辑许可/保存失败原子性；
权限、重复点击、玩家/世界失效、提交前取消和异步回复。真实服务类测试与替身平台测试须区分。
信号工具还必须覆盖正反入口、单向反向拒绝、区间内部点击、共享入口歧义、空入口创建、生成 ID 碰撞、catalog 未就绪／未知类型、
稳定默认值、设置页排序/当前标记、marker与完整PDC状态、状态revision溢出、过期回调和工具/权限/世界失效；
状态栏须覆盖模式、绑定、当前/缺失类型、单任务以及离手/retired/quit/close取消，并证明玩家访问只在 entity owner。真实 manager 测试必须断言成功后
`signalType`、placement、JS 限速和模型身份一起变化，并断言编辑许可或准备失败时旧磁盘与全部 live 状态保持不变。
删除测试还须证明主信号和直接附属设备原子消失，而无关区间、设备、磁盘与 live publication 保持一致。
附属信号测试必须覆盖同位置反向主信号排除、新建／换模型改位／精确删除、主信号和附属 revision 过期、未知 model-only ID、
共享设备 ID 碰撞，以及结果只增加显示设备而不增加规则、inspection 或资源。进路表示器测试必须证明既有定义的
`noRouteModelVariant` 和完整 route→variant 映射在改位／换模型后原样保留，过期定义不提交，空入口新建明确拒绝且不写占位映射。
五种控制类型还必须覆盖 ground 值进入同一 manager patch、存档和模型生命周期；handler 测试须证明方块面中心／玩家水平朝向确定、
地面右键不调用选轨服务，rail/air 路径仍产生 `RailAttached`，且所有 Bukkit/Player 读取都留在 owner 线程。

## 10. 存档与后续事项

两个作者字段都有明确默认值，其引入本身不要求升版。写入[世界定义合同](index.md#世界定义持久化)规定的当前schema，
不增加旧开发格式兼容路径或新的保存文件。
手动驾驶不接入新的制动或 ATS。保护区域最大范围只约束保护区域搜索，不限制固定闭塞长度。
轨道事务后的局部失效与重验见[区间局部重验](section-topology-revalidation.md)；
它与玩家直接编辑边界共用区域规则，但不等于已实现分割/合并后的信号坐标迁移。
分界点地面子模型、完整 TextDisplay 叠加、非零 inspection lead time 的实例编辑及进路表示器映射作者界面
继续按后续交互批次推进；三类主信号和附属信号的 rail/ground 两步新建／精确更新／删除，
以及既有进路表示器的 rail/ground 改位／换模型／删除属于当前能力。
测距工具仍为TARGET。已确认沿轨距离自动选择两选点间的物理最短路，不受当前道岔选择限制，不实际扳动道岔，也不申请列车资源；不能把当前选通路径距离冒充最短物理距离。

错误做法：从 PDC 直接信任类型、用类型充当工具 marker、把选择放在命令参数、只替换模型、把0秒误称为临时占位并据此绕过
动态前视／制动或授权 floor，
或绕过 manager 直接写文件和 handle。
正确做法：PDC 分离稳定 marker 与带身份/revision的类型意图；设置和编辑都重新校验完整物品状态及
exact catalog/runtime/publication/topology，通过唯一入口找到
既有设备，再用一个标准 definition patch 原子重编译逻辑、模型、许可、存档和 publication。
