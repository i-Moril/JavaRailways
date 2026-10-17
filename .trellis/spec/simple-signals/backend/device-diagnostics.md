# 设备现场只读诊断

## 范围与一次正常读取

runtime投影已实现（CURRENT，2026-10-17），由SimpleSignals runtime负责事实投影，Signal Wand只负责本地化界面，页面接线状态另见任务。用于解释已放设备的实际规则输出和不可用原因，不解释任意JS内部条件、不修改行车策略，也不展示未定义的上下行绑定摘要。

玩家选中精确设备后，worker捕获当前world runtime及作者publication，读取同一份已发布占用/信号状态，返回不可变诊断；玩家owner核对原工具、设备和会话仍有效后显示。手动刷新重新读取，不重新求值JS、不扫描线路、不申请资源、不保存定义。

## 如何保存和读取实际求值依据

JS输入本来由SignalPackEpochRuntime在信号阶段构造。求值时只保留最近一次实际输入的不可变JSON引用、RailwayStateStamp和求值时间，随完整SectionSignalSnapshot一起发布；没有无界历史，也不为诊断每tick复制观察树。输入未变而复用输出时，保留其真实求值来源，旧epoch不因此成为异常。

SectionOccupancyRuntime从单个volatile PublishedState捕获一组事实，并遵守现有拓扑/新定义baseline限制。guard已收紧或尚无新有效baseline时，不能把旧输入当作新定义的当前事实；详情显示暂无。失败发生在投影前则该次没有输入；不得把上次成功输入包装成这次失败的依据。观察输入若没有独立墙钟来源，observedAt保持null，不用刷新时间冒充。

## 私有接口与单位

`SimpleSignalsWorldRuntime.signalDeviceDiagnostics(deviceId: String): SignalDeviceDiagnostics?`只读当前设备。结果包含`state: SectionSignalState?`、求值`stamp`、`evaluatedAt: Instant?`、`observedAt: Instant?`、`selfOccupied: Boolean?`、`selfLengthMeters: Double?`、`scanDistanceMeters: Double?`、`forward`和`terminations`不可变列表。

前方条目给出沿观察路径的米制`distanceMeters`、`hasDownstreamSignal`及可空`speedLimitMetersPerSecond`，保留原`selection`和可空`routeReady`。只有hasDownstreamSignal=true时，null才表示有效下游明确不附加限速；false不是不限速。前方及终止条目都带本次树内的`branchPath`（例如1.2，根终止为0）供页面区分路径；它不是设备或线路身份。终止条目为原输入`reason`和米制距离，不从灯色推断。页面注明所有观察分支，不能将所有路径的距离合成一条线路。

附属信号读取其明确主信号的诊断，不执行另一套规则。表示器没有JS规则，不伪造JS速度/输入；若显示其当前模型选择，须明确这是表示器提交选择，不是客户端确认。

## 状态、线程与错误

- Available的0是有效JS停车输出；Available的null是明确不附加限速；Unavailable的0是宿主安全收紧，页面显示其原reason而非假JS输出；null状态表示尚无有效首次结果。
- 缺字段、缺baseline、设备不存在/已卸载及过期编辑接续均不得显示“空闲”或上一设备的结果。后端错误保留原诊断，界面标签及外层提示中英本地化。
- 可变规则、选择状态只在信号epoch owner修改；worker只读取不可变发布值。Player、物品和Dialog只能在玩家owner读写。UI读取时间独立显示，不更新数据产生时间。
- 诊断不是授权或写入入口，不新增持久化边界/版本。定义/拓扑换代沿用既有认证规则，不建立另一个world状态缓存。

## 成本、情形与验证

每次实际求值增加常数大小的来源记录，保留输入引用；查询单台设备才遍历该台已保存JSON，时间/临时空间O(N)，N为其实际观察节点数。不构造另一份世界图，不增加扫描上限、轮询或JS执行。

正常stop显示有效0及实际variant；未绑定或无ready进路显示不可用和原原因，不声称规则主动选红；首次baseline前显示等待数据；缓存未变输出仍显示其原求值时间。错误做法是刷新时重扫一遍并宣称这是上次规则输入，正确做法是读取与结果一起保存的真实来源；未知值保持未知。

必需断言：同一发布来源、成功/投影前失败、有效null和缺下游的区别、未变输出保留来源、定义/拓扑baseline不继承失效事实、附属主信号投影，以及读取不增加JS调用。复用原epoch与handoff用例，UI仅检查必要状态与过期回调；不建立大量重复样例测试。真实客户端可读性与红灯现场原因需部署后另验，离线通过不等于已定位现场问题。
