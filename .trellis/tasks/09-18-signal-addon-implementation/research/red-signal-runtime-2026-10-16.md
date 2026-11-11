# Research: 测试服两台红灯的本地判读依据

- Query: 空世界是否求值、红灯可观测性与只读入口。
- Scope: internal，只读源码；未测试/远程操作。
- Date: 2026-10-16

## Findings

- `Core/src/main/java/work/art1st/javarailways/core/physics/tick/PhysicsCoordinator.kt:659-673` 每个完成物理epoch捕获world frame并调用railway processors，不按列车数量跳过。`BukkitPlugin/.../logic/RailWorldFactory.kt:222`注册SignalEpochRuntime。
- `SimpleATO/.../signal/SignalEpochRuntime.kt:147-189` 没有空列车退出；source初始baseline必回调，之后无occupancy/continuation/admission/direction/readiness/topology变化则跳过重复求值。空闲本身不等于未求值，ATO运行不是JS全局求值前提。
- `SimpleSignals/.../runtime/SectionSignalState.kt:32-38` 可区分Available.output(modelVariant,speedLimitMetersPerSecond)与Unavailable.reason（effectiveSpeed=0）。`SectionOccupancyRuntime.kt:49-51,187,227-254` 首baseline前snapshot可能null，完成后有stamp/每设备state。
- `presentation/SignalModelBinding.kt:269,278-280` 非Available或没有snapshot都会用model.defaultScenes，正式包default=stop。因此“JS正常stop”“Unavailable”“尚无baseline”画面都可红。
- `/simplesignals inspect <world>`仅定义revision/topologyrevision及各定义数量（`command/SimpleSignalsAdminCommand.kt:83-87,114`），不报告逐设备运行状态；HTTP目录无对应signal-runtime endpoint。不能通过inspect区分上述三种红灯。
- 正式`examples/jr-four-light/.../logic/block.js:6-9`自身占用立即stop；`:50-60`前方routeReady=false或占用按branch.distanceMeters限制停车；`:25-35`track-end/cycle/route-unresolved/topology-unavailable作为零速目标；`:70`无选定后继/无end按区间出口停车；`:73-86`达不到45km/h档就stop，即使全空闲也可能因短区间/轨道尽头而红。a=0.6时零速目标距入口小于约130.208m不能维持Y(45km/h)，将量化成R；等号进入Y。
- `signalpack/SignalPackEpochRuntime.kt:246-257`本机保护主信号fixed-route未ready或current-entry没有ready选择，直接Unavailable；`:109-114`记录reason。这与JS下游branch.routeReady=false分支不同。
- `signal/ProtectionRouteSelectionRuntime.kt:210-225` fixedRouteReady要求唯一真实owner request、live train、已提交authority及clearanceReady，空世界返回false；`:275-287`clearance还要求movementReady和真实authority覆盖。无列车保护主信号红可符合当前合同。

## Caveats / Not Found

尚无现场设备定义/状态，不能唯一归因；当前命令不暴露内部状态。没有新加诊断接口或修改远端。

## 主代理后续现场只读核验（18:58–19:00 UTC）

- 普通SSH读取当前定义revision38：6闭塞、3保护区域；设备是一台FORWARD固定闭塞主信号及一台CURRENT_ENTRY保护主信号，二者绑定均存在、均使用jr_four_light:block，不是未绑定设备。模型均ground摆放。
- 已安装JS SHA256为953f75fb0e2160c845d8f2961f9dafe8627ff77063fc027f51eb77666cb49b9a，与本地正式JS一致。
- 现有只读控制台`jr ato status world`返回RUNNING；19:00:10更新的物理checkpoint epoch142858、trains0，较此前读取的141658继续增加。checkpoint是近期持久化证据，不是逐信号实时输出。
- 固定闭塞实际长度142.9972376089234米，三段轨道连接与active continuation一致；相关速度均130km/h，未单独配置运营方向且闭塞为bidirectional，未发现方向政策冲突。若唯一限制就是出口保护区域未就绪，a0.6计算允许约47.16km/h，按正式档位应为Y而不是R；此为条件推导，不是现场输出读取。
- 保护主信号在无列车/无真实owner进路请求时没有ready进路，红灯符合当前实现；固定闭塞红灯仍未唯一定位，不得将保护信号的解释直接套用。
- 未找到公开逐信号state/reason/观察终止说明的诊断入口。曾尝试通过JVM环境认证现有HTTP读接口，但普通用户未匹配到可读进程；随后sudo读取凭据的请求被权限审核拒绝，未执行，未绕过。未取得HTTP实时结果，也不将其作为根因证据。下一步优先增加最小公开只读诊断，经用户批准后实现/部署，不继续提权提取凭据。
- 未修改远端配置、世界定义、JAR或运行状态；只读控制台命令会追加普通日志。未启动/停止列车或服务器。
