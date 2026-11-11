# Basic 自动/手动共用物理控制 primitive

## Owner 与执行合同

Basic拥有`TrainActuator`、`VehicleControlCommand`、不可变控制状态/能力、逐车设备代数和纵向物理积分。
SimpleATO拥有参考曲线、运行/停车策略、ATP判断及最终指令监督。Basic不得选择mission、停车点、信号aspect或ATO档位。

`TrainActuator.step(speed, command, deltaSeconds)`仅执行最终数字指令；`ControlDemand`暂留为上层规划值，
生产执行器不提供目标速度自动转档的fallback。`TrainDynamics.commandForHandle`把人工P/N/B档映射到同一数字合同。
牵引与制动比例独立且非负，不假定最大值为1；牵引比例不得超过配置正牵引档最大比例。
`handle`仅为诊断标签。紧急制动请求牵引切除及EB制动，但实际牵引退出、电制动退出和空气建立仍遵守逐车设备速率/延迟。

`controlState`包含当前速度、加速度、平均空气压力比例、最后指令和精确逐车不可变设备状态。
平均空气比例不是电空合计制动力，不能据此证明制动力已建立。
`controlCapabilities`包含配置/编组身份、名义操作加减速度、各档稳态作用、速度性能样本和逐车设备系数。
速度样本用于名义曲线；全速度牵引安全上界来自已声明的力上界与黏着限制，未知力曲线仅使用黏着上界。
禁止用有限速度采样的最大值冒充全区间证明。

`AtoSpatialPolicy.POSITION_TOLERANCE_METERS`是宿主和外部解析器共享的0.3m位置验收边界；
策略的名义停车精度目标属于SimpleATO，不扩展该空间边界。

## 热路径与物理连续性

`HandleTrainActuator`只执行指令，`freeze()`只改变诊断手柄；运动冻结由controller/Core所属边界提交。
Basic保留纯物理preview供离线/物理测试使用，生产ATO不得调用未来多tick物理预演。
`VehicleDeviceDynamics.advanceDeviceState`只允许对已经确定的当前物理区间做一次逐车设备代数，
不积分速度/位移，不循环未来区间；实际物理kernel复用同一内联代数，避免公式漂移及逐车临时对象。

`BasicWorldData.trainDynamicsHistory`持有world-local、精确Train与Car实例关联的物理历史。
构造、准备、preview和只读查询不发布新历史；真实成功step才发布独立快照。
控制器替换、延迟安装或复用原ATO时采用最新历史，逐车设备按精确Car身份投影。
逐车弱身份索引使controllerless split的新Train构造也继承真实设备；相同ID的新Car不能继承。
该索引只用于新owner初始化，不在活动owner每tick用旧单车历史覆盖当前状态。
历史`StepResult`不因编组准备或只读适配被伪造。Core公开强制静止时消除旧速度/加速度，保留实际压力与设备状态。

## 人工监督 seam

`BasicWorldData.commandSupervisor`为可选world-local `VehicleCommandSupervisor`。
人工控制将玩家数字指令、当前状态/能力和实际正物理步长交给它，再执行返回指令。
Basic不依赖SimpleATO。零步长不执行监督、ack判定或设备积分。
`acknowledgeAtp`只提交帧间请求并验证当前controller精确身份；返回接受表示请求已记录，
是否解除仍由监督器在下一有效物理帧的新鲜安全检查决定。

既有`WorldSafetyInterlock`仍为world-local fail-closed latch，优先于普通人工指令；
不得因普通mission拒绝锁住，也不得在controller tick擅自reset。

## 验证

Basic测试须覆盖数字/手柄等价、独立牵引制动和联锁、非单位档位范围、
真实设备与共享代数的逐区间精确一致、变步长淡出、不可变快照、精确车辆身份、替换/复用历史以及人工监督。
ATO策略和ATP安全行为测试由SimpleATO拥有，不能重新放入Basic物理执行器。
