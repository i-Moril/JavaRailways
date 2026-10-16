# 闭塞与保护区域建造工具

## 1. 状态与负责模块

SimpleSignals 拥有工具物品、玩家选点、建造算法和命令；宿主只提供公开的轨道定位、运营方向和编辑许可。
本合同覆盖闭塞建造/删除，以及保护区域几何切口的放置/删除/自动补全；正式区域的入口仍有方向，但由内部推导，不由玩家视线指定。
Signal Wand 已把逻辑入口与模型位置分成两步，覆盖固定闭塞、保护区域当前入口和保护区域固定进路三类主信号，
并覆盖附属信号的完整建造及既有进路表示器的编辑／删除：
放置模式左键绑定入口并明确选择新建或精确既有设备，右键轨道或空气使用轨道附着位置，右键真实方块面使用地面位置，
然后更新位置和类型；删除模式精确选择并删除一台主信号及其直接附属设备。
现已提供[进路Wand删除与保护主信号重绑](#wand删除进路与保护信号重绑)：前者从已保存进路列表确认删除，后者从设置页确认逻辑目标，均不靠重新摆放模型完成。
新建设备的 `inspectionLeadTimeSeconds` 固定为明确策略值 `0.0` 秒，表示不要求信号插件增加额外准备提前量；
SimpleATO 的 inspection threshold 取 `max(reachableDistance(leadTime), dynamicLookahead)`，route perception horizon 也取
controller 制动／反应／安全距离与 `maxSpeed * leadTime` 的最大值，而不是把两者相加。同步信号求值本身没有 setup delay；
因此0秒只是不抬高这两个既有 minimum horizon，固定闭塞授权 floor 和入口停车约束也不消失，所以该值不是临时占位。
命名轨道摆放预设、表示器新建及映射/入口编辑、既有设备实例参数数值编辑已接入设置页和选择流程；[独立测距工具](rail-measurement.md)也已接入。inspection lead time 编辑、拖拽/任意样式参数编辑和完整轨道可视化仍未实现；
进路录制另见[录制合同](current-route-recording.md)。
本批离线门禁与审查结果记录在任务 `implement.md`，不代表真实 Folia 客户端验证。

## 2. 一次建造如何完成

玩家使用工具选中轨道位置。平台层捕获世界、玩家动作、视线和当前定义，异步定位轨道后在 worker 计算修改。
玩家统一用区间工具右键放置无向切口，不选择闭塞或保护区域类型。尚未围合的点保存为待完成边界；只有一个合法内部时自动完成，没有道岔/平交冲突的两边界通路生成固定闭塞，有冲突的完整围合生成保护区域。若切口两侧均有合法内部，保存切口并提示左键内部轨道确认，不能猜选。创建模式左键不再增添切口：命中已有区域时查询警告，否则确认该点所在的待完成内部。区间显示、F模式切换与本地化共用既有能力，不新增每tick判型。
工具只构造 `SimpleSignalsDefinitionPatch`，保存仍调用 world manager 的唯一事务：校验候选、取得编辑许可、
写临时文件、原子替换定义和观测 source。成功后再更新玩家的结果状态并回复，不绕过运营路线与运行占用保护。

## 3. 路径与自动补全的内部方法

先把已保存区间的边界和待完成边界按轨道分组，相同几何切口只参与一次切分。从新增切口的AB、BA两侧分别查找一个内部：普通通路沿当前方向寻找最近切口；遇到道岔或实际经过范围内的平面交叉则使用保护区域围合搜索。候选按内部片段去重并排除已被其他区间占用的内部，不枚举各边界朝向组合，也不能对全世界每个边界分别寻路。访问集合使环线终止，不根据空间相近猜测连接。
找到无冲突对侧边界后，复用BlockPathBuilder按已经选定的一侧构造物理路径、推导两端内向，再一次读取所需轨道的公共运营方向。
全部允许两向则生成双向闭塞；全部与路径一致则保持路径，全部相反则反转路径；混合配置拒绝建造。
普通闭塞仍允许轨道端点边界和超过保护区域半径的长区间，不能在统一时给它附加保护区域范围限制。两条可选环路不能猜选，须点击目标内部确认，不能退回生成无冲突保护区域。确认普通通路时从点击点两侧找最近切口，再构造经过该点的路径；已选定内部不能因另一侧也有环路而被全局唯一通路检查再次拒绝。既有区域的修复始终保留原类型、ID、进路和设备。

保护区域搜索把作者边界视为轨道切口，从选定侧展开。轨道连接直接读取现有拓扑，不在每次点击时重建整张图。
到达另一边界时按到达侧推导内向；遇到未标记尽头、同一切口两侧重连或超出范围，都不视为闭合。
待完成切口和修复草稿使用真正无向的位置值，不保存方向或填写AB占位；正式边界仍保存真实内向。搜索仅在明确探测AB/BA一侧或正式完成时构造有向值。既有区间边界仅作为新区域的只读屏障，按本次到达侧取得内向副本。新保护区域借用邻区边界位置时分配自己的边界ID，不修改邻区和设备引用。
`ProtectionBoundarySearch.search`接收正式有向边界并校验全部方向；`searchCuts`以一个已选择探测侧的起点和无向切口集合寻找闭合内部；`searchInterior`直接接收无向内部点，不再虚构带AB方向的边界ID。三者复用同一个局部切分与距离搜索，未保留旧的`undirectedBoundaryIds`混用参数。
内部确认只搜索点击点所在分量，不对所有待定点重复全图搜索。冲突区域先从内部点两侧探索，以两倍保护半径作为发现范围，再从发现的正式边界用原半径复验；前者不能放宽最终范围。确认重新捕获当前定义和拓扑，不使用之前点击准备的旧候选。
距离使用按轨道长度排序的搜索队列：上限是起点到区域最远位置的最短轨道距离，包含长轨道中部，不把各支路长度相加。
环线因此具有有限范围，不把无限绕行长度当作区域大小。正式候选仍复用现有区域内部与重叠校验。

平面交叉参与围合但不参与可行驶连接：搜索局部轨道在作者边界和交叉弧长处切分，两轨交点通过零距离冲突边连接；每轨一次准备位置、切口、交叉目标索引，不能在每个细分端点重复扫描全部切口/交叉。正式ProtectionRegionGraphResolver同样把实际内部交点所在片段关联到另一轨的对应片段，保证两条互不通车的交叉轨可以属于同一个保护区域；只涉及区域外的交叉不拉入内部。切口正好穿过交叉时会因外侧回连而拒绝闭合。Core物理continuations和ProtectionRouteDiscovery不添加跨线连接，因此四个边界围住两条双向交叉轨时只有四条直行进路，没有转弯。

搜索只在建造worker中运行。局部切分/排序随访问轨道上的边界及交叉数量增长，Dijkstra沿局部片段与连接运行；正式resolver的交叉检查随访问片段及其轨道上交叉候选数增长，不在tick执行，也不新建全世界空间索引。待完成时只保存新边界，不提前发布半个闭塞/区域；最后一次保存将新区间及待定点移除放在同一patch。任一许可、方向、候选或保存失败保留原值。

新区域完整闭合后，在同一 worker 为各有向入口—出口组合自动生成最短合法进路，与区域一起原子保存。
多通路警告标在有关出入口，区域工具左键已有区域内部或可唯一确定归属的边界可查看警告；共用边界不能唯一确定区域时提示点击内部。不自动修改道岔或覆盖修复区域的原表。
工作方法、无合法进路的拒绝及复杂度见[自动进路合同](automatic-protection-routes.md)。

## 4. 数据和单位

### 玩家语言与F模式切换

工具的中英文玩家文案归SimpleSignals自己的`i18n/wands_en_US.properties`、`wands_zh_CN.properties`；读取方式沿用宿主Wand的UTF-8 Properties约定，不依赖宿主内部翻译类。玩家语言为中文时使用简体中文资源，其他语言使用英文；某键缺少中文时回退同一英文键，不从已生成的英文句子反查翻译。console反馈默认英文，后台日志/编译诊断不翻译；作者名称、signalType、模型variant和持久化token保持原值。

正常流程是玩家owner捕获locale，工具选择/编辑在worker中返回消息键及参数，回到玩家owner后渲染为Adventure文字；异步编辑器需要locale时只传捕获的Locale值，worker不得调用Player.locale。item名称/lore、设置/选择页、状态栏和操作结果使用同一catalog。区间几何缓存保留独立说明消息，呈现附近标签时才按viewer语言生成文字，不为翻译重新查图、采样曲线或另建几何缓存。资源每次启用加载一次，普通文字解析只是常数次键查询及按参数长度格式化。

统一区间工具按F切换创建/删除，类型自动判断，不循环四种旧组合。信号工具仍按F切换放置/删除，沿用设置页状态更新，递增原有stateRevision并清除失效binding；进路和测距工具没有新增模式。输入调用宿主[公共Wand helper](../../bukkit-plugin/backend/interaction-render-persistence.md#共用wand按f切换模式current)，只响应当前主手且取消真实换手。消费者验证权限、撤销旧警告/异步接续，再保存物品状态；区间模式与最近判型通过原overlay刷新在状态栏呈现，仅查物品/会话，不搜索拓扑。F或潜行清理本地接续不删除已保存的待定边界，删除须明确执行。普通物品仍正常换手，切模式不改世界定义。

例如保存首个待定边界后，中文玩家收到“已保存边界，继续围合”的提示，英文玩家收到对应英文提示，两者复用相同消息身份；这不是旧的firstPoint临时选点。自动生成的UUID继续隐藏，作者自定义名称原样插入。语言资源丢失属于打包错误，首次加载catalog时明确失败，不静默使用空catalog；未知底层异常保留原诊断内容，但玩家提示外层按locale显示。既有工具物品在重新生成/更新文案时使用当前玩家语言，不修改PDC字段或增加旧数据迁移/版本号。必要检查为中英键/参数、直接模式切换与旧编辑失效，不复制整个UI流程作大型测试矩阵；真实按键与客户端显示另验。

有向轨道位置使用 `RailPositionSnapshot`：rail UUID、canonical AB 米数、朝向；正式保护边界朝向指向内部。统一区间工具的输入及几何切口使用SimpleSignals私有`RailCutPosition(railId, arcLengthInAbMeters)`，只有canonical AB米数，不携带朝向。`ProtectionBoundaryCut(boundaryId, position)`为具稳定身份的无向切口，位置有限且非负、ID符合既有namespaced规则；pending和修复草稿使用它，正式`ProtectionBoundaryDefinition`不变。它们不是Core公开API，也不新增一套拓扑。
命令为 `/simplesignals wand section [create|delete]`，默认create，权限`simplesignals.admin`。旧block/region分开的命令与四种工具状态不再保留；开发中的旧物品重新领取，不增加兼容reader。
删除沿用明确的左右键行为：左键闭塞边界删除相邻闭塞及附属显示，保护边界保留；右键闭塞内部只删除该闭塞，否则按空间位置处理保护/待定边界。唯一目标删除不依赖视线；同位置属于多个保护区域时明确拒绝“不能唯一确定删除目标”，本批不提供多步删除选择会话，不能按定义列表顺序删任意一侧。创建、删除及区域警告查询均不要求沿轨道向内观察。删除保护边界保留原区域、进路和设备并进入不可用草稿；共用闭塞/保护边界须先删除相邻闭塞，再单独删除保护边界。
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
只有Signal Wand等确实选择有向入口的工具继续使用视线判定；统一区间工具不再应用0.15视线点积门槛。
配置 `protection-region-max-distance-meters` 默认 1000，要求有限正数，启动时读取。
沿用`pendingProtectionBoundaries`保存尚未组成正式区间的无向切口，默认空列表；它们本身不是已生效区间。每项持久化只有`boundaryId`、canonical UUID字符串`railId`、米制`arcLengthInAbMeters`，不读写`insideFacing`。名称不因工具合并而改schema。待定点允许位于轨道端点，以保留普通闭塞能力；正式保护区域边界仍须严格位于轨道内部，区域重验不混入端点待定点。
闭塞分界点由各闭塞路径两端取得，不另存一批无人引用的空点。

已有区域的 `boundaries` 保留上一次认可的完整有向边界；可空的 `boundaryDraft` 保存编辑期间当前放置的无向切口，持久化项与pending相同，不含`insideFacing`。
null 表示没有未完成边界编辑，空列表表示全部边界被删但区域和配置仍保留。
保留原完整边界是为了重启后仍知道应禁止 ATO 进入哪里，不是另外存储一份派生拓扑图。
补全成功后用新边界替换完整边界并清除 draft，区域 ID 不变。
几何显示、选区和编辑影响读取派生`effectiveCuts`（draft存在时用draft，否则投影正式边界的位置）；需要有向入口的编辑消费者只读取仍放置的已认可入口`placedEntrances`。新草稿切口不能伪装成已生效入口，完整认可边界不得因移除草稿切口而从禁行依据中消失。两个视图均不持久化，不建立第二份权威定义。

## 5. 编辑和线程规则

Wand 编辑流程的图搜索、候选编译、JSON 和文件 I/O 均在 worker；玩家物品和回复在玩家所属线程。
捕获 runtime 对象、定义版本和本次拓扑；世界卸载重载、外部编辑或失效玩家动作不能提交旧选择。
成功提交只替换已经准备好的状态。定义比较和保存仍可随定义规模增长，但不阻塞物理线程等待平台交互。
同一拓扑 root 的派生连接索引在候选间复用；普通点击不能反复从全世界 rails 重建索引。
统一工具不再保留`firstPoint/firstCapture`两点临时状态；每次动作捕获当前publication，已经保存的待定边界通过世界定义参与下一次计算。定义或拓扑过期时拒绝该次在途动作，不能把旧准备值嫁接到新publication。定义事务进入host原子source callback后仍执行最后一次authoring guard；该guard失效
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
进路表示器控制类型复用有向保护入口解析，列出明确新建设备及该入口全部精确既有表示器；已有设备右键只替换模型和rail/ground placement，保留无进路外观及逐进路映射，删除只删除所选设备。新建右键捕获位置后进入下述映射编辑流程，最终确认才保存；不能用default、统一variant或route ID猜测映射。
设置页回调必须重新读取主手物品，确认 exact `itemId + stateRevision`、权限、插件状态和当前 catalog 项；成功使用
`Math.addExact` 增加 revision 后写回。普通点击捕获完整物品状态，所有返回玩家 owner 的 continuation 都要求 exact 相等；
即使先切走类型再切回来，revision 变化也会使旧操作失效。

主手持有 Signal Wand 时，每位玩家最多运行一个 entity-scheduler 周期任务，约每10 tick刷新 action bar，显示
放置／删除模式、当前 `signalType`、presentation model 和绑定状态。catalog 未就绪或所选项已删除时明确显示 unavailable，
但不静默重写物品。
join、切槽、换手、背包点击、丢弃、拾取和交互后在玩家 owner 上刷新；离手、权限/插件/玩家失效、entity retired、quit
或插件关闭时取消。状态栏任务与一次编辑 session 相互独立；它只是 SimpleSignals 工具显示，不是宿主级 action-bar 仲裁 API。

### 命名轨道摆放预设的设置页接线（CURRENT）

本节描述把已有命名预设接到Signal Wand，不新增渲染器或共享样式继承；数值输入属于下节独立的实例编辑。主信号从当前`signalType`的catalog模型取得预设，附属信号/既有进路表示器从当前`presentationModelId`的model-only catalog取得预设。设置页按预设名稳定排序并列出清除选择项；提供不可变的模型身份、名称、局部`ModelPose`及已确定的倾斜值，不把模型树交给玩家UI。

选择在玩家owner重新核对完整物品状态和当前catalog项，再将当时参数复制到物品偏好（概念形状：`modelId + name + adjustment + followRailTilt`），增加现有stateRevision并使旧continuation失效。显式预设的倾斜取预设值，再取其模型默认，再回退竖直；清除偏好保持原有无偏移/竖直轨道摆放行为。偏好随物品保存，不建立玩家全局设置或另一个存档边界，不升未发布PDC/世界schema，也不加旧开发reader。

仅修改设置不改任何已放设备。下一次明确的rail放置/更新以本次选轨位置构造`RailAttached`，调用既有`applyPreset`复制保存的值，继续走原manager patch；不重读包中的预设来动态继承。原预设被修改或删除不改变物品中已有复制值和已摆设备；主动选择/重新选择预设时必须在当前表中找到。控制来源、类型或模型改变导致有效模型改变时清除旧偏好，不将前一模型预设静默用于另一模型。模式切换可以保留偏好，逻辑binding仍按原规则处理。

Ground保持方块面捕获的绝对姿态，忽略rail预设。状态栏标明预设只适用于rail。主信号ID、控制区间/入口/进路、lead time、表示器映射和其他设备不因预设改变；旧物品回调、模型/catalog失效及事务拒绝仍保留原磁盘和live状态。

例如选择右偏2米的预设后，包作者把该预设改为右偏3米，已有物品和设备仍保存2米；玩家在设置页重新选择才复制3米。切换到另一个实际模型时清除旧偏好；直接右键方块面则始终使用方块面姿态，不叠加这2米轨道偏移。

预设投影为`RailPlacementModel(modelId, presets)`，`presets`是按名称排序的不可修改参数表；物品偏好为`SignalWandRailPreset(modelId, name, adjustment, followRailTilt)`。查询投影会复制并排序该模型的预设，P个预设成本为O(P log P)，只在设置/放置交互执行，不增加tick扫描。单次应用复制参数不扫描其他设备。必需断言包括选项排序和有效模型来源、PDC round-trip、过期选择拒绝、设置不写定义、复制值不随源改变、ground不变，以及真实manager保存参数且保留逻辑binding和无关设备。既有数值算法测试不复制一套；预设与下节实例编辑独立，均不代表测距或真实客户端验证。

### 实例参数数值编辑（CURRENT）

2026-10-15用户批准的本批由SimpleSignals负责：设置页“Edit existing device placement”选择已摆放的固定闭塞主信号、保护主信号、附属信号或表示器，再输入数值、检查摘要并确认。设备身份与revision来自当前定义，不从Wand当前模型偏好推断；尚未摆放的设备不创建占位位置。离线检查结果归任务记录，不代表真实Paper/Folia对话框和客户端验收。
轨道设备编辑有向局部X前/Y上/Z右偏移（米）、模型自身XYZ旋转（度）及跟随轨道倾斜；保留rail、AB弧长和方向。地面设备编辑绝对世界XYZ及旋转，不接受轨道倾斜字段。旋转顺序沿用模型包的XYZ角约定，写回现有单位四元数；只改位移/倾斜时保留原旋转，不能用Euler往返造成无意重写。

```text
原工具会话 -> worker捕获当前定义/设备 -> owner显示数值输入
  -> 校验有限数值并构造现有placement值 -> 显示确认摘要
  -> worker核对原设备及基线 -> 唯一manager patch -> owner回复
```

仅替换选中设备placement及checked递增revision；模型、逻辑binding、提前时间、附属关系和表示映射保持不变。保持现有capture/continuation、工具身份、权限和最终active guard；取消/过期/非法输入不写盘，同配置不推进revision。输入和页面仅为对话局部状态，不新增持久化字段、manager或schema版本；使用已有候选编译、编辑许可、显示姿态计算与handle保留，不新增tick扫描。
具体私有入口为`placedDevices(capture): List<PlacedSignalChoice>`及`savePlacement(capture, device, placement, active): SignalWandOutcome`；选择值包含kind、id、revision和原placement。后台复核所选值仍属于原publication，明确禁止更换rail anchor/facing或ground/rail类型，再按四类定义只替换placement。表示器使用原public构造保留其不可变映射，不放宽定义封装。
`PlacementWandEditor`只保存本次回调的输入和确认值；`PlacementWandInput`包含字符串fields（x/y/z/rx/ry/rz）及可空followRailTilt，null仅表示ground无该选项。Paper文本框保留double精度，不使用滑条。输入要求六项均可解析且有限；角度转弧度后调用既有JOML `rotateXYZ`，未改三项角度时直接复用原旋转分量，包含Euler奇点。输入失败结束本次编辑并报告字段，不保存部分值；host编辑拒绝透传既有结果，磁盘/source/publication保持原值。
设备选择为低频定义扫描及稳定排序，数值转换为常数成本；完整候选/写盘沿用已有成本。测试须验证四类设备的真实保存与重载、逻辑字段及无关设备保持、rail/ground几何语义、未改旋转精确保留，以及确认前零写入、取消/过期/非法数值、重复回调和无变化不保存。真实Paper/Folia与客户端验收另记。
错误做法是复用重新放置操作连带换模型/绑定，或将ground位置当作轨道偏移；正确做法是明确选中原设备，只编辑其现有摆放值。本批不实现拖拽、轨道坐标/方向编辑、模型任意样式参数、边界叠加或测距。

## 6. 删除与失效处理

### 表示器映射作者与新建

状态：部分映射后端及Wand新建/编辑/跨入口重绑均已实现并通过独立复核和离线门禁（2026-10-15），真实服务器/客户端尚未验证。SimpleSignals负责本批定义校验、模型目录投影及Wand交互，不修改宿主渲染API、资源/ATO或JS规则。
作者为表示器明确选择入口、model-only模型、无进路外观和可选的逐进路外观。映射键只允许属于该入口的作者进路，但不再要求覆盖全部进路；写错或其他入口的ID仍拒绝，已声明variant仍由真实模型严格校验。

运行时保留真实`selection.routeId`：选中且已配置的进路使用对应variant，其余使用玩家明确选定的`noRouteModelVariant`。该外观用于表示无进路指示（包作者可以制作空白外观），不是模型默认场景；不猜测名称、不把null/空场景冒充不可见，也不新增宿主隐藏API或可空运行输出。
仍使用同一section/signal epoch publication和首baseline刷新，不新增遍历/求值循环；添加一条进路即使表示器暂未补配，也允许保存且其他映射不受影响。
`ModelTypeCatalogSnapshot.variantNames(modelId): List<String>?`仅提供不可修改、按名称排序的variant列表，未知模型返回null，不向UI泄露模型树；查询只在低频作者操作执行，V个外观排序成本O(V log V)，运行时单次映射查找不增加扫描。

Wand交互：左键入口可明确选新建或精确既有表示器。新建绑定只保存入口及成对为null的indicatorId/revision意图；已有绑定仍保存精确非空身份/修订，禁止半空身份。右键先捕获rail/ground位置，再显示明确的无进路外观及逐进路映射选择，允许标记“未配置”，最终确认才生成无冲突设备ID并经原manager事务保存完整模型和位置，不预建未摆放定义。未完成映射草稿只保存在本次对话回调，不另建玩家状态仓库。
已有表示器从设置页进入编辑，选择原入口或新入口，再明确外观配置；只更新入口/映射及revision，保留设备ID、模型和摆放。跨入口不得沿用不属于新入口的映射或猜测新外观；页面明确提示未配进路，取消/关闭不提交。
所有页面复用既有WandDialogs及原continuation、itemId/stateRevision、runtime/publication/topology/catalog基线检查；worker做定义/文件工作，玩家owner显示页面和读物品。重复/迟到回调、权限/工具/世界失效、未知模型/variant、revision溢出、编辑许可或保存失败均不修改旧磁盘/source/publication。
具体映射页由`RouteIndicatorWandEditor`持有本次回调局部参数，并以不可变map加减条目，显示未配置数量和每条进路状态。`SignalWandBackend`提供`indicatorDevices(capture)`、`indicatorEntries(capture)`、`indicatorOptions(capture, binding, entryBoundaryId)`及`saveIndicator(capture, binding, entryBoundaryId, noRouteVariant, mappings, placement, active)`；选项包含routeIds、variants、原mappings。新建必须提供真实placement，已有设备编辑禁止传入placement，实际modelId从已有设备取得，不受Wand模型偏好覆盖。同配置不写盘/不推进revision。
物品编码使用同一个严格current `STATE_JSON`，`explicitNulls=true`。表示器身份两字段无decode默认值，writer显式写入null/null表示新建；字段缺失、半空或非法修订均拒绝，不能把损坏的身份省略猜成新建设备。其余有明确默认值的物品字段仍按原义处理，不新增兼容reader或schema代际。

必需断言：部分映射且真实选中缺项进路时使用明确无进路variant、selection身份不丢；其他映射正常；添加新进路无需同步补全映射；错误键/variant拒绝及失败原子性。界面批次另验目录排序/不可修改、明确新建意图、不在确认前写入、rail/ground完整创建、旧设备映射编辑/跨入口不移动、取消/重复/过期回调和PDC新旧意图严格round-trip。
只修正未发布current模型，sidecar/PDC不升版、不新增兼容reader；noRoute字段及映射存档形状保持不变。错误做法是对未配进路getValue抛错或猜default/route ID外观；正确做法是先严格校验已声明引用，再通过显式无进路外观投影未配状态。

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
后台接口在既有`SignalWandBackend`增加不可变的设备/目标选择值及重绑方法，`RuntimeSignalWandBackend`复用capture与唯一manager事务；不调用`place`，只checked递增所选设备revision并修改bindingId。控制kind、signalType、提前时间、placement及附属关系保持不变，不读取Wand当前模型/预设去重写设备。表示器跨入口重绑采用上文独立映射编辑流程，不在此保护主信号接口中猜测variant。

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

### 持工具的边界与区间编辑叠加（CURRENT）

2026-10-16按实际截图反馈简化：主手持闭塞/保护区域创建或删除工具，或Signal Wand时，向该编辑玩家显示附近分界点和连续区间中心线，不画箭头，只在分界处放说明文字。所有区间几何及文字均通过公开EditorTextService提交完整标签列表，由宿主管理临时TextDisplay，不发送区间粒子。共享图案/资源包机制归[编辑标记](../../bukkit-plugin/backend/editor-markers.md#共用轨道平面图案与客户端资源)，不统一迁移其他RailWand或车站标记，也不移除RailObjectWand专用方向箭头。进路工具原有通路预览和独立警告保持不变，显示不是许可；真实客户端仍需验收。

```text
玩家owner捕获主手工具/世界/位置 -> worker捕获定义和拓扑基线并准备几何
  -> 复核仍持支持工具与同一世界 -> 默认隐藏、仅编辑玩家可见的TextDisplay图案与文字
刷新仅使用准备值；定义/拓扑换代后重新准备，关闭后的迟到结果不得重新显示
```

`SectionWandOverlay`负责持物会话、刷新与玩家owner提交；`RuntimeSectionOverlayBackend.capture/current/prepare`分别捕获输入、检查是否仍有效、准备不可变几何；`SectionOverlayDrawing`只保存不可变标签并作附近筛选。闭塞/保护区域/信号工具间切换复用同一会话、handle和准备缓存，换成其他物品才撤销；命令发放Signal Wand与设置页检查也接入同一入口。缓存以实际world runtime、authoring publication、exact topology和topology guard view的对象身份为基线，任一替换就重新准备；guard变化也会刷新局部重验中的不可用状态，不能只比较定义revision。每个会话最多一个几何准备任务，返回时再次检查同一会话/世界/支持工具及基线，失效结果不提交。

闭塞端点从既有traversal得出，保护边界采用effectiveCuts并标明draft/pending。边界横条宽3米、沿轨长0.3米，按真实切线/法线摆放；不再把入口箭头叠在横条上，也不以start/reverse entry文字重复标记一个共用闭塞分界。相邻闭塞的同轨canonical AB位置合并，跨轨端点由RuntimeSectionOverlayBackend使用已捕获TopologyState的普通连接或degree-two LoopAnchor取一致端点身份；中段位置不归并，空间重合但未连接的轨道不合并。只查询已发布常量邻接，不重建图或扫描世界。共用分界只画一条横标记及一份文字，任一相邻闭塞不可用则分界为不可用；保留单一作者名称，多个区间名称不堆叠显示。保护区域边界保留其自身状态说明。缺失/缩短曲线跳过失效点并明确不可用，不将其当作合法几何；正常AQUA、不可用RED，颜色不是灯色或放行依据。
复用既有64米编辑范围及20tick刷新，所有实体仅相关玩家可见。低频捕获变化才重做曲线采样；可线性筛选已准备的附近标记，但不得每帧重建世界拓扑或重新解析信号包。完整准备和缓存空间随作者边界/片段/标记数量增长，不新增业务规模上限或第二套线路注册表。实体差量更新由宿主处理，不再保留旧512粒子轮转预算或每个旧粒子一个实体。
区间中心细线宽0.12米，按每段不超过1米弧长取样，相邻样本的真实坐标差给出弦方向、长度及中点，两端法线平均后交给共用helper准备姿态。使用实心COVERAGE字形拼接短直段拟合曲线，不以长切线矩形或8米间断图案代替。内部每侧接头至多重叠5毫米（且不超过弦长的1%）遮蔽法线抬升产生的微小接缝，片段整体两端不延长；正向/反向/双向区间均只描一条中心线。区间内部不再生成箭头或重复文字。

保护区域的中心线只描绘仍可确定出入口的已保存进路，不再把旧区域完整interior当成显示路径。准备时把作者进路表按regionId分组，用`placedEntrances`的ID集合筛选入口、出口均仍存在的进路；该视图同时核对切口位置与认可边界一致，同ID挪到别处不能继续显示旧路径。将合格进路的traversals投影为canonical AB片段，按rail/start/end排序，严格重叠或相接时求并集，再复用现有coverage采样；正反路线或多路线共用一段只画一次，不用容差跨过未覆盖间隙。没有表、空表或没有合格进路时无内部中心线，不回退到完整interior，边界标记仍保留。
例如保留A—B和A—C两条进路但删除D边界时，涉及D的路线隐藏，两条保留路线的共用干线继续显示一份；删除所有出入口后不再有内部线。草稿及失效区域仍用不可用颜色，显示进路不表示它恢复放行；认可interior继续用于原缺失几何/状态判断和运行保护，不因显示缩小而解除禁行。不能为显示重跑最短路或修改进路表。

几何准备和缓存空间为O(采样线段数+作者边界数)，闭塞边界聚合用map完成；区域额外按合格进路的P个traversal排序求并集，约O(P log P)，工作临时空间O(P)，入口匹配和表分组另随定义数量线性增长。此工作只在既有worker缓存准备时执行，附近筛选仍为O(已准备标签数)。几何准备期间同一轨道曲线只查询一次，移动或定时刷新不重新采样。说明文字仅表示定义/draft/pending/不可用，不表示占用或放行；生成UUID仅用于稳定标签身份，不出现在玩家文字、状态栏、lore或按钮里，作者命名可以保留，多个自动命名设备用当前选择页的局部编号区分，不存新显示身份。
加入/持物变化/命令发放可启动检查，但只有主手持合法工具且有权限时显示。离手、退出、切世界、世界卸载、失去权限及插件停用须关闭刷新并移除临时文字；实体创建、显隐和删除分别遵守location/entity/player owner，worker只传不可变显示值。关闭必须使已排队spawn失效并清理已创建实体；插件停用的调度可用性需在实现中明确，不吞掉清理失败冒充完成。
文字关闭使用宿主调度，插件将`EditorTextService.closeOwner`与既有模型清理合并为非阻塞完成结果，不另建停用管理器。缺少runtime或曲线时显示明确的不可用提示，不编造几何；工作线程准备失败只返回本地错误说明，不影响定义、信号或列车授权。例如持工具移动到长闭塞中部只筛选缓存，编辑其定义后才重新采样；将错误当作空区间或在每次移动时重新搜索拓扑均不符合本合同。
会话同时保存具体Player与World实例；旧玩家退出/退休/排队检查只能清理自己的会话，不能仅按UUID关闭同UUID重连玩家的新显示。世界卸载也按实际World实例清理。
显示调整只保留直接行为回归：无区间箭头/中段文字、相邻闭塞分界去重及真实连接身份；进路中心线另验删边界只隐藏相关路线、共用/反向片段不重画、无表/无存活路线不画旧interior、同ID移动切口不复用旧路径，以及草稿不可用状态保持。已有状态、缓存和清理测试随行为更新，不重复扩充。不要为纯显示重算每一段姿态、复制渲染算法或反复运行整模块套件；弯道/坡道的实际连续性与可见性留给客户端验收。实心COVERAGE遮罩需完整不透明，方向与边界资源不变。无存档字段、schema/PDC版本、资源申请或授权变化，不为纯显示另建通用资源或物理状态总线。

## 7. 成本与限制

闭塞路径成本随经过轨道数线性增长；有限环线由访问集合终止，不套用保护区域的距离上限。
保护区域搜索为局部图上的加权搜索，约 `O((V + E) log V)`；作者边界分组/排序另按边界数量计算。
无向创建至多检查新增切口两侧，内部确认至多执行一次所在分量发现及一次正式边界复验；不随待定切口数量重复整次图搜索。`SectionBoundaryComponents.beside(cut)`返回去重后的闭合分量，`containing(point)`返回点击点所在分量或null；每个分量包含派生有向边界、内部片段，以及普通闭塞适用的可空物理路径。`SectionAuthoringActions.confirmSection`与放置操作一样只构造patch；无闭合分量、点在切口上或共享删除目标不明确时返回本地化提示及空patch，backend不为提示写盘。
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

统一工具待定点保存/重载后围合、无冲突自动闭塞、道岔/交叉自动保护区域、跨轨道 AB/BA、轨道运营方向混合拒绝、双向；
普通闭塞端点与长区间不受保护半径误限、最近切口优先、交叉各腿完整围合且不跨线转弯；
同轨道吸附且空间相邻的另一轨道不吸附；重合删除顺序、唯一保护边界反向观察也可删、多保护区域共享切口拒绝、闭塞设备连带删除和外部依赖不可用；
无向切口交叉渡线围合、多个内部提示及点击消歧；pending/draft编码不含insideFacing且旧字段严格拒绝、正式边界方向保留；未标记尽头、正式边界外侧回连、长轨道、分支不累加、环线有界、零剩余边界、重启保留禁行范围和修复身份；
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
无向切口直接修正未发布current DTO并保持schema 1：pending/draft不再包含insideFacing，strict codec拒绝旧开发字段，不加reader、alias、upcaster或静默删字段。正式区域的边界方向和信号绑定原样保留。开发服旧定义若需保留，应在单独授权的部署准备中整目录打包备份，再一次性迁移pending/draft项；本地改代码不自动触碰服务器。错误做法是为避免迁移而永久写AB占位，正确做法是current无向模型与正式有向边界各表达真实语义。多保护区域共享切口的显式删除选择仍未实现，本批只拒绝歧义，不宣称支持全部共享切口删除。
手动驾驶不接入新的制动或 ATS。保护区域最大范围只约束保护区域搜索，不限制固定闭塞长度。
轨道事务后的局部失效与重验见[区间局部重验](section-topology-revalidation.md)；
它与玩家直接编辑边界共用区域规则，但不等于已实现分割/合并后的信号坐标迁移。
分界点地面子模型、全轨道/车站标记的统一显示及非零inspection lead time实例编辑继续按后续交互批次推进；持工具的闭塞/保护区域文字叠加已接入。三类主信号、附属信号和表示器的rail/ground新建/精确更新/删除，以及表示器映射/入口编辑属于当前离线验证能力，不等同现场验收。
[测距工具](rail-measurement.md)已提供直线与沿轨距离，自动选择两选点间的物理最短路，不受当前道岔选择限制，不实际扳动道岔，也不申请列车资源；不能把当前选通路径距离冒充最短物理距离。

错误做法：从 PDC 直接信任类型、用类型充当工具 marker、把选择放在命令参数、只替换模型、把0秒误称为临时占位并据此绕过
动态前视／制动或授权 floor，
或绕过 manager 直接写文件和 handle。
正确做法：PDC 分离稳定 marker 与带身份/revision的类型意图；设置和编辑都重新校验完整物品状态及
exact catalog/runtime/publication/topology，通过唯一入口找到
既有设备，再用一个标准 definition patch 原子重编译逻辑、模型、许可、存档和 publication。
