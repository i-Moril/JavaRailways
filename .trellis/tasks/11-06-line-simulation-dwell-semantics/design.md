# 设计：真实运行线测时与单一停站边界

状态：用户已批准实施；随后明确批准删除新增关门边界保护，并允许自定义程序提前关门、等到最短停站满足后发车。采用同一任务内两个有依赖的工作批次，先收敛停站执行，再替换使用该执行链的运行线模拟；不另建任务树或第二个调度器。

## 1. 停站合同与时间来源

对于营运中间STOP，记录真实到站事件A，最短停站D，计划发车P。最短停站边界为A+D，正常准备/发车目标T=max(P,A+D)。开门完成不修改A、D或T。关门、显式任务、资源申请实际未完成仍可使发车晚于T，但不能在完成后再补一段D。

从已有BERTHED记录取得A，而不是下一tick创建StationStopProgramRuntime时的dispatcherTick。DESTINATION使用实际确认停车事件。入口统一dispatcher/timetable时基，覆盖非零cycleStart；不增加时钟服务、跨站时间账本或新的周期状态。

ORIGIN仅遵守作者计划始发时刻及准备任务；TERMINAL仅完成到达任务和作者显式后续时刻，不自动加D，也不自动执行关门准备。下一RUN的ORIGIN不继承上一个TERMINAL的D。PASS/DEADHEAD不施加全局D。首尾生成载荷和运行时角色判断需一致，不能让null触发基础设施defaultDwell回落而重新引入等待。

## 2. 既有执行器内的最小修改

修改StationStopProgramRuntime与Dispatcher调用点：

- 删除OPEN_DOORS完成后重置dwellDeadline；准备时序和最终时间门依据同一个到站下限与计划时刻。
- 默认准备按T减实际列车关门提前量开始。估算耗时不能充当真实关门完成证据，仍以真实操作ticket成功为准。
- 自定义程序可以提前关门；最终发车仍等待同一T及真实操作/任务/安全门禁。不能宣称任意自定义程序的实际关门完成时刻必然不早于A+D，也不从提前关门完成重新加D。
- 保留到达主序列、异步子任务join、作者显式WAIT、失败/取消、门关闭和滚动授权门禁。多次比较同一个已过的绝对时刻不等于重复等待，不能机械删除。
- 无stationProgram的手动任务/API是另一条合法入口，不是已证明的旧数据兼容兜底；本任务不顺带删除或改写其独立停站功能。

不改ATP、信号自占用规则或车辆位置修复，不用整段闭塞/授权兜底替代滚动申请。

### 已批准的简化边界

复用现有preparation estimate和真实门动作ticket，不新增当前动画状态查询、PreparedTrainDoorClose、最长车厢见证、提交前复核或相应取消状态。撤掉本任务为这些机制增加的Basic门操作API、Bukkit播放内核和LIVE/SIM适配改动及专有测试。
正常开门后的默认准备依原关门提前量安排；任意自定义程序不强制保证关门完成时刻，允许先关门再等待既有发车下限。动画、车厢操作与跨region失败语义保持原实现，不用精确预测门状态实现额外保护。

## 3. 编辑与编译传播

WebUI在现有authoring工具内统一call-aware取值，只有PASSENGER+STOP得到全局D。生成、单点/后续移动、图点范围、连接编辑采用一致适用范围。删除终到minimum的pendingDwellUntil继承和后续移动消费规则；保留作者显式terminalDeparture、任务先后及连续性检查，不把显式时刻和隐式最短停站混删。

Reset的fixedDwellTicks继续是独立的生成计划参数，不能改名代替minimumDwellTicks，不无提示clamp。内外层options/stopPrograms保持一致。编译器不新增旧行为开关、兼容分支或持久化版本。更新固化首尾注入及开门后计时的测试、过时spec和提示。

## 4. 运行线模拟替换

保留已有HTTP入口、队列、进度UI、墙钟超时和取消生命周期；将当前IsolatedLineSimulator/DetailedTrainTravelSimulator的排图调用替换为IsolatedTimetableSimulationFactory捕获与创建的真实单运行线执行。

目标world包含同一份Core/Basic/SimpleATO设施、真实局部限速、所选车辆/逐车JS、已注册的完整信号与资源贡献。不得复制LIVE列车、票据、锁，不默认空signals，也不每区间重置车辆/设备状态。

输入必须包含测量所需的准确路线和停站上下文：始发方向、站台/via/偏移、车辆、线路速度、fixedDwell、global minimum、适用站点程序。不能只传路线长度和profile再假定其他状态相同。复用现有编译/程序执行，不构建第二套ATO、物理或站点程序解释器。

