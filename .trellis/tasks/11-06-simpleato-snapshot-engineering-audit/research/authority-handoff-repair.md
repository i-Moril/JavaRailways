# 授权交接与边界精度：已批准修复

## 诊断证据

正常回放 `/tmp/jr-authority-cause-t4Yrsx/replay-control.log` 在 Tick2002 失败。
Tick2001 Dispatcher 提交 Mission9，controller 仍为 Mission2；下一物理控制周期激活 Mission9，restrictionRoute.missionRevision 仍为2。
`restrictionRouteProgress` 于是返回490.6657562299659（路线总长），而真实车头约471.0215906425109，授权终点490.66575622996584，实际尚余19.644米。
授权边界在Tick160已由SimpleSignals的authority floor给出，Basic原样授予；并非Basic重算。
最后一段routeStart=471.91541186264385，railLength=162.55741170015818，canonical exit=143.80706733283614。
`start+railLength-railLength` 产生471.9154118626438，fixed-block/region重复转换均形成490.66575622996584，而原分段末端是490.6657562299659。
反事实回放只把Tick160这次请求改为route.totalLength，保持真实Basic/ATP，运行到设定的2100 Tick；仅说明旧全路线兜底可掩盖该错误，不是正确修复或完整模拟成功。

## 最小实现边界

- SimpleATO owner：controller的冻结授权路线定位与Mission交接；优先复用已有FrozenMissionRoute几何/投影能力，保证本次Core frame为位置来源，去掉假终点。考虑重复经过的occurrence身份、没有active Mission的退休/折返过渡，不能以错误默认值消除异常。若真实位置不能属于保留路线，沿现有错误通道明确失败，不能静默跳过授权。
- SimpleSignals owner：DirectedRouteOccurrence及fixed-block/region matcher，已有canonical端点命中时复用routeStart/routeEnd，不借新增epsilon把接近当相等。中间点只计算局部有符号偏移，不先加大前缀再相减。
- 检查/测试：精确端点AB/BA、内部交点、重复occurrence；生产controller交接在新Mission/旧binding时保留真实剩余距离，部分/完整grant和真实越界仍受保护。
- 不调整ATP、安全余量、停车语义、Basic资源策略、旧数据格式；不恢复缺车长全路线申请，不增加旁路/影子物理缓存。

## 验证

两名implement分别拥有SimpleATO和SimpleSignals文件，主会话同步规范并做原输入回放；独立check复核全链路。
Gradle串行执行，复用 `/tmp/jr-simpleato-audit-az6Vw0/concurrent-tests.init.gradle`，避免并发构建互相污染结果。
原输入回放使用 `/tmp/jr-frame-remediation-DryXuH` 的正常代理记录脚本，另建日志，不使用counterfactual开关或2100 Tick上限。
不提交、不部署，不把Gradle成功当作模拟成功。

## 首轮实现验证与生命周期回归

- SimpleATO 聚焦49项通过；临时只恢复旧Mission不匹配→路线总长分支，5项选中回归失败；移除该精确hunk后49项再次通过。没有恢复整个脏文件。
- SimpleSignals 聚焦40项通过，其中5项新增精确边界测试。
- 首轮完整SimpleATO共805项，4项失败，不能据聚焦结果宣称完成。三个换向相关用例暴露旧冻结绑定未随真实Basic rebase及时更新；连挂继续用例暴露新车体头端已经改变而controller仍持有旧接触路线。原来假终点分支隐藏了这种生命周期错位。
- 正在沿已有Dispatcher/binding生命周期修正，不通过放松位置区间、允许假终点或跳过有效grant来消除失败。最终结果将在独立验证记录中给出。

后续修正：Dispatcher在成功rebase/adopt之后立即复用原绑定安装流程；安装与inspection readiness检查拆开，前者异常stall，后者尚无发布时正常等待。六个相关测试类合计220项通过，包括上述4项失败。另补直接controller环线cursor测试，最终联合检查和正常回放仍待完成。
