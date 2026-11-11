# 按World执行信号与隔离模拟安装

## 状态、范围与负责模块

状态：`CURRENT`，本批已实现并通过定向回归；组合门禁及独立检查结果另记任务验证，CURRENT不代表Paper/Folia现场验收。触发条件是修改SimpleSignals规则执行归属、manager锁、捕获/模拟安装或对应生命周期。

SimpleSignals拥有定义编译、固定闭塞/保护区域策略及JS；SimpleATO拥有平台无关的模拟安装端点与既有领域运行链；Bukkit只认证并登记插件准备器。车厢行为、模型/实体显示、包热重载和完整SimpleSignalsBukkit模块拆分不在本机制内。正式四灯算法归[四灯规则](formal-four-light.md)，JS输入与沙箱归[规则合同](js-signal-rules.md)。

## 一次捕获与目标安装

源World处于现有owner/帧间稳定边界时，宿主取得必需领域owner集合并调用对应准备器。准备器只取当前不可变定义、已捕获的规则源/元数据/有效外观名和静态设置，返回脱离LIVE运行实例的PreparedSimulationFeature；缺少必需准备器或可用规则时明确拒绝，不报告空信号模拟成功。

每次install使用目标UUID及其exact topology重新编译定义、观察图和信号规则，创建新的World runtime、JS执行器、occupancy与protection selection。随后安装epoch source、route planning、两类inspection、资源provider/contributor。Basic本地准入原有admission update直接交给同目标SignalEpochRuntime；发布后尾释放也复用既有路径。安装发生在列车seed/physics启动前，关闭发生在目标physics/generic回调真正退出之后。

## 每World执行与目录互斥

每个LIVE World和每次SIM安装分别拥有SignalLogicRuntime的执行与关闭。同一World的定义/拓扑替换可复用其执行器；同World evaluate/close仍同步，每次evaluate仍创建并强制关闭fresh Context。只共享中立只读规则输入；当前各自构造Engine，不共享Context或可变运行状态，不额外引入共享Engine优化。

manager短锁只负责目录、对象身份、关闭标记和不可变read view交换。JS构造、单World编译、host source replacement等待及关闭不在该锁内执行。局部管理入口只串行所属World；物理epoch callback不能反向取得host owner或管理锁。

宿主准备器已持源World owner，因此capture不得再取得manager的World管理锁或JS monitor；否则会和编辑的管理锁→host owner顺序形成AB/BA。capture只读取短目录/不可变快照，目标编译留给install。

authoring保留当前定义CAS和原始选择root：准备期间无关轨道变化可在实际选择轨道的`preservesRails`检查通过后重新准备一次，继续变化返回`RETRY_REQUIRED`；选中轨道删除、替换或相关连接改变仍拒绝。重试在所属World管理锁之外开始，不把递归编译/等待/关闭放进manager目录锁。rail incarnation来自当前Core topology，测试构造同一物理轨道的新root必须保留真实identity；不能仅按UUID假定是原轨道。

安装、remove、同UUID重装与迟到失败依靠实际runtime对象身份及closed状态确认提交归属，不引入公开generation或永久tombstone。已退休候选不得发布source、撤销新实例的source或成为新的可见runtime。atomic source callback保留exact root/base/catalog校验和已准备结果的有界提交，不在其中构图、构造JS或关闭执行器。

## 公开边界与中立规则输入

公开安装契约位于simpleato.api.simulation，Bukkit认证服务位于bukkit.api.simulation。PreparedSimulationFeature.id使用宿主现有owner.name.lowercase身份；install返回目标资源关闭对象。SimulationDomainTarget只暴露target UUID、exact TopologyState、方向/静态速度读取及实际需要的source/provider/contributor安装方法，不暴露CoreWorld、OperationalWorldRuntime、SimulationRouteAdmissionHost或Bukkit模型。

稳定资源DTO使用`core.api.physics.infrastructure.StableInfrastructureResource`，target本地provider通过SimpleATO注册到所属CoreWorld唯一arbiter的validator token；不创建或关闭第二套registry。固定闭塞资源贡献算法仍是LIVE与SIM共用的`FixedBlockDomainResourcePolicy`，设备准备、grant和尾释放属于Basic原准入实现。

方向沿用Basic RailOperatingDirections；静态有效限速单位m/s，present null表示没有CURVE/SUBGRADE限速，不能把null解释为0。定义revision、physics epoch和runtime UUID不构成schema version。

完整SignalPackCatalog包含CompiledModelAsset及Bukkit Material，不能仅因map不可变就把它带进SIM。单一catalog的中立rules视图仅保存logic源/元数据和signal type→logic/variant字符串；LIVE/SIM均消费它，presentation仍消费原catalog，不新增第二loader或规则算法。

## 生命周期、清理与失败

SIM不保留Plugin、LIVE manager/publication、model handle、live provider、预约或显示回调。每次install创建自己的可变对象，源定义复制到目标UUID后针对目标root编译，不删除既有world/stamp核对。

