# Stage 2: SimpleATO extraction and SimpleSignals

## Goal

Finish separating the operational ATO stack from the JavaRailways host, then implement the first independently deployable Java/Kotlin signal-system plugin using only the completed Stage 1 observation, resource, restriction, inspection, applied-plan dependency and lifecycle contracts.

The result must prove that protection regions and fixed blocks are optional policy supplied by a concrete signal system rather than universal Basic/SimpleATO concepts.

## Confirmed facts

- Stage 1 is complete and archived. It provides the Basic physical-resource floor, plugin-contributed resources, one claim/preparation/grant lifecycle, suffix extension, tail release, compiled inspection bindings, namespaced restrictive outputs, applied-plan edit dependencies and one owner-level unload gate.
- `SimpleATO` owns its controller, mission/constraint policy, world coordinator/diagnostics, frozen-route/authority client, inspection runtime, signal epoch runtime, restriction adapter, timetable dispatcher, manual navigation and SimpleATO runtime snapshot. The legacy `AtoProvider` registry/boundary switching path has been deleted; signal extensions use inspection plus namespaced restrictive outputs. Destination, Station, Depot, Line, Timetable and rolling-stock assignment also belong to SimpleATO. Basic owns public rail operating-direction authoring/storage/persistence; SimpleATO consumes that configuration rather than retaining a second owner.
- `SimpleATOBukkit` now exists as an independent Gradle source/test module embedded into the JavaRailways main plugin JAR. It is not a second deployable Bukkit plugin and has no separate descriptor, `JavaPlugin` lifecycle or internal service-discovery hop.
- Basic's transitional protection-region catalog, graph-cut resolver, route window and old admission seam have been removed. SimpleSignals now owns the definitions, graph cut, catalog/index and route-window matcher; generic topology projection, resource identities, claims, preparation and release remain in Basic.
- Basic derives a non-removable physical footprint and allows signal plugins only to add validated resources or stricter holds/restrictions.
- SimpleATO owns operating-route selection, resource inspection scheduling and the single most-restrictive ATO/ATP pipeline.
- Generic longitudinal dynamics, actuation contracts and physical safety mechanisms shared by manual driving and automation remain in Basic; SimpleATO owns the automated operating policy that consumes them.
- Each independent signalling system will be a separately compiled Java/Kotlin plugin. It may choose its own JS, YAML or other customization layer; no common script engine is mandatory.
- The Java/Kotlin signal runtime validates and transports a rule-selected speed limit plus opaque model variant; it must not hard-code a national aspect vocabulary or map `R/Y/YG/G`-style names to fixed speeds. Only unavailable/invalid rule state has a host-owned zero-speed safety meaning.
- Operational route selection now uses one SimpleATO seam over Basic's immutable-`TopologyState` planner. Mission, Station, Line, Timetable and route diagnostics all compose operating directions with owner/world registered neutral restriction sources before search; the legacy live-`RailView` planner has been deleted.

## Requirements

### Signal Wand Dialog与灯头反馈（2026-10-16，灯面先行，UI继续讨论）

用户反馈设置界面不友好，希望先用Dialog UI模拟器讨论，再决定正式交互；同时报告灯头方向反了，并建议灯泡独立模型以单独设置亮度。

- 当前阶段仅制作可点击的离线讨论稿，现有设置首页按源码按钮顺序复现，另提供“建造设置/编辑已有设备”的任务分组候选。样例设备不来自服务器；不是Minecraft客户端像素级截图，不连接世界、不构成正式UI已实现声明。
- 当前设置把模式、五种控制对象、signal type、model-only目录、轨道预设和三项既有设备编辑入口铺在同一列表，每次设置后返回游戏。如何分组、是否批量应用及目录显示仍待用户决定，不能把讨论稿的分组或应用按钮直接写入生产。
- 方向研究核对26.1.2客户端已有JAR，确认ItemDisplay绘制在部件变换后另乘Yπ；正式四灯Y+90与旧测试遗漏此步骤。候选修复仅将本包部件适配改为Y−90并更新真实方向断言，不全局反转通用姿态；具体现场placement/客户端覆盖情况仍未核验。依据见[方向研究](research/signal-model-direction-2026-10-16.md)。
- 灯面拆分复用现有多部件及每部件brightness，无需增加信号身份、attached设备、JS规则或区间扫描。初步候选为外壳自然光+独立亮灯层，以最少部件表达；逐灯独立亮度、亮度数值及是否按实例编辑尚未冻结。brightness只改变显示光照，不会照亮世界。依据见[灯面研究](research/signal-lamp-parts-2026-10-16.md)。

以上为初始研究范围。用户随后批准先实施灯面拆分：正式包采用自然光外壳/暗灯与15/15亮灯层两个Item叶子，复用既有多部件、brightness和lamp场景；不改JS、通用运行时、方向、版本、原models工作区或ZIP，不部署。定向更新原资产测试，不扩展测试矩阵。

UI按分组方案继续讨论，但已有设备应从右键信号机或轨道进入编辑，不把全世界设备列表作为主要入口。轨道入口可复用选轨与既有保存事务，直接模型点击尚缺公开拾取能力；多设备选择及与放置动作的优先级待讨论，不在灯面批次实现UI。见[现场编辑研究](research/contextual-signal-editing-2026-10-16.md)。方向修复单独一笔；已部署的Stage 2游戏验收状态不因此变为完成。

用户随后批准修复方向：正式head共同父Y旋转由+90改−90，原姿态回归纳入客户端ItemDisplay额外Yπ，检查AB/BA及ground的灯面方向和原高度/侧偏。只修包内适配，不改通用坐标算法、实例placement、灯面资源/亮度、JS或版本；更新README/契约，定向验证后单独提交，不部署。手工反转过的旧实例可能需要玩家撤销实例补偿，不新增运行时自动猜测/兼容。

### 区域经路显示、整体删除与设备保留（需求与三笔实施计划已批准）

目标：修改区域边界时让画面反映仍有确定出入口的进路；允许明确删除整个区域，同时可选择保留已经摆放的设备，避免重建模型。

已确认需求与验收：

- 删除保护边界后，仅显示入口和出口均仍放置的已保存进路所覆盖的中心线；涉及被删边界的进路隐藏，共用片段只要仍被保留进路覆盖就显示，不重复叠画。此行为只改变显示，草稿区域不因此恢复放行。
- 删除模式右键区域内部可确认删除整个保护区域，包括零剩余边界的草稿；区域及其进路表整体移除，仍经过原有编辑许可和原子定义事务。确认前不修改定义，过期或受保护的编辑不修改磁盘/发布状态。
- Shift+右键打开区间Wand设置，“保留设备”默认开启，偏好属于工具物品；F仍只切换创建/删除。此选项控制整体闭塞/保护区域删除，单独删保护边界仍属于修复编辑并保留配置。
- 开启保留时保留设备身份、类型、模型、摆放及附属关系，解除被删对象的绑定，清除已删除进路的映射，使用明确不可用/无进路状态；用户可手动重绑，不自动按位置或同名ID绑定新对象。关闭时整体删除连带删除关联主信号、附属设备和表示器，不影响其他区域设备。
- 现有保护主信号可空binding/重绑继续复用；闭塞主信号和表示器需补齐相同的显式未绑定语义及必要重绑入口，不能保留悬空旧ID。中英文设置、确认和结果与实际行为一致。

非目标：既有单个AD区间的直接拆分/合并、新资源管理器、设备回收站、自动重绑、运行时旧开发格式兼容、全仓审查或性能基准。世界定义保持schema 1，不因本开发批次升版。本批只本地实现、定向验证和小批提交；部署及开发服数据操作须另行授权。

需求决策已收敛，默认保留及解除绑定语义、三笔实施计划均已由用户确认；具体顺序见implement.md。按小批实现、独立复核及提交推进，不包含部署。

本子批次三笔离线实现及复核已完成，定向验证与实际执行范围见implement.md；未部署或完成玩家验收，Stage 2继续in_progress。新工具采用严格物品身份/修订状态，旧区间Wand重新领取，不增加旧开发格式reader。