使用一条测量交路在同一world中推进，首尾不附加全局D，连续PASS不停车。合成的最早计划不能因尚未测得行程而被当作真实时刻表晚点失败：沿用现有独立程序测量的最大tick边界及大于该边界的delay阈值，仅用于测量；真实全局验收保持原阈值。禁止把达到测量上限/失败当作成功结果。

测量输入保留首站firstDeparture和真实绝对WAIT时刻，不在0测完再任意平移。后续按顺序使用已到期的计划下限；仅合成的测量STOP以fixedDwell作为A之后的目标间隔，复用同一个站点执行器，不写回或替换用户global minimum。先验证fixedDwell>=适用的global minimum，不隐式max。真实程序或门作业不能在目标间隔内完成时返回“模拟用停站间隔不足”的具体站点/实测信息，不生成成功Reset结果；用户自行调整输入。不是通过自动伸长固定间隔掩盖不可实现性。

### 到发采样与结果

本轮完成时间修复：复用隔离模拟既有afterTick（pending commits之后）只读观察当前测量run尚未完成的操作ticket，记录首次成功的真实tick；不在该钩子推进程序计数器、启动动作或重新执行Dispatcher。随后原解释器消费ticket时沿用记录时间，END/已完成join仅汇总，实际新动作、WAIT和新派生任务仍按真实执行时刻处理。此改动限StationStopProgramRuntime、Dispatcher窄测量入口和IsolatedLineSimulator，不扩Basic ticket/API或Bukkit门网关、不改共享阶段次序。门与运动放行继续由原解释器完成全部检查后决定，测量证据不授予提前发车。

- STOP/PASS到达使用Dispatcher真实到点事件及call身份；重复站点/同轨多次出现不能仅按stationId聚合。
- 区间计时起点为经核对可满足的departure目标（首站firstDeparture，中间站A+fixedDwell，PASS为真实通过tick），终点为下一实际到达/通过事件。采集准备/门完成事件用于检查该目标能否实现；目标之后的信号/资源等待必须计入到下一站时间。不能用DEPARTED（驶离站台）、first-motion或拿到授权的时刻作起点扣掉这段等待。优先扩展现有观察接口，不轮询message猜事件，不持久化诊断历史。
- 响应使用按call顺序的整数tick到发结果作为Reset权威，保留travelSeconds/峰速/停车误差等真实测量展示值，不填0占位；不将tick转秒再ceil作为权威回填。Reset不再次加fixedDwell。程序/车门超出目标间隔则按上述策略失败，不把停站超时压入区间时间。
- 无法复现的显式程序、缺少模型/扩展、失败/超时必须明确报错，不回退粗略估算。只在完整事件齐全且真实完成时返回可用于重置的结果。

## 5. 数据、范围与回滚

预计主要改动：WebUI authoring/graph bounds/Reset API映射；Bukkit HTTP/line adapter/factory接线；SimpleATO站点runtime、Dispatcher调用/窄观察接口。Basic门操作API、Bukkit动画和门网关不增加新机制。SimpleSignals和Basic算法复用，只添加必要联动回归。实施前检查全体consumer再删除仅用于旧排图估算的入口；保留有独立用途的物理内核或实验，不为旧估算保留fallback。

当前生产引用检查显示DetailedTrainTravelSimulator仅由旧IsolatedLineSimulator适配到HTTP，无独立产品用途。因此删除这条估算器及仅测试旧估算器的专有测试，改用真实隔离链测试；不删除仍服务实际运行的TrainDynamics/VehicleControlExecutor等共享组件。不以“以后可能用于诊断”为由保留第二套排图算法。

不自动改写已有时刻表文档；用户通过Reset重算。瞬态HTTP结果可按当前contract更新，存档字段/版本不为本任务递增，不新增runtime旧开发格式兼容；如发现必须改变独立持久化边界，回到版本规则检查并报告，不猜测迁移。

分批保存可回退代码，不推送/部署。回滚是还原本任务工作提交，不碰无关dirty文件；真实资产只作离线验证，未经授权不改服务器数据。

## 6. 验收边界与技术检查

以固定输入的单运行线与单交路完整模拟对齐同一到发事件：整数tick严格比较，不凭空加误差预算；浮点几何使用既有领域合同，不恢复停车容差到进度投影。专项覆盖局部限速、PASS、站台偏移、折返始发、车门真实耗时、自定义准备和取消清理。

旧102P数据可用于说明差异及重置后回放；旧排图四段时刻本身偏短，不能要求保留原时刻却宣称修复后全局不晚点。区分“旧时刻表原阈值结果”和“新运行线结果重置后的同条件回放”。测试、离线回放、浏览器和部署分别报告，不以退出码代替成功。