返回的关闭对象只撤销自己的source/provider/contributor并关闭自己的JS；partial install按逆序关闭已取得资源，保留原异常与suppressed。停止后的inspection source退休使用既有removeSource入口，不能用拒绝active binding卸载的计划性replaceSources作为最终清理；运行期的planned卸载安全门仍保持。

LIVE manager的epoch→planning→inspection安装任一步失败时，在原World管理gate释放前标记该次runtime关闭，并向宿主`retireSources`传入本次exact源对象；随后才释放本地模型/引擎。失败诊断函数只发布Issue，不再用空全集replacement清源。需要保留的禁行planning fallback是独立对象，不引用runtime/Engine；安装fallback也必须仍属于同一次directory reservation，不能覆盖同UUID新安装。宿主的身份筛选及活动限制保护归[Bukkit生命周期](../../bukkit-plugin/backend/lifecycle-and-threading.md#失败安装按源对象身份退注册)。

退注册异常时，runtime以sourceRetirementPending保留关闭的reservation和引擎，不发布到活动read view，也不允许同manager再次安装覆盖。remove/clearLocal可重试；只有exact退注册成功后才删除reservation并关闭引擎。clear与在途install交错时，目录不能提前丢弃候选，因为候选稍后仍可能产生待退源。关闭、编译和宿主等待仍在短目录锁之外；pending标记有跨管理/目录入口的可见性。无自动轮询、强制关闭或永久墓碑。

未知signal type、坏规则或缺准备器在准备/安装边界明确失败；既有初始/拓扑安全恢复仍按原合同收紧。求值抛错或非法输出仍为Unavailable/0，clearSpeedLimit仍是显式null，不保留旧许可。LIVE诊断走原插件出口；SIM诊断走无Bukkit依赖的JVM日志，包含simulation、实际target UUID及原Throwable。正常停车或尚未ready不当成脚本异常。

## 成本与退化

规则文件只在包加载时读取，中立规则视图一次派生；每World新执行器初始化成本随规则数增长。目标定义/观察图编译在安装阶段执行，复杂度沿用现有编译器；普通epoch只执行已准备的同epoch规则，未变输入继续IDLE。

目录查找/身份交换为短内存操作，不以共享目录锁包围编译或等待。target安装时组合当前source集合的成本随已注册source数增长，不新增逐tick注册、轮询或跨World屏障。第三方脚本永久不返回仍只保留该目标依赖等待；不加watchdog、伪超时结果或强行卸载。

## 具体情形

正式四灯包配500/300/150/500m区间，尾段占用时按既有正式JS给出对应档位；释放后仍可能因轨道尽头限速，不能断言所有空闲段都绿。保护入口只有真实选择、准备和净空条件满足后才放行；Basic资源底线不因JS clear取消。

同一个prepared输入安装到两个SIM时，定义IDs可相同，但target UUID、topology编译结果、JS、claim/selection均各自独立。暂停A的实际logic monitor时B继续物理和信号发布；同A另一调用保持等待。A卸载或失败不能关闭B的执行器。

## 必需回归与断言点

- 直接加载分发四灯JS，真实LIVE与SIM安装，断言占用/释放、有限/clear、短区间/静态速度及不可用规则。
- 完整目标链覆盖保护选择/准备、方向与折返、两类inspection、provider/contributor、claim/尾释放及admission更新，不能用空source或手写替代算法证明完整安装。
- LIVE-LIVE、LIVE-SIM、SIM-SIM均在真实logic monitor或求值路径barrier内暂停A，B必须完成实际physics及正确输出；同World有序、close/failure/reinstall仅目标受影响。
- route/inspection部分安装失败，检查源退注册先于Engine关闭、旧异步close与同UUID重装不串用；清理失败保留引擎可重试，clear与在途install重叠时reservation不丢失。
- manager安装/编辑/关闭交错验证目录锁范围，remove/重装/迟到失败不能改新binding；capture不反向取管理锁。
- 源LIVE先以正式规则发布有效信号，并持有真实非空route claim和Dispatcher运行状态，再capture并运行/关闭两个同源SIM；逐阶段核对源World/列车frame、实际claimSnapshots、Dispatcher statuses、signal snapshot与限制保持不变。SIM使用相同文本train ID、各自真实申请同资源，以避免空状态或不同ID掩盖串用。
- active route的inspection绑定在模拟结束时正常退休，另一feature的source仍保留；partial cleanup和真实worker终止边界不回归。
- API consumer、独立插件/JAR边界、受影响模块及文档完整门禁通过，现场Paper/Folia/classloader验证单独记录。

## 迁移与开放边界

无持久化格式或版本变化；未发布的原预留实现package接口直接收敛到api，不新增alias/兼容路径。禁止直接从SimpleSignals导入宿主实现类，或复制一份simulation专属信号/资源规则；应由公开安装端点适配现有同一领域实现。

完整车厢行为由[Bukkit逐车模拟规范](../../bukkit-plugin/backend/simulation-car-behavior.md)拥有；全部Feature结论还须同时满足父任务联合验收，不能只依据信号安装回归。