### 统一区间工具与自动判型（2026-10-16，离线完成，未部署）

闭塞与保护区域合并为一个区间Wand，玩家不手选类型，只划定几何切口；未围合时保留待完成边界，围合后内部没有道岔/平面交叉冲突则生成普通固定闭塞，存在冲突则生成保护区域并沿用自动进路。F只切换创建/删除，状态栏显示模式与自动识别结果，界面保持中英文。删除按真实选中对象处理，保护区域原有保留进路/设备的修复语义不变。

2026-10-16追加批准：玩家不再指定边界内向，插件根据切分后的内部连通分量推导正式边界方向；只有存在多个合法内部时，点击内部轨道确认。本条取代下文历史批次中“放置时朝向内部、不需内部选择”的交互要求。未完成切口不携带方向；已成立区域和信号仍保留有向入口。普通/唯一保护边界删除不依赖视线；多个保护区域共用切口而不能唯一确定删除目标时明确拒绝，本批不新增删除选择会话。只实施本地修改与定向验证，不部署或修改开发服存档。

后续批准直接修正开发中数据模型：移除待完成切口的持久化方向和AB占位，不为旧开发存档保留reader/字段别名。区域修复草稿和工具输入同样不得填假方向；正式边界内向不删除，上一次认可的完整边界仍作为禁行与设备引用依据。世界定义保持schema 1，需要保留开发服数据时另行进行可审计、有恢复点的一次性迁移，而非打包运行时兼容逻辑。本批只本地实现与检查，不执行迁移或部署。

菱形交叉的围合需要覆盖冲突的各条轨道，但不能增加可行驶转弯。沿用原世界定义、现有manager事务、资源校验与授权；不自动重分类已保存区间、不新增持久化类型/schema版本或旧工具兼容reader。新工具重新发放。保留普通闭塞已有合法边界/范围能力，不能因统一操作而无意套用保护区域限制。少量定向验证，真实客户端另验，本批不部署。

### 游戏工具本地化与F切模式（2026-10-16，离线完成，未部署）

用户反馈SimpleSignals工具尚未本地化，且不能按F切换模式。补齐中英文玩家提示、物品名称/说明、设置页、状态栏及区间标记；按照玩家语言选择，其他语言回退英文，作者名称和稳定机器ID不翻译。闭塞/保护区域工具可在创建与删除之间切换，信号工具可在放置与删除之间切换；F不实际交换主副手，切换须撤销旧选点及异步编辑接续。Bukkit提供可由附属插件复用的最小Wand按键能力，不迁移信号业务或构建完整工具框架。

仅做受影响的定向检查，不重复执行整模块测试；游戏内效果与离线验证分开。本批不修改信号规则、物理、授权或持久化格式，不部署。

### 区间显示简化（2026-10-16，离线完成，未部署）

用户要求只标分界点，中间用连续短横杠描绘曲线，不画箭头。本批取代上一版区间边界/内部箭头及稀疏双侧虚线显示：区间沿轨道中心以连续短直段拟合，仅分界处保留横向标记与说明，相邻闭塞共用分界点合并显示；不再放中段重复文字。保留TextDisplay、自定义资源包、已有几何缓存和Signal Wand可见性；RailObjectWand专门表达运营方向的箭头不在本批修改。只改编辑显示，不改物理、定义/授权、选轨容差或存档，不部署。边界去重必须依据同轨弧长或已发布拓扑的真实端点连接，不能合并仅空间接近的无关轨道。

已通过独立复核及Bukkit410项/Signals592项验证，具体产物与重跑范围归[简化验证](research/editor-marker-ui-validation-2026-10-16.md#区间显示简化后续截图反馈批次)。后续只需同时更新SimpleSignals与编辑资源ZIP，主JAR未变；不能只替换资源包让旧8米间隔显示错误套用实心字形。尚未部署/游戏验收，Stage 2保持in_progress。

### 游戏建造显示修正（2026-10-16，已部署，待客户端验收）

- 普通建造叠加、选轨信息与操作确认不显示闭塞/轨道的UUID，包括截短UUID；内部身份、存档和管理员诊断不变，真实作者命名仍可显示。
- 闭塞/保护区域边界、入口方向及覆盖标记改为TextDisplay自定义字体图案，并提供完整客户端资源包；RailObjectWand的运行方向与到达方向显示复用同一能力。不以普通文字加旧粒子冒充几何迁移。
- Signal Wand同样显示现有区间和有向入口，方便先绑定逻辑入口再摆放模型；复用已有区间叠加的准备缓存、玩家隔离和工具生命周期，不新增观察/授权系统。
- 不改变区间划分、进路/授权、信号JS、存档和版本；实现批次与后续独立获批的部署分开记录。离线测试、打包与真实客户端显示分别记录，具体机制归[编辑标记合同](../../spec/bukkit-plugin/backend/editor-markers.md)。

三项均已实现并通过独立复核；最终Bukkit410项、Signals589项、公开API消费者、插件/资源包和模块门禁通过。方法、交付hash及真实客户端未验范围归[验证记录](research/editor-marker-ui-validation-2026-10-16.md)。用户批准分批提交后，另行批准整目录外部备份、配套两个JAR部署及正常重启，现有2闭塞/1信号恢复，客户端资源需玩家启用；详见[部署结果](research/dev-server-deployment-2026-10-16.md#编辑标记与工具交互修正部署07450747-utc)。真实显示验收未完成，Stage 2继续in_progress。

### 当前游戏验收就绪状态（2026-10-16）

后续06:51真实建造发现Kotlin共享类型的插件classloader冲突，阻塞闭塞创建；本地依赖配置已修正并通过门禁，随后获授权在整目录外部备份后仅更新远端SimpleSignals JAR，07:00正常重启。下一步由玩家重试原操作，不能用空定义启动成功说明当前建造已通过。验证和候选身份归[当前修复批次](implement.md#游戏验收发现共享运行库类加载冲突2026-10-16已部署重启待原操作复验)。

用户授权的迁移及配套部署已完成，开发服已正常启动；完整正式四灯包安装，26.1.2客户端资源ZIP单独提供。WebUI打包缺口已用既有构建流程修正，最终HTTP资源、桥接、ATO与信号世界安装检查通过，详见[部署记录](research/dev-server-deployment-2026-10-16.md)。没有创建测试设施、列车或应用时刻表；真实客户端、信号JS联动与行车验收仍未完成，Stage 2保持in_progress。下文各历史批次“不部署/未启动”只描述当批次授权与结果，不代表当前状态。

### 正式游戏验收信号包准备（2026-10-15）

用户批准在CR-040/041修复后准备正式信号包，不以测试专用smoke规则替代正式游戏验收。复用现有JR四灯灯头/资源及统一信号包加载器，补齐玩家可选择的signal类型与真实JS运行规则；不将规则写回Kotlin，不增加另一套寻路、资源授权或制动系统。验收应直接加载交付包，覆盖四种输出、连续短区间、已选/未选分支、保护区域、环终止和不可用输入；离线结果不能冒充真实客户端验收。

用户随后批准先补运行时能力：G明确不附加限速，而非120km/h或大数哨兵；扫描距离不固定2000米，按拟定减速度和参考速度计算。参考速度取当前允许的最大速度，unlimited时取线路最大允许速度，排除本信号自身输出以避免反馈振荡；速度依据变化才更新观察计划，不逐tick无条件重建。此决定尚不冻结Y/YG速度、减速度数值，也不允许把运营Line与物理线路的速度来源混为一谈。

用户进一步确认物理线路速度已保存在轨道中：复用CURVE/SUBGRADE有效限速及SUBGRADE上级限速，不新增线路速度配置。速度不在运行时变化，加载/准备计划时计算扫描范围后缓存，不增加速度事件、订阅或轮询；轨道编辑后沿已有重准备流程。逻辑显式提供参考减速度，无猜测默认值；既有固定扫描距离仍是可独立选择的模式，不是兼容旧格式的分支。

用户纠正参考范围：应取整个受控闭塞区间内各轨道有效限速的最大值，不只看入口。固定闭塞两向共用区间最高值，保护root按对应进路区间取值，不能混入下游区间或其他进路；任一受控rail缺少有限速度仍拒绝准备。该要求取代上一笔入口取速实现，缓存/静态读取边界不变。

2026-10-16用户在区分站台停车、区间行车和日本铁路信号设计后，明确批准本份正式四灯包先采用`reference-deceleration-metres-per-second-squared: 0.6`。这是本包扫描/现示判断的工程参考，不以站台停车范围证明区间行车或日本铁路采用统一数值，也不是实际ATO制动指令。来源及适用边界归[参考值记录](research/formal-pack-braking-reference.md)。值只属于这份包，通用loader/runtime仍无默认a；不改列车物理或ATO实际制动力。须随完整JS包一起交付，不向smoke目录或不完整包写入冒称生效。

分笔实现：先贯通明确不限速的JS输出、同epoch下游观察及inspection消费；再确认并接入参考速度来源/扫描计划，最后补正式包。不限速不移除资源授权底线，也不改变无效、缺失或过期状态收紧至0的语义。包规则与速度来源仍未确定的部分不得用smoke值、猜测线路速度或旧输出补齐。不部署、迁移存档或修改测试目标版本。

2026-10-16上述前置与完整JR四灯包均已离线实现：0.6参考值、距离敏感JS、Y45/YG65作者档位及G明确clear，规则和四灯能力限制归[包契约](../../spec/simple-signals/backend/formal-four-light.md)。585项离线门禁通过，实际客户端资源版本、一次性存档迁移及真实游戏验收仍未完成，Stage 2保持in_progress。Y/YG暂沿此前已告知默认档位，不冒称用户已单独确认备选档位。

后续2026-10-16用户批准停服、整目录外部归档及一次性迁移，远端114文件候选已应用并校验，记录归[迁移结果](research/dev-server-migration-2026-10-16.md)。旧JAR保持不变、未重启；部署配套新版及真实游戏验收仍待授权，不能用旧JAR启动已迁移数据。此为已审计开发服的独立离线工具，不将09-24未来端点/自动迁移任务冒充完成。

### 建造交互收尾顺序（2026-10-14）

本轮建造交互决定已收敛，以下为待实现目标，不代表当前代码已支持。用户于2026-10-15批准范围和实施顺序，独立测距工具延后到其余建造交互之后；具体执行顺序见[实施计划](implement.md)。

用户确认边界地面标记仅在持建造工具时向相关编辑玩家显示，离手或编辑上下文失效时清理；不作为面向所有玩家的常驻线路模型。该决定与现有临时编辑叠加的玩家隔离及清理规则一致，显示不能影响行车逻辑。

用户确认实例参数编辑首版使用设置页数值输入，包含三轴偏移、旋转角度及跟随轨道倾斜设置，不实现鼠标拖拽微调；复用既有实例摆放计算，修改明确选中的设备，不改变其逻辑绑定或无关设备。

用户确认删除被引用进路时保留相关设备，之后允许显式绑定新进路，不连带删除设备。设备身份、模型、摆放、实例参数及附属关系须保留；缺失进路的绑定不可用于放行，不自动选择替代进路，不影响无关设备及其他有效进路。重绑流程已确认先采用“选中已有设备 → 选择新入口/进路 → 确认”，独立于摆放，只改逻辑绑定，模型位置保持不变；更进一步的交互优化延后。实施状态归[实施计划](implement.md)，具体交互/校验归[工具合同](../../spec/simple-signals/backend/section-authoring-wands.md#wand删除进路与保护信号重绑)。

用户确认表示器缺少新增进路对应的模型映射时，允许保存进路。选中未映射进路时不显示进路指示，编辑页提示补配；其他已有映射继续正常显示。不猜测默认variant或由模型名反查外观，不因展示配置不完整而阻止编辑进路。此为待实现目标，需同步修正当前要求完整映射的校验。

本轮验收：删除进路后设备及摆放保留、失效绑定不能放行，重绑只修改逻辑关系；缺失表示映射只影响该进路的指示；数值编辑只改变选定设备的实例参数；边界叠加仅编辑者可见并按工具生命周期清理。各批验证候选编辑、实际输出和受影响回归；离线测试不能替代Paper/Folia与客户端验收。不重开Core/Basic全模块审计、不扩展ATO算法、不增加运行时旧开发数据兼容或猜测schema升版，本轮不部署。

### 停车精度修复（2026-10-14）

在真实50ms、正式手柄动力学及Basic准入的往返场景中修复停车误差，实际停车位置须符合既有停车容差。固定闭塞回库目标20米、实际18.983016737339824米是沿返库方向超调约1.017米，不是提前停车。先采样定位控制链路的原因，再在其owner内做最小修正；不得移动目标、放宽容差、直接修正位置或撤回真实授权余量/越界完成检查。生产控制契约归[controller runtime](../../spec/javarailways/simpleato/controller-runtime.md)，若涉及共享物理执行则同时遵守Basic动力学契约。离线通过不代替游戏内验收。

### 全局安全距离与真实越界完成检查（2026-10-14）

用户批准优先处理移动闭塞停站真实授权余量及通用完成检查，安全距离作为全局设置、默认5米。静态应用保护、实际ATO与隔离仿真共用同一值；余量必须获得真实轨道授权，不以控制标量替代，不选择未选分支或擅自跨信号边界。真正越过committed authority的停车不得推进开门、换向、后续动作或归库台账。精确机制及错误/测试边界归[终点分离owner契约](../../spec/javarailways/simpleato/terminal-route-completion.md#当前实施目标全局安全距离与真实完成检查)。本批不修制动算法/停车精度、不部署或升schema版本。

### 固定闭塞完整授权与运营停车（2026-10-14）

用户确认：固定闭塞的正式物理授权应覆盖信号允许通过的完整区间，不因运营停车点位于内部就截短。停车目标独立保留；完整block token不能代替其余轨道的真实授权。补全、后续任务复用、区间内部起步与停车换向按[终点分离契约](../../spec/javarailways/simpleato/terminal-route-completion.md)实施。不一概删除终点控制补偿，不靠移动停车点、放宽Basic校验或盲加固定余量让测试通过。

### 信号对照审查后的新决定（2026-10-05）

以下决定取代本文早期的默认进路和共享双向信号结果限制；历史完成记录不代表新行为已经实现。

- 取消默认进路；保护信号只有匹配的有效列车请求已取得授权且实际设备状态正确时才能开放。
- 保护区域放行前须利用列车长度确认可完整驶出，不能把拥有后继闭塞资源等同于车尾净空。
- 双向闭塞保持共享资源，但应允许两端按各自后续线路独立计算信号。
  本批已批准：按作者完整路径的两个入口分别配置输出、下游依赖、提前时间和模型；未配置端仅执行资源约束，已配置端缺有效状态则停车。
- 按独立、可验证的批次修正，不新增重复扫描、真实铁路全套联锁或运行时旧开发数据兼容。
  实施顺序与边界见[修正批次](research/signal-comparison-corrections.md)。

### JS 信号规则决定（2026-10-07）

以下是已批准且已进入 `CURRENT` foundation 的方向，取代 2026-10-06 草案中“扁平前视、DAG 拒环、执行预算仍待决定”的部分；
当前 SimpleSignals 主信号只通过 catalog signal type 执行 JS，不再保留 Kotlin 规则 provider。精确字段名、文件名解析规则和
公开 Java 类型只由[JS 规则 owner 契约](../../spec/simple-signals/backend/js-signal-rules.md)冻结，本任务不复制第二份 API。

- 距离敏感的自定义信号逻辑由 JS 完成，不再为现有 Kotlin 规则新增距离条件配置表。输出通过独立的限速与
  模型外观 setter 提交，不以 `return "YG"` 或模型名反查速度。
- 一个包使用元数据 `pack.yml` 和 `model/`、`logic/`、`signal/` 三类文件。model、logic、signal 的稳定 namespaced
  ID 均由包名和安全相对路径派生；`signal/*.yml` 是玩家可选择的一等类型并引用一个 modelId 和一个 logicId。
  跨包引用按完整 ID 两阶段解析；未来世界定义引用公开 signal 类型身份，不保存脚本路径或源码。
- 复用仓库已经采用的 GraalJS 技术栈和能力隔离原则，但 SimpleSignals 不依赖或导入
  `BukkitPlugin` 私有运行时类。包准备可在工作线程完成；规则求值在物理快照之后、同一信号 epoch 的
  同步信号阶段执行，随后统一校验并原子发布结果。
- 信号逻辑声明最远扫描距离，不声明前视区间个数。宿主从既有拓扑／区间快照准备完整分支树，JS 可观察
  所有候选分支及当前选择／准备状态；扫描距离对每条分支分别累计，只限定输入可见范围，实际判断仍由 JS 完成。
- 不增加节点数、分支数、执行时间、内存、整波次数或 context-pool/watchdog 预算。包作者对脚本耗时和终止
  负责；宿主仍保留无宿主对象、文件、网络、线程、进程和直接世界修改的能力边界，以及输入输出校验。
- 唯一遍历兜底是环：一条分支再次遇到相同的有向扫描状态时停止扩展该分支，兄弟分支继续，并把
  `cycle` 等终止原因作为只读输入交给 JS。这里不使用 SCC、fixed-point 或陈旧 permissive 输出来“收敛”。
- 验收要求：连续短区间可根据多个下游信号的距离／限速判断；树形分支和保护区域可统一观察；环只截断
  命中的分支且脚本能分辨原因；脚本异常不保留旧许可、不影响无关信号。文档完成不等于运行时已经实现。

### A. SimpleATO module extraction

- Move the remaining automated operating policy, Destination, Station, Depot, Line, Timetable, rolling-stock assignment, operational route planning, timetable apply state and their persistence into platform-neutral `SimpleATO`.
- 用户已批准新的方向归属：轨道长期允许运行方向由 Basic 保存，SimpleATO 与信号插件读取，取代原先的 SimpleATO 所有权。
  用户确认格式从未部署，直接修正当前存档，Basic保持2、SimpleATO保持1，不新增旧方向格式兼容或搬迁转换。
  具体机制见[方向契约](../../spec/javarailways/basic/operating-directions.md)。
- Keep generic longitudinal dynamics, train-actuation primitives and physical safety floors shared with manual driving in Basic; do not move them merely because SimpleATO consumes them.
- Keep generic graph algorithms and physical/resource validation in Basic; do not move concrete signalling policy into SimpleATO.
- Keep `SimpleATOBukkit` as the Bukkit/Paper adapter module for platform behavior specifically owned by SimpleATO, such as startup wiring and ATO diagnostics/presentation. `BukkitPlugin` directly constructs/calls it and embeds it in the main plugin JAR; there is one deployable JavaRailways plugin and one Bukkit lifecycle. Generic host rendering/Folia scheduling remains in `BukkitPlugin`, while each independent signal plugin owns its signal presentation and may consume a separately reviewed public host platform API if one is needed.
- Leave low-level `RailWand`, manual driving and host world/composition behavior in `BukkitPlugin`.
- Remove old Basic/BukkitPlugin owners as each slice moves; never keep two writable production implementations or add runtime compatibility wrappers for development data without separate approval.
- Converge operational route selection on the immutable normalized `TopologyState` before exposing an external restriction contract. Basic owns the generic deterministic graph search, while SimpleATO owns composition of operating direction, line/train policy and independently installed plugin constraints for one planning query.
- External planning constraints must not mutate Core topology, create physical connectivity or bypass Basic validation. They may only restrict a query-local graph and contribute validated complete physical traversal macros; the selected result is expanded to a normal frozen route and independently validated by Basic. Basic must not expose a generic `RouteChoiceZone`/protection-area abstraction: a concrete signal plugin keeps its own region/interlocking model and compiles it into neutral query-graph primitives.
- Temporary live occupancy or a denied resource claim does not silently remove a route from the planning graph. The default response is to wait; replanning is an explicit higher-level action at a safe boundary.

### B. Independent `SimpleSignals` plugin

- Create a separately deployable Java/Kotlin plugin/artifact with its own descriptor, lifecycle, persistence boundary, commands/tools and rendering.
- Depend on JavaRailways/SimpleATO public contracts at compile time and host services at runtime; do not shadow host classes.
- Own its fixed-block definitions, protection-region boundaries/interiors, interlocking policy, signal definitions/aspects, renderer state, authoring and optional script/configuration runtime.
- Publish model/aspect changes as immutable per-object presentation revisions. The adapter may skip obsolete pending visual revisions but must converge to the newest value without delaying the railway safety epoch.
- Migrate the current protection-region domain code out of Basic: definition, catalog, graph-cut/interior derivation, route-window matching, authoring validation, persistence and diagnostics. Reuse Basic topology/resource APIs instead of copying the physical graph or claim registry.
- Allow one player-authored region to protect a simple turnout or a complete station throat. Do not synthesize one stop/protection boundary per turnout.
- At authoring/update time, cut the immutable directed topology at region gates, derive the selected bounded interior and reject leaks, ambiguity, invalid directions and unsupported overlap. Cache/index the result by topology and plugin-definition revision; do not flood-fill each tick.
- At runtime, match only regions/blocks reached by the train's frozen route and shared perception. The plugin chooses the requested authority boundary and additive resources needed by its policy; Basic independently restores all traversed rail/turnout/diamond resources.
- For a protection-region entry, request enough frozen-route range for the train to leave the region and satisfy the plugin's exit policy. If the following territory is a fixed block, the plugin may require that whole block; in moving-block territory it may choose an exact route boundary. Basic does not make this policy choice.
- A region ID must not be an exclusive whole-region lock by default. Physical path/conflict resources and explicit flank/approach resources determine conflicts, allowing disjoint paths through one throat when the concrete policy permits it.
- Implement fixed blocks as plugin-owned authority/resource policy. Protection regions and blocks may expose a common plugin-local section observation API, but Basic and SimpleATO do not own or persist that abstraction.
- Allow an operational route to start or stop inside a fixed block, including a platform inside that block. Holding the whole block resource does not require granting movement to its physical exit; stopping or finishing a mission must not release a block still occupied by the train.
- Keep block-resource intersection separate from an actual entrance signal. An internal route start must not manufacture an entrance declaration or entry authority floor; real passed signals are excluded by the existing head-forward inspection scheduler, while held resources remain managed by the existing grant/release queue.
- Do not reject a timetable operation merely because it visits the same block or protection region more than once. Stopped reversal separates forward and return traversal coordinates while retaining occupied resources; separate visits within a frozen route keep distinct positions but reuse the same physical resource identity.
- Derive every permissive aspect from a confirmed Basic grant plus current SimpleATO authority. Plugin output can only keep or tighten speed/authority; missing, stale, corrupt or disabled state is restrictive.
- Use SimpleATO's existing shared route perception, inspection scheduler and reviewed declaration contract while keeping dynamic braking distance host-owned per tick. Do not invoke unchanged signal policy every tick, add a second forward scan or move the concrete plugin's downstream dependency graph into SimpleATO.
- Do not add a second per-train route scanner, world-wide per-signal tick loop or `allTrains × allSignals` lookup.
- Support diamond conflicts through Basic's route-bound physical resources without treating a diamond as a switch or topology continuation.
- Release resources through the host grant/cursor lifecycle. Planned plugin disable must first remove its applied-plan dependencies and then pass the single owner-unregister gate; the gate rejects disable while that owner still has pending requests, live claims, referenced resource types, active inspection bindings or non-empty restrictions. If Bukkit has already forced the plugin off, the host removes dead callbacks, retains claims/dependencies and stalls only affected worlds instead of widening authority.

#### Direction-aware blocks — implemented validation and online failure policy

The user identified that opposite-facing traversal must not silently bypass a single-direction
block's resource policy. Commit `5e375e79` was the bidirectional baseline that retained this bypass.
The earlier correction, including low-speed fallback, was implemented and verified offline.
The user has now replaced that fallback with refusal of ATO admission for every invalid block,
including known operating-direction mismatch. Direction changes and live topology transactions now apply this policy;
the current verification and startup/remapping limitations are recorded in [the implementation checklist](implement.md).
Live Folia/client validation remains pending.

- An effective single-direction block declares normal acquisition lookahead only at its entrance
  for approaching trains travelling in the permitted direction. Reverse entry from its exit must
  explicitly refuse resource admission, not return an empty contribution or rely only on a displayed stop aspect.
- Check the block's declared directionality against the operating directions of every traversal,
  including mixed canonical AB/BA paths. The user confirmed exact allowed-direction-set equality:
  a single-direction block requires every traversed rail to permit only the authored path direction;
  a bidirectional block requires every traversed rail to permit both directions. An unset track
  policy permits both directions and therefore mismatches a single-direction block. Direction is
  relative to the block path: `rail A AB -> rail B BA` requires A's policy AB and B's policy BA,
  not identical AB/BA enum values. Any mismatching traversal disables the whole block, not just
  that rail or one entrance. A mismatch retains the authored definition and devices but makes the
  whole block unavailable to ATO until corrected and revalidated, just like structural invalidity.
  It must not disappear from route validation or allow admission through an empty resource contribution.
- Known direction mismatch no longer permits low-speed passage. Basic's physical-resource validation
  and all stricter restrictions remain in force.
- Existing claims must not disappear because normal block control is disabled. Reuse the current
  edit guards and grant/tail-release lifecycle; do not introduce a second resource owner or an
  ad-hoc release-on-disable path.
- Public rail operating directions are owned by Basic. SimpleSignals uses the neutral public read/change
  seam rather than importing private world state, duplicating that policy, or polling all blocks every tick.
- Remove the low-speed fallback and its `disabled-block-speed-kmh` configuration; do not retain
  a compatibility switch or a second selectable failure policy.
- The user explicitly keeps manual driving unchanged. This refusal applies only
  to ATO; manual drivers control speed and maintain visual separation themselves. Do not add a manual
  limiter, automatic braking or an ATS integration in this correction, and do not remove existing
  Basic manual safety behavior. More systematic manual protection is deferred.

#### Model presentation and placement

These behaviors are approved. Package loading, placement persistence, production signal/model binding,
per-device handle retention and unload cleanup are implemented and verified offline. Real Folia/client
presentation remains unverified. The owning [model presentation contract](../../spec/simple-signals/backend/model-presentation.md)
defines the implemented flow and its verification boundary.

- SimpleSignals model packages must explicitly define `default` for initial missing signal state or `Unavailable`; an unknown explicit model variant remains a configuration error. This is plugin-local presentation policy, not a new requirement on host/train/rail models; the signal safety state keeps its existing name and semantics.
- Support rail-attached and ground/world-position placement. Rail coordinates retain their AB/BA direction to interpret offsets; ground placement uses a world pose without rail offsets. Placement and named left/right presets are independent of signal aspect variants and logical control boundaries.
- A future Wand can edit per-device offsets, rail coordinates and direction. Changing attachment direction retains offset values and recomputes placement; rotating the model in place preserves its placement point.
- Models may default to following rail slope/cant and players may override that choice, with world-upright as the fallback. Selecting a preset copies the effective offset, rotation and tilt choice into independent instance parameters. Later preset edits/removal do not move existing devices; adopting new parameters requires explicitly reapplying a preset. Do not add runtime field-inheritance tracking.
- The approved [model-package format](../../spec/simple-signals/backend/model-packages.md) provides multiple models and local shared parts. Static scenes are named without author time fields; imports merge offsets and Euler angles as train imports do, while explicit containers provide hierarchical assembly. Load and validate the whole package into immutable host values outside the signal tick.
- All three device kinds use one optional model reference plus rail/ground placement. Backend-only devices need no display position; remove the route indicator's duplicate standalone position. Reuse the current sidecar and authoring transaction, with old/new attachment rails included in the existing edit permit. Correct this unpublished format directly at schema 1, preserving default-route removal and independent directional-entry rules; reject mistaken versions 2/3 without compatibility readers. The exact current shape belongs in the model presentation contract.
- Arbitrary model instance parameters such as mast height are deferred. Later they must be separate from signal aspect `modelVariant`; do not reserve empty fields or add parameter evaluation now. Static model IDs can distinguish preconfigured heights in the meantime.

#### Guided authoring and measurement

The following player-facing requirements are confirmed. The user deferred tools, measurement,
style-editing UI and track overlays to the next batch. The backend primary/attached-signal relationship
is complete offline. Bidirectional single-track single-block control is also implemented and verified offline,
with two true entrances sharing one block token and occupancy; independent per-entry rules are implemented offline. See the
[bidirectional block contract](../../spec/simple-signals/backend/bidirectional-fixed-block.md).
尚未决定的交互不阻塞已经确认的独立批次。独立道岔 Wand 已提交；用户现已批准先实施
闭塞与保护区域 Wand 的确定部分，见[本批边界和测试](research/section-wands-batch.md)。
进路录制、信号机放置、测距和规则预设暂不包含在本批。

下列操作由用户确认。
它们取代上一轮名称输入、确认页面、单独连接模式以及创建闭塞时同时放置信号机的建议。

- 闭塞工具选择两个分界点后尝试生成区间，不要求玩家填写名称、指定出入口方向或打开确认页。
  内部稳定 ID 仍由插件生成。区间路径确定后，逐段读取 Basic 的公共运营方向，自动推导闭塞方向与出入口朝向；
  不改写轨道方向，也不放宽既有整条路径一致性校验；方向失配统一按不可用处理，禁止 ATO 驶入。
- 闭塞区间禁止经过道岔，包括经过道岔直向。两处分界点之间若需要经过道岔，拒绝创建，
  不按当前道岔状态选择支路，也不自动截短区间。此规则约束闭塞定义本身，Wand 和现有定义导入入口须统一校验。
- 未选择分界点时，左键现有分界点进入接续放置状态，沿用轨道创建模式的操作习惯。
  不设单独连接模式；选取两个相连的现有分界点时直接尝试在两点之间创建闭塞区间。
- 分界点可以配置地面显示子模型；它是分界点的建造显示，不是额外的列车控制信号、占用区或资源。
- 进路表支持录制当前通路：玩家调整道岔后选择入口和出口，保存当前实际连接构成的完整路径。
  保存后的进路不会随之后的道岔切换而改变。录制功能之前必须先提供玩家可操作的道岔切换工具，
  使用主插件提供的独立道岔 Wand，复用现有手动道岔选择和占用检查，不并入 RailWand 的建造模式。
- 进路录制必须有通路可视化：明确显示所选入口和出口目前是否连通，并沿实际通路高亮轨道及方向。
  无法到达所选出口时显示实际到达的位置和原因，不只弹出“失败”；道岔切换后按真实状态更新预览。
  “通路连通”只表示物理连接成立，不表示列车已取得资源或允许运行，不能把预览当成授权。
- 信号机使用独立 Wand 放置和编辑，不在创建区间时同时放置。放置前左键闭塞区间完成绑定，
  再右键轨道或方块选择摆放位置；绑定状态可用物品附魔光泽标示，Wand 设置选择待放置信号机模型。
  当前信号与进路显示器左键绑定保护区域入口；固定进路信号选择入口后，从该入口的已录制进路中选一条。
  中继和附属信号机左键绑定对应的主信号机，之后同样右键轨道或方块放置；不要求玩家手填对象 ID。
  生存模式直接消耗具体信号机物品是未来需求，本批不实现物品消耗或经济系统。
- 设置独立删除模式，沿用轨道工具的创建/删除分工。删除区间分界点时，同时移除该点两侧相接的闭塞区间；
  也能只删除某个闭塞区间。删除后不再被任何闭塞或保护区域使用的分界点自动清理。
  若闭塞分界点与保护区域边界重合，必须先删除闭塞分界点及其相邻闭塞，保留保护区域边界和区域本身；
  后续删除保护区域边界须走保护区域编辑与校验，不能因重合位置的一次删除而连带移除整个咽喉或进路表。
  删除闭塞时，同时删除该闭塞所属的逻辑信号、绑定信号机和中继信号机及其模型；
  不保留未绑定设备，不影响其他闭塞或保护区域所属的信号。
- 保护区域编辑允许暂时不完整：删除边界或为新增侧线调整边界时，保留原区域身份、其余边界、进路表、信号机及摆放，
  区域降为不可用，待玩家补齐缺口后重新校验并恢复。不可用区域仍拥有其配置，不能被孤立点清理或设备清理误删。
  仅补齐边界不代表原进路全部仍然有效；不再适用的进路和设备绑定保留供修改，重新通过校验的部分才恢复使用。
  此规则不改变明确删除闭塞时的连带删除行为，也不解除已应用时刻表和运行占用的防编辑保护。
- 创建保护区域边界和闭塞分界点时可以互相吸附到已有位置；吸附使用相同的轨道坐标，
  但位置重合不合并两者的删除职责，不要求新增共用坐标对象。
  吸附须遵守真实轨道定位，不能把空间相近但不相连的轨道误认为同一点。
- 保护区域边界在创建时即带有方向，方向指向保护区域内部，沿用现有有向边界含义。
  不要求额外点击区域内轨道来推导边界朝向；普通闭塞的方向仍按前述规则自动推导。
- 保护区域自动生成：放置有向边界后尝试识别区域，满足完整性与既有校验时自动生成，
  不要求玩家执行“完成”操作。区域必须由玩家明确放置的边界界定；遇到未标记的轨道尽头视为尚未完成，
  不能把轨道尽头自动当作区域边界。
  每次放置都从新边界向内尝试补全区域，不要求先选中旧区域，也不增加单独的修复/编辑状态。
  补全原有区域时自动沿用其身份、进路表和设备绑定，不能因为这次放的是新边界就另建区域并丢弃原配置。
  识别由编辑触发并在异步 worker 中执行，不进入每 tick 的信号更新；不能为每次点选重复重建整份世界索引。
- 提供可配置的保护区域最大范围，口径为从起始边界沿轨道到最远位置的距离。
  道岔各支路分别累计到起点的轨道距离，不把各支路长度相加，也不使用空间直线距离。
  在搜索过程中检查并在超限时停止，避免缺少边界时继续扩展到远处路网。
  新建时超限不生成区域；已有区域重算超限则保留配置并标记不可用。不把搜索截断处充当玩家边界，并向玩家说明超限原因。
- 闭塞区间和保护区域不得有内部重叠；共享边界位置不算重叠。
  创建、编辑和删除仍经过原有编辑许可与候选校验；事务失败保留旧状态。
  经许可保存的暂时不完整区域是明确的不可用配置，不等于保存失败留下的残缺数据，不能继续用于放行。
- An added signal machine references a selected primary logical signal associated with that section and reuses its result,
  rather than creating another signal rule or running another occupancy/downstream scan. Repeaters
  also belong to the primary signal. Each display keeps its own model, style, position and facing;
  a repeater's display position is not automatically another train-control boundary.
- Support the user's single-track example of two signal machines displaying the same signal.
  Bidirectional admission is now approved through `bidirectional = true` on the block; missing/false
  keeps existing directed behavior. Reverse entry uses the same physical resource but may bind its own independently
  configured rule; no second direction-specific token. A reverse-facing display alone still does not enable this control.
- Provide a separate measurement tool showing both the straight-line distance and the along-track
  distance between two selected rail positions. Confirmed 2026-10-14: choose the physical shortest path automatically,
  independently of the current turnout selection, without moving turnouts or acquiring train resources.
- Like RailWand, allow editing the signal Wand's default style for future placement and editing the style
  of an already placed signal. Default-setting changes must not silently restyle existing devices;
  existing per-device placement/preset-copy behavior remains applicable.

#### 进路自动生成与鼠标替换（已实现并通过独立离线检查）

- 进路录制通过鼠标交互完成，不要求玩家执行录制命令。现有通路预览继续复用：
  左键选择入口，Shift（潜行）+ 左键选择出口并预览，右键确认保存或替换。
  原来的潜行点击取消规则必须移除，不能与选择出口冲突；更换入口可重新选择，
  退出工具仍清理选择与预览。删除现有 `/simplesignals route record` 录制命令，不保留兼容入口。
  计算未完成、预览过期或通路不完整时不能保存。
- 创建保护区域时，检查各有向入口—出口边界组合，尝试自动生成进路表。
  对有可行通路的组合先记录沿轨道距离最短的一条；存在多条通路时不因此禁止这条进路使用。
  玩家随后可以手动录制当前通路，替换该入口—出口组合的进路。
  对存在多条可行通路的组合，在相关入口和出口边界显示警告，不在内部道岔上标记该警告。
  搜索只涉及已识别区域的内部，并在异步 worker 中执行，不放入物理 tick。
- 为轨道、车站、闭塞和保护区域编辑制定共用的节点/区间视觉规范。
  先统一标记含义、方向、选中及警告表达；不因此将各模块的业务定义合并或移入渲染层。
- 验收：唯一通路自动入表；多通路采用最短路且警告仅出现在相关出入口；
  鼠标替换后表内对应路径改变，之后切换道岔不改写已保存路径；
  不完整预览、受运营保护的编辑或候选校验失败均保留原表与设备绑定。

Two further UX topics remain open: protection-region route authoring and device binding, and clearer
editor visualization. User-proposed visualization candidates are directed `▴` TextDisplays colored
by speed limit, dedicated resource-pack items for stopping points/anchors, and differently colored
TextDisplay bars for fixed-block extents. These are proposals, not a frozen rendering/API design.
Multiple displays now have confirmed shared-output semantics. This does not require every entrance
signal on a physical section to share one result. The approved directional-entry batch removes the
one-rule-per-section implementation limit. A bidirectional
physical block must use the same resource identity from both ends; reuse Basic's existing arbitration
and tail-release lifecycle rather than adding another direction lock for a single block. The authoring
scope for independently computed directional rules is frozen for this batch; no configured rule on one end
must not borrow the opposite end's output. These extension boundaries are recorded in the
[owner spec](../../spec/simple-signals/backend/index.md#后续单线支持的边界目标尚未实现).

### C. Timetable, editing and persistence

- Reuse the existing signal-plugin route matching/binding during selected-route timetable compilation before apply and collect its exact edit dependencies. Do not add a parallel signal-audit framework, repeat authoring-time validation, perform another path search, or reject transient red aspects/occupancy/resource waits as an invalid timetable.
- Extend the existing atomic applied-plan dependency declaration with the signal plugin's exact referenced runtime resource IDs. Draft save/dry-run publishes no dependencies; draining unapply blocks new work and removes dependencies only after live grants clear.
- Route every covered topology, region, block, signal and operational-object edit through the common edit guard with owner/revision diagnostics.
- Store each plugin's data in its own independently versioned sidecar using stable namespaced IDs, rail UUIDs, canonical AB positions/directions and explicit references.
- Decode/migrate only at the plugin persistence boundary; business logic sees the current model and writers emit only the current version. No reflection from stored class names and no unreviewed runtime compatibility path.
- Provide in-game create/edit/delete/inspect/validate/dry-run operations and recovery points for destructive topology-related migration.
- All online infrastructure editing uses prepare/commit separation. Geometry, graph/index compilation,
  document encoding, validation and ordinary file writes run on an asynchronous worker. The final
  commit is scoped to the affected world, performs only stale checks plus bounded state/file swaps,
  and never holds a cross-world simulation lock. This applies equally to signal definitions,
  tracks, topology nodes and other resource definitions; describing an edit as low-frequency does
  not permit an `O(world)` rebuild or blocking I/O on the Paper/Folia owner thread.

## Acceptance criteria

- [x] 轨道公共方向及其中立 API、通用转换和基础设施存档归 Basic；SimpleATO 不再保留方向存储或转发兼容别名。
  该迁移批次当时保持寻路、方向判断、降级限速、编辑保护和手动行为不变；降级限速现由本任务的新失效策略取代。
  当时直接修正未部署当前存档，不升版、不新增方向兼容路径。

- [ ] `SimpleATO` contains the platform-neutral operational stack and Basic has no production owner for the migrated state.
- [ ] `SimpleATOBukkit` is a separate Gradle source/test module embedded in the JavaRailways main plugin JAR, has no independent Bukkit descriptor/lifecycle, and does not become the owner of any concrete signalling system.
- [ ] Signal presentation is applied through target-aware latest-only dispatch: an unchanged or superseded model revision creates no replay backlog and cannot delay logical signal evaluation.
- [x] Placement tests prove directed rail offsets, model/player tilt selection, in-place rotation and ground placement without a rail lookup. Existing instance parameters survive preset changes, with explicit reapplication adopting the new values; calculation creates no entities or signalling side effects.
- [x] 闭塞创建无名称输入、方向输入或确认页；成功后方向和两端朝向逐段自动推导，失败保留有效旧定义。
- [x] 闭塞路径经过道岔时拒绝，无论直向、侧向或运行方向；Wand 与定义导入使用同一约束，失败不截短区间或修改原定义。
- [x] 左键现有分界点可接续创建，选取两个相连分界点直接尝试生成区间，无单独连接模式。
- [ ] 分界点地面子模型可配置；独立信号Wand先绑定闭塞再放置，显示绑定状态且不重复创建逻辑区间。
- [ ] 信号Wand按设备类型绑定入口、已录制进路或主信号机，统一右键放置，无须手填ID；附属设备仍复用主信号输出。
- [x] 主插件提供独立道岔 Wand，玩家可切换未被占用锁定的道岔，复用现有手动选择接口并显示拒绝原因；成功操作不产生行车授权。
  已通过离线工具/命令及真实 Core/宿主服务组合测试，完整 Folia 交互仍待验证。
- [x] 录制前能看到真实连通路径、行进方向及出口；未连通时标出实际到达位置和原因。道岔切换更新预览，保存进路不随后续切换改变。
  预览与录制已通过离线工具/真实manager/store/Basic planner检查，保存仍受原编辑保护；live Folia/客户端验证另行完成。
- [x] 删除模式支持删分界点及其相邻区间、只删区间，并清理不再使用的分界点；重合位置先删闭塞分界点，保护区域边界和区域保持不变。
- [x] 删除闭塞同时移除其逻辑信号和所有附属信号设备，不留下未绑定模型；无关区间和保护区域的设备配置保持不变。依赖被删信号的设备明确不可用，修复引用后恢复。
- [x] 删除保护区域边界造成缺口时，保留区域身份、其余边界、进路表及设备，标记不可用；补齐后重新校验，恢复有效内容，失效进路或绑定保留待修改。
- [x] 保护区域边界和闭塞分界点可互相吸附；允许边界接触，但拒绝内部重叠、错误轨道吸附和受运营保护的修改。
- [x] 保护区域边界创建时记录指向内部的方向，无须额外选择内部位置；该交互不改变普通闭塞自动推导方向的规则。
- [x] 每次放置有向边界都自动尝试补全区域，无“完成”操作或预先选择旧区域的要求；补全旧区域保留身份和配置。识别在编辑时异步进行，未完成的边界不产生错误区域。
- [x] 未标记的轨道尽头不能完成区域；最大范围可配置，搜索超限及时停止并提示，不生成被截断的区域。
- [x] 最大范围按起始边界到最远位置的沿轨道距离检查；分支共享的前段计入各自距离，各支路长度不相加，弯曲线路不使用直线距离代替。
- [x] Additional signal machines and repeaters reference their primary signal and reuse its output without duplicating signal evaluation, creating another block resource, or adding a control boundary at every display position.
- [x] Attached devices round-trip in the existing sidecar and admin workflow, reject invalid/orphan references atomically, update only related models, and retain unaffected handles through edits and unload cleanup; verified offline, not on a live Folia/client.
- [x] A bidirectional single block matches the same physical path from both ends, produces actual entrance checks,
  denies opposing claims atomically and retains the same token during an internal stopped reversal until tail clearance.
  Reverse protection-region successors and edit protection use the same geometry; the direction-policy correction below supersedes the old single-direction bypass.
  Verified with real Basic class composition and the 295-test module gate; live Folia and a complete depot mission remain unverified.
- [x] Correct the single-direction bypass: reverse admission is explicitly refused while direction-consistent
  control is active; held claims are preserved and direction edits invalidate only relevant cached checks
  outside the tick hot path. The earlier low-speed failure policy is superseded by the pending criterion below.
- [x] Multi-rail direction validation checks every traversal relative to the authored path. One
  mismatching rail disables the whole block; unset/bidirectional rails mismatch a single-direction
  block, and a bidirectional block requires all its rails to permit both directions. Mixed canonical
  AB/BA paths with matching per-rail policy remain valid.
- [ ] 运营方向失配与结构失效统一禁止 ATO 驶入，保留区间和设备配置；不允许以低速或空资源贡献继续通行。
  已完成方向失配及直接边界编辑缺口的禁行；任意轨道结构编辑后的自动局部失效/修复仍待完成。
  删除 `disabled-block-speed-kmh` 及失配专用的低速逻辑，覆盖正反向、多轨道和无模型/无信号规则的闭塞。
  已有资源仍按原生命周期保留和释放，手动驾驶行为不变；修复并校验通过后恢复可用。
- [ ] The separate rail measurement tool reports straight-line and along-track distances for the selected positions/path without modifying infrastructure or acquiring train resources.
- [ ] Tool defaults affect future signal placement, while instance-style editing changes only the selected device and preserves the existing logical control binding.
- [ ] The `SimpleSignals` JAR installs beside the host, is absent from host JARs and imports only documented public contracts.
- [ ] Protection-region/fixed-block classes, persistence and authoring are absent from Basic's target API and owned by `SimpleSignals`.
- [ ] One region protects both a simple turnout and a multi-turnout throat without internal per-turnout stop boundaries.
- [ ] Region validation rejects leaks, ambiguity, bad gate direction and unsupported overlap outside the runtime tick.
- [x] Basic still inserts every traversed rail, turnout and diamond resource when the plugin supplies no or incomplete additions.
- [ ] Plugin-added flank/approach/block resources join the same atomic claim; denial leaves no partial ownership or wider authority.
- [x] Disjoint route footprints through one region may coexist when policy allows; overlapping rail/conflict/additional resources cannot.
- [ ] Fixed-block, protection-region and moving-block transitions choose their authority boundary in the signal/ATO layer without a Basic contributor closure.
- [ ] ATO stops before an unavailable grant and applies plugin speed limits/model variants only as stricter outputs.
- [x] Unchanged signal state does not execute every signal script/update every tick; dependency changes propagate to affected upstream resources.
- [x] Diamond crossings prevent conflicting passages without cross-line routing or runtime geometry scans.
- [x] Disable, failure, world unload/reload and stale-state tests prove authority never widens and live claims are not dropped beneath trains.
- [x] Timetable apply/replace/unapply and draft/draining tests prove atomic applied-plan dependency replacement, edit blocking and separation from transient live claims/occupancy waits. The lifecycle regressions use real Basic dependency/edit permits with scripted admission completion; physical tail release and live Folia validation are not established by those fixtures.
- [ ] A deliberately paused signal-definition commit cannot delay another railway world or an
  already-running physics epoch; old epochs finish with the old immutable source and the next epoch
  establishes the new baseline.
- [ ] Rail create/replace/split/merge/remove prepares curve, crossing and normalized topology
  candidates away from the platform owner thread; final publication pauses only the affected world
  and contains no complete topology rebuild, persistence write or Bukkit wait.
- [x] Plugin persistence round-trips current fixtures and rejects/preserves corrupt or orphaned input with actionable diagnostics.
- [ ] Unit, integration, packaging and Folia smoke checks pass.
- [x] Mission, Line, Station and Timetable use one immutable-topology planning path; registered restrictions have identical semantics for all callers and no legacy `RailView` planner remains in production.
- [x] A signalling plugin can compile its private interlocking/region model into neutral suppressed traversals plus complete physical traversal macros, without permitting hybrid paths assembled from portions of different table routes; Basic neither defines the region nor accepts any expanded traversal absent from physical topology. SimpleSignals restores strict current-model world definitions and compiles them against the exact host topology before publishing one usable planning source per world load; missing starts empty, while corrupt or orphaned input installs only a rejecting fail-closed source and no usable runtime.

## Out of scope

- Requiring every signalling system to use `SimpleSignals` protection-region model, aspects, scripts or persistence.
- Multiple concrete national signalling systems in the first Stage 2 implementation.
- Certification-grade communication, redundant control channels or train-integrity proving.
- Permanent runtime compatibility for arbitrary pre-release Basic/Signal data.
- Final WebUI module ownership during the extraction and first reference-plugin batches.
- Multi-block single-track direction coordination is explicitly deferred to [TODO.md](../../../TODO.md).
  Following capacity, direction switching and integration with resource admission must be discussed
  later; do not add placeholder group state, a scheduling framework or persistence fields now.

## Dependency

- `09-18-signal-addon-host-apis` is complete and archived. Stage 2 must consume its public contracts rather than reopening their ownership model.
- The separate `09-24-explicit-rail-endpoint-connections` world-format migration does not block new Stage 2 runtime work. Any need to import pre-release protection definitions must be added to that auditable one-time migrator instead of becoming a runtime compatibility path.

## Deferred decisions

- JS 的引擎族、同步信号阶段、包形状、树形前视、逐分支距离和遇环停止已经决定，不再重新引入执行预算、
  扁平数组或 SCC/fixed-point 方案。仍待逐批实现和验证的是具体 loader/runtime 类边界、未冻结的字段名、
  跨包解析诊断、上下文生命周期，以及它们与现有 publication/edit 事务的接线；以
  [JS owner 契约](../../spec/simple-signals/backend/js-signal-rules.md)为准。
- Guided authoring interaction/API,
  protection-region route/device editing and editor visualization. Confirmed player-facing requirements are recorded above;
  these unresolved details must be reviewed before the authoring implementation batch.
- Arbitrary model instance parameters. Production model binding is complete offline; actual Folia/client presentation remains to be verified.
- Guided/shunting workflow details and WebUI packaging.

## Blocking open questions

建造交互：工具分工已确认，信号机使用独立 Wand，测距也独立。
Signal Wand 是一种通用物品，不按信号类型发放不同工具。信号类型在 Wand 的 Shift+右键设置页中选择，完整状态跟随物品；
主手持有时由 SimpleSignals 自己显示周期 action bar，包含当前模式和类型。设置回调与异步编辑都用 item identity/revision
拒绝过期操作；该状态栏不是 BukkitPlugin 的通用显示仲裁系统。
控制类型也在同一设置页选择，当前覆盖固定闭塞主信号、保护当前入口主信号和保护固定进路主信号；控制类型与放置／删除模式分离。
保护入口点击后必须通过接续页明确选择新建目标或精确既有设备，因为同一入口／进路允许多台主信号。不可用作者进路不允许新建或改绑，
但既有设备继续允许原地更新和删除。完整绑定、过期检查与事务规则见建造工具 owner 契约。
删除顺序和绑定设备的连带删除行为已确认，见上方要求。
闭塞禁止经过道岔已确认，不提供按道岔状态选择闭塞路径的交互。
保护区域边界在创建时携带方向已确认，额外选择内部位置的建议已撤销。
自动生成、仅使用玩家边界以及可配置最大范围已确认。现有 resolver 允许在未标记尽头停止并成功返回，
自动建造接入时必须补足完整性检查，不能直接把现有 resolve 成功当成边界已放齐。
最大范围按起始边界到最远位置的沿轨道距离计量已确认；累计所有轨道片段长度的建议已撤销。
保护区域缺口期间保留配置并降为不可用已确认，删除边界连带删除整个区域的建议已撤销。

区间失效统一禁止 ATO 驶入已确认，包括运营方向失配，取消低速降级及其配置项。
轨道编辑处理方案是在既有防编辑保护允许施工时接受轨道修改，
对闭塞同样保留定义和设备，标记不可用并停止 ATO 放行；闭塞内新增道岔必须调整边界或移除道岔才能恢复，
不能自动转换为保护区域。结构失效与运营方向失配采用相同的拒绝准入行为。
原有区域新增未设边界的侧线时采用同样的不可用行为；内部连接变化若仍符合边界和不重叠规则，则重新校验后继续使用。
实施时需保证失效对象仍参与路线校验/准入阻断，新增侧线不能因没有旧入口信号而绕过检查；
相关旧解释应在新拓扑被运营使用前停止放行，重算在 worker 进行，无关区间不因某个对象失效而整体不可用。
用户进一步确认统一从轨道编辑事务触发：取得事务影响的全部轨道，找到与这些轨道相交的闭塞和保护区域，
再使用相同的成立规则重新检查。受影响集合包括连接关系变化的既有轨道，删除轨道使用编辑前的关联信息。
不可用区域仍保留关联信息以便后续修复触发重验；不按施工操作类型分别编排监听流程，也不逐 tick 扫描区域。
用户已批准取消运行流程以整个世界拓扑版本变化为统一失效条件的做法，改以事务影响与对象依赖是否相交判定。
未受影响的占用状态、既定路线及已有授权应继续使用，不因其他线路施工而重规划或重新申请。
保留世界／列车／路线身份、物理观测顺序和既有防编辑保护；编辑提交的一致性检查不等于运行对象有效性判断。
轨道事务重验须按对象保留配置和可用性，不能因为一处区域编译失败而拒绝其他不相关区域的运行。
显式定义编辑仍走原有完整候选验证与原子保存；这与后台重验的逐对象不可用结果是不同入口。
新边界自动补全区域，不引入先选区域的编辑步骤，已确认。
录制当前通路、先提供道岔操作工具及连通状态/完整通路预览已确认。
独立道岔 Wand 已确认，由主插件提供；现有 RailWand 道岔模式继续负责建造。
宿主已经提供 `InfrastructureResourceService.selectTurnout`，不需要为此重复实现一套资源锁或行车授权。
保护区域信号和附属信号的左键绑定、右键放置操作已确认，见上方要求。
信号规则预设/模板选择暂时搁置，不实施该建议、不预留字段，也不作为道岔工具的前置条件。
测距选路已于2026-10-14确认采用物理最短路；其余可视化细节仍需后续讨论。

None for the route-restriction foundation. Sources compile immutable neutral contributions before search; no plugin callback is evaluated during graph expansion. Owner/world registration now shares the existing signal lifecycle, and the plugin restores its strict current-model route-table sidecar before publishing the compiled source; invalid persisted policy leaves a rejecting source installed. Authoring and runtime route/resource policy remain later reviewable batches.

Placement, package loading, the JR four-light demo, device persistence and production model binding
are complete offline. The backend primary/attached-signal relationship is implemented and verified offline. Tools, measurement,
style-editing UI and track visualization are explicitly deferred to the next batch. Bidirectional
single-block control is complete offline; broader direction coordination and protection-region authoring remain separate decisions.

The earlier direction-aware block correction was verified offline, but its 20 km/h fallback is now
superseded by the approved, not-yet-implemented refusal policy above. Manual driving remains unchanged. Historical prerequisites remain in
[the bounded research](research/fixed-block-direction-policy.md); current behavior belongs in the
[owning contract](../../spec/simple-signals/backend/bidirectional-fixed-block.md), with actual verification
in [the implementation checklist](implement.md). This bounded completion does not finish Stage 2,
the guided tools, multi-block single-track coordination, a complete depot mission or live presentation validation.
